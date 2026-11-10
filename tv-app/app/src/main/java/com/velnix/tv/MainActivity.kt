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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val Night = Color(0xFF090D16)
private val Panel = Color(0xFF142238)
private val Accent = Color(0xFF65DDCA)
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
    var error by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        error = null
        try {
            val result = VelnixApi.loadCatalog()
            categories = result.first
            channels = result.second
        } catch (e: Exception) {
            error = "تعذر تحميل القنوات. تحقق من اتصال التلفزيون بالإنترنت."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    val visibleChannels = remember(channels, selectedCategory, search, favorites) {
        channels.filter { channel ->
            (selectedCategory == "all" || (selectedCategory == "favorites" && favorites.contains(channel.id)) ||
                channel.categoryId == selectedCategory) &&
                (search.isBlank() || channel.name.contains(search, true) || channel.englishName.contains(search, true))
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Night,
            surface = Panel,
            primary = Accent,
            onPrimary = Night,
            onBackground = Color(0xFFF4F7FB),
            onSurface = Color(0xFFF4F7FB)
        )
    ) {
        Box(Modifier.fillMaxSize().background(Night)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 46.dp, vertical = 26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("VELNIX", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Accent, letterSpacing = 4.sp)
                    Spacer(Modifier.width(24.dp))
                    Text("TV", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${channels.size} قناة", color = Muted, fontSize = 14.sp)
                    Spacer(Modifier.width(20.dp))
                    TextButton(onClick = { selectedCategory = "all"; search = ""; /* reload on demand */ }) {
                        Text("الرئيسية", color = Accent)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("شاهد عالمك بطريقتك", fontSize = 31.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("قنوات مباشرة • رياضة • أخبار • ترفيه", color = Muted, fontSize = 16.sp)
                Spacer(Modifier.height(22.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        CategoryPill("كل القنوات", selectedCategory == "all") { selectedCategory = "all" }
                    }
                    item {
                        CategoryPill("المفضلة", selectedCategory == "favorites") { selectedCategory = "favorites" }
                    }
                    items(categories) { category ->
                        CategoryPill(category.title, selectedCategory == category.id) { selectedCategory = category.id }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("بحث", color = Accent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        modifier = Modifier.width(360.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF111B2A)).padding(horizontal = 16.dp, vertical = 12.dp),
                        decorationBox = { inner ->
                            if (search.isEmpty()) Text("ابحث عن قناة...", color = Muted, fontSize = 15.sp)
                            inner()
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { refresh() }) { Text("تحديث القنوات", color = Accent) }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                    error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error!!, color = Color.White, fontSize = 18.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { scope.launch { refresh() } }) { Text("إعادة المحاولة") }
                        }
                    }
                    visibleChannels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("ماكو قنوات بهالقسم", color = Muted, fontSize = 18.sp)
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 30.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(visibleChannels, key = { it.id }) { channel ->
                            ChannelCard(
                                channel = channel,
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
            selectedChannel?.let { channel ->
                TvPlayerDialog(channel = channel, onClose = { selectedChannel = null })
            }
        }
    }
}

@Composable
private fun CategoryPill(title: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(when { active -> Accent; focused -> Color(0xFF23434A); else -> Color(0xFF152238) })
            .clickable(onClick = onClick).focusable()
            .onFocusChanged { focused = it.isFocused }
            .padding(1.dp)
    ) {
        Text(title, color = if (active) Night else Color.White, fontSize = 14.sp,
            fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp))
    }
}

@Composable
private fun ChannelCard(
    channel: TvChannel,
    favorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.height(112.dp).clip(RoundedCornerShape(15.dp))
            .background(if (focused) Color(0xFF1C3947) else Panel)
            .clickable(onClick = onOpen).focusable()
            .onFocusChanged { focused = it.isFocused }
            .padding(1.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF20334A)), contentAlignment = Alignment.Center) {
                if (channel.logo != null) {
                    AsyncImage(model = channel.logo, contentDescription = channel.name, modifier = Modifier.fillMaxSize().padding(5.dp))
                } else {
                    Text(channel.name.take(2), color = Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text("بث مباشر", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = onFavorite, contentPadding = PaddingValues(0.dp)) {
                Text(if (favorite) "★" else "☆", color = if (favorite) Accent else Muted, fontSize = 21.sp)
            }
        }
    }
}

@Composable
private fun TvPlayerDialog(channel: TvChannel, onClose: () -> Unit) {
    val context = LocalContext.current
    var playbackError by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    val player = remember(channel.id) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("VELNIX-TV/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("apikey" to BuildConfig.SUPABASE_PUBLISHABLE_KEY))
        ExoPlayer.Builder(context)
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
                if (retryCount < 4) {
                    retryCount++
                    playbackError = "انقطع الاتصال، إعادة المحاولة ${retryCount}/4..."
                    player.playWhenReady = false
                } else {
                    playbackError = "تعذر تشغيل القناة. جرّب قناة ثانية أو أعد المحاولة."
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playbackError = null
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(retryCount) {
        if (retryCount in 1..4) {
            delay((retryCount * 1500L).coerceAtMost(6000L))
            playbackError = null
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
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x99070B12)).padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("VELNIX  /  ", color = Accent, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("رجوع ✕", color = Color.White) }
            }
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
