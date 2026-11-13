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
    const initialResponse = upstream;
    let cancelled = false;
    let activeReader = null;
    const stream = new ReadableStream({
      start(controller) {
        const run = async () => {
          let response = initialResponse;
          let useSupabase = initialViaSupabase;
          let reconnects = 0;
          let failures = 0;

          while (!cancelled && reconnects <= 30) {
            if (!response?.ok && response?.status !== 206) {
              failures++;
              console.error("VELNIX_RELAY_RECONNECT_STATUS", JSON.stringify({
                status: response?.status || 0, via: useSupabase ? "supabase" : "direct",
                attempt: reconnects
              }));
              await response?.body?.cancel().catch(() => {});
              if (failures >= 4) break;
            } else if (response.body) {
              activeReader = response.body.getReader();
              try {
                while (!cancelled) {
                  const part = await activeReader.read();
                  if (part.done) break;
                  if (part.value) controller.enqueue(part.value);
                }
                failures = 0;
              } catch (error) {
                failures++;
                console.error("VELNIX_RELAY_STREAM_INTERRUPTED", JSON.stringify({
                  name: error?.name, message: error?.message, via: useSupabase ? "supabase" : "direct"
                }));
              } finally {
                try { await activeReader.cancel(); } catch {}
                activeReader = null;
              }
            } else {
              failures++;
            }

            if (cancelled) return;
            reconnects++;
            if (reconnects > 30 || failures >= 4) break;
            await new Promise(resolve => setTimeout(resolve, 350));

            try {
              response = useSupabase
                ? await proxyThroughSupabase(env, channelId, accessToken)
                : await fetch(resolved.resource_url, { method: "GET", headers: upstreamHeaders, redirect: "follow" });
              if (!response.ok && response.status !== 206 && !useSupabase && response.status === 403) {
                await response.body?.cancel().catch(() => {});
                useSupabase = true;
                response = await proxyThroughSupabase(env, channelId, accessToken);
              }
            } catch (error) {
              failures++;
              console.error("VELNIX_RELAY_RECONNECT_EXCEPTION", JSON.stringify({
                name: error?.name, message: error?.message, attempt: reconnects
              }));
              response = null;
            }
          }

          if (!cancelled) controller.close();
        };

        run().catch(error => {
          console.error("VELNIX_RELAY_STREAM_FATAL", JSON.stringify({ name: error?.name, message: error?.message }));
          if (!cancelled) controller.error(error);
        });

      },
      async cancel() {
        cancelled = true;
        try { await activeReader?.cancel(); } catch {}
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