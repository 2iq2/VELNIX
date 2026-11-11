package com.velnix.tv

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VelnixApi(private val context: Context) {
    companion object {
        private const val API = "https://shungcjflcjwubdlhyzq.supabase.co"
        private const val KEY = "sb_publishable_stitJ7MdKnH573UQ5GRbYg_3XdSgEGo"
        private const val SESSION = "auth_session"
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()
    private val prefs by lazy {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "velnix_secure", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private var current: UserSession? = null

    fun session(): UserSession? {
        if (current != null) return current
        val raw = prefs.getString(SESSION, null) ?: return null
        return try {
            val j = JSONObject(raw)
            UserSession(j.getString("access_token"), j.getString("refresh_token"), j.optJSONObject("user")?.optString("email").orEmpty(), j.optJSONObject("user")?.optString("id").orEmpty()).also { current = it }
        } catch (_: Exception) { null }
    }

    private fun saveSession(json: JSONObject): UserSession {
        val value = UserSession(json.getString("access_token"), json.getString("refresh_token"), json.optJSONObject("user")?.optString("email").orEmpty(), json.optJSONObject("user")?.optString("id").orEmpty())
        prefs.edit().putString(SESSION, json.toString()).apply()
        current = value
        return value
    }

    private suspend fun request(path: String, method: String = "GET", body: String? = null, token: String? = null, rest: Boolean = false): String = withContext(Dispatchers.IO) {
        val url = if (rest) "$API/rest/v1/$path" else "$API/$path"
        val builder = Request.Builder().url(url)
            .header("apikey", KEY)
            .header("Accept", "application/json")
        builder.header("Authorization", "Bearer ${token ?: session()?.accessToken ?: KEY}")
        if (rest && method == "POST") builder.header("Prefer", "resolution=merge-duplicates,return=minimal")
        if (body != null) builder.method(method, body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
        else builder.method(method, if (method == "GET" || method == "HEAD") null else ByteArray(0).toRequestBody(null))
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("error").ifBlank { "HTTP ${response.code}" } } } } catch (_: Exception) { "HTTP ${response.code}" }
                throw IllegalStateException(message)
            }
            text
        }
    }

    suspend fun signIn(email: String, password: String): UserSession {
        val payload = JSONObject().put("email", email).put("password", password).toString()
        return saveSession(JSONObject(request("auth/v1/token?grant_type=password", "POST", payload, token = KEY)))
    }

    suspend fun signUp(email: String, password: String): UserSession {
        val payload = JSONObject().put("action", "register_user").put("email", email).put("password", password).toString()
        request("functions/v1/xtream-gateway", "POST", payload, token = KEY)
        return signIn(email, password)
    }

    suspend fun resetPassword(email: String) {
        val payload = JSONObject().put("email", email.trim()).toString()
        request("auth/v1/recover", "POST", payload, token = KEY)
    }

    fun signOut() {
        val token = session()?.accessToken
        if (token != null) {
            Thread {
                try {
                    val req = Request.Builder().url("$API/auth/v1/logout").post(ByteArray(0).toRequestBody())
                        .header("apikey", KEY).header("Authorization", "Bearer $token").build()
                    client.newCall(req).execute().close()
                } catch (_: Exception) {}
            }.start()
        }
        prefs.edit().remove(SESSION).apply()
        current = null
    }

    private suspend fun refreshIfNeeded(): String {
        val s = session() ?: return KEY
        // Supabase tokens are refreshed on a 401 response; the API request is retried once.
        return s.accessToken
    }

    suspend fun channels(): List<Channel> {
        val all = mutableListOf<JSONObject>()
        var offset = 0
        while (offset < 10000) {
            val path = "channels?select=id,name_ar,name_en,description,logo_url,stream_type,source_provider,source_external_id,channel_categories(slug,name_ar,name_en)&is_active=eq.true&order=name_ar.asc&limit=500&offset=$offset"
            val page = JSONArray(request(path, rest = true))
            for (i in 0 until page.length()) all.add(page.getJSONObject(i))
            if (page.length() < 500) break
            offset += page.length()
        }
        return groupRows(all)
    }

    private fun groupRows(rows: List<JSONObject>): List<Channel> {
        data class Part(val row: JSONObject, val category: String, val base: String, val quality: String, val rank: Int)
        val parts = rows.mapNotNull { row ->
            val names = row.optJSONObject("channel_categories")
            val group = names?.optString("name_en").orEmpty().ifBlank { names?.optString("name_ar").orEmpty() }
            val ar = row.optString("name_ar").ifBlank { row.optString("name_en") }
            val en = row.optString("name_en").ifBlank { ar }
            val clean = cleanName(en)
            val q = quality(clean)
            Part(row, categoryFor(group, en), q.first, q.second, q.third)
        }
        val grouped = parts.groupBy { p ->
            "${p.row.optString("source_provider")}|${p.category}|${p.base.lowercase()}"
        }
        return grouped.values.flatMap { variants ->
            val sorted = variants.sortedByDescending { it.rank }
            val distinctQualities = sorted.map { it.quality.ifBlank { "SOURCE" } }.distinct()
            val combine = sorted.size > 1 && distinctQualities.size > 1
            val sets = if (combine) listOf(sorted) else sorted.map { listOf(it) }
            sets.map { set ->
                val best = set.first()
                val r = best.row
                val cat = best.category
                val ar = quality(cleanName(r.optString("name_ar").ifBlank { r.optString("name_en") })).first
                val en = quality(cleanName(r.optString("name_en").ifBlank { r.optString("name_ar") })).first
                Channel(
                    id = r.getString("id"),
                    name = ar.ifBlank { en },
                    english = en.ifBlank { ar },
                    category = cat,
                    categoryLabel = CATEGORIES.firstOrNull { it.id == cat }?.title ?: "منوعات",
                    logo = r.optString("logo_url").takeIf { it.isNotBlank() && it != "null" },
                    provider = r.optString("source_provider"),
                    quality = best.quality.ifBlank { if (combine) "مصادر متعددة" else "" },
                    streamIds = set.map { it.row.getString("id") },
                    externalId = r.optString("source_external_id").takeIf { it.isNotBlank() && it != "null" },
                    streamType = r.optString("stream_type").ifBlank { "hls" }
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    private fun cleanName(value: String): String = value
        .replace(Regex("^\\s*(?:\\|\\s*)?(?:AR|TR|FR|UK|US|USA|CA|CAN|DE|ES|ITA|IT|NL|PT|PL|EU|LAT|SP|AF|NEWS|SPORT|DEPORTE)(?:\\s*\\|+\\s*|\\s*\\|\\s*)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^\\s*\\|+\\s*"), "")
        .replace(Regex("\\s*\\|\\s*"), " ")
        .replace(Regex("\\s{2,}"), " ").trim()

    private fun quality(value: String): Triple<String, String, Int> {
        val match = Regex("(?:\\s+|[-_.])(FULL\\s*HD|ULTRA\\s*HD|FHD|UHD|4K|8K|1080P|720P|576P|480P|SD|HD)$", RegexOption.IGNORE_CASE).find(value)
            ?: return Triple(value, "", 0)
        val token = match.groupValues[1].replace(" ", "").uppercase()
        val rank = if (token in listOf("FHD","UHD","4K","8K","1080P","ULTRAHD")) 4 else if (token in listOf("HD","720P")) 3 else if (token in listOf("SD","576P","480P")) 2 else 1
        val label = when(token) {
            "ULTRAHD" -> "UHD"
            "1080P" -> "FHD"
            "720P" -> "HD"
            "576P","480P" -> "SD"
            else -> token
        }
        return Triple(value.substring(0, match.range.first).trimEnd(' ','.','_','-'), label, rank)
    }

    suspend fun stream(channelId: String, fallbackType: String = "hls"): StreamInfo {
        val payload = JSONObject().put("action", "stream").put("channel_id", channelId).toString()
        val result = JSONObject(request("functions/v1/xtream-gateway", "POST", payload))
        val url = result.optString("stream_url").takeIf { it.isNotBlank() && it != "null" }
            ?: throw IllegalStateException("مصدر البث غير مربوط لهذه القناة")
        return StreamInfo(url, result.optString("stream_type").ifBlank { fallbackType.ifBlank { "hls" } })
    }

    suspend fun epg(channel: Channel): List<EpgProgram> {
        if (channel.provider == "xtream" && channel.externalId?.matches(Regex("\\d+")) == true) {
            try {
                val response = JSONObject(request("functions/v1/xtream-gateway", "POST",
                    JSONObject().put("action", "epg").put("stream_id", channel.externalId).toString()))
                val rows = response.optJSONArray("epg_listings") ?: JSONArray()
                val result = (0 until rows.length()).mapNotNull { index ->
                    val row = rows.optJSONObject(index) ?: return@mapNotNull null
                    val start = epochMillis(row.optString("start_timestamp"), row.optString("start"))
                    val end = epochMillis(row.optString("stop_timestamp"), row.optString("end"))
                    val title = decodeEpg(row.optString("title"))
                    if (title.isBlank() || start <= 0L || end <= start) null
                    else EpgProgram(title, decodeEpg(row.optString("description")), start, end)
                }
                if (result.isNotEmpty()) return result.sortedBy { it.startsAt }
            } catch (_: Exception) { }
        }
        val response = JSONObject(request("functions/v1/xtream-gateway", "POST",
            JSONObject().put("action", "epg_database").put("channel_id", channel.id).toString()))
        val rows = response.optJSONArray("programs") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val start = parseIso(row.optString("starts_at"))
            val end = parseIso(row.optString("ends_at"))
            val title = row.optString("title")
            if (title.isBlank() || start <= 0L || end <= start) null
            else EpgProgram(title, row.optString("description"), start, end)
        }.sortedBy { it.startsAt }
    }

    private fun decodeEpg(value: String): String = try {
        String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) { value }

    private fun epochMillis(timestamp: String, fallback: String): Long {
        val number = timestamp.toLongOrNull()
        if (number != null) return if (number > 1_000_000_000_000L) number else number * 1000L
        return parseIso(fallback)
    }

    private fun parseIso(value: String): Long = try {
        val clean = value.take(19)
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(clean)?.time ?: 0L
    } catch (_: Exception) { 0L }

    suspend fun activateSubscriptionCode(code: String): String {
        val response = JSONObject(request("functions/v1/xtream-gateway", "POST",
            JSONObject().put("action", "activate_subscription_code").put("code", code.trim().uppercase()).toString()))
        if (!response.optBoolean("success", false)) throw IllegalStateException(response.optString("error").ifBlank { "تعذر تفعيل كود الباقة" })
        val sub = response.optJSONObject("subscription")
        return (sub?.optString("plan_code").orEmpty().ifBlank { "اشتراك" }) + " · " + response.optString("expires_at")
    }

    suspend fun activateBeinCode(code: String): String {
        val response = JSONObject(request("functions/v1/xtream-gateway", "POST",
            JSONObject().put("action", "activate_bein_code").put("code", code.trim().uppercase()).toString()))
        if (!response.optBoolean("success", false)) throw IllegalStateException(response.optString("error").ifBlank { "تعذر تفعيل كود beIN" })
        return response.optString("expires_at")
    }

    suspend fun activateAnyCode(code: String): String {
        return try { activateSubscriptionCode(code) }
        catch (_: Exception) { activateBeinCode(code) }
    }

    suspend fun beinAccessExpiry(): String? {
        val response = JSONObject(request("functions/v1/xtream-gateway", "POST",
            JSONObject().put("action", "check_bein_access").toString()))
        return response.optString("expires_at").takeIf { it.isNotBlank() && it != "null" }
    }

    suspend fun checkBein(): Boolean {
        return try {
            JSONObject(request("functions/v1/xtream-gateway", "POST", JSONObject().put("action","check_bein_access").toString()))
                .optBoolean("has_access", false)
        } catch (_: Exception) { false }
    }

    suspend fun syncFavorites(local: Set<String>): Set<String> {
        val userId = session()?.userId.orEmpty()
        if (userId.isBlank()) return local
        val rows = JSONArray(request("favorites?select=channel_id&user_id=eq.$userId", rest = true))
        val remote = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("channel_id")?.takeIf { value -> value.isNotBlank() } }.toSet()
        val merged = remote + local
        val missing = merged - remote
        if (missing.isNotEmpty()) {
            val payload = JSONArray()
            missing.forEach { id -> payload.put(JSONObject().put("user_id", userId).put("channel_id", id)) }
            request("favorites", "POST", payload.toString(), rest = true)
        }
        return merged
    }

    suspend fun setFavorite(channelId: String, enabled: Boolean) {
        val userId = session()?.userId.orEmpty()
        if (userId.isBlank()) return
        if (enabled) {
            val payload = JSONArray().put(JSONObject().put("user_id", userId).put("channel_id", channelId))
            request("favorites", "POST", payload.toString(), rest = true)
        } else {
            request("favorites?user_id=eq.$userId&channel_id=eq.$channelId", "DELETE", rest = true)
        }
    }

    suspend fun syncRecent(local: List<String>): List<String> {
        val userId = session()?.userId.orEmpty()
        if (userId.isBlank()) return local
        val rows = JSONArray(request("watch_history?select=channel_id&user_id=eq.$userId&order=watched_at.desc&limit=30", rest = true))
        val remote = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("channel_id")?.takeIf { value -> value.isNotBlank() } }
        val merged = (remote + local).distinct().take(30)
        val missing = merged.filterNot { it in remote }
        if (missing.isNotEmpty()) {
            val payload = JSONArray()
            missing.forEach { id -> payload.put(JSONObject().put("user_id", userId).put("channel_id", id)) }
            request("watch_history", "POST", payload.toString(), rest = true)
        }
        return merged
    }

    suspend fun recordWatch(channelId: String) {
        val userId = session()?.userId.orEmpty()
        if (userId.isBlank()) return
        val payload = JSONArray().put(JSONObject().put("user_id", userId).put("channel_id", channelId).put("watched_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())))
        request("watch_history", "POST", payload.toString(), rest = true)
    }

    suspend fun accountInfo(): AccountInfo {
        val userId = session()?.userId.orEmpty()
        if (userId.isBlank()) throw IllegalStateException("جلسة الحساب غير صالحة")
        val profiles = JSONArray(request("profiles?select=display_name&id=eq.$userId", rest = true))
        val profile = profiles.optJSONObject(0)
        val subscriptions = JSONArray(request("subscriptions?select=plan_code,status,expires_at&user_id=eq.$userId&order=created_at.desc&limit=1", rest = true))
        val sub = subscriptions.optJSONObject(0)
        return AccountInfo(profile?.optString("display_name").orEmpty(), sub?.optString("plan_code").orEmpty().ifBlank { "مجاني" }, sub?.optString("status").orEmpty().ifBlank { "غير مشترك" }, sub?.optString("expires_at").orEmpty())
    }

    suspend fun updateDisplayName(name: String) {
        val userId = session()?.userId.orEmpty()
        if (userId.isBlank()) throw IllegalStateException("جلسة الحساب غير صالحة")
        request("profiles?on_conflict=id", "POST", JSONArray().put(JSONObject().put("id", userId).put("display_name", name.trim())).toString(), rest = true)
    }

    fun loadFavorites(): Set<String> = prefs.getString("favorites", "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()

    fun saveFavorites(ids: Set<String>) { prefs.edit().putString("favorites", ids.joinToString(",")).apply() }

    fun loadRecent(): List<String> = prefs.getString("recent_channels", "").orEmpty()
        .split(",").filter { it.isNotBlank() }.distinct().take(30)

    fun saveRecent(ids: List<String>) {
        prefs.edit().putString("recent_channels", ids.distinct().take(30).joinToString(",")).apply()
    }
}

data class StreamInfo(val url: String, val type: String)
data class EpgProgram(val title: String, val description: String, val startsAt: Long, val endsAt: Long)
