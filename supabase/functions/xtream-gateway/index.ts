import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, range",
  "Access-Control-Expose-Headers": "Content-Length, Content-Range, Accept-Ranges",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });

const chunk = <T>(items: T[], size = 400) => {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
};

const clean = (value: unknown, fallback = "") =>
  String(value ?? fallback).replace(/[\u0000-\u001f]/g, "").trim();

const sha256 = async (value: string) => {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((x) => x.toString(16).padStart(2, "0")).join("");
};
const hasPremiumSubscription = async (adminClient: ReturnType<typeof createClient>, userId: string) => {
  const { data, error } = await adminClient.from("subscriptions").select("plan_code,expires_at")
    .eq("user_id", userId).eq("status", "active")
    .in("plan_code", ["premium", "gold", "platinum"])
    .gt("expires_at", new Date().toISOString())
    .order("expires_at", { ascending: false }).limit(1).maybeSingle();
  if (error) throw new Error("تعذر التحقق من الاشتراك");
  return data ?? null;
};
const hasBeinAccess = async (adminClient: ReturnType<typeof createClient>, userId: string) => {
  const { data, error } = await adminClient.from("bein_access_codes").select("id,access_expires_at")
    .eq("used_by", userId).is("revoked_at", null).gt("access_expires_at", new Date().toISOString())
    .order("access_expires_at", { ascending: false }).limit(1).maybeSingle();
  if (error) throw new Error("تعذر التحقق من صلاحية beIN");
  if (data) return data;
  const subscription = await hasPremiumSubscription(adminClient, userId);
  return subscription ? { id: "subscription", access_expires_at: subscription.expires_at } : null;
};
const base64url = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
const signAccessToken = async (payload: Record<string, unknown>) => {
  const secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const encoded = base64url(new TextEncoder().encode(JSON.stringify(payload)));
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(encoded)));
  return encoded + "." + base64url(signature);
};
const verifyAccessToken = async (token: string, channelId: string) => {
  try {
    const [encoded, signature] = token.split(".");
    if (!encoded || !signature) return null;
    const secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const bytes = Uint8Array.from(atob(signature.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - signature.length % 4) % 4)), (c) => c.charCodeAt(0));
    const valid = await crypto.subtle.verify("HMAC", key, bytes, new TextEncoder().encode(encoded));
    if (!valid) return null;
    const payload = JSON.parse(atob(encoded.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - encoded.length % 4) % 4)));
    if (payload.channel_id !== channelId || Number(payload.exp) <= Math.floor(Date.now() / 1000) || !payload.user_id) return null;
    return payload;
  } catch { return null; }
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST" && req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    const token = authHeader.replace(/^Bearer\s+/i, "");
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    let isAdmin = false;
    let isAuthenticated = false;
    if (token) {
      const authClient = createClient(supabaseUrl, anonKey, {
        auth: { persistSession: false, autoRefreshToken: false },
      });
      const { data: authData, error: authError } = await authClient.auth.getUser(token);
      if (!authError && authData.user) { isAuthenticated = true; isAdmin = authData.user.app_metadata?.role === "admin"; }
    }
    const adminClient = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const body = req.method === "POST" ? await req.json().catch(() => ({})) : {};
    const requestUrl = new URL(req.url);
    const action = req.method === "GET" ? "proxy" : clean(body?.action);

    if (action === "register_user") {
      const email = clean(body?.email).toLowerCase();
      const password = String(body?.password ?? "");
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return json({ error: "اكتب بريداً إلكترونياً صحيحاً" }, 400);
      if (password.length < 8 || password.length > 72) return json({ error: "كلمة المرور لازم تكون 8 أحرف على الأقل" }, 400);
      const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
      const now = Date.now();
      const signupAttempts = (globalThis as any).__velnixSignupAttempts ??= new Map<string, { count: number; started: number }>();
      const attempt = signupAttempts.get(ip);
      if (attempt && now - attempt.started < 10 * 60 * 1000 && attempt.count >= 5) {
        return json({ error: "محاولات التسجيل كثيرة. انتظر 10 دقائق ثم حاول مجدداً." }, 429);
      }
      if (!attempt || now - attempt.started >= 10 * 60 * 1000) signupAttempts.set(ip, { count: 1, started: now });
      else signupAttempts.set(ip, { count: attempt.count + 1, started: attempt.started });
      const { data: created, error: createError } = await adminClient.auth.admin.createUser({
        email, password, email_confirm: true,
        user_metadata: { display_name: clean(body?.display_name).slice(0, 60) || email.split("@")[0] }
      });
      if (createError) {
        const message = createError.message.toLowerCase();
        if (message.includes("already") || message.includes("registered") || message.includes("exists")) {
          return json({ error: "هذا البريد مسجّل مسبقاً. سجّل الدخول بدلاً من إنشاء حساب جديد." }, 409);
        }
        console.error("VELNIX registration failed:", createError.message);
        return json({ error: "تعذر إنشاء الحساب حالياً. تأكد من البريد وكلمة المرور وحاول مجدداً." }, 400);
      }
      if (!created.user) return json({ error: "لم يكتمل إنشاء الحساب. حاول مرة أخرى." }, 500);
      return json({ success: true, user_id: created.user.id, email: created.user.email, email_confirmed: true });
    }

    const isBeinChannel = async (channelId: string) => {
      const { data, error } = await adminClient.from("channels")
        .select("name_ar,name_en,channel_categories(name_ar,name_en)")
        .eq("id", channelId).maybeSingle();
      if (error) throw new Error("تعذر التحقق من تصنيف القناة");
      const category = clean(data?.channel_categories?.name_en || data?.channel_categories?.name_ar);
      const name = clean(data?.name_en || data?.name_ar);
      return /bein[\s._-]*sp.{0,3}rts|بي\s*ان\s*سبورت|بين\s*سبورت/i.test(category + " " + name);
    };

    if (action === "check_bein_access") {
      if (!isAuthenticated) return json({ error: "سجّل الدخول أولاً", has_access: false }, 401);
      const user = (await adminClient.auth.getUser(token)).data.user;
      const access = user ? await hasBeinAccess(adminClient, user.id) : null;
      return json({ success: true, has_access: !!access, expires_at: access?.access_expires_at ?? null });
    }
    if (action === "activate_bein_code") {
      if (!isAuthenticated) return json({ error: "سجّل الدخول لتفعيل الكود" }, 401);
      const user = (await adminClient.auth.getUser(token)).data.user;
      if (!user) return json({ error: "جلسة تسجيل الدخول غير صالحة" }, 401);
      const existing = await hasBeinAccess(adminClient, user.id);
      if (existing) return json({ success: true, already_active: true, expires_at: existing.access_expires_at });
      const code = clean(body?.code).toUpperCase().replace(/[^A-Z0-9-]/g, "");
      if (!/^VELNIX-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(code)) return json({ error: "صيغة كود التفعيل غير صحيحة" }, 400);
      const codeHash = await sha256(code);
      const { data: codeRow, error: lookupError } = await adminClient.from("bein_access_codes")
        .select("valid_days,used_by,revoked_at").eq("code_hash", codeHash).maybeSingle();
      if (lookupError) return json({ error: "تعذر التحقق من الكود" }, 500);
      if (!codeRow) return json({ error: "كود التفعيل غير صحيح" }, 404);
      if (codeRow.revoked_at) return json({ error: "هذا الكود ملغي من الإدارة" }, 409);
      if (codeRow.used_by) return json({ error: "هذا الكود مستخدم مسبقاً ولا يمكن مشاركته" }, 409);
      const expiry = new Date(Date.now() + Number(codeRow.valid_days) * 86400000).toISOString();
      const { data: redeemed, error: redeemError } = await adminClient.from("bein_access_codes")
        .update({ used_by: user.id, redeemed_at: new Date().toISOString(), access_expires_at: expiry })
        .eq("code_hash", codeHash).is("used_by", null).is("revoked_at", null)
        .select("id").maybeSingle();
      if (redeemError) return json({ error: "تعذر تفعيل الكود حالياً" }, 500);
      if (!redeemed) return json({ error: "تم استخدام هذا الكود من حساب آخر للتو" }, 409);
      return json({ success: true, expires_at: expiry });
    }
    if (action === "activate_subscription_code") {
      if (!isAuthenticated) return json({ error: "سجّل الدخول لتفعيل كود الباقة" }, 401);
      const user = (await adminClient.auth.getUser(token)).data.user;
      if (!user) return json({ error: "جلسة تسجيل الدخول غير صالحة" }, 401);
      const code = clean(body?.code).toUpperCase().replace(/[^A-Z0-9-]/g, "");
      if (!/^VELNIX-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(code)) return json({ error: "صيغة كود التفعيل غير صحيحة" }, 400);
      const codeHash = await sha256(code);
      const { data: codeRow, error: lookupError } = await adminClient.from("subscription_access_codes")
        .select("id,plan_code,valid_days,used_by,revoked_at").eq("code_hash", codeHash).maybeSingle();
      if (lookupError) return json({ error: "تعذر التحقق من الكود" }, 500);
      if (!codeRow) return json({ error: "كود الباقة غير صحيح" }, 404);
      if (codeRow.revoked_at) return json({ error: "هذا الكود ملغي من الإدارة" }, 409);
      if (codeRow.used_by) return json({ error: "هذا الكود مستخدم مسبقاً" }, 409);
      const now = new Date();
      const { data: current } = await adminClient.from("subscriptions").select("expires_at")
        .eq("user_id", user.id).eq("status", "active").gt("expires_at", now.toISOString())
        .order("expires_at", { ascending: false }).limit(1).maybeSingle();
      const startsAt = current?.expires_at && new Date(current.expires_at) > now ? new Date(current.expires_at) : now;
      const expiresAt = new Date(startsAt.getTime() + Number(codeRow.valid_days) * 86400000);
      const { data: redeemed, error: redeemError } = await adminClient.from("subscription_access_codes")
        .update({ used_by: user.id, redeemed_at: now.toISOString(), access_expires_at: expiresAt.toISOString() })
        .eq("id", codeRow.id).is("used_by", null).is("revoked_at", null).select("id").maybeSingle();
      if (redeemError) return json({ error: "تعذر تفعيل الكود حالياً" }, 500);
      if (!redeemed) return json({ error: "تم استخدام هذا الكود من حساب آخر للتو" }, 409);
      await adminClient.from("subscriptions").update({ status: "cancelled" }).eq("user_id", user.id).eq("status", "active");
      const { data: subscription, error: subscriptionError } = await adminClient.from("subscriptions")
        .insert({ user_id: user.id, plan_code: codeRow.plan_code, status: "active", starts_at: startsAt.toISOString(), expires_at: expiresAt.toISOString() })
        .select("id,plan_code,status,starts_at,expires_at").single();
      if (subscriptionError) {
        await adminClient.from("subscription_access_codes").update({ used_by: null, redeemed_at: null, access_expires_at: null }).eq("id", codeRow.id);
        return json({ error: "تعذر حفظ الاشتراك. حاول مرة أخرى." }, 500);
      }
      return json({ success: true, subscription, expires_at: expiresAt.toISOString() });
    }

    if (action.startsWith("admin_") && action.includes("bein")) {
      if (!isAdmin) return json({ error: "Administrator access required" }, 403);
      if (action === "admin_generate_bein_codes") {
        const count = Math.max(1, Math.min(50, Math.floor(Number(body?.count) || 1)));
        const validDays = Math.max(1, Math.min(3650, Math.floor(Number(body?.valid_days) || 30)));
        const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        const codes: string[] = [];
        const rows = [];
        const adminUser = (await adminClient.auth.getUser(token)).data.user;
        if (!adminUser) return json({ error: "Administrator session is invalid" }, 401);
        for (let i = 0; i < count; i++) {
          const bytes = crypto.getRandomValues(new Uint8Array(12));
          const raw = [...bytes].map((b) => alphabet[b % alphabet.length]).join("");
          const code = "VELNIX-" + raw.slice(0, 4) + "-" + raw.slice(4, 8) + "-" + raw.slice(8, 12);
          codes.push(code);
          rows.push({ code_hash: await sha256(code), code_hint: "••••-" + raw.slice(-4), valid_days: validDays, created_by: adminUser.id });
        }
        const { error } = await adminClient.from("bein_access_codes").insert(rows);
        if (error) return json({ error: "تعذر إنشاء الأكواد. حاول مرة أخرى." }, 500);
        return json({ success: true, count: codes.length, valid_days: validDays, codes });
      }
      if (action === "admin_list_bein_codes") {
        const { data, error } = await adminClient.from("bein_access_codes")
          .select("id,code_hint,valid_days,created_at,used_by,redeemed_at,access_expires_at,revoked_at")
          .order("created_at", { ascending: false }).limit(100);
        if (error) return json({ error: "تعذر تحميل قائمة الأكواد" }, 500);
        return json({ success: true, codes: data ?? [] });
      }
      if (action === "admin_revoke_bein_code") {
        const id = clean(body?.code_id);
        if (!id) return json({ error: "code_id is required" }, 400);
        const { error } = await adminClient.from("bein_access_codes").update({ revoked_at: new Date().toISOString() }).eq("id", id).is("revoked_at", null);
        if (error) return json({ error: "تعذر إلغاء الكود" }, 500);
        return json({ success: true });
      }
    }

    if (action === "stream" || action === "proxy") {
      const channelId = clean(req.method === "GET" ? requestUrl.searchParams.get("channel_id") : body?.channel_id);
      if (!channelId) return json({ error: "channel_id is required" }, 400);
      const protectedBein = await isBeinChannel(channelId);
      if (action === "stream") {
        if (!isAuthenticated) return json({ error: "سجّل الدخول لمشاهدة القنوات" }, 401);
        if (protectedBein) {
          const user = (await adminClient.auth.getUser(token)).data.user;
          const access = user ? await hasBeinAccess(adminClient, user.id) : null;
          if (!isAdmin && !access) return json({ error: "هذا القسم مقفول. أدخل كود تفعيل صالحاً." }, 403);
        }
      } else {
        const signedAccess = await verifyAccessToken(clean(requestUrl.searchParams.get("access_token")), channelId);
        if (!signedAccess) return new Response("A valid VELNIX sign-in is required", { status: 401, headers: cors });
        if (protectedBein) {
          const access = signedAccess.admin ? true : await hasBeinAccess(adminClient, String(signedAccess.user_id));
          if (!access) return new Response("beIN access required", { status: 403, headers: cors });
        }
      }

      const { data, error } = await adminClient
        .from("channel_streams")
        .select("stream_url, stream_type")
        .eq("channel_id", channelId)
        .maybeSingle();
      if (error) return json({ error: "Could not load stream" }, 500);
      if (!data) return json({ error: "Stream is not configured" }, 404);
      if (action === "stream") {
        const type = clean(data.stream_type).toLowerCase();
        let safeUrl = data.stream_url;
        const user = (await adminClient.auth.getUser(token)).data.user;
        if (user && ["hls","mpegts","ts","file"].includes(type)) {
          const query = new URLSearchParams({ channel_id: channelId });
          query.set("access_token", await signAccessToken({ user_id: user.id, admin: isAdmin, channel_id: channelId, resource_url: data.stream_url, exp: Math.floor(Date.now() / 1000) + 6 * 3600 }));
          safeUrl = supabaseUrl + "/functions/v1/xtream-gateway?" + query.toString();
        }
        return json({ stream_url: safeUrl, stream_type: type });
      }

      const signedAccess = await verifyAccessToken(clean(requestUrl.searchParams.get("access_token")), channelId);
      const upstreamUrl = clean(signedAccess?.resource_url || data.stream_url);
      let parsedUpstream: URL;
      try { parsedUpstream = new URL(upstreamUrl); } catch { return new Response("Invalid signed stream resource", { status: 400, headers: cors }); }
      if (!["http:","https:"].includes(parsedUpstream.protocol)) return new Response("Unsupported stream protocol", { status: 400, headers: cors });
      const upstreamHeaders = new Headers({ "Accept": "*/*", "Accept-Encoding": "identity", "User-Agent": "VELNIX-Stream-Proxy/2.0" });
      // Live IPTV transport streams must remain open-ended. Do not forward browser byte ranges.
      // A ranged response can make the browser treat a live channel as a short finite clip.
      const upstream = await fetch(parsedUpstream.toString(), {
        method: "GET",
        headers: upstreamHeaders,
        redirect: "follow",
      });
      if (!upstream.ok && upstream.status !== 206) {
        return new Response("Upstream stream unavailable (" + upstream.status + ")", {
          status: 502,
          headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
        });
      }
      const headers = new Headers(cors);
      headers.set("Cache-Control", "no-store, no-transform");
      headers.set("X-Accel-Buffering", "no");
      if (!upstream.body) return new Response("Upstream returned an empty stream", { status: 502, headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" } });
      const contentType = upstream.headers.get("Content-Type") || "";
      if (/mpegurl|\.m3u8/i.test(contentType) || ["hls","m3u8"].includes(clean(data.stream_type).toLowerCase()) || parsedUpstream.pathname.toLowerCase().endsWith(".m3u8")) {
        const playlist = await upstream.text();
        if (!playlist.trimStart().startsWith("#EXTM3U")) return new Response("Invalid HLS playlist", { status: 502, headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" } });
        const makeResourceUrl = async (raw: string) => {
          const absolute = new URL(raw, parsedUpstream).toString();
          const resourceToken = await signAccessToken({ user_id: signedAccess.user_id, admin: !!signedAccess.admin, channel_id: channelId, resource_url: absolute, exp: Math.floor(Date.now() / 1000) + 15 * 60 });
          const query = new URLSearchParams({ channel_id: channelId, access_token: resourceToken });
          return supabaseUrl + "/functions/v1/xtream-gateway?" + query.toString();
        };
        const lines = playlist.split(/\r?\n/);
        const rewritten: string[] = [];
        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed) { rewritten.push(line); continue; }
          if (trimmed.startsWith("#")) {
            const match = trimmed.match(/URI="([^"]+)"/);
            if (match) {
              const proxied = await makeResourceUrl(match[1]);
              rewritten.push(line.replace(match[0], 'URI="' + proxied + '"'));
            } else rewritten.push(line);
          } else {
            rewritten.push(await makeResourceUrl(trimmed));
          }
        }
        headers.set("Content-Type", "application/vnd.apple.mpegurl");
        headers.set("Accept-Ranges", "none");
        return new Response(rewritten.join("\n"), { status: 200, headers });
      }
      headers.set("Content-Type", contentType || "video/mp2t");
      // Never advertise a finite file length or byte-range support for a live stream.
      headers.set("Accept-Ranges", "none");
      headers.set("Access-Control-Expose-Headers", "Accept-Ranges");
      const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
      const relay = upstream.body.pipeTo(writable).catch((error) => console.error("VELNIX stream relay ended:", error));
      EdgeRuntime.waitUntil(relay);
      return new Response(readable, { status: upstream.status === 206 ? 200 : upstream.status, headers });
    }

    if (action === "epg_database") {
      if (!isAuthenticated) return json({ error: "Sign in to view the EPG" }, 401);
      const channelId = clean(body?.channel_id);
      if (!/^[0-9a-f-]{36}$/i.test(channelId)) return json({ error: "Valid channel_id is required" }, 400);
      const { data, error } = await adminClient.from("epg_programs")
        .select("id,title,description,category,starts_at,ends_at")
        .eq("channel_id", channelId).eq("is_published", true)
        .gte("ends_at", new Date(Date.now() - 2 * 3600000).toISOString())
        .lte("starts_at", new Date(Date.now() + 7 * 86400000).toISOString())
        .order("starts_at", { ascending: true }).limit(20);
      if (error) return json({ error: "تعذر تحميل دليل البرامج" }, 500);
      return json({ programs: data ?? [] });
    }

    if (action === "epg") {
      if (!isAuthenticated) return json({ error: "Sign in to view the EPG" }, 401);
      const streamId = clean(body?.stream_id);
      if (!streamId || !/^[0-9]+$/.test(streamId)) return json({ error: "Valid stream_id is required" }, 400);
      const baseValue = Deno.env.get("XTREAM_BASE_URL");
      const username = Deno.env.get("XTREAM_USERNAME");
      const password = Deno.env.get("XTREAM_PASSWORD");
      if (!baseValue || !username || !password) return json({ error: "EPG provider is not configured" }, 503);
      const base = new URL(baseValue);
      if (base.pathname.endsWith("/")) base.pathname = base.pathname.slice(0, -1);
      base.pathname += "/player_api.php";
      base.search = "";
      base.searchParams.set("username", username);
      base.searchParams.set("password", password);
      base.searchParams.set("action", "get_short_epg");
      base.searchParams.set("stream_id", streamId);
      base.searchParams.set("limit", "10");
      const response = await fetch(base, { signal: AbortSignal.timeout(12000) });
      if (!response.ok) return json({ error: "EPG provider returned HTTP " + response.status }, 502);
      const payload = await response.json();
      const listings = Array.isArray(payload?.epg_listings) ? payload.epg_listings.slice(0, 10) : [];
      return json({ epg_listings: listings });
    }

    if (action === "assistant") {
      if (!isAuthenticated) return json({ error: "Sign in to use the AI assistant" }, 401);
      const message = clean(body?.message).slice(0, 1200);
      if (!message) return json({ error: "A message is required" }, 400);
      const apiKey = Deno.env.get("OPENAI_API_KEY");
      if (!apiKey) return json({ error: "AI assistant is not configured. Add OPENAI_API_KEY to Supabase Edge Function secrets." }, 503);
      const model = Deno.env.get("OPENAI_MODEL") || "gpt-4o-mini";
      const response = await fetch("https://api.openai.com/v1/chat/completions", {
        method: "POST",
        headers: { "Authorization": "Bearer " + apiKey, "Content-Type": "application/json" },
        body: JSON.stringify({
          model,
          messages: [
            { role: "system", content: "You are the VELNIX support assistant. Reply in concise Iraqi Arabic when the user writes Arabic. Help with account login, channel playback, favorites, playlists, EPG, and site navigation. Never claim a channel works without a test. Never reveal provider credentials, stream URLs, secrets, or private account data. Do not provide subscription or payment promises." },
            { role: "user", content: message }
          ],
          max_tokens: 450,
          temperature: 0.35
        }),
        signal: AbortSignal.timeout(20000)
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) return json({ error: "AI provider request failed (" + response.status + ")" }, 502);
      const answer = clean(payload?.choices?.[0]?.message?.content);
      if (!answer) return json({ error: "AI provider returned an empty answer" }, 502);
      return json({ answer, model });
    }

    if (action.startsWith("admin_")) {
      if (!isAdmin) return json({ error: "Administrator access required" }, 403);

      if (action === "admin_generate_subscription_codes") {
        const count = Math.max(1, Math.min(100, Math.floor(Number(body?.count) || 1)));
        const validDays = Math.max(1, Math.min(3650, Math.floor(Number(body?.valid_days) || 30)));
        const plan = clean(body?.plan_code).toLowerCase();
        const allowedPlans = ["basic", "standard", "premium", "gold", "platinum"];
        if (!allowedPlans.includes(plan)) return json({ error: "نوع الباقة غير صالح" }, 400);
        const adminUser = (await adminClient.auth.getUser(token)).data.user;
        if (!adminUser) return json({ error: "Administrator session is invalid" }, 401);
        const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        const codes: string[] = [];
        const rows = [];
        for (let i = 0; i < count; i++) {
          const raw = [...crypto.getRandomValues(new Uint8Array(12))].map((b) => alphabet[b % alphabet.length]).join("");
          const code = "VELNIX-" + raw.slice(0, 4) + "-" + raw.slice(4, 8) + "-" + raw.slice(8, 12);
          codes.push(code);
          rows.push({ code_hash: await sha256(code), code_hint: "••••-" + raw.slice(-4), plan_code: plan, valid_days: validDays, created_by: adminUser.id });
        }
        const { error } = await adminClient.from("subscription_access_codes").insert(rows);
        if (error) return json({ error: "تعذر إنشاء أكواد الباقات" }, 500);
        return json({ success: true, count: codes.length, plan_code: plan, valid_days: validDays, codes });
      }

      if (action === "admin_list_subscription_codes") {
        const { data, error } = await adminClient.from("subscription_access_codes")
          .select("id,code_hint,plan_code,valid_days,created_at,used_by,redeemed_at,access_expires_at,revoked_at")
          .order("created_at", { ascending: false }).limit(200);
        if (error) return json({ error: "تعذر تحميل أكواد الباقات" }, 500);
        return json({ success: true, codes: data ?? [] });
      }

      if (action === "admin_revoke_subscription_code") {
        const id = clean(body?.code_id);
        if (!id) return json({ error: "code_id is required" }, 400);
        const { error } = await adminClient.from("subscription_access_codes")
          .update({ revoked_at: new Date().toISOString() }).eq("id", id).is("revoked_at", null);
        if (error) return json({ error: "تعذر إلغاء الكود" }, 500);
        return json({ success: true });
      }

      if (action === "admin_dashboard") {
        const countRows = async (table: string, query?: (q: any) => any) => {
          let q = adminClient.from(table).select("id", { count: "exact", head: true });
          if (query) q = query(q);
          const { count, error } = await q;
          if (error) throw error;
          return count ?? 0;
        };
        const [channels, profiles, subscriptions, epg] = await Promise.all([
          countRows("channels", (q) => q.eq("is_active", true)),
          countRows("profiles"),
          countRows("subscriptions", (q) => q.eq("status", "active").or("expires_at.is.null,expires_at.gt." + new Date().toISOString())),
          countRows("epg_programs", (q) => q.eq("is_published", true)),
        ]);
        return json({ success: true, stats: { active_channels: channels, users: profiles, active_subscriptions: subscriptions, epg_programs: epg } });
      }

      if (action === "admin_search_user") {
        const email = clean(body?.email).toLowerCase();
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return json({ error: "اكتب بريداً إلكترونياً صحيحاً" }, 400);
        const { data, error } = await adminClient.auth.admin.listUsers({ page: 1, perPage: 1000 });
        if (error) return json({ error: "تعذر البحث عن الحساب" }, 500);
        const user = (data.users ?? []).find((u) => clean(u.email).toLowerCase() === email);
        if (!user) return json({ error: "ما لكيت حساب بهذا البريد" }, 404);
        const { data: profile } = await adminClient.from("profiles").select("display_name").eq("id", user.id).maybeSingle();
        const { data: subscriptions } = await adminClient.from("subscriptions")
          .select("id,plan_code,status,starts_at,expires_at,created_at")
          .eq("user_id", user.id).order("created_at", { ascending: false }).limit(5);
        return json({ success: true, user: { id: user.id, email: user.email, display_name: profile?.display_name || user.user_metadata?.display_name || "" }, subscriptions: subscriptions ?? [] });
      }

      if (action === "admin_set_subscription") {
        const email = clean(body?.email).toLowerCase();
        const plan = clean(body?.plan_code).toLowerCase();
        const days = Math.max(1, Math.min(3650, Math.floor(Number(body?.days) || 30)));
        const allowedPlans = ["basic", "standard", "premium", "gold", "platinum"];
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return json({ error: "بريد الحساب غير صحيح" }, 400);
        if (!allowedPlans.includes(plan)) return json({ error: "نوع الباقة غير صالح" }, 400);
        const { data: users, error: usersError } = await adminClient.auth.admin.listUsers({ page: 1, perPage: 1000 });
        if (usersError) return json({ error: "تعذر البحث عن الحساب" }, 500);
        const user = (users.users ?? []).find((u) => clean(u.email).toLowerCase() === email);
        if (!user) return json({ error: "الحساب غير موجود" }, 404);
        const now = new Date();
        const expiry = new Date(now.getTime() + days * 86400000);
        const { error: cancelError } = await adminClient.from("subscriptions")
          .update({ status: "cancelled" }).eq("user_id", user.id).eq("status", "active");
        if (cancelError) return json({ error: "تعذر تحديث الاشتراك السابق" }, 500);
        const { data: row, error } = await adminClient.from("subscriptions")
          .insert({ user_id: user.id, plan_code: plan, status: "active", starts_at: now.toISOString(), expires_at: expiry.toISOString() })
          .select("id,plan_code,status,starts_at,expires_at").single();
        if (error) return json({ error: "تعذر تفعيل الاشتراك" }, 500);
        return json({ success: true, subscription: row, user: { email: user.email, id: user.id } });
      }

      if (action === "admin_save_epg") {
        const channelId = clean(body?.channel_id);
        const title = clean(body?.title).slice(0, 240);
        const startsAt = new Date(body?.starts_at);
        const endsAt = new Date(body?.ends_at);
        if (!/^[0-9a-f-]{36}$/i.test(channelId) || !title || Number.isNaN(startsAt.getTime()) || Number.isNaN(endsAt.getTime()) || endsAt <= startsAt) {
          return json({ error: "تحقق من القناة وعنوان البرنامج ووقت البداية والنهاية" }, 400);
        }
        const record: Record<string, unknown> = {
          channel_id: channelId, title, description: clean(body?.description).slice(0, 2000) || null,
          category: clean(body?.category).slice(0, 80) || null,
          starts_at: startsAt.toISOString(), ends_at: endsAt.toISOString(),
          is_published: body?.is_published !== false, updated_at: new Date().toISOString(),
        };
        const id = clean(body?.id);
        if (id) record.id = id;
        const { data, error } = await adminClient.from("epg_programs").upsert(record).select("id,title,starts_at,ends_at").single();
        if (error) return json({ error: "تعذر حفظ البرنامج في دليل القنوات" }, 500);
        return json({ success: true, program: data });
      }

      if (action === "admin_check_streams") {
        const offset = Math.max(0, Math.min(100000, Math.floor(Number(body?.offset) || 0)));
        const limit = Math.max(1, Math.min(20, Math.floor(Number(body?.limit) || 20)));
        const { data: channels, error: channelError } = await adminClient
          .from("channels")
          .select("id,name_ar,name_en,stream_type")
          .eq("is_active", true)
          .order("name_ar", { ascending: true })
          .range(offset, offset + limit - 1);
        if (channelError) return json({ error: "تعذر تحميل دفعة القنوات للفحص" }, 500);
        const ids = (channels ?? []).map((row) => row.id);
        if (!ids.length) return json({ success: true, offset, count: 0, results: [], done: true });
        const { data: streams, error: streamError } = await adminClient
          .from("channel_streams")
          .select("channel_id,stream_url,stream_type")
          .in("channel_id", ids);
        if (streamError) return json({ error: "تعذر تحميل مصادر القنوات" }, 500);
        const streamMap = new Map((streams ?? []).map((row) => [row.channel_id, row]));
        let cursor = 0;
        const results = new Array(channels.length);
        const checkOne = async (channel, index) => {
          const stream = streamMap.get(channel.id);
          if (!stream?.stream_url) {
            results[index] = { id: channel.id, name_ar: channel.name_ar, name_en: channel.name_en, ok: false, status: null, latency_ms: 0, detail: "لا يوجد رابط بث مربوط" };
            return;
          }
          const started = Date.now();
          const controller = new AbortController();
          const timer = setTimeout(() => controller.abort(), 7000);
          try {
            const response = await fetch(stream.stream_url, {
              method: "GET",
              headers: { "Range": "bytes=0-1023", "Accept": "*/*", "User-Agent": "VELNIX-Health-Check/2.0" },
              redirect: "follow",
              signal: controller.signal,
            });
            const status = response.status;
            const type = clean(stream.stream_type || channel.stream_type).toLowerCase();
            let sample = "";
            if (response.body) {
              const reader = response.body.getReader();
              try {
                const part = await reader.read();
                if (part.value && ["hls", "m3u8", "dash", "mpd"].includes(type)) sample = new TextDecoder().decode(part.value.slice(0, 1024));
                await reader.cancel().catch(() => {});
              } finally {
                reader.releaseLock?.();
              }
            }
            const contentType = (response.headers.get("content-type") || "").toLowerCase();
            const playlistType = ["hls", "m3u8", "dash", "mpd"].includes(type);
            const playlistValid = !playlistType || (type === "hls" || type === "m3u8"
              ? sample.trimStart().startsWith("#EXTM3U")
              : sample.includes("<MPD") || sample.includes("<?xml"));
            const htmlError = contentType.includes("text/html") && playlistType;
            const ok = (response.ok || status === 206) && playlistValid && !htmlError;
            results[index] = {
              id: channel.id, name_ar: channel.name_ar, name_en: channel.name_en,
              ok, status, latency_ms: Date.now() - started,
              detail: ok ? "استجابة البث سليمة" : htmlError ? "المصدر أعاد صفحة HTML بدل البث" : !playlistValid ? "ملف البث غير صالح أو لا يحتوي قائمة تشغيل" : "استجابة HTTP غير ناجحة",
            };
          } catch (error) {
            results[index] = {
              id: channel.id, name_ar: channel.name_ar, name_en: channel.name_en,
              ok: false, status: null, latency_ms: Date.now() - started,
              detail: error instanceof Error && error.name === "AbortError" ? "انتهت مهلة الاختبار (7 ثوانٍ)" : "تعذر الاتصال بمصدر البث",
            };
          } finally {
            clearTimeout(timer);
          }
        };
        const workers = Array.from({ length: Math.min(5, channels.length) }, async () => {
          while (cursor < channels.length) {
            const index = cursor++;
            await checkOne(channels[index], index);
          }
        });
        await Promise.all(workers);
        return json({
          success: true, offset, count: channels.length,
          done: channels.length < limit,
          working: results.filter((row) => row.ok).length,
          failed: results.filter((row) => !row.ok).length,
          results,
        });
      }

      if (action === "admin_import_m3u") {
        const playlist = typeof body?.content === "string" ? body.content : "";
        if (!playlist || playlist.length > 2000000) return json({ error: "ملف M3U فارغ أو أكبر من الحد المسموح" }, 400);
        if (!/^\s*#EXTM3U/m.test(playlist)) return json({ error: "الملف ليس بصيغة M3U صحيحة" }, 400);
        const lines = playlist.replace(/^\uFEFF/, "").split(/\r?\n/);
        const parsed = [];
        let meta = null;
        for (const raw of lines) {
          const line = raw.trim();
          if (!line) continue;
          if (line.startsWith("#EXTINF:")) {
            const attr = (key) => {
              const re = new RegExp(key + '="([^"]*)"', "i");
              const match = line.match(re);
              return match ? clean(match[1]) : "";
            };
            const comma = line.lastIndexOf(",");
            const name = clean(comma >= 0 ? line.slice(comma + 1) : attr("tvg-name"), "قناة");
            meta = { name, group: attr("group-title") || "منوعات", logo: /^https:\/\//i.test(attr("tvg-logo")) ? attr("tvg-logo") : null, tvg: attr("tvg-id") };
          } else if (!line.startsWith("#") && meta && /^https?:\/\//i.test(line)) {
            const ext = new URL(line).pathname.split(".").pop()?.toLowerCase() || "ts";
            const type = ext === "m3u8" ? "hls" : ext === "mpd" ? "dash" : "file";
            parsed.push({ ...meta, url: line, type });
            meta = null;
          }
        }
        if (!parsed.length) return json({ error: "لم يتم العثور على قنوات وروابط صالحة داخل الملف" }, 400);
        const digest = async (value) => {
          const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
          return [...new Uint8Array(hash)].map(x => x.toString(16).padStart(2,"0")).join("");
        };
        const groups = [...new Set(parsed.map(x => x.group))];
        const categoryIds = new Map();
        const categoryRows = groups.map((group,i) => ({
          source_provider:"m3u", source_external_id:group,
          slug:"m3u-" + group.toLowerCase().replace(/[^a-z0-9\u0600-\u06ff_-]+/g,"-").slice(0,42) + "-" + i,
          name_ar:group, name_en:group, sort_order:i, is_active:true
        }));
        for (const batch of chunk(categoryRows,100)) {
          const {data,error}=await adminClient.from("channel_categories").upsert(batch,{onConflict:"source_provider,source_external_id"}).select("id,source_external_id");
          if(error) throw new Error("تعذر حفظ تصنيفات M3U: " + error.message);
          for(const row of data ?? []) categoryIds.set(row.source_external_id,row.id);
        }
        const rows = [];
        for (const item of parsed) rows.push({item,externalId:await digest(item.url)});
        let imported=0;
        for (const batch of chunk(rows,100)) {
          const channelBatch=batch.map(({item,externalId})=>({
            source_provider:"m3u",source_external_id:externalId,
            name_ar:item.name,name_en:item.name,description:"M3U",
            logo_url:item.logo,category_id:categoryIds.get(item.group) ?? null,
            stream_type:item.type,is_active:true
          }));
          const {data,error}=await adminClient.from("channels").upsert(channelBatch,{onConflict:"source_provider,source_external_id"}).select("id,source_external_id");
          if(error) throw new Error("تعذر حفظ مجموعة قنوات: " + error.message);
          const ids=new Map((data ?? []).map(x=>[x.source_external_id,x.id]));
          const streamBatch=batch.map(({item,externalId})=>({
            channel_id:ids.get(externalId),stream_url:item.url,stream_type:item.type,
            source_external_id:externalId,updated_at:new Date().toISOString()
          })).filter(x=>x.channel_id);
          if(streamBatch.length){
            const {error:streamError}=await adminClient.from("channel_streams").upsert(streamBatch,{onConflict:"channel_id"});
            if(streamError) throw new Error("تعذر حفظ مصادر البث: " + streamError.message);
            imported+=streamBatch.length;
          }
        }
        const { error: deactivateError } = await adminClient.from("channels").update({is_active:false,updated_at:new Date().toISOString()}).eq("source_provider","xtream");
        if (deactivateError) return json({ error: "تم استيراد القنوات لكن تعذر تعطيل قنوات Xtream القديمة. راجع لوحة الإدارة قبل إعادة الاستيراد." }, 500);
        return json({success:true,channels:imported,categories:groups.length,provider:"m3u"});
      }

      if (action === "admin_save_channel") {
        const channelId = clean(body?.channel_id);
        const nameAr = clean(body?.name_ar);
        const nameEn = clean(body?.name_en);
        const logoUrl = clean(body?.logo_url);
        const streamUrl = clean(body?.stream_url);
        const categoryId = clean(body?.category_id);
        const countryCode = clean(body?.country_code).toUpperCase();
        const languageCode = clean(body?.language_code).toLowerCase();
        if (countryCode && !/^[A-Z]{2,3}$/.test(countryCode)) return json({ error: "Country code must be ISO alpha-2 or alpha-3" }, 400);
        if (languageCode && !/^[a-z]{2,3}(-[a-z0-9]{2,8})?$/.test(languageCode)) return json({ error: "Language code is invalid" }, 400);
        if (!nameAr && !nameEn) return json({ error: "A channel name is required" }, 400);
        if (logoUrl && !/^https:\/\//i.test(logoUrl)) return json({ error: "Logo URL must use HTTPS" }, 400);
        if (streamUrl && !/^https?:\/\//i.test(streamUrl)) return json({ error: "Stream URL must use HTTP or HTTPS" }, 400);
        const row: Record<string, unknown> = {
          name_ar: nameAr || nameEn,
          name_en: nameEn || nameAr,
          logo_url: logoUrl || null,
          country_code: countryCode || null,
          language_code: languageCode || null,
          is_active: true,
          updated_at: new Date().toISOString(),
        };
        if (categoryId) row.category_id = categoryId;
        let savedId = channelId;
        if (channelId) {
          const { error } = await adminClient.from("channels").update(row).eq("id", channelId);
          if (error) return json({ error: "Could not update channel" }, 500);
        } else {
          row.source_provider = "manual";
          const { data, error } = await adminClient.from("channels").insert(row).select("id").single();
          if (error || !data) return json({ error: "Could not create channel" }, 500);
          savedId = data.id;
        }
        if (streamUrl) {
          const streamType = clean(body?.stream_type, "hls").toLowerCase();
          if (!["hls", "dash", "file", "embed"].includes(streamType)) return json({ error: "Unsupported stream type" }, 400);
          const { error } = await adminClient.from("channel_streams").upsert({
            channel_id: savedId, stream_url: streamUrl, stream_type: streamType, updated_at: new Date().toISOString()
          }, { onConflict: "channel_id" });
          if (error) return json({ error: "Channel saved, but stream source could not be saved" }, 500);
        }
        return json({ success: true, channel_id: savedId });
      }

      if (action === "admin_save_category") {
        const categoryId = clean(body?.category_id);
        const slug = clean(body?.slug).toLowerCase();
        const nameAr = clean(body?.name_ar);
        const nameEn = clean(body?.name_en);
        const sortOrder = Number.isFinite(Number(body?.sort_order)) ? Number(body.sort_order) : 0;
        if (!/^[a-z0-9][a-z0-9_-]{1,47}$/.test(slug)) return json({ error: "Category slug must be 2-48 lowercase letters, digits, dashes or underscores" }, 400);
        if (!nameAr && !nameEn) return json({ error: "A category name is required" }, 400);
        const row = { slug, name_ar: nameAr || nameEn, name_en: nameEn || nameAr, sort_order: sortOrder, is_active: true };
        if (categoryId) {
          const { error } = await adminClient.from("channel_categories").update(row).eq("id", categoryId);
          if (error) return json({ error: "Could not update category" }, 500);
          return json({ success: true, category_id: categoryId });
        }
        const { data, error } = await adminClient.from("channel_categories").insert(row).select("id").single();
        if (error || !data) return json({ error: "Could not create category" }, 500);
        return json({ success: true, category_id: data.id });
      }

      if (action === "admin_deactivate_channel") {
        const channelId = clean(body?.channel_id);
        if (!channelId) return json({ error: "channel_id is required" }, 400);
        const { error } = await adminClient.from("channels").update({ is_active: false, updated_at: new Date().toISOString() }).eq("id", channelId);
        if (error) return json({ error: "Could not deactivate channel" }, 500);
        return json({ success: true });
      }

      if (action === "admin_test_stream") {
        const channelId = clean(body?.channel_id);
        if (!channelId) return json({ error: "channel_id is required" }, 400);
        const { data, error } = await adminClient.from("channel_streams").select("stream_url").eq("channel_id", channelId).maybeSingle();
        if (error || !data?.stream_url) return json({ success: false, message: "No stream source is configured" }, 404);
        const controller = new AbortController();
        const started = Date.now();
        const timer = setTimeout(() => controller.abort(), 8000);
        try {
          const response = await fetch(data.stream_url, { method: "GET", headers: { "Range": "bytes=0-0", "User-Agent": "VELNIX-Health-Check/1.0" }, signal: controller.signal, redirect: "follow" });
          const status = response.status;
          await response.body?.cancel().catch(() => {});
          return json({ success: response.ok || status === 206, status, latency_ms: Date.now() - started });
        } catch (error) {
          return json({ success: false, message: error instanceof Error ? error.name === "AbortError" ? "Health check timed out" : error.message : "Health check failed" });
        } finally {
          clearTimeout(timer);
        }
      }

      if (action === "admin_enrich_catalog") {
        const [channelsResponse, logosResponse, feedsResponse] = await Promise.all([
          fetch("https://iptv-org.github.io/api/channels.json", { signal: AbortSignal.timeout(25000) }),
          fetch("https://iptv-org.github.io/api/logos.json", { signal: AbortSignal.timeout(25000) }),
          fetch("https://iptv-org.github.io/api/feeds.json", { signal: AbortSignal.timeout(25000) })
        ]);
        if (!channelsResponse.ok || !logosResponse.ok || !feedsResponse.ok) return json({ error: "Public channel metadata provider is temporarily unavailable" }, 502);
        const [externalChannels, externalLogos, feeds] = await Promise.all([channelsResponse.json(), logosResponse.json(), feedsResponse.json()]);
        if (!Array.isArray(externalChannels) || !Array.isArray(externalLogos) || !Array.isArray(feeds)) return json({ error: "Unexpected metadata provider response" }, 502);
        const normalizeName = (value: unknown) => String(value ?? "")
          .normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase()
          .replace(/\b(uhd|fhd|hd|sd|4k|8k|1080p|720p|576p|live)\b/g, " ")
          .replace(/[^a-z0-9\u0600-\u06ff]+/g, " ").trim().replace(/\s+/g, " ");
        const externalById = new Map<string, Record<string, unknown>>(externalChannels.map((x: Record<string, unknown>) => [clean(x.id), x]));
        const idsByName = new Map<string, Set<string>>();
        for (const item of externalChannels) {
          const id = clean(item?.id);
          if (!id) continue;
          for (const name of [item?.name, ...(Array.isArray(item?.alt_names) ? item.alt_names : [])]) {
            const key = normalizeName(name);
            if (!key || key.length < 3) continue;
            if (!idsByName.has(key)) idsByName.set(key, new Set());
            idsByName.get(key)!.add(id);
          }
        }
        const logoByChannel = new Map<string, string>();
        for (const item of externalLogos) {
          const id = clean(item?.channel), url = clean(item?.url);
          if (id && item?.in_use === true && /^https:\/\//i.test(url) && !logoByChannel.has(id)) logoByChannel.set(id, url);
        }
        const languagesByChannel = new Map<string, string>();
        for (const feed of feeds) {
          const id = clean(feed?.channel);
          if (!id || feed?.is_main !== true || !Array.isArray(feed?.languages)) continue;
          const codes = [...new Set(feed.languages.map((x: unknown) => clean(x).toLowerCase()).filter((x: string) => /^[a-z]{3}$/.test(x)))];
          if (codes.length === 1 && !languagesByChannel.has(id)) languagesByChannel.set(id, codes[0]);
        }
        const localRows: Record<string, unknown>[] = [];
        for (let offset = 0; offset < 5000; offset += 500) {
          const { data, error } = await adminClient.from("channels")
            .select("id,name_ar,name_en,logo_url,country_code,language_code")
            .eq("is_active", true).range(offset, offset + 499);
          if (error) return json({ error: "Could not read the channel catalogue for metadata matching" }, 500);
          localRows.push(...(data ?? []));
          if (!data || data.length < 500) break;
        }
        const updates: Record<string, unknown>[] = [];
        let ambiguous = 0;
        for (const row of localRows) {
          const candidates = new Set<string>();
          for (const name of [row.name_en, row.name_ar]) {
            const key = normalizeName(name);
            const ids = idsByName.get(key);
            if (ids?.size === 1) for (const id of ids) candidates.add(id);
            else if (ids && ids.size > 1) ambiguous++;
          }
          if (candidates.size !== 1) { if (candidates.size > 1) ambiguous++; continue; }
          const externalId = [...candidates][0];
          const external = externalById.get(externalId);
          if (!external) continue;
          const patch: Record<string, unknown> = { id: row.id };
          if (!row.logo_url && logoByChannel.has(externalId)) patch.logo_url = logoByChannel.get(externalId);
          if (!row.country_code && /^[A-Z]{2}$/.test(clean(external.country))) patch.country_code = clean(external.country);
          if (!row.language_code && languagesByChannel.has(externalId)) patch.language_code = languagesByChannel.get(externalId);
          if (Object.keys(patch).length > 1) updates.push(patch);
        }
        let updated = 0;
        for (let i = 0; i < updates.length; i += 200) {
          const batch = updates.slice(i, i + 200);
          const { error } = await adminClient.from("channels").upsert(batch, { onConflict: "id" });
          if (error) return json({ error: "Metadata matched, but a catalogue update batch failed" }, 500);
          updated += batch.length;
        }
        return json({ success: true, matched: updated, logos_available: logoByChannel.size, ambiguous, scanned: localRows.length });
      }

      if (action === "admin_bulk_logos") {
        const rows = Array.isArray(body?.rows) ? body.rows.slice(0, 100) : [];
        let updatedCount = 0;
        for (const item of rows) {
          const id = clean(item?.channel_id);
          const logo = clean(item?.logo_url);
          if (!id || !/^https:\/\//i.test(logo)) continue;
          const { error } = await adminClient.from("channels").update({ logo_url: logo, updated_at: new Date().toISOString() }).eq("id", id);
          if (!error) updatedCount++;
        }
        return json({ success: true, updated: updatedCount, received: rows.length });
      }

      return json({ error: "Unsupported administrator action" }, 400);
    }


    if (action === "extract_xtream") {
      if (!isAdmin) return json({ error: "هذا الحساب غير مخوّل لاستخراج بيانات Xtream." }, 403);
      const baseInput = clean(body?.base_url);
      const username = clean(body?.username);
      const password = String(body?.password ?? "");
      if (!baseInput || !username || !password) return json({ error: "أدخل رابط السيرفر واسم المستخدم وكلمة المرور." }, 400);
      let base: URL;
      try { base = new URL(baseInput); } catch { return json({ error: "رابط السيرفر غير صحيح." }, 400); }
      if (!["http:", "https:"].includes(base.protocol) || base.username || base.password) return json({ error: "استخدم رابط HTTP أو HTTPS بدون بيانات دخول داخله." }, 400);
      const host = base.hostname.toLowerCase();
      if (host === "localhost" || host.endsWith(".localhost") || host === "127.0.0.1" || host === "::1" ||
          /^10\./.test(host) || /^192\.168\./.test(host) || /^172\.(1[6-9]|2\d|3[01])\./.test(host) ||
          host === "0.0.0.0" || host.endsWith(".local")) return json({ error: "عنوان السيرفر غير مسموح." }, 400);
      base.pathname = base.pathname.replace(/\/$/, "").replace(/\/player_api\.php$/i, "") + "/player_api.php";
      base.search = "";
      const apiUrl = (actionName?: string) => {
        const u = new URL(base.toString());
        u.searchParams.set("username", username);
        u.searchParams.set("password", password);
        if (actionName) u.searchParams.set("action", actionName);
        return u;
      };
      const getJson = async (actionName?: string) => {
        const response = await fetch(apiUrl(actionName), {
          headers: { "Accept": "application/json", "User-Agent": "VELNIX-Extractor/1.0" },
          signal: AbortSignal.timeout(40000),
          redirect: "follow"
        });
        if (!response.ok) throw new Error("خادم Xtream أعاد HTTP " + response.status);
        return await response.json();
      };
      const account = await getJson();
      if (!account || typeof account !== "object" || !account.user_info || Number(account.user_info.auth) !== 1) {
        return json({ error: "بيانات الدخول غير صحيحة أو الاشتراك غير مفعّل." }, 401);
      }
      const [rawCategories, rawStreams] = await Promise.all([getJson("get_live_categories"), getJson("get_live_streams")]);
      if (!Array.isArray(rawCategories) || !Array.isArray(rawStreams)) return json({ error: "استجابة Xtream غير متوقعة." }, 502);
      if (rawStreams.length > 15000) return json({ error: "القائمة كبيرة جداً. تواصل مع المزوّد لتقسيمها حسب التصنيف." }, 413);
      const groups = new Map<string, string>();
      for (const c of rawCategories) {
        const id = clean(c?.category_id);
        if (id) groups.set(id, clean(c?.category_name, "بدون تصنيف"));
      }
      const channels = rawStreams.map((s) => {
        const id = clean(s?.stream_id);
        const ext = clean(s?.container_extension, "ts").toLowerCase().replace(/[^a-z0-9]/g, "") || "ts";
        const direct = clean(s?.direct_source);
        const streamUrl = /^https?:\/\//i.test(direct) ? direct :
          base.origin + base.pathname.replace(/\/player_api\.php$/i, "") + "/live/" +
          encodeURIComponent(username) + "/" + encodeURIComponent(password) + "/" + encodeURIComponent(id) + "." + ext;
        return {
          name: clean(s?.name, "Channel " + id),
          group: groups.get(clean(s?.category_id)) || "بدون تصنيف",
          logo: clean(s?.stream_icon),
          tvgId: clean(s?.epg_channel_id),
          url: streamUrl
        };
      }).filter((x) => x.name && x.url && /^https?:\/\//i.test(x.url));
      return json({ success: true, channels, categories: groups.size, count: channels.length });
    }

    if (action !== "import") return json({ error: "Unsupported action" }, 400);
    if (!isAdmin) return json({ error: "Administrator access required" }, 403);

    const baseValue = Deno.env.get("XTREAM_BASE_URL");
    const username = Deno.env.get("XTREAM_USERNAME");
    const password = Deno.env.get("XTREAM_PASSWORD");
    if (!baseValue || !username || !password) {
      return json({ error: "Xtream server secrets are missing" }, 500);
    }

    const base = new URL(baseValue);
    if (!["http:", "https:"].includes(base.protocol)) {
      return json({ error: "Xtream URL must use HTTP or HTTPS" }, 400);
    }
    base.pathname = base.pathname.replace(/\/$/, "") + "/player_api.php";
    base.search = "";
    base.searchParams.set("username", username);
    base.searchParams.set("password", password);

    const api = async (actionName: string) => {
      const url = new URL(base.toString());
      url.searchParams.set("action", actionName);
      const response = await fetch(url, { signal: AbortSignal.timeout(45000) });
      if (!response.ok) throw new Error(`Xtream API returned HTTP ${response.status}`);
      const value = await response.json();
      if (!Array.isArray(value)) throw new Error(`Unexpected response for ${actionName}`);
      return value as Record<string, unknown>[];
    };

    const [rawCategories, rawStreams] = await Promise.all([
      api("get_live_categories"),
      api("get_live_streams"),
    ]);
    if (!rawStreams.length) {
      return json({ error: "The Xtream API returned no live channels. Nothing was changed." }, 502);
    }

    const categories = rawCategories.map((c, i) => ({
      source_provider: "xtream",
      source_external_id: clean(c.category_id),
      slug: "xtream-" + clean(c.category_id).toLowerCase().replace(/[^a-z0-9_-]/g, "-").slice(0, 48),
      name_ar: clean(c.category_name, "Other"),
      name_en: clean(c.category_name, "Other"),
      sort_order: 100 + i,
      is_active: true,
    })).filter((c) => c.source_external_id);

    const categoryIdByExternal = new Map<string, string>();
    for (const batch of chunk(categories)) {
      const { data, error } = await adminClient
        .from("channel_categories")
        .upsert(batch, { onConflict: "source_provider,source_external_id" })
        .select("id, source_external_id");
      if (error) throw new Error("Could not save Xtream categories: " + error.message);
      for (const row of data ?? []) categoryIdByExternal.set(row.source_external_id, row.id);
    }

    const streamRows: Record<string, unknown>[] = [];
    const channelRows = rawStreams.map((s) => {
      const externalId = clean(s.stream_id);
      const categoryExternalId = clean(s.category_id);
      const ext = clean(s.container_extension, "ts").toLowerCase().replace(/[^a-z0-9]/g, "") || "ts";
      const streamType = ext === "m3u8" ? "hls" : ext === "mpd" ? "dash" : "file";
      let streamUrl = "";
      const direct = clean(s.direct_source);
      if (/^https?:\/\//i.test(direct)) {
        streamUrl = direct;
      } else {
        const streamBase = new URL(baseValue);
        streamBase.pathname = streamBase.pathname.replace(/\/$/, "") +
          "/live/" + encodeURIComponent(username) + "/" + encodeURIComponent(password) +
          "/" + encodeURIComponent(externalId) + "." + ext;
        streamBase.search = "";
        streamUrl = streamBase.toString();
      }
      const row = {
        source_provider: "xtream",
        source_external_id: externalId,
        name_ar: clean(s.name, "Channel " + externalId),
        name_en: clean(s.name, "Channel " + externalId),
        description: "Imported from Xtream",
        logo_url: clean(s.stream_icon) || null,
        category_id: categoryIdByExternal.get(categoryExternalId) ?? null,
        stream_type: streamType,
        is_active: true,
      };
      return { row, externalId, streamUrl, streamType };
    }).filter((x) => x.externalId);

    let imported = 0;
    for (const batch of chunk(channelRows)) {
      const { data, error } = await adminClient
        .from("channels")
        .upsert(batch.map((x) => x.row), { onConflict: "source_provider,source_external_id" })
        .select("id, source_external_id");
      if (error) throw new Error("Could not save channels: " + error.message);
      const idByExternal = new Map((data ?? []).map((r) => [r.source_external_id, r.id]));
      const streams = batch.map((x) => ({
        channel_id: idByExternal.get(x.externalId),
        stream_url: x.streamUrl,
        stream_type: x.streamType,
        source_external_id: x.externalId,
        updated_at: new Date().toISOString(),
      })).filter((x) => x.channel_id);
      if (streams.length) {
        const { error: streamError } = await adminClient
          .from("channel_streams")
          .upsert(streams, { onConflict: "channel_id" });
        if (streamError) throw new Error("Could not save protected stream records: " + streamError.message);
        imported += streams.length;
      }
    }

    return json({
      success: true,
      categories: categories.length,
      channels: imported,
      message: "Live channel catalog imported. Stream URLs are stored separately behind subscription checks.",
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unexpected import error";
    return json({ error: message }, 502);
  }
});