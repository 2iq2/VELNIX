package com.velnix.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Night = Color(0xFF07090E)
private val Panel = Color(0xFF111722)
private val Panel2 = Color(0xFF1A2432)
private val Gold = Color(0xFFD6B77B)
private val TextMain = Color(0xFFF4F0E8)
private val Muted = Color(0xFF9BA8B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.keepScreenOn = true
        setContent { VelnixTvApp() }
    }
}

data class TvSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val email: String
)

data class TvCategory(val id: String, val title: String, val order: Int)
data class TvChannel(
    val id: String,
    val categoryId: String?,
    val name: String,
    val englishName: String,
    val logo: String?,
    val streamType: String
)
data class TvStream(val url: String, val type: String)
data class BeinStatus(val active: Boolean, val expiresAt: String?)
data class TvSubscription(val plan: String, val expiresAt: String?)
data class TvFixture(
    val id: String, val league: String, val home: String, val away: String,
    val homeLogo: String?, val awayLogo: String?, val homeScore: String,
    val awayScore: String, val state: String, val status: String, val date: String
)

private object VelnixApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val api get() = BuildConfig.SUPABASE_URL
    private val key get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    private fun request(url: String, method: String = "GET", body: JSONObject? = null, token: String? = null): String {
        val builder = Request.Builder().url(url)
            .header("apikey", key)
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        if (method == "POST") builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
        http.newCall(builder.build()).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try {
                    val obj = JSONObject(payload)
                    obj.optString("msg").ifBlank { obj.optString("message") }
                        .ifBlank { obj.optString("error_description") }
                        .ifBlank { obj.optString("error") }
                        .ifBlank { "HTTP ${response.code}" }
                } catch (_: Exception) { "HTTP ${response.code}" }
                error(message)
            }
            return payload
        }
    }

    fun savedSession(context: Context): TvSession? {
        val prefs = context.getSharedPreferences("velnix_tv_auth", Context.MODE_PRIVATE)
        val access = prefs.getString("access", null) ?: return null
        val refresh = prefs.getString("refresh", null) ?: return null
        return TvSession(access, refresh, prefs.getLong("expires", 0), prefs.getString("email", "") ?: "")
    }

    private fun saveSession(context: Context, session: TvSession?) {
        val edit = context.getSharedPreferences("velnix_tv_auth", Context.MODE_PRIVATE).edit()
        if (session == null) edit.clear() else edit
            .putString("access", session.accessToken)
            .putString("refresh", session.refreshToken)
            .putLong("expires", session.expiresAt)
            .putString("email", session.email)
        edit.apply()
    }

    private suspend fun auth(path: String, body: JSONObject, token: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(request("$api/auth/v1/$path", "POST", body, token))
        }

    suspend fun signIn(context: Context, email: String, password: String): TvSession {
        val data = auth("token?grant_type=password", JSONObject()
            .put("email", email.trim().lowercase()).put("password", password))
        val user = data.optJSONObject("user")
        val session = TvSession(
            data.getString("access_token"),
            data.getString("refresh_token"),
            System.currentTimeMillis() / 1000 + data.optLong("expires_in", 3600),
            user?.optString("email").orEmpty().ifBlank { email.trim() }
        )
        saveSession(context, session)
        return session
    }

    suspend fun registerAndSignIn(context: Context, email: String, password: String): TvSession {
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("action", "register_user")
                .put("email", email.trim().lowercase()).put("password", password)
            request(BuildConfig.GATEWAY_URL, "POST", body, key)
        }
        return signIn(context, email, password)
    }

    suspend fun validSession(context: Context): TvSession? {
        val current = savedSession(context) ?: return null
        if (current.expiresAt > System.currentTimeMillis() / 1000 + 90) return current
        return try {
            val data = auth("token?grant_type=refresh_token", JSONObject().put("refresh_token", current.refreshToken))
            val updated = TvSession(
                data.getString("access_token"),
                data.optString("refresh_token").ifBlank { current.refreshToken },
                System.currentTimeMillis() / 1000 + data.optLong("expires_in", 3600),
                data.optJSONObject("user")?.optString("email").orEmpty().ifBlank { current.email }
            )
            saveSession(context, updated)
            updated
        } catch (_: Exception) {
            saveSession(context, null)
            null
        }
    }

    suspend fun signOut(context: Context) {
        val session = validSession(context)
        if (session != null) {
            withContext(Dispatchers.IO) {
                try { request("$api/auth/v1/logout", "POST", JSONObject(), session.accessToken) } catch (_: Exception) {}
            }
        }
        saveSession(context, null)
    }

    suspend fun loadCatalog(context: Context): Pair<List<TvCategory>, List<TvChannel>> = withContext(Dispatchers.IO) {
        validSession(context)
        val categoriesJson = JSONArray(request(
            "$api/rest/v1/channel_categories?select=id,name_ar,name_en,sort_order&is_active=eq.true&order=sort_order.asc",
            token = null
        ))
        val channelsJson = JSONArray(request(
            "$api/rest/v1/channels?select=id,category_id,name_ar,name_en,logo_url,stream_type&is_active=eq.true&order=name_en.asc&limit=5000",
            token = null
        ))
        val categories = buildList {
            for (i in 0 until categoriesJson.length()) {
                val item = categoriesJson.getJSONObject(i)
                add(TvCategory(item.optString("id"), item.optString("name_ar").ifBlank { item.optString("name_en") }, item.optInt("sort_order")))
            }
        }
        val channels = buildList {
            for (i in 0 until channelsJson.length()) {
                val item = channelsJson.getJSONObject(i)
                add(TvChannel(
                    id = item.optString("id"),
                    categoryId = item.optString("category_id").takeIf { it.isNotBlank() && it != "null" },
                    name = item.optString("name_ar").ifBlank { item.optString("name_en") },
                    englishName = item.optString("name_en"),
                    logo = item.optString("logo_url").takeIf { it.startsWith("http") },
                    streamType = item.optString("stream_type").lowercase()
                ))
            }
        }
        categories to channels
    }


    suspend fun checkBein(context: Context): BeinStatus = withContext(Dispatchers.IO) {
        val session = validSession(context) ?: error("سجّل الدخول أولاً")
        val result = JSONObject(request(BuildConfig.GATEWAY_URL, "POST", JSONObject().put("action", "check_bein_access"), session.accessToken))
        BeinStatus(result.optBoolean("has_access"), result.optString("expires_at").takeIf { it.isNotBlank() && it != "null" })
    }

    suspend fun activateBein(context: Context, code: String): BeinStatus = withContext(Dispatchers.IO) {
        val session = validSession(context) ?: error("سجّل الدخول أولاً")
        val result = JSONObject(request(BuildConfig.GATEWAY_URL, "POST",
            JSONObject().put("action", "activate_bein_code").put("code", code.trim().uppercase()), session.accessToken))
        BeinStatus(true, result.optString("expires_at").takeIf { it.isNotBlank() && it != "null" })
    }

    suspend fun loadFavorites(context: Context): Set<String> = withContext(Dispatchers.IO) {
        val session = validSession(context) ?: return@withContext emptySet()
        val userId = sessionUserId(context, session)
        val url = api + "/rest/v1/favorites?select=channel_id&user_id=eq." + userId
        val request = Request.Builder().url(url).header("apikey", key)
            .header("Authorization", "Bearer " + session.accessToken).get().build()
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("تعذر تحميل المفضلة")
            val array = JSONArray(payload)
            buildSet { for (i in 0 until array.length()) add(array.getJSONObject(i).optString("channel_id")) }
        }
    }

    private suspend fun sessionUserId(context: Context, session: TvSession): String = withContext(Dispatchers.IO) {
        val result = JSONObject(request(api + "/auth/v1/user", token = session.accessToken))
        result.optString("id").takeIf { it.isNotBlank() } ?: error("تعذر التحقق من الحساب")
    }

    suspend fun setFavorite(context: Context, channelId: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        val session = validSession(context) ?: error("سجّل الدخول أولاً")
        val userId = sessionUserId(context, session)
        val url = api + "/rest/v1/favorites?user_id=eq." + userId + "&channel_id=eq." + channelId
        val builder = Request.Builder().url(url).header("apikey", key)
            .header("Authorization", "Bearer " + session.accessToken)
        val request = if (favorite) builder.header("Prefer", "resolution=ignore-duplicates,return=minimal")
            .post(JSONObject().put("user_id", userId).put("channel_id", channelId).toString().toRequestBody(jsonType)).build()
        else builder.delete().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("تعذر تحديث المفضلة")
        }
    }

    suspend fun loadSubscription(context: Context): TvSubscription? = withContext(Dispatchers.IO) {
        val session = validSession(context) ?: return@withContext null
        val userId = sessionUserId(context, session)
        val url = api + "/rest/v1/subscriptions?select=plan_code,expires_at,status&user_id=eq." + userId + "&status=eq.active&order=created_at.desc&limit=1"
        val request = Request.Builder().url(url).header("apikey", key)
            .header("Authorization", "Bearer " + session.accessToken).get().build()
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("تعذر تحميل بيانات الاشتراك")
            val array = JSONArray(payload)
            if (array.length() == 0) null else {
                val row = array.getJSONObject(0)
                TvSubscription(row.optString("plan_code"), row.optString("expires_at").takeIf { it.isNotBlank() && it != "null" })
            }
        }
    }

    suspend fun loadFixtures(offset: Int): List<TvFixture> = withContext(Dispatchers.IO) {
        val date = LocalDate.now(ZoneId.of("Asia/Baghdad")).plusDays(offset.toLong())
        val keyDate = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard?dates=" + keyDate
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("تعذر تحميل جدول المباريات")
            val events = JSONObject(response.body?.string().orEmpty()).optJSONArray("events") ?: JSONArray()
            buildList {
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: JSONObject()
                    val teams = competition.optJSONArray("competitors") ?: JSONArray()
                    var home: JSONObject? = null
                    var away: JSONObject? = null
                    for (j in 0 until teams.length()) {
                        val team = teams.optJSONObject(j) ?: continue
                        if (team.optString("homeAway") == "home") home = team else if (team.optString("homeAway") == "away") away = team
                    }
                    val homeTeam = home?.optJSONObject("team") ?: JSONObject()
                    val awayTeam = away?.optJSONObject("team") ?: JSONObject()
                    val status = competition.optJSONObject("status")?.optJSONObject("type") ?: JSONObject()
                    add(TvFixture(
                        id = event.optString("id"),
                        league = event.optJSONObject("league")?.optString("name").orEmpty().ifBlank { "كرة القدم" },
                        home = homeTeam.optString("displayName").ifBlank { "الفريق الأول" },
                        away = awayTeam.optString("displayName").ifBlank { "الفريق الثاني" },
                        homeLogo = homeTeam.optString("logo").takeIf { it.startsWith("http") },
                        awayLogo = awayTeam.optString("logo").takeIf { it.startsWith("http") },
                        homeScore = home?.optString("score").orEmpty(),
                        awayScore = away?.optString("score").orEmpty(),
                        state = status.optString("state", "pre"),
                        status = status.optString("shortDetail").ifBlank { "قادمة" },
                        date = event.optString("date")
                    ))
                }
            }.sortedBy { it.date }
        }
    }

    suspend fun stream(context: Context, channelId: String): TvStream = withContext(Dispatchers.IO) {
        val session = validSession(context) ?: error("سجّل الدخول أولاً")
        val body = JSONObject().put("action", "stream").put("channel_id", channelId)
        val result = JSONObject(request(BuildConfig.GATEWAY_URL, "POST", body, session.accessToken))
        val url = result.optString("stream_url")
        if (url.isBlank()) error(result.optString("error").ifBlank { "مصدر البث غير متاح لهذه القناة" })
        TvStream(url, result.optString("stream_type", "hls").lowercase())
    }
}

@Composable
private fun VelnixTvApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(VelnixApi.savedSession(context)) }
    var categories by remember { mutableStateOf(emptyList<TvCategory>()) }
    var channels by remember { mutableStateOf(emptyList<TvChannel>()) }
    var selectedCategory by remember { mutableStateOf("all") }
    var beinCategoryId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var selectedChannel by remember { mutableStateOf<TvChannel?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    var history by remember { mutableStateOf(loadHistory(context)) }
    var signingIn by remember { mutableStateOf(false) }
    var beinAccess by remember { mutableStateOf(false) }
    var beinExpiry by remember { mutableStateOf<String?>(null) }
    var beinCode by remember { mutableStateOf("") }
    var beinMessage by remember { mutableStateOf("") }
    var beinBusy by remember { mutableStateOf(false) }
    var subscription by remember { mutableStateOf<TvSubscription?>(null) }
    var subscriptionMessage by remember { mutableStateOf("جارٍ تحميل بيانات الاشتراك...") }
    var pinEnabled by remember { mutableStateOf(isPinEnabled(context)) }
    var pinHash by remember { mutableStateOf(getPinHash(context)) }
    var pinTarget by remember { mutableStateOf<TvChannel?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }

    fun refreshCatalog() {
        scope.launch {
            loading = true
            error = null
            try {
                val refreshed = VelnixApi.validSession(context)
                if (refreshed == null) {
                    session = null
                    error = "انتهت جلسة الدخول. سجّل الدخول مرة ثانية."
                    return@launch
                }
                session = refreshed
                val result = VelnixApi.loadCatalog(context)
                categories = result.first
                channels = result.second
                beinCategoryId = categories.firstOrNull { isBeinCategory(it.title) }?.id
                try { favorites = VelnixApi.loadFavorites(context).also { saveFavorites(context, it) } }
                catch (_: Exception) { favorites = loadFavorites(context) }
                try {
                    val access = VelnixApi.checkBein(context)
                    beinAccess = access.active
                    beinExpiry = access.expiresAt
                } catch (_: Exception) { beinAccess = false; beinExpiry = null }
                try {
                    subscription = VelnixApi.loadSubscription(context)
                    subscriptionMessage = if (subscription == null) "لا يوجد اشتراك نشط على هذا الحساب" else "اشتراكك فعال"
                } catch (e: Exception) { subscriptionMessage = e.message ?: "تعذر تحميل الاشتراك" }
            } catch (e: Exception) {
                error = e.message ?: "تعذر تحميل القنوات"
            } finally { loading = false }
        }
    }

    fun playChannel(channel: TvChannel) {
        history = recordHistory(context, history, channel.id)
        selectedChannel = channel
    }

    fun requestChannel(channel: TvChannel) {
        if (isBeinCategory(channel.name) && !beinAccess) {
            selectedCategory = "bein-gate"
            return
        }
        if (pinEnabled && pinHash.isNotBlank()) {
            pinTarget = channel
            pinInput = ""
            pinError = ""
        } else playChannel(channel)
    }

    LaunchedEffect(session?.accessToken) {
        if (session != null) refreshCatalog()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Night, surface = Panel, primary = Gold,
        onPrimary = Night, onBackground = TextMain, onSurface = TextMain
    )) {
        Box(Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF18202B), Night), radius = 1300f)
        )) {
            if (session == null) {
                LoginScreen(
                    busy = signingIn,
                    onLogin = { email, password ->
                        scope.launch {
                            signingIn = true
                            try { session = VelnixApi.signIn(context, email, password) }
                            catch (e: Exception) { error = e.message ?: "تعذر تسجيل الدخول" }
                            finally { signingIn = false }
                        }
                    },
                    onRegister = { email, password ->
                        scope.launch {
                            signingIn = true
                            try { session = VelnixApi.registerAndSignIn(context, email, password) }
                            catch (e: Exception) { error = e.message ?: "تعذر إنشاء الحساب" }
                            finally { signingIn = false }
                        }
                    },
                    error = error
                )
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("VELNIX", color = Gold, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("TV", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(session?.email.orEmpty(), color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.width(16.dp))
                        TvAction("الإعدادات", false) { settingsOpen = true }
                        Spacer(Modifier.width(9.dp))
                        TvAction("تحديث", loading) { refreshCatalog() }
                        Spacer(Modifier.width(9.dp))
                        TvAction("خروج", false) {
                            scope.launch { VelnixApi.signOut(context); session = null; channels = emptyList() }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.width(205.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                            .background(Panel.copy(alpha = .94f)).padding(14.dp)) {
                            Text("القائمة الرئيسية", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                item { TvNavItem("كل القنوات", selectedCategory == "all") { selectedCategory = "all" } }
                                item { TvNavItem("المفضلة", selectedCategory == "favorites") { selectedCategory = "favorites" } }
                                item { TvNavItem("المباريات", selectedCategory == "matches") { selectedCategory = "matches" } }
                                item { TvNavItem("آخر المشاهدات", selectedCategory == "recent") { selectedCategory = "recent" } }
                                item { TvNavItem("حسابي واشتراكي", selectedCategory == "account") { selectedCategory = "account" } }
                                items(categories, key = { it.id }) { category ->
                                    val lockedBein = isBeinCategory(category.title) && !beinAccess
                                    TvNavItem(if (lockedBein) "🔒 " + category.title else category.title,
                                        selectedCategory == category.id || (selectedCategory == "bein-gate" && category.id == beinCategoryId)) {
                                        if (isBeinCategory(category.title) && !beinAccess) {
                                            beinCategoryId = category.id
                                            selectedCategory = "bein-gate"
                                        } else selectedCategory = category.id
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text("VELNIX TV  •  PREMIUM", color = Gold, fontSize = 9.sp, letterSpacing = 1.5.sp)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        when (selectedCategory) {
                                            "all" -> "اكتشف القنوات"
                                            "favorites" -> "قنواتك المفضلة"
                                            "matches" -> "مواعيد المباريات"
                                            "recent" -> "آخر ما شاهدت"
                                            "account" -> "حسابك في VELNIX"
                                            "bein-gate" -> "تفعيل beIN SPORTS"
                                            else -> categories.firstOrNull { it.id == selectedCategory }?.title ?: "القنوات"
                                        },
                                        color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        when (selectedCategory) {
                                            "matches" -> "جدول كرة القدم بتوقيت بغداد"
                                            "account" -> subscriptionMessage
                                            else -> "\${channels.size} قناة في الدليل"
                                        }, color = Muted, fontSize = 12.sp
                                    )
                                }
                                if (selectedCategory !in listOf("matches", "recent", "account", "bein-gate")) {
                                    OutlinedTextField(
                                        value = search, onValueChange = { search = it },
                                        modifier = Modifier.width(270.dp), singleLine = true,
                                        label = { Text("بحث عن قناة") },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Gold, focusedLabelColor = Gold,
                                            unfocusedBorderColor = Panel2, focusedTextColor = TextMain,
                                            unfocusedTextColor = TextMain
                                        )
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            when {
                                loading && channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Gold)
                                }
                                selectedCategory == "matches" -> TvMatchesScreen()
                                selectedCategory == "account" -> TvAccountScreen(subscription, subscriptionMessage, beinAccess, beinExpiry)
                                selectedCategory == "bein-gate" -> TvBeinGate(
                                    code = beinCode, onCode = { beinCode = it },
                                    message = beinMessage, busy = beinBusy,
                                    onActivate = {
                                        scope.launch {
                                            beinBusy = true
                                            beinMessage = "جارٍ التحقق من كود التفعيل..."
                                            try {
                                                val result = VelnixApi.activateBein(context, beinCode)
                                                beinAccess = result.active
                                                beinExpiry = result.expiresAt
                                                beinMessage = "تم تفعيل القسم بنجاح"
                                                selectedCategory = beinCategoryId ?: "all"
                                                beinCode = ""
                                            } catch (e: Exception) { beinMessage = e.message ?: "تعذر تفعيل الكود" }
                                            finally { beinBusy = false }
                                        }
                                    }
                                )
                                selectedCategory == "recent" -> {
                                    val recentChannels = history.mapNotNull { id -> channels.firstOrNull { it.id == id } }
                                    if (recentChannels.isEmpty()) TvEmpty("ما شاهدت أي قناة بعد", "عرض القنوات") { selectedCategory = "all" }
                                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        items(recentChannels, key = { it.id }) { channel ->
                                            TvChannelRow(channel, favorites.contains(channel.id),
                                                onOpen = { requestChannel(channel) },
                                                onFavorite = { toggleFavorite(context, scope, channel, favorites) { favorites = it } })
                                        }
                                    }
                                }
                                error != null && channels.isEmpty() -> TvEmpty(error!!, "إعادة المحاولة") { refreshCatalog() }
                                else -> {
                                    val filtered = channels.filter { channel ->
                                        (selectedCategory == "all" ||
                                            (selectedCategory == "favorites" && favorites.contains(channel.id)) ||
                                            channel.categoryId == selectedCategory) &&
                                            (search.isBlank() || channel.name.contains(search, true) || channel.englishName.contains(search, true))
                                    }
                                    if (filtered.isEmpty()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("ماكو قنوات بهذا القسم", color = Muted, fontSize = 18.sp)
                                        }
                                    } else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                                        items(filtered, key = { it.id }) { channel ->
                                            TvChannelRow(
                                                channel = channel,
                                                favorite = favorites.contains(channel.id),
                                                onOpen = { requestChannel(channel) },
                                                onFavorite = { toggleFavorite(context, scope, channel, favorites) { favorites = it } }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                selectedChannel?.let { channel -> TvPlayerDialog(channel, onClose = { selectedChannel = null }) }
                if (pinTarget != null) {
                    TvPinPrompt(pinInput, pinError, onInput = { pinInput = it.filter(Char::isDigit).take(4) }, onConfirm = {
                        if (hashPin(pinInput) == pinHash) {
                            val target = pinTarget
                            pinTarget = null
                            pinInput = ""
                            if (target != null) playChannel(target)
                        } else pinError = "الرمز غير صحيح"
                    }, onCancel = { pinTarget = null; pinInput = "" })
                }
                if (settingsOpen) {
                    TvSettingsDialog(pinEnabled, onSave = { newPin ->
                        pinHash = hashPin(newPin)
                        pinEnabled = true
                        savePin(context, pinHash, true)
                        settingsOpen = false
                    }, onDisable = {
                        pinHash = ""
                        pinEnabled = false
                        savePin(context, "", false)
                        settingsOpen = false
                    }, onClose = { settingsOpen = false })
                }
            }
        }
    }
}

private fun isBeinCategory(value: String): Boolean =
    value.contains("bein", true) || value.contains("بي ان") || value.contains("بين سبورت")

private fun toggleFavorite(
    context: Context, scope: kotlinx.coroutines.CoroutineScope, channel: TvChannel,
    current: Set<String>, update: (Set<String>) -> Unit
) {
    val next = if (current.contains(channel.id)) current - channel.id else current + channel.id
    update(next)
    saveFavorites(context, next)
    scope.launch {
        try { VelnixApi.setFavorite(context, channel.id, next.contains(channel.id)) }
        catch (_: Exception) { /* The next successful refresh reconciles this device with the account. */ }
    }
}

@Composable
private fun TvBeinGate(code: String, onCode: (String) -> Unit, message: String, busy: Boolean, onActivate: () -> Unit) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Panel).padding(34.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🔒", fontSize = 42.sp)
        Spacer(Modifier.height(12.dp))
        Text("قسم beIN SPORTS", color = Gold, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("أدخل كود التفعيل المرتبط بحسابك لفتح القنوات الرياضية", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(value = code, onValueChange = onCode, modifier = Modifier.width(390.dp),
            singleLine = true, label = { Text("VELNIX-XXXX-XXXX-XXXX") },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, focusedLabelColor = Gold))
        Spacer(Modifier.height(12.dp))
        Button(onClick = onActivate, enabled = !busy && code.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Night)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Night, strokeWidth = 2.dp)
            else Text("تفعيل القسم", fontWeight = FontWeight.Bold)
        }
        if (message.isNotBlank()) Text(message, color = if (message.contains("نجاح")) Gold else Muted, fontSize = 13.sp)
    }
}

@Composable
private fun TvAccountScreen(subscription: TvSubscription?, message: String, beinAccess: Boolean, beinExpiry: String?) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Panel).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("ملخص الحساب", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvInfoCard("حالة الاشتراك", if (subscription == null) "غير مشترك" else "نشط")
            TvInfoCard("الباقة", subscription?.plan ?: "مجاني")
            TvInfoCard("الانتهاء", formatExpiry(subscription?.expiresAt))
        }
        Text(message, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text("beIN SPORTS", color = Gold, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(if (beinAccess) "مفعّل حتى \${formatExpiry(beinExpiry)}" else "غير مفعّل — يتطلب كود تفعيل من إدارة VELNIX",
            color = if (beinAccess) TextMain else Muted, fontSize = 14.sp)
    }
}

@Composable
private fun TvInfoCard(label: String, value: String) {
    Column(Modifier.width(180.dp).clip(RoundedCornerShape(14.dp)).background(Panel2).padding(16.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text(value, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun formatExpiry(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    return try {
        val date = java.time.Instant.parse(value).atZone(ZoneId.of("Asia/Baghdad"))
        date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
    } catch (_: Exception) { value.take(10) }
}

@Composable
private fun TvMatchesScreen() {
    var day by remember { mutableIntStateOf(0) }
    var fixtures by remember(day) { mutableStateOf(emptyList<TvFixture>()) }
    var loading by remember(day) { mutableStateOf(true) }
    var error by remember(day) { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(day, reload) {
        loading = true
        error = ""
        try { fixtures = VelnixApi.loadFixtures(day) }
        catch (e: Exception) { error = e.message ?: "تعذر تحميل المباريات" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { offset ->
                val date = LocalDate.now(ZoneId.of("Asia/Baghdad")).plusDays(offset.toLong())
                TvDayButton(if (offset == 0) "اليوم" else if (offset == 1) "غداً" else date.format(DateTimeFormatter.ofPattern("dd/MM")),
                    day == offset) { day = offset }
            }
        }
        Spacer(Modifier.height(14.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Gold) }
            error.isNotBlank() -> TvEmpty(error, "إعادة المحاولة") { reload++ }
            fixtures.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد مباريات منشورة لهذا اليوم", color = Muted, fontSize = 17.sp)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(fixtures, key = { it.id }) { TvFixtureCard(it) }
            }
        }
    }
}

@Composable
private fun TvDayButton(title: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (active || focused) Color(0xFF403622) else Panel)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).focusable()
        .padding(horizontal = 16.dp, vertical = 11.dp)) {
        Text(title, color = if (active || focused) Gold else TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TvFixtureCard(fixture: TvFixture) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(fixture.league, color = Gold, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.height(7.dp))
            Text(fixture.home, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(fixture.away, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (fixture.state == "in" || fixture.state == "post") "\${fixture.homeScore} - \${fixture.awayScore}" else "VS",
                color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Black)
            Text(if (fixture.state == "in") "● مباشر" else fixture.status, color = if (fixture.state == "in") Gold else Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TvPinPrompt(value: String, error: String, onInput: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text("الرقابة الأبوية") },
        text = {
            Column {
                Text("أدخل رمز PIN المكوّن من 4 أرقام لمتابعة المشاهدة", color = Muted)
                OutlinedTextField(value = value, onValueChange = onInput, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("رمز PIN") })
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF9A91), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.length == 4) { Text("فتح") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("إلغاء") } },
        containerColor = Panel, titleContentColor = TextMain, textContentColor = Muted)
}

@Composable
private fun TvSettingsDialog(enabled: Boolean, onSave: (String) -> Unit, onDisable: () -> Unit, onClose: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("إعدادات المشاهدة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("الرقابة الأبوية", color = Gold, fontWeight = FontWeight.Bold)
                Text(if (enabled) "الرقابة الأبوية مفعّلة. سيُطلب الرمز قبل فتح أي قناة." else "أنشئ رمزاً من 4 أرقام لحماية فتح القنوات على هذا الجهاز.", color = Muted)
                OutlinedTextField(value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("رمز PIN جديد") })
                if (message.isNotBlank()) Text(message, color = Color(0xFFFF9A91), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { if (pin.length == 4) onSave(pin) else message = "أدخل 4 أرقام بالضبط" }) { Text("حفظ الرمز") } },
        dismissButton = {
            Row {
                if (enabled) TextButton(onClick = onDisable) { Text("إيقاف الحماية") }
                TextButton(onClick = onClose) { Text("إغلاق") }
            }
        },
        containerColor = Panel, titleContentColor = TextMain, textContentColor = Muted)
}

private fun hashPin(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private fun savePin(context: Context, hash: String, enabled: Boolean) {
    context.getSharedPreferences("velnix_tv_security", Context.MODE_PRIVATE).edit()
        .putString("pin_hash", hash).putBoolean("pin_enabled", enabled).apply()
}

private fun getPinHash(context: Context): String =
    context.getSharedPreferences("velnix_tv_security", Context.MODE_PRIVATE).getString("pin_hash", "").orEmpty()

private fun isPinEnabled(context: Context): Boolean =
    context.getSharedPreferences("velnix_tv_security", Context.MODE_PRIVATE).getBoolean("pin_enabled", false)

private fun loadHistory(context: Context): List<String> {
    val raw = context.getSharedPreferences("velnix_tv", Context.MODE_PRIVATE).getString("history", "[]") ?: "[]"
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { array.getString(it) }
    } catch (_: Exception) { emptyList() }
}

private fun recordHistory(context: Context, current: List<String>, id: String): List<String> {
    val next = (listOf(id) + current.filterNot { it == id }).take(30)
    context.getSharedPreferences("velnix_tv", Context.MODE_PRIVATE).edit()
        .putString("history", JSONArray(next).toString()).apply()
    return next
}

@Composable
private fun LoginScreen(
    busy: Boolean,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
    error: String?
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(50.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("VELNIX", color = Gold, fontSize = 52.sp, fontWeight = FontWeight.Black, letterSpacing = 9.sp)
        Text("TV EXPERIENCE", color = Muted, fontSize = 12.sp, letterSpacing = 4.sp)
        Spacer(Modifier.height(28.dp))
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.width(460.dp)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (createAccount) "إنشاء حساب VELNIX" else "تسجيل الدخول", color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = email, onValueChange = { email = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("البريد الإلكتروني") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("كلمة المرور") }, visualTransformation = PasswordVisualTransformation())
                if (!error.isNullOrBlank()) Text(error, color = Color(0xFFFF9A91), fontSize = 12.sp)
                Button(
                    onClick = { if (createAccount) onRegister(email, password) else onLogin(email, password) },
                    enabled = !busy && email.contains("@") && password.length >= if (createAccount) 8 else 6,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Night)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Night, strokeWidth = 2.dp)
                    else Text(if (createAccount) "إنشاء الحساب" else "دخول", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { createAccount = !createAccount }) {
                    Text(if (createAccount) "عندي حساب بالفعل" else "إنشاء حساب جديد", color = Gold)
                }
            }
        }
    }
}

@Composable
private fun TvNavItem(title: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(when { active -> Color(0xFF3A3020); focused -> Panel2; else -> Color.Transparent })
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).focusable()
            .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        if (active) {
            Box(Modifier.width(3.dp).height(22.dp).clip(RoundedCornerShape(3.dp)).background(Gold))
            Spacer(Modifier.width(10.dp))
        }
        Text(title, color = if (active) Gold else TextMain, fontSize = 14.sp,
            fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvAction(title: String, busy: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(if (focused) Color(0xFF403622) else Panel2)
        .onFocusChanged { focused = it.isFocused }.clickable(enabled = !busy, onClick = onClick).focusable()
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = Gold, strokeWidth = 2.dp)
        else Text(title, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TvChannelRow(channel: TvChannel, favorite: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(12.dp))
        .background(if (focused) Color(0xFF302B21) else Panel.copy(alpha = .94f))
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onOpen).focusable()
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)).background(Panel2), contentAlignment = Alignment.Center) {
            if (channel.logo != null) AsyncImage(channel.logo, null, modifier = Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
            else Text(channel.name.take(2), color = Gold, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(channel.name, color = TextMain, fontSize = 15.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("● مباشر", color = Gold, fontSize = 10.sp)
        }
        Text(if (favorite) "★" else "☆", color = if (favorite) Gold else Muted, fontSize = 25.sp,
            modifier = Modifier.clickable(onClick = onFavorite).focusable().padding(horizontal = 12.dp, vertical = 6.dp))
        Text("▶", color = Gold, fontSize = 17.sp)
    }
}

@Composable
private fun TvEmpty(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = TextMain, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun TvPlayerDialog(channel: TvChannel, onClose: () -> Unit) {
    val context = LocalContext.current
    var player by remember(channel.id) { mutableStateOf<ExoPlayer?>(null) }
    var message by remember(channel.id) { mutableStateOf("جارٍ تجهيز البث...") }
    var retry by remember(channel.id) { mutableIntStateOf(0) }
    val currentRetry by rememberUpdatedState(retry)

    LaunchedEffect(channel.id, retry) {
        try {
            delay(if (retry == 0) 0 else (1000L shl (retry - 1).coerceAtMost(5)).coerceAtMost(30_000L))
            val stream = VelnixApi.stream(context, channel.id)
            player?.release()
            val dataSource = DefaultHttpDataSource.Factory().setUserAgent("VELNIX-TV/1.0")
                .setConnectTimeoutMs(20_000).setReadTimeoutMs(60_000).setAllowCrossProtocolRedirects(true)
            val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 90_000, 5_000, 15_000).setBackBuffer(0, false).build()
            val newPlayer = ExoPlayer.Builder(context).setLoadControl(loadControl).setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSource)
            ).build()
            player = newPlayer
            val item = MediaItem.Builder().setUri(stream.url).apply {
                if (stream.type.contains("hls") || stream.type.contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
                else if (stream.type.contains("dash") || stream.type.contains("mpd")) setMimeType(MimeTypes.APPLICATION_MPD)
            }.build()
            newPlayer.setMediaItem(item)
            newPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    message = "تعذر استمرار البث، جاري إعادة الاتصال..."
                    retry = currentRetry + 1
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) { message = "" }
                    if (state == Player.STATE_ENDED) { message = "انتهى المصدر، جاري إعادة الاتصال..."; retry = currentRetry + 1 }
                }
            })
            newPlayer.prepare()
            newPlayer.playWhenReady = true
            message = ""
        } catch (e: Exception) {
            message = e.message ?: "تعذر تشغيل القناة"
            val detail = message
            val requiresUserAction = detail.contains("سجّل الدخول") || detail.contains("القسم مقفول") ||
                detail.contains("غير متاح") || detail.contains("غير مربوط") || detail.contains("403") || detail.contains("401") || detail.contains("404")
            if (!requiresUserAction) retry = currentRetry + 1
        }
    }

    DisposableEffect(player) { onDispose { player?.release() } }
    BackHandler { onClose() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val activePlayer = player
            if (activePlayer != null) AndroidView(
                factory = { PlayerView(it).apply { player = activePlayer; useController = true; keepScreenOn = true } },
                update = { it.player = activePlayer },
                modifier = Modifier.fillMaxSize()
            )
            Column(Modifier.align(Alignment.TopStart).padding(26.dp)) {
                Text(channel.name, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                if (message.isNotBlank()) Text(message, color = Gold, fontSize = 13.sp)
            }
            Text("رجوع للإغلاق", color = TextMain, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp))
        }
    }
}

private fun loadFavorites(context: Context): Set<String> =
    context.getSharedPreferences("velnix_tv", Context.MODE_PRIVATE).getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

private fun saveFavorites(context: Context, favorites: Set<String>) {
    context.getSharedPreferences("velnix_tv", Context.MODE_PRIVATE).edit().putStringSet("favorites", favorites).apply()
}
