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
      let viaSupabase = false;
      try {
        const probe = await fetch(resolved.resource_url, {
          method: "GET",
          headers: { ...upstreamHeaders, "Range": "bytes=0-0" },
          redirect: "follow",
          signal: AbortSignal.timeout(5000),
        });
        const needsFallback = probe.status === 403 || probe.status === 429 || probe.status >= 500;
        await probe.body?.cancel().catch(() => {});
        if (needsFallback) {
          const fallback = await proxyThroughSupabase(env, channelId, accessToken);
          if (!fallback.ok && fallback.status !== 206) {
            await fallback.body?.cancel().catch(() => {});
            return new Response(null, { status: 502, headers: cors });
          }
          await fallback.body?.cancel().catch(() => {});
          viaSupabase = true;
        }
      } catch (error) {
        console.error("VELNIX_RELAY_PROBE_DIRECT_EXCEPTION", JSON.stringify({
          name: error?.name, message: error?.message, host: hostOf(resolved.resource_url)
        }));
        try {
          const fallback = await proxyThroughSupabase(env, channelId, accessToken);
          if (!fallback.ok && fallback.status !== 206) {
            await fallback.body?.cancel().catch(() => {});
            return new Response(null, { status: 502, headers: cors });
          }
          await fallback.body?.cancel().catch(() => {});
          viaSupabase = true;
        } catch (fallbackError) {
          console.error("VELNIX_RELAY_PROBE_FALLBACK_FAILED", JSON.stringify({
            name: fallbackError?.name, message: fallbackError?.message
          }));
          return new Response(null, { status: 502, headers: cors });
        }
      }
      return new Response(null, {
        status: 200,
        headers: { ...cors, "Cache-Control": "no-store", "X-Velnix-Relay-Via": viaSupabase ? "supabase" : "direct" },
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
      if (upstream.status === 403 || upstream.status === 429 || upstream.status >= 500) {
        console.error("VELNIX_RELAY_DIRECT_RETRY", JSON.stringify({ status: upstream.status, host: hostOf(resolved.resource_url) }));
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
    headers.set("X-Velnix-Relay-Via", viaSupabase ? "supabase" : "direct");
    headers.set("Accept-Ranges", "none");
    headers.set("X-Content-Type-Options", "nosniff");

    // Pass the upstream stream through natively. The previous per-chunk
    // ReadableStream pull/reconnect loop could consume the Worker's CPU budget
    // on long MPEG-TS sessions, terminating playback despite a healthy upstream.
    // The player already has retry/fallback handling for EOF or network failures.
    console.log("VELNIX_RELAY_STREAM_PASSTHROUGH", JSON.stringify({
      via: viaSupabase ? "supabase" : "direct",
      host: hostOf(resolved.resource_url),
      contentType
    }));

    return new Response(upstream.body, {
      status: upstream.status === 206 ? 200 : upstream.status,
      headers,
    });
  },
};