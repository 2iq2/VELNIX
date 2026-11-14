import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" },
});
const clean = (v: unknown) => String(v ?? "").replace(/[\u0000-\u001f]/g, "").trim();
const dateKey = (d: Date) => d.toISOString().slice(0, 10).replaceAll("-", "");
const LEAGUES = [
  ["eng.1", "Premier League"], ["esp.1", "La Liga"], ["ger.1", "Bundesliga"],
  ["ita.1", "Serie A"], ["fra.1", "Ligue 1"], ["uefa.champions", "UEFA Champions League"],
  ["ksa.1", "Saudi Pro League"], ["ira.1", "Iraqi Stars League"],
];
const stateFor = (event: any): string => {
  const state = clean(event?.status?.type?.state).toLowerCase();
  const detail = clean(event?.status?.type?.name + " " + event?.status?.type?.description).toLowerCase();
  if (detail.includes("postpon")) return "postponed";
  if (["in", "live"].includes(state)) return "live";
  if (["post", "finished"].includes(state) || detail.includes("final")) return "finished";
  return "scheduled";
};
const numericScore = (v: unknown): number | null => {
  const n = Number(String(v ?? "").replace(/[^0-9]/g, ""));
  return Number.isFinite(n) && String(v ?? "").trim() !== "" ? n : null;
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    const bearer = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    if (!supabaseUrl || !anonKey || !serviceKey || !bearer) return json({ error: "Missing server configuration or session" }, 503);
    const authClient = createClient(supabaseUrl, anonKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: auth, error: authError } = await authClient.auth.getUser(bearer);
    if (authError || !auth.user) return json({ error: "Authentication required" }, 401);
    const admin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: cache } = await admin.from("tv_sports_feed_state").select("last_synced_at").eq("id", true).maybeSingle();
    const last = cache?.last_synced_at ? Date.parse(cache.last_synced_at) : 0;
    if (Date.now() - last < 10 * 60 * 1000) return json({ success: true, cached: true, last_synced_at: cache.last_synced_at });
    const now = new Date(), from = new Date(now.getTime() - 2 * 86400000), to = new Date(now.getTime() + 8 * 86400000);
    const range = dateKey(from) + "-" + dateKey(to), errors: string[] = [], rows: Record<string, unknown>[] = [];
    for (const [league, title] of LEAGUES) {
      try {
        const url = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + encodeURIComponent(league) + "/scoreboard?dates=" + range + "&limit=100";
        const response = await fetch(url, { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(12000) });
        if (!response.ok) { errors.push(league + ":" + response.status); continue; }
        const payload = await response.json();
        for (const event of Array.isArray(payload?.events) ? payload.events : []) {
          const competition = event?.competitions?.[0], competitors = Array.isArray(competition?.competitors) ? competition.competitors : [];
          const home = competitors.find((x: any) => x?.homeAway === "home"), away = competitors.find((x: any) => x?.homeAway === "away");
          const starts = new Date(event?.date);
          if (!home || !away || !Number.isFinite(starts.getTime())) continue;
          rows.push({
            home_team: clean(home?.team?.displayName || home?.team?.name).slice(0, 100),
            away_team: clean(away?.team?.displayName || away?.team?.name).slice(0, 100),
            competition: clean(event?.season?.name || competition?.series?.title || title).slice(0, 120),
            starts_at: starts.toISOString(), status: stateFor(event),
            home_score: numericScore(home?.score), away_score: numericScore(away?.score),
            source_provider: "espn", source_event_id: clean(event?.id),
          });
        }
      } catch (error) { errors.push(league + ":" + (error instanceof Error ? error.message : "fetch failed")); }
    }
    let upserted = 0;
    if (rows.length) {
      const { error } = await admin.from("tv_match_fixtures").upsert(rows, { onConflict: "source_provider,source_event_id" });
      if (error) throw new Error("Fixture save failed: " + error.message);
      upserted = rows.length;
    }
    await admin.from("tv_sports_feed_state").upsert({
      id: true, last_synced_at: new Date().toISOString(),
      last_error: errors.length ? errors.slice(0, 8).join("; ").slice(0, 1000) : null,
      updated_at: new Date().toISOString(),
    }, { onConflict: "id" });
    return json({ success: true, cached: false, upserted, leagues: LEAGUES.length, source: "ESPN public scoreboard API", warnings: errors });
  } catch (error) {
    console.error("TV sports feed error", error);
    return json({ error: error instanceof Error ? error.message : "Sports feed failed" }, 500);
  }
});
