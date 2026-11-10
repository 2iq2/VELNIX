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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val Night = Color(0xFF070B13)
private val Panel = Color(0xFF111B2B)
private val PanelLight = Color(0xFF19283B)
private val Accent = Color(0xFF5BE0C5)
private val Muted = Color(0xFF9AABC0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.keepScreenOn = true
        setContent { VelnixTvApp() }
    }
}

data class TvCategory(val id: String, val title: String, val order: Int)
data class TvChannel(
    val id: String,
    val categoryId: String?,
    val name: String,
    val englishName: String,
    val logo: String?,
    val streamType: String
)

private object VelnixApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun get(path: String): String {
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL + path)
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: "[]"
        }
    }

    suspend fun loadCatalog(): Pair<List<TvCategory>, List<TvChannel>> = withContext(Dispatchers.IO) {
        val categoriesJson = JSONArray(get("/rest/v1/channel_categories?select=id,name_ar,name_en,sort_order&is_active=eq.true&order=sort_order.asc"))
        val channelsJson = JSONArray(get("/rest/v1/channels?select=id,category_id,name_ar,name_en,logo_url,stream_type&is_active=eq.true&order=name_en.asc&limit=5000"))
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

    fun streamUrl(channelId: String): String =
        BuildConfig.GATEWAY_URL + "?channel_id=" + URLEncoder.encode(channelId, "UTF-8")
}

@Composable
private fun VelnixTvApp() {
    val context = LocalContext.current
    var categories by remember { mutableStateOf(emptyList<TvCategory>()) }
    var channels by remember { mutableStateOf(emptyList<TvChannel>()) }
    var selectedCategory by remember { mutableStateOf("all") }
    var search by remember { mutableStateOf("") }
    var selectedChannel by remember { mutableStateOf<TvChannel?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        error = null
        if (channels.isEmpty()) loading = true
        try {
            val result = VelnixApi.loadCatalog()
            categories = result.first
            channels = result.second
        } catch (e: Exception) {
            error = "تعذر تحميل القنوات. تحقق من اتصال التلفزيون بالإنترنت."
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val visibleChannels by produceState(
        initialValue = emptyList(),
        channels, selectedCategory, search, favorites
    ) {
        delay(120)
        value = withContext(Dispatchers.Default) {
            channels.filter { channel ->
                (selectedCategory == "all" ||
                    (selectedCategory == "favorites" && favorites.contains(channel.id)) ||
                    channel.categoryId == selectedCategory) &&
                    (search.isBlank() || channel.name.contains(search, true) ||
                        channel.englishName.contains(search, true))
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Night, surface = Panel, primary = Accent,
            onPrimary = Night, onBackground = Color(0xFFF4F7FB),
            onSurface = Color(0xFFF4F7FB)
        )
    ) {
        Box(Modifier.fillMaxSize().background(Night)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(54.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("VELNIX", color = Accent, fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("TV", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(if (loading) "جارٍ التحميل..." else "${channels.size} قناة متاحة", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.width(18.dp))
                    ActionChip("تحديث", refreshing) { scope.launch { refresh() } }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(
                        Modifier.width(180.dp).fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp)).background(Panel).padding(14.dp)
                    ) {
                        Text("التصفح", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            item(key = "all") {
                                SidebarItem("كل القنوات", selectedCategory == "all") { selectedCategory = "all" }
                            }
                            item(key = "favorites") {
                                SidebarItem("المفضلة", selectedCategory == "favorites") { selectedCategory = "favorites" }
                            }
                            items(categories, key = { it.id }, contentType = { "category" }) { category ->
                                SidebarItem(category.title, selectedCategory == category.id) { selectedCategory = category.id }
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(
                            Modifier.fillMaxWidth().height(48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (selectedCategory) {
                                        "all" -> "اكتشف القنوات"
                                        "favorites" -> "قنواتك المفضلة"
                                        else -> categories.firstOrNull { it.id == selectedCategory }?.title ?: "القنوات"
                                    },
                                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
                                )
                                Text("${visibleChannels.size} قناة", color = Muted, fontSize = 12.sp)
                            }
                            SearchField(search, onSearch = { search = it })
                        }
                        Spacer(Modifier.height(12.dp))
                        when {
                            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Accent)
                            }
                            error != null && channels.isEmpty() -> EmptyState(error!!, "إعادة المحاولة") { scope.launch { refresh() } }
                            visibleChannels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("ماكو قنوات بهالقسم", color = Muted, fontSize = 18.sp)
                            }
                            else -> LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(
                                    visibleChannels,
                                    key = { _, channel -> channel.id },
                                    contentType = { _, _ -> "channel-row" }
                                ) { index, channel ->
                                    ChannelListRow(
                                        channel = channel,
                                        channelNumber = index + 1,
                                        favorite = favorites.contains(channel.id),
                                        onOpen = { selectedChannel = channel },
                                        onFavorite = {
                                            favorites = if (favorites.contains(channel.id)) favorites - channel.id else favorites + channel.id
                                            saveFavorites(context, favorites)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            selectedChannel?.let { channel ->
                TvPlayerDialog(channel = channel, onClose = { selectedChannel = null })
            }
        }
    }
}

@Composable
private fun HeroPanel(channelCount: Int) {
    Box(
        Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF183A48), Color(0xFF14243A), Color(0xFF111827))))
            .padding(horizontal = 30.dp, vertical = 18.dp)
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text("تجربة مشاهدة مختلفة", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(5.dp))
            Text("رياضة • أخبار • ترفيه • قنوات مباشرة", color = Color(0xFFC2D0DF), fontSize = 14.sp)
        }
        Column(Modifier.align(Alignment.CenterEnd), horizontalAlignment = Alignment.End) {
            Text("LIVE", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("$channelCount", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("قناة", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SidebarItem(title: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp))
            .background(when { active -> Color(0xFF244B50); focused -> PanelLight; else -> Color.Transparent })
            .clickable(onClick = onClick).onFocusChanged { focused = it.isFocused }.focusable()
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (active) Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(Accent))
        if (active) Spacer(Modifier.width(9.dp))
        Text(title, color = if (active) Accent else Color(0xFFE4EAF1), fontSize = 14.sp,
            fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionChip(title: String, busy: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF28545A) else PanelLight)
            .clickable(enabled = !busy, onClick = onClick)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = Accent, strokeWidth = 2.dp)
        else Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchField(value: String, onSearch: (String) -> Unit) {
    Row(
        Modifier.width(270.dp).height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(PanelLight).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⌕", color = Accent, fontSize = 22.sp)
        Spacer(Modifier.width(9.dp))
        BasicTextField(
            value = value, onValueChange = onSearch, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text("ابحث عن قناة", color = Muted, fontSize = 13.sp)
                inner()
            }
        )
    }
}

@Composable
private fun EmptyState(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = Color.White, fontSize = 17.sp)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ChannelListRow(
    channel: TvChannel,
    channelNumber: Int,
    favorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val logoRequest = remember(channel.logo) {
        channel.logo?.let { url ->
            ImageRequest.Builder(context).data(url).size(96, 96).crossfade(false).build()
        }
    }
    Row(
        Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(9.dp))
            .background(if (focused) Color(0xFF203B4A) else Panel)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onOpen).focusable()
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            channelNumber.toString().padStart(2, '0'),
            color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(34.dp)
        )
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF20334A)),
            contentAlignment = Alignment.Center
        ) {
            if (logoRequest != null) {
                AsyncImage(
                    model = logoRequest, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                )
            } else {
                Text(channel.name.take(2), color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                channel.name,
                color = Color.White,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Clip
            )
            Text("مباشر", color = Accent, fontSize = 9.sp, lineHeight = 11.sp)
        }
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(7.dp))
                .background(if (favorite) Color(0xFF254A4C) else Color.Transparent)
                .clickable(onClick = onFavorite).focusable(),
            contentAlignment = Alignment.Center
        ) {
            Text(if (favorite) "★" else "☆", color = if (favorite) Accent else Muted, fontSize = 19.sp)
        }
    }
}

@Composable
private fun TvPlayerDialog(channel: TvChannel, onClose: () -> Unit) {
    val context = LocalContext.current
    var playbackError by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    val currentRetryCount by rememberUpdatedState(retryCount)
    val player = remember(channel.id) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("VELNIX-TV/1.0")
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("apikey" to BuildConfig.SUPABASE_PUBLISHABLE_KEY))
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 5_000, 15_000)
            .setBackBuffer(0, false)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build().apply {
                playWhenReady = true
                val itemBuilder = MediaItem.Builder().setUri(VelnixApi.streamUrl(channel.id))
                if (channel.streamType.contains("hls") || channel.streamType.contains("m3u8")) {
                    itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                setMediaItem(itemBuilder.build())
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                retryCount = currentRetryCount + 1
                playbackError = "انقطع البث، جاري إعادة الاتصال..."
                player.playWhenReady = false
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        retryCount = 0
                        playbackError = null
                    }
                    Player.STATE_ENDED -> {
                        retryCount = currentRetryCount + 1
                        playbackError = "انتهى البث، جاري إعادة تشغيله..."
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(retryCount) {
        if (retryCount > 0) {
            // Keep retrying indefinitely; back off to avoid hammering an unavailable stream.
            val waitMs = (1000L shl (retryCount - 1).coerceAtMost(5)).coerceAtMost(30_000L)
            delay(waitMs)
            playbackError = "جاري إعادة الاتصال بالبث..."
            player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = true
        }
    }

    BackHandler { onClose() }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = true; keepScreenOn = true } },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )
            playbackError?.let {
                Text(it, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp)
                    .background(Color(0xCC101827), RoundedCornerShape(10.dp)).padding(14.dp))
            }
        }
    }
}

private fun loadFavorites(context: Context): Set<String> =
    context.getSharedPreferences("velnix_tv", Context.MODE_PRIVATE)
        .getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

private fun saveFavorites(context: Context, favorites: Set<String>) {
    context.getSharedPreferences("velnix_tv", Context.MODE_PRIVATE)
        .edit().putStringSet("favorites", favorites).apply()
}
