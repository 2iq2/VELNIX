// VELNIX long-lived stream relay for Cloudflare Workers.
// Validates signed channel access in Supabase. Falls back to the Supabase
// egress path when a provider rejects Cloudflare's direct egress IP.
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Range, Content-Type",
  "Access-Control-Expose-Headers": "Accept-Ranges, Content-Type, X-Velnix-Relay-Via",
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
  // Keep the signed stream token out of Supabase request URLs and access logs.
  return fetch(env.SUPABASE_RESOLVE_URL, {
    method: "POST",
    headers: { ...upstreamHeaders, "Content-Type": "application/json" },
    body: JSON.stringify({ action: "proxy", channel_id: channelId, access_token: accessToken }),
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
      // Detect whether the origin rejects Cloudflare egress without opening
      // a second Supabase streaming response during the preflight.
      let viaSupabase = false;
      try {
        const probe = await fetch(resolved.resource_url, {
          method: "GET",
          headers: upstreamHeaders,
          redirect: "follow",
          signal: AbortSignal.timeout(5000),
        });
        viaSupabase = probe.status === 403 || probe.status === 429 || probe.status >= 500;
        await probe.body?.cancel().catch(() => {});
      } catch (error) {
        viaSupabase = true;
        console.error("VELNIX_RELAY_PROBE_DIRECT_EXCEPTION", JSON.stringify({
          name: error?.name, message: error?.message, host: hostOf(resolved.resource_url)
        }));
      }
      return new Response(null, {
        status: 200,
        headers: { ...cors, "Cache-Control": "no-store", "X-Velnix-Relay-Via": viaSupabase ? "supabase" : "direct" },
      });
    }

    const fetchWithConnectTimeout = async (url, init, timeoutMs = 12000) => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try { return await fetch(url, { ...init, signal: controller.signal }); }
      finally { clearTimeout(timer); }
    };

    const openUpstream = async (preferSupabase = false) => {
      if (!preferSupabase) {
        try {
          const direct = await fetchWithConnectTimeout(resolved.resource_url, {
            method: "GET", headers: upstreamHeaders, redirect: "follow",
          });
          if (direct.status !== 403 && direct.status !== 429 && direct.status < 500) {
            return { response: direct, viaSupabase: false };
          }
          console.error("VELNIX_RELAY_DIRECT_RETRY", JSON.stringify({
            status: direct.status, host: hostOf(resolved.resource_url)
          }));
          await direct.body?.cancel().catch(() => {});
        } catch (error) {
          console.error("VELNIX_RELAY_DIRECT_EXCEPTION", JSON.stringify({
            name: error?.name, message: error?.message, host: hostOf(resolved.resource_url)
          }));
        }
      }
      const fallback = await fetchWithConnectTimeout(env.SUPABASE_RESOLVE_URL, {
        method: "POST",
        headers: { ...upstreamHeaders, "Content-Type": "application/json" },
        body: JSON.stringify({ action: "proxy", channel_id: channelId, access_token: accessToken }),
        redirect: "follow",
      }, 20000);
      return { response: fallback, viaSupabase: true };
    };

    let opened;
    try { opened = await openUpstream(false); }
    catch (error) {
      console.error("VELNIX_RELAY_FALLBACK_EXCEPTION", JSON.stringify({ name: error?.name, message: error?.message }));
      return new Response("Stream connection failed", { status: 502, headers: cors });
    }
    let upstream = opened.response;
    const viaSupabase = opened.viaSupabase;

    if (!upstream?.ok && upstream?.status !== 206) {
      console.error("VELNIX_RELAY_UPSTREAM_STATUS", JSON.stringify({
        status: upstream?.status || 0, via: viaSupabase ? "supabase" : "direct",
        host: hostOf(resolved.resource_url)
      }));
      const status = upstream?.status === 401 || upstream?.status === 403 ? upstream.status : 502;
      await upstream?.body?.cancel().catch(() => {});
      return new Response("Upstream stream unavailable", {
        status, headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
      });
    }
    if (!upstream.body) {
      console.error("VELNIX_RELAY_EMPTY_BODY", JSON.stringify({ status: upstream.status, via: viaSupabase ? "supabase" : "direct" }));
      return new Response("Upstream returned an empty stream", {
        status: 502, headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
      });
    }

    const contentType = upstream.headers.get("Content-Type") || "video/mp2t";
    const headers = new Headers(cors);
    headers.set("Cache-Control", "no-store, no-transform");
    headers.set("X-Accel-Buffering", "no");
    headers.set("Content-Type", contentType);
    headers.set("X-Velnix-Relay-Via", viaSupabase ? "supabase" : "direct");
    headers.set("Accept-Ranges", "none");
    headers.set("X-Content-Type-Options", "nosniff");

    // Backpressure-aware recovery: each pull waits for real upstream data.
    // On EOF, read error, or 18s of silence, reopen the origin (or Supabase
    // fallback) while keeping this browser response and its media buffer alive.
    let reader = upstream.body.getReader();
    let closed = false;
    let consecutiveReconnects = 0;
    let useSupabase = viaSupabase;
    const reconnectingBody = new ReadableStream({
      async pull(controller) {
        while (!closed) {
          let timer;
          let result;
          try {
            result = await Promise.race([
              reader.read().then(value => ({ kind: "read", value })),
              new Promise(resolve => { timer = setTimeout(() => resolve({ kind: "idle" }), 18000); }),
            ]);
          } catch (error) {
            result = { kind: "error", error };
          } finally {
            if (timer) clearTimeout(timer);
          }

          if (result.kind === "read" && !result.value.done && result.value.value?.byteLength) {
            consecutiveReconnects = 0;
            controller.enqueue(result.value.value);
            return;
          }

          const reason = result.kind === "idle" ? "idle-timeout" :
            result.kind === "error" ? "read-error" : "upstream-ended";
          await reader.cancel().catch(() => {});
          if (closed) return;
          if (consecutiveReconnects >= 10) {
            controller.error(new Error("Upstream stream could not be restored"));
            return;
          }

          const delay = Math.min(8000, 500 * Math.pow(2, Math.min(consecutiveReconnects, 4)));
          await new Promise(resolve => setTimeout(resolve, delay));
          if (closed) return;

          try {
            const next = await openUpstream(useSupabase);
            const response = next.response;
            if ((!response?.ok && response?.status !== 206) || !response.body) {
              await response?.body?.cancel().catch(() => {});
              consecutiveReconnects++;
              console.error("VELNIX_RELAY_RECONNECT_FAILED", JSON.stringify({
                attempt: consecutiveReconnects, reason, status: response?.status || 0,
                via: next.viaSupabase ? "supabase" : "direct", host: hostOf(resolved.resource_url)
              }));
              continue;
            }
            reader = response.body.getReader();
            useSupabase = next.viaSupabase;
            consecutiveReconnects++;
            console.log("VELNIX_RELAY_RECONNECTED", JSON.stringify({
              attempt: consecutiveReconnects, reason,
              via: useSupabase ? "supabase" : "direct", host: hostOf(resolved.resource_url)
            }));
          } catch (error) {
            consecutiveReconnects++;
            console.error("VELNIX_RELAY_RECONNECT_EXCEPTION", JSON.stringify({
              attempt: consecutiveReconnects, reason, name: error?.name,
              message: error?.message, host: hostOf(resolved.resource_url)
            }));
          }
        }
      },
      async cancel(reason) {
        closed = true;
        await reader.cancel(reason).catch(() => {});
      },
    });

    console.log("VELNIX_RELAY_STREAM_PASSTHROUGH", JSON.stringify({
      via: viaSupabase ? "supabase" : "direct", host: hostOf(resolved.resource_url),
      contentType, reconnect: "idle-or-eof"
    }));

    return new Response(reconnectingBody, {
      status: upstream.status === 206 ? 200 : upstream.status,
      headers,
    });
  },
};