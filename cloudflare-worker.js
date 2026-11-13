// VELNIX long-lived stream relay for Cloudflare Workers.
// Configure Worker secrets/vars:
//   SUPABASE_RESOLVE_URL = https://<project-ref>.supabase.co/functions/v1/xtream-gateway
//   VELNIX_WORKER_SECRET = same value configured in Supabase Edge Function secrets
// Deploy this file as a Cloudflare Worker. Do not add upstream IPTV credentials here.

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Range, Content-Type",
  "Access-Control-Expose-Headers": "Content-Length, Content-Range, Accept-Ranges",
};

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });
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
        headers: {
          "Content-Type": "application/json",
          "x-velnix-worker-secret": env.VELNIX_WORKER_SECRET,
        },
        body: JSON.stringify({ action: "resolve_stream", channel_id: channelId, access_token: accessToken }),
      });
      resolved = await auth.json().catch(() => ({}));
      if (!auth.ok || !resolved.resource_url) {
        return new Response(resolved.error || "Stream authorization failed", {
          status: auth.status || 502, headers: { ...cors, "Cache-Control": "no-store" },
        });
      }
    } catch {
      return new Response("Could not verify stream access", { status: 502, headers: cors });
    }

    let upstream;
    try {
      upstream = await fetch(resolved.resource_url, {
        method: request.method,
        headers: {
          "Accept": request.headers.get("Accept") || "*/*",
          "Accept-Encoding": "identity",
          "User-Agent": "VELNIX-Stream-Relay/1.0",
        },
        redirect: "follow",
      });
    } catch {
      return new Response("Upstream stream connection failed", { status: 502, headers: cors });
    }
    if (!upstream.ok && upstream.status !== 206) {
      return new Response("Upstream stream unavailable (" + upstream.status + ")", {
        status: 502, headers: { ...cors, "Cache-Control": "no-store" },
      });
    }
    const headers = new Headers(cors);
    headers.set("Cache-Control", "no-store, no-transform");
    headers.set("Content-Type", upstream.headers.get("Content-Type") || "video/mp2t");
    headers.set("Accept-Ranges", "none");
    headers.set("X-Content-Type-Options", "nosniff");
    for (const name of ["Content-Length", "Content-Range"]) {
      const value = upstream.headers.get(name);
      if (value) headers.set(name, value);
    }
    return new Response(request.method === "HEAD" ? null : upstream.body, {
      status: upstream.status === 206 ? 200 : upstream.status,
      headers,
    });
  },
};
