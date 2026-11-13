// VELNIX long-lived stream relay for Cloudflare Workers.
// Access is validated by the Supabase Edge Function using a signed, channel-bound token.
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Range, Content-Type",
  "Access-Control-Expose-Headers": "Accept-Ranges, Content-Type",
};
export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    if (!["GET", "HEAD"].includes(request.method)) return new Response("Method not allowed", { status: 405, headers: cors });
    const incoming = new URL(request.url);
    const channelId = incoming.searchParams.get("channel_id") || "";
    const accessToken = incoming.searchParams.get("access_token") || "";
    if (!channelId || !accessToken || !env.SUPABASE_RESOLVE_URL) return new Response("Missing stream configuration", { status: 400, headers: cors });

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

    let upstream;
    try {
      // Match the previously working VELNIX relay semantics for live TS:
      // keep the request open-ended and never forward browser byte ranges.
      upstream = await fetch(resolved.resource_url, {
        method: request.method,
        headers: {
          "Accept": "*/*",
          "Accept-Encoding": "identity",
          "User-Agent": "VELNIX-Stream-Proxy/2.0",
        },
        redirect: "follow",
      });
    } catch (error) {
      console.error("VELNIX_RELAY_UPSTREAM_EXCEPTION", JSON.stringify({
        name: error?.name,
        message: error?.message,
        host: (() => { try { return new URL(resolved.resource_url).host; } catch { return "invalid-url"; } })()
      }));
      return new Response("Upstream stream connection failed", { status: 502, headers: cors });
    }

    if (!upstream.ok && upstream.status !== 206) {
      console.error("VELNIX_RELAY_UPSTREAM_STATUS", JSON.stringify({
        status: upstream.status,
        host: (() => { try { return new URL(resolved.resource_url).host; } catch { return "invalid-url"; } })()
      }));
      return new Response("Upstream stream unavailable (" + upstream.status + ")", {
      status: 502,
      headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
      });
    }
    if (!upstream.body && request.method !== "HEAD") {
      console.error("VELNIX_RELAY_EMPTY_BODY", JSON.stringify({ status: upstream.status }));
      return new Response("Upstream returned an empty stream", {
      status: 502,
      headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
      });
    }

    const headers = new Headers(cors);
    headers.set("Cache-Control", "no-store, no-transform");
    headers.set("X-Accel-Buffering", "no");
    headers.set("Content-Type", upstream.headers.get("Content-Type") || "video/mp2t");
    headers.set("Accept-Ranges", "none");
    headers.set("X-Content-Type-Options", "nosniff");

    // Match VELNIX's known-good live proxy: do not expose finite file/range metadata.
    return new Response(request.method === "HEAD" ? null : upstream.body, {
      status: upstream.status === 206 ? 200 : upstream.status,
      headers,
    });
  },
};