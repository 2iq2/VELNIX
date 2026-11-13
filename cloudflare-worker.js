// VELNIX long-lived stream relay for Cloudflare Workers.
// Validates signed channel access in Supabase. Falls back to the Supabase
// egress path when a provider rejects Cloudflare's direct egress IP.
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Range, Content-Type",
  "Access-Control-Expose-Headers": "Accept-Ranges, Content-Type",
};

const upstreamHeaders = {
  "Accept": "*/*",
  "Accept-Encoding": "identity",
  "User-Agent": "VELNIX-Stream-Proxy/2.0",
};

const hostOf = (value) => {
  try { return new URL(value).host; } catch { return "invalid-url"; }
};

const proxyThroughSupabase = (env, channelId, accessToken) => {
  const url = new URL(env.SUPABASE_RESOLVE_URL);
  url.searchParams.set("channel_id", channelId);
  url.searchParams.set("access_token", accessToken);
  return fetch(url.toString(), {
    method: "GET",
    headers: upstreamHeaders,
    redirect: "follow",
  });
};

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    if (!["GET", "HEAD"].includes(request.method)) return new Response("Method not allowed", { status: 405, headers: cors });

    const incoming = new URL(request.url);
    const channelId = incoming.searchParams.get("channel_id") || "";
    const accessToken = incoming.searchParams.get("access_token") || "";
    if (!channelId || !accessToken || !env.SUPABASE_RESOLVE_URL) {
      return new Response("Missing stream configuration", { status: 400, headers: cors });
    }

    let resolved;
    try {
      const auth = await fetch(env.SUPABASE_RESOLVE_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "resolve_stream", channel_id: channelId, access_token: accessToken }),
      });
      resolved = await auth.json().catch(() => ({}));
      if (!auth.ok || !resolved.resource_url) {
        console.error("VELNIX_RELAY_AUTH_FAILED", JSON.stringify({ status: auth.status, hasResource: !!resolved.resource_url }));
        return new Response(resolved.error || "Stream authorization failed", {
          status: auth.status || 502,
          headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
        });
      }
    } catch (error) {
      console.error("VELNIX_RELAY_AUTH_EXCEPTION", JSON.stringify({ name: error?.name, message: error?.message }));
      return new Response("Could not verify stream access", { status: 502, headers: cors });
    }

    if (request.method === "HEAD") {
      return new Response(null, {
        status: 200,
        headers: { ...cors, "Content-Type": "video/mp2t", "Cache-Control": "no-store", "Accept-Ranges": "none" },
      });
    }

    let upstream = null;
    let viaSupabase = false;
    try {
      upstream = await fetch(resolved.resource_url, {
        method: "GET",
        headers: upstreamHeaders,
        redirect: "follow",
      });
      if (upstream.status === 403) {
        console.error("VELNIX_RELAY_DIRECT_DENIED", JSON.stringify({ status: 403, host: hostOf(resolved.resource_url) }));
        await upstream.body?.cancel().catch(() => {});
        upstream = await proxyThroughSupabase(env, channelId, accessToken);
        viaSupabase = true;
      }
    } catch (error) {
      console.error("VELNIX_RELAY_DIRECT_EXCEPTION", JSON.stringify({
        name: error?.name, message: error?.message, host: hostOf(resolved.resource_url)
      }));
      try {
        upstream = await proxyThroughSupabase(env, channelId, accessToken);
        viaSupabase = true;
      } catch (fallbackError) {
        console.error("VELNIX_RELAY_FALLBACK_EXCEPTION", JSON.stringify({
          name: fallbackError?.name, message: fallbackError?.message
        }));
        return new Response("Stream connection failed", { status: 502, headers: cors });
      }
    }

    if (!upstream?.ok && upstream?.status !== 206) {
      console.error("VELNIX_RELAY_UPSTREAM_STATUS", JSON.stringify({
        status: upstream?.status || 0,
        via: viaSupabase ? "supabase" : "direct",
        host: hostOf(resolved.resource_url)
      }));
      const status = upstream?.status === 401 || upstream?.status === 403 ? upstream.status : 502;
      await upstream?.body?.cancel().catch(() => {});
      return new Response("Upstream stream unavailable", {
        status,
        headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
      });
    }
    if (!upstream.body) {
      console.error("VELNIX_RELAY_EMPTY_BODY", JSON.stringify({ status: upstream.status, via: viaSupabase ? "supabase" : "direct" }));
      return new Response("Upstream returned an empty stream", {
        status: 502,
        headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
      });
    }

    const contentType = upstream.headers.get("Content-Type") || "video/mp2t";
    const headers = new Headers(cors);
    headers.set("Cache-Control", "no-store, no-transform");
    headers.set("X-Accel-Buffering", "no");
    headers.set("Content-Type", contentType);
    headers.set("Accept-Ranges", "none");
    headers.set("X-Content-Type-Options", "nosniff");

    const initialViaSupabase = viaSupabase;
    let response = upstream;
    let useSupabase = initialViaSupabase;
    let activeReader = null;
    let cancelled = false;
    let reconnects = 0;
    let failures = 0;
    let totalBytes = 0;
    let sourceOpenedAt = Date.now();

    const openNextResponse = async () => {
      let next = useSupabase
        ? await proxyThroughSupabase(env, channelId, accessToken)
        : await fetch(resolved.resource_url, { method: "GET", headers: upstreamHeaders, redirect: "follow" });

      if (!useSupabase && next.status === 403) {
        console.error("VELNIX_RELAY_DIRECT_DENIED", JSON.stringify({ status: 403, host: hostOf(resolved.resource_url), phase: "reconnect" }));
        await next.body?.cancel().catch(() => {});
        useSupabase = true;
        next = await proxyThroughSupabase(env, channelId, accessToken);
      }
      return next;
    };

    const stream = new ReadableStream({
      async pull(controller) {
        if (cancelled) return;

        while (!cancelled) {
          if (!activeReader) {
            if (!response) {
              if (reconnects >= 30 || failures >= 4) {
                console.error("VELNIX_RELAY_RECONNECT_LIMIT", JSON.stringify({
                  reconnects, failures, via: useSupabase ? "supabase" : "direct", totalBytes
                }));
                controller.close();
                return;
              }

              await new Promise(resolve => setTimeout(resolve, Math.min(1200, 250 + reconnects * 100)));
              try {
                response = await openNextResponse();
                sourceOpenedAt = Date.now();
              } catch (error) {
                failures++;
                reconnects++;
                console.error("VELNIX_RELAY_RECONNECT_EXCEPTION", JSON.stringify({
                  name: error?.name, message: error?.message, attempt: reconnects,
                  via: useSupabase ? "supabase" : "direct"
                }));
                continue;
              }
            }

            if (!response?.ok && response?.status !== 206) {
              const status = response?.status || 0;
              console.error("VELNIX_RELAY_RECONNECT_STATUS", JSON.stringify({
                status, via: useSupabase ? "supabase" : "direct", attempt: reconnects
              }));
              await response?.body?.cancel().catch(() => {});
              response = null;
              failures++;
              reconnects++;
              if (failures >= 4 || reconnects >= 30) {
                controller.error(new Error("Upstream reconnect failed with HTTP " + status));
                return;
              }
              continue;
            }

            if (!response.body) {
              console.error("VELNIX_RELAY_RECONNECT_EMPTY_BODY", JSON.stringify({
                status: response.status, via: useSupabase ? "supabase" : "direct"
              }));
              response = null;
              failures++;
              reconnects++;
              if (failures >= 4 || reconnects >= 30) {
                controller.error(new Error("Upstream returned an empty stream"));
                return;
              }
              continue;
            }

            activeReader = response.body.getReader();
            console.log("VELNIX_RELAY_RECONNECTED", JSON.stringify({
              status: response.status, via: useSupabase ? "supabase" : "direct",
              attempt: reconnects, contentType: response.headers.get("Content-Type") || ""
            }));
          }

          try {
            const part = await activeReader.read();
            if (part.done) {
              const durationMs = Date.now() - sourceOpenedAt;
              console.warn("VELNIX_RELAY_STREAM_EOF", JSON.stringify({
                via: useSupabase ? "supabase" : "direct",
                duration_ms: durationMs, bytes: totalBytes, next_attempt: reconnects + 1
              }));
              try { activeReader.releaseLock(); } catch {}
              activeReader = null;
              response = null;
              reconnects++;
              if (reconnects >= 30) {
                console.error("VELNIX_RELAY_RECONNECT_LIMIT", JSON.stringify({
                  reconnects, failures, via: useSupabase ? "supabase" : "direct", totalBytes
                }));
                controller.close();
                return;
              }
              continue;
            }

            if (part.value) {
              totalBytes += part.value.byteLength || 0;
              failures = 0;
              controller.enqueue(part.value);
              return;
            }
          } catch (error) {
            failures++;
            reconnects++;
            console.error("VELNIX_RELAY_STREAM_INTERRUPTED", JSON.stringify({
              name: error?.name, message: error?.message,
              via: useSupabase ? "supabase" : "direct",
              attempt: reconnects, totalBytes
            }));
            try { await activeReader?.cancel(); } catch {}
            try { activeReader?.releaseLock(); } catch {}
            activeReader = null;
            response = null;
            if (failures >= 4 || reconnects >= 30) {
              controller.error(error);
              return;
            }
          }
        }
      },
      async cancel() {
        cancelled = true;
        try { await activeReader?.cancel(); } catch {}
        try { activeReader?.releaseLock(); } catch {}
        activeReader = null;
      },
    });

    console.log("VELNIX_RELAY_STREAM_STARTED", JSON.stringify({
      via: viaSupabase ? "supabase" : "direct",
      host: hostOf(resolved.resource_url),
      reconnectEnabled: true
    }));

    return new Response(stream, {
      status: initialResponse.status === 206 ? 200 : initialResponse.status,
      headers,
    });
  },
};