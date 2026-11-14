package com.velnix.livingroom

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
        .retryOnConnectionFailure(true)
        .build()

    private val sessionLock = Any()

    private val prefs by lazy {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "velnix_secure",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Volatile
    private var current: UserSession? = null

    private data class HttpResult(val code: Int, val body: String)

    fun session(): UserSession? = synchronized(sessionLock) {
        current?.let { return@synchronized it }
        val raw = prefs.getString(SESSION, null) ?: return@synchronized null
        try {
            val json = JSONObject(raw)
            val user = json.optJSONObject("user")
            UserSession(
                accessToken = json.optString("access_token"),
                refreshToken = json.optString("refresh_token"),
                userEmail = user?.optString("email").orEmpty(),
                userId = user?.optString("id").orEmpty(),
                expiresAt = json.optLong("expires_at", 0L)
            ).also { current = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveSession(json: JSONObject): UserSession = synchronized(sessionLock) {
        val saved = JSONObject(json.toString())
        val now = System.currentTimeMillis() / 1000L
        val expiresAt = saved.optLong("expires_at", 0L).let { value ->
            if (value > now) value else now + saved.optLong("expires_in", 3600L).coerceAtLeast(60L)
        }
        saved.put("expires_at", expiresAt)

        val previous = current
        val user = saved.optJSONObject("user")
        val value = UserSession(
            accessToken = saved.optString("access_token"),
            refreshToken = saved.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() },
            userEmail = user?.optString("email").orEmpty().ifBlank { previous?.userEmail.orEmpty() },
            userId = user?.optString("id").orEmpty().ifBlank { previous?.userId.orEmpty() },
            expiresAt = expiresAt
        )
        prefs.edit().putString(SESSION, saved.toString()).apply()
        current = value
        value
    }

    private fun clearSession() = synchronized(sessionLock) {
        prefs.edit().remove(SESSION).apply()
        current = null
    }

    private fun perform(
        path: String,
        method: String,
        body: String?,
        bearer: String,
        rest: Boolean,
        extraHeaders: Map<String, String>
    ): HttpResult {
        val url = (if (rest) API + "/rest/v1/" else API + "/") + path
        val builder = Request.Builder()
            .url(url)
            .header("apikey", KEY)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearer)

        extraHeaders.forEach { (name, value) -> builder.header(name, value) }

        val requestBody = if (body != null) {
            body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        } else if (method != "GET" && method != "HEAD") {
            ByteArray(0).toRequestBody(null)
        } else null

        builder.method(method, requestBody)
        return client.newCall(builder.build()).execute().use { response ->
            HttpResult(response.code, response.body?.string().orEmpty())
        }
    }

    private fun responseMessage(body: String, code: Int): String {
        return try {
            val json = JSONObject(body)
            json.optString("msg").ifBlank {
                json.optString("message").ifBlank {
                    json.optString("error_description").ifBlank {
                        json.optString("error").ifBlank { "HTTP " + code }
                    }
                }
            }
        } catch (_: Exception) {
            "HTTP " + code
        }
    }

    // Refresh the persisted Supabase session before expiry. A network failure never deletes the saved login.
    private fun refreshSessionBlocking(base: UserSession, force: Boolean = false): UserSession {
        return synchronized(sessionLock) {
        val latest = session() ?: base
        val now = System.currentTimeMillis() / 1000L
        if (!force && latest.expiresAt > now + 90L) return@synchronized latest
        if (force && latest.accessToken != base.accessToken && latest.expiresAt > now + 90L) {
            return@synchronized latest
        }
        if (latest.refreshToken.isBlank()) {
            throw IllegalStateException("جلسة الدخول تحتاج إلى تسجيل دخول جديد.")
        }

        val payload = JSONObject().put("refresh_token", latest.refreshToken).toString()
        val response = try {
            perform(
                "auth/v1/token?grant_type=refresh_token",
                "POST",
                payload,
                KEY,
                false,
                emptyMap()
            )
        } catch (e: Exception) {
            throw IllegalStateException("تعذر تجديد جلسة VELNIX بسبب الاتصال. حسابك محفوظ؛ افحص الإنترنت وأعد المحاولة.", e)
        }

        if (response.code !in 200..299) {
            val invalidGrant = response.code == 400 &&
                (response.body.contains("invalid_grant", true) ||
                    response.body.contains("refresh_token_not_found", true) ||
                    response.body.contains("Invalid Refresh Token", true))
            if (invalidGrant) clearSession()
            throw IllegalStateException(
                if (invalidGrant) "انتهت صلاحية جلسة الدخول. سجّل الدخول مرة ثانية."
                else "تعذر تجديد جلسة الدخول: " + responseMessage(response.body, response.code)
            )
        }

        saveSession(JSONObject(response.body))
        }
    }

    private suspend fun request(
        path: String,
        method: String = "GET",
        body: String? = null,
        token: String? = null,
        rest: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val originalSession = if (token == null) session() else null
        val bearer = if (token != null) {
            token
        } else if (originalSession != null) {
            refreshSessionBlocking(originalSession).accessToken
        } else {
            KEY
        }

        var response = perform(path, method, body, bearer, rest, extraHeaders)

        if (response.code == 401 && token == null && originalSession != null) {
            val fresh = refreshSessionBlocking(session() ?: originalSession, force = true)
            response = perform(path, method, body, fresh.accessToken, rest, extraHeaders)
        }

        if (response.code !in 200..299) {
            throw IllegalStateException(responseMessage(response.body, response.code))
        }
        response.body
    }

    suspend fun createDevicePairing(deviceName: String = "VELNIX TV"): DevicePairingInfo {
        val payload = JSONObject()
            .put("action", "create")
            .put("device_name", deviceName)
            .toString()
        val result = JSONObject(
            request(
                "functions/v1/device-pairing",
                method = "POST",
                body = payload,
                token = KEY
            )
        )
        return DevicePairingInfo(
            code = result.optString("code"),
            deviceToken = result.optString("device_token"),
            pairUrl = result.optString("pair_url"),
            expiresAt = try {
                java.time.Instant.parse(result.optString("expires_at")).toEpochMilli()
            } catch (_: Exception) {
                System.currentTimeMillis() + 180_000L
            }
        ).also {
            if (it.code.length != 8 || it.deviceToken.length < 40 || it.pairUrl.isBlank()) {
                throw IllegalStateException("خدمة ربط الأجهزة أعادت بيانات غير مكتملة.")
            }
        }
    }

    suspend fun pollDevicePairing(deviceToken: String): String? {
        val payload = JSONObject()
            .put("action", "poll")
            .put("device_token", deviceToken)
            .toString()
        val result = JSONObject(
            request(
                "functions/v1/device-pairing",
                method = "POST",
                body = payload,
                token = KEY
            )
        )
        return when (result.optString("status")) {
            "pending" -> null
            "complete" -> result.optString("token_hash").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("وصلت موافقة الربط لكن رمز تسجيل الدخول غير مكتمل.")
            "expired" -> throw IllegalStateException("انتهت صلاحية رمز الربط. أنشئ رمزاً جديداً.")
            else -> throw IllegalStateException("حالة ربط الجهاز غير معروفة.")
        }
    }

    suspend fun verifyDeviceMagicLink(tokenHash: String): UserSession {
        if (tokenHash.isBlank()) throw IllegalArgumentException("رمز تسجيل الدخول فارغ.")
        val payload = JSONObject()
            .put("type", "magiclink")
            .put("token_hash", tokenHash)
            .toString()
        return saveSession(
            JSONObject(
                request(
                    "auth/v1/verify",
                    method = "POST",
                    body = payload,
                    token = KEY
                )
            )
        )
    }

    suspend fun signIn(email: String, password: String): UserSession {
        if (!email.contains("@") || password.length < 6) {
            throw IllegalArgumentException("أدخل بريداً إلكترونياً صحيحاً وكلمة مرور لا تقل عن 6 أحرف.")
        }
        val payload = JSONObject().put("email", email).put("password", password).toString()
        return saveSession(
            JSONObject(
                request(
                    "auth/v1/token?grant_type=password",
                    method = "POST",
                    body = payload,
                    token = KEY
                )
            )
        )
    }

    suspend fun signUp(email: String, password: String): UserSession {
        if (!email.contains("@") || password.length < 6) {
            throw IllegalArgumentException("أدخل بريداً إلكترونياً صحيحاً وكلمة مرور لا تقل عن 6 أحرف.")
        }
        val payload = JSONObject()
            .put("action", "register_user")
            .put("email", email)
            .put("password", password)
            .toString()
        request("functions/v1/xtream-gateway", "POST", payload, token = KEY)
        return signIn(email, password)
    }

    fun signOut() {
        val active = session()
        if (active != null) {
            Thread {
                try {
                    perform(
                        "auth/v1/logout",
                        "POST",
                        null,
                        active.accessToken,
                        false,
                        emptyMap()
                    )
                } catch (_: Exception) {
                    // Local sign-out must still complete when the network is unavailable.
                }
            }.start()
        }
        clearSession()
    }

    suspend fun channels(offset: Int = 0, pageSize: Int = 100): ChannelPage {
        val safeLimit = pageSize.coerceIn(50, 100)
        val safeOffset = offset.coerceAtLeast(0)
        val path = "channels?select=id,name_ar,name_en,logo_url,stream_type,source_provider,source_external_id,channel_categories(slug,name_ar,name_en)&is_active=eq.true&order=name_ar.asc&limit=" + safeLimit + "&offset=" + safeOffset
        val rows = JSONArray(request(path, rest = true))
        val all = (0 until rows.length()).map { rows.getJSONObject(it) }
        return ChannelPage(
            channels = groupRows(all),
            nextOffset = safeOffset + rows.length(),
            hasMore = rows.length() == safeLimit
        )
    }

    private fun groupRows(rows: List<JSONObject>): List<Channel> {
        data class Part(
            val row: JSONObject,
            val category: String,
            val base: String,
            val quality: String,
            val rank: Int
        )

        val parts = rows.mapNotNull { row ->
            val names = row.optJSONObject("channel_categories")
            val group = names?.optString("name_en").orEmpty()
                .ifBlank { names?.optString("name_ar").orEmpty() }
            val ar = row.optString("name_ar").ifBlank { row.optString("name_en") }
            val en = row.optString("name_en").ifBlank { ar }
            val clean = cleanName(en)
            val q = quality(clean)
            Part(row, categoryFor(group, en), q.first, q.second, q.third)
        }

        val grouped = parts.groupBy { part ->
            part.row.optString("source_provider") + "|" + part.category + "|" + part.base.lowercase()
        }

        return grouped.values.flatMap { variants ->
            val sorted = variants.sortedByDescending { it.rank }
            val distinctQualities = sorted.map { it.quality.ifBlank { "SOURCE" } }.distinct()
            val combine = sorted.size > 1 && distinctQualities.size > 1
            val sets = if (combine) listOf(sorted) else sorted.map { listOf(it) }
            sets.map { set ->
                val best = set.first()
                val row = best.row
                val category = best.category
                val ar = quality(cleanName(row.optString("name_ar").ifBlank { row.optString("name_en") })).first
                val en = quality(cleanName(row.optString("name_en").ifBlank { row.optString("name_ar") })).first
                Channel(
                    id = row.getString("id"),
                    name = ar.ifBlank { en },
                    english = en.ifBlank { ar },
                    category = category,
                    categoryLabel = CATEGORIES.firstOrNull { it.id == category }?.title ?: "منوعات",
                    logo = row.optString("logo_url").takeIf { it.isNotBlank() && it != "null" },
                    provider = row.optString("source_provider"),
                    quality = best.quality.ifBlank { if (combine) "مصادر متعددة" else "" },
                    streamIds = set.map { it.row.getString("id") },
                    externalId = row.optString("source_external_id").takeIf { it.isNotBlank() && it != "null" },
                    streamType = row.optString("stream_type").ifBlank { "hls" }
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    private fun cleanName(value: String): String = value
        .replace(
            Regex("^\\s*(?:\\|\\s*)?(?:AR|TR|FR|UK|US|USA|CA|CAN|DE|ES|ITA|IT|NL|PT|PL|EU|LAT|SP|AF|NEWS|SPORT|DEPORTE)(?:\\s*\\|+\\s*|\\s*\\|\\s*)", RegexOption.IGNORE_CASE),
            ""
        )
        .replace(Regex("^\\s*\\|+\\s*"), "")
        .replace(Regex("\\s*\\|\\s*"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    private fun quality(value: String): Triple<String, String, Int> {
        val match = Regex(
            "(?:\\s+|[-_.])(FULL\\s*HD|ULTRA\\s*HD|FHD|UHD|4K|8K|1080P|720P|576P|480P|SD|HD)$",
            RegexOption.IGNORE_CASE
        ).find(value) ?: return Triple(value, "", 0)

        val token = match.groupValues[1].replace(" ", "").uppercase()
        val rank = if (token in listOf("FHD", "UHD", "4K", "8K", "1080P", "ULTRAHD")) 4
            else if (token in listOf("HD", "720P")) 3
            else if (token in listOf("SD", "576P", "480P")) 2
            else 1
        val label = when (token) {
            "ULTRAHD" -> "UHD"
            "1080P" -> "FHD"
            "720P" -> "HD"
            "576P", "480P" -> "SD"
            else -> token
        }
        return Triple(value.substring(0, match.range.first).trimEnd(' ', '.', '_', '-'), label, rank)
    }

    suspend fun stream(channelId: String, fallbackType: String = "hls"): StreamInfo {
        val payload = JSONObject().put("action", "stream").put("channel_id", channelId).toString()
        val result = JSONObject(request("functions/v1/xtream-gateway", "POST", payload))
        val url = result.optString("stream_url").takeIf { it.isNotBlank() && it != "null" }
            ?: throw IllegalStateException("مصدر البث غير مربوط لهذه القناة")
        return StreamInfo(
            url,
            result.optString("stream_type").ifBlank { fallbackType.ifBlank { "hls" } }
        )
    }

    suspend fun epg(channel: Channel): List<EpgProgram> {
        if (channel.provider == "xtream" && channel.externalId?.matches(Regex("\\d+")) == true) {
            try {
                val response = JSONObject(
                    request(
                        "functions/v1/xtream-gateway",
                        "POST",
                        JSONObject().put("action", "epg").put("stream_id", channel.externalId).toString()
                    )
                )
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
            } catch (_: Exception) {
                // Fall through to the local programme guide.
            }
        }

        val response = JSONObject(
            request(
                "functions/v1/xtream-gateway",
                "POST",
                JSONObject().put("action", "epg_database").put("channel_id", channel.id).toString()
            )
        )
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

    suspend fun checkBein(): Boolean {
        return try {
            JSONObject(
                request(
                    "functions/v1/xtream-gateway",
                    "POST",
                    JSONObject().put("action", "check_bein_access").toString()
                )
            ).optBoolean("has_access", false)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun loadProfile(userId: String, fallbackName: String): ProfileInfo {
        if (userId.isBlank()) return ProfileInfo(fallbackName, "anime1")
        val rows = JSONArray(
            request(
                "profiles?select=id,display_name,avatar_url&id=eq." + userId + "&limit=1",
                rest = true
            )
        )
        if (rows.length() == 0) return ProfileInfo(fallbackName, "anime1")
        val row = rows.getJSONObject(0)
        val displayName = row.optString("display_name").ifBlank { fallbackName }
        val storedAvatar = row.optString("avatar_url")
            .removePrefix("velnix-avatar:")
            .ifBlank { "anime1" }
        val validAvatar = storedAvatar.takeIf {
            it.matches(Regex("anime[1-8]"))
        } ?: "anime1"
        return ProfileInfo(displayName, validAvatar)
    }

    suspend fun saveProfile(userId: String, displayName: String, avatarId: String) {
        if (userId.isBlank()) throw IllegalStateException("تعذر تحديد الحساب الحالي.")
        if (displayName.trim().length < 2) throw IllegalArgumentException("الاسم لازم يكون حرفين على الأقل.")
        val validAvatar = avatarId.takeIf { it.matches(Regex("anime[1-8]")) } ?: "anime1"
        val row = JSONObject()
            .put("id", userId)
            .put("display_name", displayName.trim())
            .put("avatar_url", "velnix-avatar:" + validAvatar)
            .put("updated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
        request(
            "profiles?on_conflict=id",
            method = "POST",
            body = row.toString(),
            rest = true,
            extraHeaders = mapOf(
                "Content-Type" to "application/json",
                "Prefer" to "resolution=merge-duplicates,return=minimal"
            )
        )
    }

    private fun decodeEpg(value: String): String = try {
        String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        value
    }

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
    } catch (_: Exception) {
        0L
    }

    fun loadAutoReconnect(): Boolean = prefs.getBoolean("auto_reconnect", true)

    fun saveAutoReconnect(value: Boolean) {
        prefs.edit().putBoolean("auto_reconnect", value).apply()
    }

    fun loadDataSaver(): Boolean = prefs.getBoolean("data_saver", false)

    fun saveDataSaver(value: Boolean) {
        prefs.edit().putBoolean("data_saver", value).apply()
    }

    fun loadShowLogos(): Boolean = prefs.getBoolean("show_channel_logos", true)

    fun saveShowLogos(value: Boolean) {
        prefs.edit().putBoolean("show_channel_logos", value).apply()
    }

    suspend fun tvProfiles(userId: String): List<TvProfile> {
        val rows = JSONArray(request("tv_profiles?select=id,name,avatar_id,is_kids&user_id=eq.$userId&order=created_at.asc", rest = true))
        return (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            TvProfile(r.getString("id"), r.getString("name"), r.optString("avatar_id","anime1"), r.optBoolean("is_kids"))
        }
    }

    suspend fun createTvProfile(userId: String, name: String, avatar: String, kids: Boolean, pin: String) {
        require(name.trim().length in 1..32) { "اسم الملف الشخصي يجب أن يكون من 1 إلى 32 حرفاً." }
        require(pin.isBlank() || pin.matches(Regex("\\d{4,6}"))) { "رمز PIN يجب أن يكون من 4 إلى 6 أرقام." }
        val hash = if (pin.isBlank()) JSONObject.NULL else pinHash(pin)
        val row = JSONObject().put("user_id", userId).put("name", name.trim())
            .put("avatar_id", avatar).put("is_kids", kids).put("pin_hash", hash)
        request("tv_profiles", "POST", row.toString(), rest = true,
            extraHeaders = mapOf("Prefer" to "return=minimal"))
    }

    suspend fun removeTvProfile(userId: String, profileId: String) {
        request("tv_profiles?id=eq.$profileId&user_id=eq.$userId", "DELETE", rest = true)
    }

    suspend fun setTvProfilePin(userId: String, profileId: String, pin: String) {
        require(pin.isBlank() || pin.matches(Regex("\\d{4,6}"))) { "رمز PIN يجب أن يكون من 4 إلى 6 أرقام." }
        val row = JSONObject().put("pin_hash", if (pin.isBlank()) JSONObject.NULL else pinHash(pin))
        request("tv_profiles?id=eq.$profileId&user_id=eq.$userId", "PATCH", row.toString(), rest = true)
    }

    suspend fun checkTvProfilePin(userId: String, profileId: String, pin: String): Boolean {
        val rows = JSONArray(request("tv_profiles?select=pin_hash&id=eq.$profileId&user_id=eq.$userId&limit=1", rest = true))
        if (rows.length()==0) return false
        val hash = rows.getJSONObject(0).optString("pin_hash")
        return hash.isBlank() || hash == pinHash(pin)
    }

    private fun pinHash(pin: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(pin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    suspend fun loadLockedChannels(userId: String, profileId: String): Set<String> {
        val rows = JSONArray(request("tv_profile_channel_locks?select=channel_id&user_id=eq.$userId&profile_id=eq.$profileId", rest = true))
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("channel_id") }.toSet()
    }

    suspend fun setChannelLocked(userId: String, profileId: String, channelId: String, locked: Boolean) {
        if (locked) {
            val row=JSONObject().put("user_id",userId).put("profile_id",profileId).put("channel_id",channelId)
            request("tv_profile_channel_locks","POST",row.toString(),rest=true,extraHeaders=mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal"))
        } else request("tv_profile_channel_locks?user_id=eq.$userId&profile_id=eq.$profileId&channel_id=eq.$channelId","DELETE",rest=true)
    }

    suspend fun addMatchReminder(userId: String, profileId: String?, fixtureId: String, minutes: Int = 15) {
        val row=JSONObject().put("user_id",userId).put("profile_id",profileId ?: JSONObject.NULL)
            .put("fixture_id",fixtureId).put("remind_before_minutes",minutes)
        request("tv_match_reminders","POST",row.toString(),rest=true,extraHeaders=mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal"))
    }

    suspend fun upcomingMatches(): List<TvMatch> {
        val rows=JSONArray(request("tv_match_fixtures?select=id,home_team,away_team,competition,starts_at,status,home_score,away_score&starts_at=gte."+java.net.URLEncoder.encode(java.time.Instant.now().toString(),"UTF-8")+"&order=starts_at.asc&limit=40",rest=true))
        return (0 until rows.length()).map { i ->
            val r=rows.getJSONObject(i)
            TvMatch(r.getString("id"),r.getString("home_team"),r.getString("away_team"),r.optString("competition"),r.getString("starts_at"),r.optString("status"),r.optInt("home_score"),r.optInt("away_score"))
        }
    }

    suspend fun shareChannel(userId: String, channelId: String): String {
        val code=(1..8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        val expires=java.time.Instant.now().plusSeconds(86400).toString()
        val row=JSONObject().put("owner_id",userId).put("channel_id",channelId).put("share_code",code).put("expires_at",expires)
        request("tv_channel_shares","POST",row.toString(),rest=true,extraHeaders=mapOf("Prefer" to "return=minimal"))
        return code
    }

    fun loadFavorites(): Set<String> =
        prefs.getString("favorites", "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()

    fun saveFavorites(ids: Set<String>) {
        prefs.edit().putString("favorites", ids.joinToString(",")).apply()
    }

    fun loadRecent(): List<String> = prefs.getString("recent_channels", "").orEmpty()
        .split(",").filter { it.isNotBlank() }.distinct().take(30)

    fun saveRecent(ids: List<String>) {
        prefs.edit().putString("recent_channels", ids.distinct().take(30).joinToString(",")).apply()
    }
}

data class TvProfile(val id:String,val name:String,val avatarId:String,val isKids:Boolean)\ndata class TvMatch(val id:String,val home:String,val away:String,val competition:String,val startsAt:String,val status:String,val homeScore:Int,val awayScore:Int)\n\ndata class DevicePairingInfo(
    val code: String,
    val deviceToken: String,
    val pairUrl: String,
    val expiresAt: Long
)

data class UserSession(
    val accessToken: String,
    val refreshToken: String,
    val userEmail: String,
    val userId: String,
    val expiresAt: Long
)

data class ChannelPage(val channels: List<Channel>, val nextOffset: Int, val hasMore: Boolean)
data class StreamInfo(val url: String, val type: String)
data class EpgProgram(val title: String, val description: String, val startsAt: Long, val endsAt: Long)
