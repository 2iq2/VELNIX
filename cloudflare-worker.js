// VELNIX stream relay for Cloudflare Workers.
// The Edge Function validates the signed, channel-bound access token before revealing the upstream URL.
// Required Worker variable: SUPABASE_RESOLVE_URL

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Range, Content-Type",
  "Access-Control-Expose-Headers": "Content-Length, Content-Range, Accept-Ranges, Content-Type",
};

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    if (!["GET", "HEAD"].includes(request.method)) {
      return new Response("Method not allowed", { status: 405, headers: cors });
    }

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
        body: JSON.stringify({
          action: "resolve_stream",
          channel_id: channelId,
          access_token: accessToken,
        }),
      });
      resolved = await auth.json().catch(() => ({}));
      if (!auth.ok || !resolved.resource_url) {
        return new Response(resolved.error || "Stream authorization failed", {
          status: auth.status || 502,
          headers: { ...cors, "Cache-Control": "no-store" },
        });
      }
    } catch {
      return new Response("Could not verify stream access", { status: 502, headers: cors });
    }

    let upstream;
    try {
      const upstreamHeaders = new Headers({
        "Accept": request.headers.get("Accept") || "*/*",
        "Accept-Encoding": "identity",
        "User-Agent": "VELNIX-Stream-Relay/1.1",
      });
      // Preserve valid byte-range semantics for clients that request them.
      const range = request.headers.get("Range");
      if (range) upstreamHeaders.set("Range", range);

      upstream = await fetch(resolved.resource_url, {
        method: request.method,
        headers: upstreamHeaders,
        redirect: "follow",
      });
    } catch {
      return new Response("Upstream stream connection failed", {
        status: 502,
        headers: { ...cors, "Cache-Control": "no-store" },
      });
    }

    if (!upstream.ok) {
      return new Response("Upstream stream unavailable (" + upstream.status + ")", {
        status: 502,
        headers: { ...cors, "Cache-Control": "no-store" },
      });
    }

    const headers = new Headers(cors);
    headers.set("Cache-Control", "no-store, no-transform");
    headers.set("X-Content-Type-Options", "nosniff");
    for (const name of [
      "Content-Type",
      "Content-Length",
      "Content-Range",
      "Accept-Ranges",
      "ETag",
      "Last-Modified",
    ]) {
      const value = upstream.headers.get(name);
      if (value) headers.set(name, value);
    }

    // Keep the origin's status (including 206). Never turn a partial response into 200:
    // that creates an invalid Content-Range/status combination in media clients.
    return new Response(request.method === "HEAD" ? null : upstream.body, {
      status: upstream.status,
      headers,
    });
  },
};
