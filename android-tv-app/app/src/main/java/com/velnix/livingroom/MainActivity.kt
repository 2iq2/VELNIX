package com.velnix.livingroom

import android.content.pm.ActivityInfo
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.os.Build
import android.os.StatFs
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.LayoutInflater

private val AppBg = Color(0xFF08090D)
private val Panel = Color(0xFF14151B)
private val PanelRaised = Color(0xFF1B1C24)
private val Accent = Color(0xFFE50914)
private val Muted = Color(0xFFA4A5AD)
private val Line = Color(0x24FFFFFF)
private val White = Color(0xFFF7F7F8)
private val LocalVelnixAccent = compositionLocalOf { Color(0xFFE50914) }

private val AvatarOptions = listOf(
    AvatarOption("anime1", "المحقق الهادئ", "anime1-detective.svg"),
    AvatarOption("anime2", "الفتاة الغامضة", "anime2-mystery.svg"),
    AvatarOption("anime3", "المقاتل القوي", "anime3-warrior.svg"),
    AvatarOption("anime4", "الفتاة المرحة", "anime4-cheerful.svg"),
    AvatarOption("anime5", "القرصان الإلكتروني", "anime5-cyber-pirate.svg"),
    AvatarOption("anime6", "الفارس الأنيق", "anime6-knight.svg"),
    AvatarOption("anime7", "العالم العبقري", "anime7-scholar.svg"),
    AvatarOption("anime8", "القائد", "anime8-leader.svg")
)

private fun avatarUrl(id: String): String {
    val file = AvatarOptions.firstOrNull { it.id == id }?.file ?: AvatarOptions.first().file
    return "https://2iq2.github.io/VELNIX/assets/avatars/" + file
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.statusBarColor = android.graphics.Color.rgb(8, 9, 13)
        window.navigationBarColor = android.graphics.Color.rgb(8, 9, 13)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setContent { VelnixApp(this) }
    }
}

@Composable
private fun VelnixApp(activity: ComponentActivity) {
    val api = remember { VelnixApi(activity) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activityManager = remember {
        context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    }
    val lowRamDevice = remember {
        activityManager.isLowRamDevice || activityManager.memoryClass <= 256
    }
    val imageLoader = remember(lowRamDevice) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(false)
            .memoryCache {
                coil.memory.MemoryCache.Builder(context)
                    .maxSizePercent(if (lowRamDevice) 0.04 else 0.07)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("velnix_channel_images"))
                    .maxSizeBytes(if (lowRamDevice) 40L * 1024L * 1024L else 64L * 1024L * 1024L)
                    .build()
            }
            .build()
    }

    var accentArgb by remember { mutableIntStateOf(context.getSharedPreferences("velnix_ui", android.content.Context.MODE_PRIVATE).getInt("accent_argb", android.graphics.Color.rgb(229, 9, 20))) }
    val activeAccent = Color(accentArgb)
    var pairingInfo by remember { mutableStateOf<DevicePairingInfo?>(null) }
    var pairingError by remember { mutableStateOf("") }
    var pairingBusy by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(api.session()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var catalogOffset by remember { mutableIntStateOf(0) }
    var hasMoreChannels by remember { mutableStateOf(false) }
    var loadingMoreChannels by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(api.loadFavorites()) }
    var recent by remember { mutableStateOf(api.loadRecent()) }
    var streamType by remember { mutableStateOf("hls") }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var beinAccess by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("جارٍ الاتصال بدليل القنوات…") }
    var splashVisible by remember { mutableStateOf(true) }
    var profileVisible by remember { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    var profile by remember {
        mutableStateOf(ProfileInfo(session?.userEmail?.substringBefore("@") ?: "مستخدم VELNIX", "anime1"))
    }
    var profileSaving by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf("") }
    var settingsVisible by remember { mutableStateOf(false) }
    var familyVisible by remember { mutableStateOf(false) }
    var autoReconnect by remember { mutableStateOf(api.loadAutoReconnect()) }
    var dataSaver by remember { mutableStateOf(api.loadDataSaver()) }
    var showLogos by remember { mutableStateOf(api.loadShowLogos()) }
    var busyChannel by remember { mutableStateOf<String?>(null) }

    fun refreshCatalog() {
        scope.launch {
            busy = true
            try {
                val page = api.channels(offset = 0, pageSize = 100)
                channels = page.channels
                catalogOffset = page.nextOffset
                hasMoreChannels = page.hasMore
                status = "تم تحميل " + channels.size + " قناة"
            } catch (e: Exception) {
                status = e.message ?: "تعذر تحميل القنوات. تحقق من الاتصال ثم أعد المحاولة."
            } finally {
                busy = false
            }
        }
    }

    fun loadMoreCatalog() {
        if (loadingMoreChannels || !hasMoreChannels) return
        scope.launch {
            loadingMoreChannels = true
            try {
                val page = api.channels(offset = catalogOffset, pageSize = 100)
                channels = (channels + page.channels).distinctBy { it.id }
                catalogOffset = page.nextOffset
                hasMoreChannels = page.hasMore
                status = "تم تحميل " + channels.size + " قناة"
            } catch (e: Exception) {
                status = e.message ?: "تعذر تحميل بقية القنوات"
            } finally {
                loadingMoreChannels = false
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(900)
        splashVisible = false
        refreshCatalog()
    }

    LaunchedEffect(session?.userId) {
        val active = session
        if (active != null) {
            profile = ProfileInfo(active.userEmail.substringBefore("@"), "anime1")
            try {
                profile = api.loadProfile(active.userId, active.userEmail.substringBefore("@"))
                beinAccess = api.checkBein()
            } catch (_: Exception) {
                // Keep the locally available account identity if the profile service is temporarily unreachable.
            }
        }
    }

    LaunchedEffect(pairingInfo?.deviceToken) {
        val pendingPair = pairingInfo ?: return@LaunchedEffect
        pairingError = ""
        while (System.currentTimeMillis() < pendingPair.expiresAt) {
            try {
                val tokenHash = api.pollDevicePairing(pendingPair.deviceToken)
                if (!tokenHash.isNullOrBlank()) {
                    val linkedSession = api.verifyDeviceMagicLink(tokenHash)
                    session = linkedSession
                    beinAccess = api.checkBein()
                    pairingInfo = null
                    refreshCatalog()
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                pairingError = e.message ?: "تعذر إكمال ربط التلفزيون."
                pairingInfo = null
                return@LaunchedEffect
            }
            delay(2200)
        }
        pairingInfo = null
        pairingError = "انتهت صلاحية رمز الربط. أنشئ رمزاً جديداً."
    }

    BackHandler(enabled = !splashVisible && selected == null && !profileVisible) {
        if (settingsVisible) settingsVisible = false else exitPrompt = true
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = activeAccent,
            onPrimary = White,
            secondary = activeAccent,
            background = AppBg,
            onBackground = White,
            surface = Panel,
            onSurface = White,
            surfaceVariant = PanelRaised,
            onSurfaceVariant = Muted
        )
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalVelnixAccent provides activeAccent) {
            Surface(Modifier.fillMaxSize(), color = AppBg) {
                when {
                    splashVisible -> SplashScreen()
                    selected != null && streamUrl != null -> NativePlayer(
                        api = api,
                        channel = selected!!,
                        url = streamUrl!!,
                        streamType = streamType,
                        imageLoader = imageLoader,
                        lowRamDevice = lowRamDevice,
                        autoReconnect = autoReconnect,
                        dataSaver = dataSaver,
                        onBack = {
                            selected = null
                            streamUrl = null
                        }
                    )
                    familyVisible && session != null -> FamilyManagementScreen(
                        api = api, userId = session!!.userId, channels = channels,
                        onBack = { familyVisible = false }
                    )
                    settingsVisible && session != null -> SettingsScreen(
                        accentArgb = accentArgb,
                        onAccentArgb = { value ->
                            accentArgb = value
                            context.getSharedPreferences("velnix_ui", android.content.Context.MODE_PRIVATE).edit().putInt("accent_argb", value).apply()
                        },
                        autoReconnect = autoReconnect,
                        onAutoReconnect = {
                            autoReconnect = it
                            api.saveAutoReconnect(it)
                        },
                        dataSaver = dataSaver,
                        onDataSaver = {
                            dataSaver = it
                            api.saveDataSaver(it)
                        },
                        showLogos = showLogos,
                        onShowLogos = {
                            showLogos = it
                            api.saveShowLogos(it)
                        },
                        lowRamDevice = lowRamDevice,
                        onClearCache = {
                            imageLoader.memoryCache?.clear()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    context.cacheDir.resolve("velnix_channel_images").deleteRecursively()
                                }
                                status = "تم مسح ذاكرة صور القنوات"
                            }
                        },
                        onClearHistory = {
                            recent = emptyList()
                            api.saveRecent(emptyList())
                            status = "تم مسح سجل المشاهدة"
                        },
                        onBack = { settingsVisible = false }
                    )
                    profileVisible && session != null -> ProfileScreen(
                        current = profile,
                        imageLoader = imageLoader,
                        saving = profileSaving,
                        saveError = profileError,
                        onBack = { profileVisible = false },
                        onSave = { name, avatar ->
                            val active = session
                            if (active != null) {
                                scope.launch {
                                    profileSaving = true
                                    profileError = ""
                                    try {
                                        api.saveProfile(active.userId, name, avatar)
                                        profile = ProfileInfo(name, avatar)
                                        profileVisible = false
                                    } catch (e: Exception) {
                                        profileError = e.message ?: "تعذر حفظ الملف الشخصي"
                                    } finally {
                                        profileSaving = false
                                    }
                                }
                            }
                        }
                    )
                    session == null -> WelcomeAuthScreen(
                        pairing = pairingInfo,
                        pairingError = pairingError,
                        pairingBusy = pairingBusy,
                        onPair = {
                            scope.launch {
                                pairingBusy = true
                                pairingError = ""
                                try {
                                    pairingInfo = api.createDevicePairing("VELNIX TV · " + Build.MODEL)
                                } catch (e: Exception) {
                                    pairingError = e.message ?: "تعذر إنشاء رمز الربط."
                                } finally {
                                    pairingBusy = false
                                }
                            }
                        },
                        onCancelPair = { pairingInfo = null },

                        email = email,
                        onEmail = { email = it },
                        password = password,
                        onPassword = { password = it },
                        signup = signup,
                        onSwitch = {
                            signup = !signup
                            error = ""
                        },
                        busy = busy,
                        error = error,
                        onSubmit = {
                            scope.launch {
                                busy = true
                                error = ""
                                try {
                                    session = if (signup) {
                                        api.signUp(email.trim(), password)
                                    } else {
                                        api.signIn(email.trim(), password)
                                    }
                                    beinAccess = api.checkBein()
                                    refreshCatalog()
                                } catch (e: Exception) {
                                    error = e.message ?: "تعذر تسجيل الدخول. تحقق من البريد وكلمة المرور."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    )
                    else -> HomeScreen(
                        session = session!!,
                        profile = profile,
                        channels = channels,
                        hasMoreChannels = hasMoreChannels,
                        loadingMoreChannels = loadingMoreChannels,
                        onLoadMore = { loadMoreCatalog() },
                        category = category,
                        onCategory = { category = it },
                        query = query,
                        onQuery = { query = it },
                        favorites = favorites,
                        recent = recent,
                        beinAccess = beinAccess,
                        status = status,
                        busy = busy,
                        busyChannel = busyChannel,
                        imageLoader = imageLoader,
                        showLogos = showLogos,
                        onProfile = { profileVisible = true },
                        onSettings = { settingsVisible = true },
                        onFamily = { familyVisible = true },
                        onRefresh = { refreshCatalog() },
                        onSignOut = {
                            api.signOut()
                            session = null
                            profileVisible = false
                            password = ""
                        },
                        onFavorite = { channel ->
                            favorites = if (channel.id in favorites) favorites - channel.id else favorites + channel.id
                            api.saveFavorites(favorites)
                        },
                        onPlay = { channel ->
                            scope.launch {
                                busyChannel = channel.id
                                error = ""
                                try {
                                    if (channel.category == "bein" && !beinAccess) {
                                        throw IllegalStateException("قسم beIN SPORTS غير مفعّل على هذا الحساب.")
                                    }
                                    val source = api.stream(channel.streamIds.firstOrNull() ?: channel.id, channel.streamType)
                                    streamUrl = source.url
                                    streamType = source.type
                                    selected = channel
                                    recent = (listOf(channel.id) + recent.filterNot { it == channel.id }).take(30)
                                    api.saveRecent(recent)
                                } catch (e: Exception) {
                                    error = e.message ?: "تعذر تجهيز البث"
                                } finally {
                                    busyChannel = null
                                }
                            }
                        },
                        error = error
                    )
                }
            }

            if (exitPrompt) {
                AlertDialog(
                    onDismissRequest = { exitPrompt = false },
                    containerColor = Panel,
                    title = { Text("الخروج من VELNIX؟", color = White, fontWeight = FontWeight.Bold) },
                    text = { Text("اضغط خروج مرة ثانية للتأكيد. حسابك يبقى محفوظاً ولن يتم تسجيل خروجك.", color = Muted) },
                    confirmButton = {
                        TextButton(onClick = { exitPrompt = false; activity.finish() }) {
                            Text("خروج", color = LocalVelnixAccent.current, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { exitPrompt = false }) { Text("البقاء في التطبيق", color = White) }
                    }
                )
            }
        }
    }
}

@Composable
private fun BrandMark(size: Int, imageLoader: ImageLoader? = null) {
    Box(
        Modifier.size(size.dp)
            .clip(CircleShape)
            .background(LocalVelnixAccent.current)
            .border(2.dp, Color.White.copy(alpha = 0.82f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("V", color = White, fontSize = (size * 0.56f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SplashScreen() {
    val transition = rememberInfiniteTransition(label = "velnix-splash")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100), repeatMode = RepeatMode.Reverse),
        label = "logo-pulse"
    )
    Box(
        Modifier.fillMaxSize().background(AppBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size((112 * pulse).dp)
                    .clip(CircleShape)
                    .background(LocalVelnixAccent.current),
                contentAlignment = Alignment.Center
            ) {
                Text("V", color = White, fontSize = 64.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(22.dp))
            Text("VELNIX", color = White, fontSize = 43.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
            Spacer(Modifier.height(8.dp))
            Text("كل الترفيه، بمكان واحد", color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(22.dp))
            Box(Modifier.width(150.dp).height(3.dp).clip(CircleShape).background(LocalVelnixAccent.current))
            Spacer(Modifier.height(12.dp))
            Text("LIVE TV  ·  MOVIES  ·  SPORTS", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun WelcomeAuthScreen(
    pairing: DevicePairingInfo?,
    pairingError: String,
    pairingBusy: Boolean,
    onPair: () -> Unit,
    onCancelPair: () -> Unit,
    email: String,
    onEmail: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    signup: Boolean,
    onSwitch: () -> Unit,
    busy: Boolean,
    error: String,
    onSubmit: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(AppBg), contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 1120.dp).padding(34.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BrandMark(88)
                Text("أهلاً بك في VELNIX", color = White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(
                    "منصة مشاهدة تجمع القنوات المباشرة والرياضة والأفلام في تجربة واحدة.",
                    color = Muted,
                    fontSize = 17.sp,
                    lineHeight = 27.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FeaturePill("قنوات مباشرة")
                    FeaturePill("جودة متكيّفة")
                    FeaturePill("تحكم بالريموت")
                }
            }
            Column(
                Modifier.weight(1f)
                    .border(1.dp, Line, RoundedCornerShape(24.dp))
                    .background(Panel, RoundedCornerShape(24.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Text(if (signup) "إنشاء حساب" else "تسجيل الدخول", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (signup) "أنشئ حساب VELNIX للبدء بالمشاهدة." else "سجّل الدخول للمتابعة إلى القنوات.",
                    color = Muted,
                    fontSize = 13.sp
                )
                if (pairing == null) {
                    OutlinedButton(
                        onClick = onPair,
                        enabled = !pairingBusy,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, LocalVelnixAccent.current.copy(alpha = 0.65f))
                    ) {
                        if (pairingBusy) CircularProgressIndicator(Modifier.size(17.dp), color = LocalVelnixAccent.current, strokeWidth = 2.dp)
                        else Text("ربط التلفزيون عبر QR أو رمز يدوي", color = White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(14.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(14.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PairingQr(pairing.pairUrl)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("امسح QR أو أدخل الرمز يدوياً", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("افتح الرابط على الهاتف ثم وافق على ربط التلفزيون بحسابك.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                            Text(pairing.code.chunked(4).joinToString("-"), color = LocalVelnixAccent.current, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("الرمز صالح لمدة 3 دقائق", color = Muted, fontSize = 9.sp)
                            TextButton(onClick = onCancelPair, contentPadding = PaddingValues(0.dp)) {
                                Text("إلغاء الرمز", color = Color(0xFFFFA0A0), fontSize = 10.sp)
                            }
                        }
                    }
                }
                if (pairingError.isNotBlank()) {
                    Text(pairingError, color = Color(0xFFFF9A9A), fontSize = 11.sp, lineHeight = 16.sp)
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = onEmail,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("البريد الإلكتروني") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPassword,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("كلمة المرور") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp)
                )
                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFFF8A8A), fontSize = 12.sp, lineHeight = 19.sp)
                }
                Button(
                    onClick = onSubmit,
                    enabled = !busy && email.contains("@") && password.length >= 6,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LocalVelnixAccent.current, contentColor = White)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = White, strokeWidth = 2.dp)
                    else Text(if (signup) "إنشاء الحساب والمتابعة" else "دخول إلى VELNIX", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onSwitch, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (signup) "عندي حساب بالفعل" else "مستخدم جديد؟ إنشاء حساب", color = White)
                }
                Text("بمتابعة الدخول، أنت تستخدم حساب VELNIX نفسه الموجود على الموقع.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun PairingQr(pairUrl: String) {
    val bitmap = remember(pairUrl) {
        try {
            val matrix = QRCodeWriter().encode(
                pairUrl,
                BarcodeFormat.QR_CODE,
                360,
                360,
                mapOf(EncodeHintType.MARGIN to 1)
            )
            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    pixels[y * matrix.width + x] =
                        if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "رمز QR لربط تلفزيون VELNIX",
            modifier = Modifier.size(132.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(5.dp)
        )
    } else {
        Box(Modifier.size(132.dp).background(Color.White, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text("QR", color = Color.Black, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun FeaturePill(label: String) {
    Box(
        Modifier.background(PanelRaised, RoundedCornerShape(50))
            .border(1.dp, Line, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HomeScreen(
    session: UserSession,
    profile: ProfileInfo,
    channels: List<Channel>,
    hasMoreChannels: Boolean,
    loadingMoreChannels: Boolean,
    onLoadMore: () -> Unit,
    category: String,
    onCategory: (String) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    favorites: Set<String>,
    recent: List<String>,
    beinAccess: Boolean,
    status: String,
    busy: Boolean,
    busyChannel: String?,
    imageLoader: ImageLoader,
    showLogos: Boolean,
    onProfile: () -> Unit,
    onSettings: () -> Unit,
    onFamily: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onFavorite: (Channel) -> Unit,
    onPlay: (Channel) -> Unit,
    error: String
) {
    val config = LocalConfiguration.current
    val tvLayout = config.screenWidthDp >= 700
    var visibleChannels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var menuExpanded by remember { mutableStateOf(true) }
    val sidebarWidth by animateDpAsState(
        targetValue = if (menuExpanded) 218.dp else 76.dp,
        animationSpec = tween(durationMillis = 230),
        label = "sidebar-width"
    )
    val gridState = rememberLazyGridState()

    LaunchedEffect(channels, category, query, favorites, recent, beinAccess) {
        if (query.isNotBlank()) delay(140)
        visibleChannels = withContext(Dispatchers.Default) {
            channels.asSequence().filter { channel ->
                val categoryMatch = category == "all" || channel.category == category ||
                    (category == "favorites" && channel.id in favorites) ||
                    (category == "recent" && channel.id in recent)
                val accessMatch = channel.category != "bein" || beinAccess
                categoryMatch && accessMatch &&
                    (query.isBlank() || channel.name.contains(query, true) || channel.english.contains(query, true))
            }.sortedBy { it.name.lowercase() }.toList()
        }
    }

    val menuItems = CATEGORIES + listOf(
        Category("favorites", "المفضلة"),
        Category("recent", "شوهدت مؤخراً")
    )
    fun menuSymbol(id: String): String = when (id) {
        "all" -> "▦"
        "sports", "bein" -> "⚽"
        "arabic" -> "🌐"
        "movies" -> "▣"
        "news" -> "◉"
        "kids" -> "☺"
        "documentaries" -> "▤"
        "music" -> "♫"
        "variety", "world" -> "✦"
        "favorites" -> "♡"
        "recent" -> "◷"
        else -> "•"
    }

    Row(
        Modifier.fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFF0A1020), Color(0xFF10152B), Color(0xFF080B14))
                )
            )
    ) {
        Column(
            Modifier.width(sidebarWidth)
                .fillMaxHeight()
                .padding(start = 10.dp, top = 12.dp, bottom = 12.dp, end = 7.dp)
                .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(24.dp))
                .background(Color(0xCC151D32), RoundedCornerShape(24.dp))
                .animateContentSize()
                .pointerInput(menuExpanded) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag > 35f) menuExpanded = true
                            if (drag < -35f) menuExpanded = false
                            drag = 0f
                        },
                        onHorizontalDrag = { _, amount -> drag += amount }
                    )
                }
                .padding(horizontal = if (menuExpanded) 11.dp else 7.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (menuExpanded) Arrangement.SpaceBetween else Arrangement.Center
            ) {
                if (menuExpanded) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrandMark(34)
                        Text("VELNIX", color = White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                    }
                } else {
                    BrandMark(35)
                }
                TextButton(
                    onClick = { menuExpanded = !menuExpanded },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)
                ) {
                    Text(if (menuExpanded) "‹" else "›", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.055f))
                    .clickable(onClick = onProfile)
                    .focusable()
                    .padding(vertical = 9.dp, horizontal = if (menuExpanded) 8.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (menuExpanded) Arrangement.spacedBy(9.dp) else Arrangement.Center
            ) {
                AsyncImage(
                    model = avatarUrl(profile.avatarId),
                    imageLoader = imageLoader,
                    contentDescription = "الملف الشخصي",
                    modifier = Modifier.size(37.dp).clip(CircleShape)
                        .border(2.dp, LocalVelnixAccent.current.copy(alpha = 0.8f), CircleShape),
                    contentScale = ContentScale.Crop
                )
                if (menuExpanded) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(profile.displayName, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("ملفي الشخصي", color = Muted, fontSize = 9.sp)
                    }
                    Text("›", color = Muted, fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(13.dp))
            if (menuExpanded) {
                Text(
                    "القائمة الرئيسية",
                    color = Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(start = 7.dp, bottom = 7.dp)
                )
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(menuItems, key = { it.id }) { item ->
                    val active = category == item.id
                    Row(
                        Modifier.fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (active) LocalVelnixAccent.current.copy(alpha = 0.88f)
                                else Color.White.copy(alpha = 0.025f)
                            )
                            .border(
                                if (active) 1.dp else 0.5.dp,
                                if (active) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.045f),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onCategory(item.id) }
                            .focusable()
                            .padding(horizontal = if (menuExpanded) 11.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (menuExpanded) Arrangement.spacedBy(12.dp) else Arrangement.Center
                    ) {
                        Text(menuSymbol(item.id), color = if (active) White else Color(0xFFD5DCEC), fontSize = 19.sp)
                        if (menuExpanded) {
                            Text(
                                item.title,
                                color = if (active) White else Color(0xFFD5DCEC),
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.10f), modifier = Modifier.padding(vertical = 9.dp))
            MenuFooterAction("◎", "ملفات العائلة والحماية", menuExpanded, onFamily)
            MenuFooterAction("⚙", "الإعدادات", menuExpanded, onSettings)
            MenuFooterAction("⟳", "تحديث القنوات", menuExpanded, onRefresh)
            MenuFooterAction("⇥", "تسجيل الخروج", menuExpanded, onSignOut)
            if (menuExpanded) {
                Text("TV  ·  2.5", color = Muted.copy(alpha = 0.65f), fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 18.dp, end = 22.dp, top = 17.dp, bottom = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("مرحباً، " + profile.displayName, color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("كل الترفيه، بمكان واحد", color = Muted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.075f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .padding(horizontal = 13.dp, vertical = 8.dp)
                    ) {
                        Text(if (beinAccess) "الحساب الرياضي مفعّل" else "حساب VELNIX", color = Color(0xFFD9E2F5), fontSize = 10.sp)
                    }
                    Text(status, color = Muted, fontSize = 10.sp, maxLines = 1)
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
                placeholder = { Text("ابحث عن قناة بالاسم…", color = Muted) },
                singleLine = true,
                shape = RoundedCornerShape(15.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryCard("القنوات", channels.size.toString(), Modifier.weight(1f))
                SummaryCard("المفضلة", favorites.size.toString(), Modifier.weight(1f))
                SummaryCard("آخر مشاهدة", recent.size.toString(), Modifier.weight(1f))
            }
            when {
                busy && channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LocalVelnixAccent.current)
                }
                category == "bein" && !beinAccess -> EmptyState("قسم beIN SPORTS يحتاج إلى تفعيل على الحساب.")
                visibleChannels.isEmpty() -> EmptyState(if (query.isBlank()) "ماكو قنوات ضمن هذا القسم حالياً." else "ما لقينا قناة تطابق البحث.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (tvLayout) 245.dp else 210.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = visibleChannels,
                        key = { it.id },
                        contentType = { "channel" }
                    ) { channel ->
                        ChannelTile(
                            channel = channel,
                            favorite = channel.id in favorites,
                            imageLoader = imageLoader,
                            showLogo = showLogos,
                            loading = busyChannel == channel.id,
                            onFavorite = { onFavorite(channel) },
                            onClick = { onPlay(channel) }
                        )
                    }
                    if (hasMoreChannels) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Button(
                                onClick = onLoadMore,
                                enabled = !loadingMoreChannels,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = LocalVelnixAccent.current, contentColor = White)
                            ) {
                                if (loadingMoreChannels) CircularProgressIndicator(Modifier.size(18.dp), color = White, strokeWidth = 2.dp)
                                else Text("تحميل المزيد من القنوات")
                            }
                        }
                    }
                }
            }
            if (error.isNotBlank()) {
                Text(error, color = Color(0xFFFF8A8A), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun MenuFooterAction(symbol: String, title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .clickable(onClick = onClick).focusable()
            .padding(horizontal = if (expanded) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.spacedBy(12.dp) else Arrangement.Center
    ) {
        Text(symbol, color = Color(0xFFD5DCEC), fontSize = 18.sp)
        if (expanded) Text(title, color = Color(0xFFD5DCEC), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Panel, RoundedCornerShape(14.dp))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(value, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandMark(44)
            Text(message, color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun CategoryButton(category: Category, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 43.dp),
        shape = RoundedCornerShape(11.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) LocalVelnixAccent.current else PanelRaised,
            contentColor = if (active) White else Muted
        )
    ) {
        Text(category.title, modifier = Modifier.fillMaxWidth(), fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun ChannelTile(
    channel: Channel,
    favorite: Boolean,
    imageLoader: ImageLoader,
    showLogo: Boolean,
    loading: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember(channel.id) { mutableStateOf(false) }
    var imageFailed by remember(channel.id, channel.logo) { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 100.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                if (focused) BorderStroke(2.dp, LocalVelnixAccent.current) else BorderStroke(1.dp, Line),
                RoundedCornerShape(16.dp)
            )
            .background(Panel, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(66.dp).clip(CircleShape)
                .background(PanelRaised)
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (showLogo && !channel.logo.isNullOrBlank() && !imageFailed && focused) {
                AsyncImage(
                    model = channel.logo,
                    imageLoader = imageLoader,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize().padding(7.dp).clip(CircleShape),
                    contentScale = ContentScale.Fit,
                    onError = { imageFailed = true }
                )
            } else {
                Text(channel.name.take(2).uppercase(), color = White, fontSize = 19.sp, fontWeight = FontWeight.Black)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(channel.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(channel.quality.ifBlank { channel.categoryLabel }, color = Muted, fontSize = 10.sp, maxLines = 1)
            Text("مشاهدة مباشرة", color = LocalVelnixAccent.current, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = onFavorite) {
                Text(if (favorite) "★" else "☆", color = if (favorite) LocalVelnixAccent.current else Muted, fontSize = 23.sp)
            }
            if (loading) CircularProgressIndicator(Modifier.size(15.dp), color = LocalVelnixAccent.current, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun FamilyManagementScreen(
    api: VelnixApi,
    userId: String,
    channels: List<Channel>,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<TvProfile>>(emptyList()) }
    var matches by remember { mutableStateOf<List<TvMatch>>(emptyList()) }
    var selectedProfile by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var kids by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("جارٍ تحميل بيانات العائلة…") }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("profiles") }
    LaunchedEffect(userId) {
        try {
            profiles = api.tvProfiles(userId)
            matches = api.upcomingMatches()
            selectedProfile = profiles.firstOrNull()?.id
            message = ""
        } catch (e: Exception) { message = e.message ?: "تعذر تحميل البيانات" }
    }
    LaunchedEffect(selectedProfile) {
        val id = selectedProfile ?: return@LaunchedEffect
        try { locked = api.loadLockedChannels(userId, id) } catch (_: Exception) { locked = emptySet() }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(AppBg).padding(horizontal = 30.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("العائلة والحماية", color = White, fontSize = 26.sp, fontWeight = FontWeight.Black); Text("ملفات مستقلة، رقابة أبوية، ومواعيد المباريات", color = Muted, fontSize = 11.sp) }
            TextButton(onClick = onBack) { Text("رجوع", color = White) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("profiles" to "الملفات الشخصية", "locks" to "قفل القنوات", "sports" to "المباريات").forEach { (id,label) ->
                FilterChip(selected = tab==id, onClick={tab=id}, label={Text(label)}, colors=FilterChipDefaults.filterChipColors(selectedContainerColor=LocalVelnixAccent.current,selectedLabelColor=White))
            }
        }
        if (message.isNotBlank()) Text(message, color=LocalVelnixAccent.current, fontSize=11.sp)
        when(tab) {
            "profiles" -> {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().background(Panel,RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        Text("ملفات العائلة",color=White,fontSize=16.sp,fontWeight=FontWeight.Bold)
                        if(profiles.isEmpty()) Text("ماكو ملفات إضافية. أنشئ أول ملف.",color=Muted)
                        profiles.forEach { p ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if(selectedProfile==p.id) LocalVelnixAccent.current.copy(alpha=.17f) else PanelRaised).clickable { selectedProfile=p.id }.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                                Column { Text(p.name,color=White,fontWeight=FontWeight.Bold); Text(if(p.isKids) "ملف أطفال" else "ملف عادي",color=Muted,fontSize=10.sp) }
                                TextButton(onClick={scope.launch { try { api.removeTvProfile(userId,p.id); profiles=api.tvProfiles(userId); selectedProfile=profiles.firstOrNull()?.id } catch(e:Exception){message=e.message ?: "تعذر الحذف"} }}) { Text("حذف",color=Color(0xFFFF9292)) }
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().background(Panel,RoundedCornerShape(16.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        Text("إضافة ملف شخصي",color=White,fontSize=16.sp,fontWeight=FontWeight.Bold)
                        OutlinedTextField(value=name,onValueChange={name=it.take(32)},label={Text("اسم الملف")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        Text("الافتار",color=White,fontSize=12.sp)
                        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) { (1..8).forEach { n -> FilterChip(selected=false,onClick={kids=kids},label={Text("A$n")}) } }
                        OutlinedTextField(value=pin,onValueChange={pin=it.filter(Char::isDigit).take(6)},label={Text("PIN اختياري (4–6 أرقام)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(checked=kids,onCheckedChange={kids=it}); Text("ملف أطفال مع تقييد المحتوى",color=White,fontSize=12.sp) }
                        Button(onClick={scope.launch { busy=true; try { api.createTvProfile(userId,name,"anime1",kids,pin); profiles=api.tvProfiles(userId); name="";pin="";message="تم إنشاء الملف الشخصي." } catch(e:Exception){message=e.message ?: "تعذر إنشاء الملف"} finally{busy=false} }},enabled=!busy && name.isNotBlank()) { Text(if(busy) "جارٍ الحفظ…" else "إنشاء الملف") }
                        Text("كل ملف له سجل مفضلة ومشاهدة مستقل في قاعدة البيانات. ربط واجهة التبديل النشط ما زال مطلوباً قبل اعتبارها مكتملة.",color=Muted,fontSize=10.sp)
                    }
                }
            }
            "locks" -> {
                Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().background(Panel,RoundedCornerShape(16.dp)).padding(12.dp)) {
                        Text("اختَر الملف",color=White,fontWeight=FontWeight.Bold)
                        profiles.forEach { p -> FilterChip(selected=selectedProfile==p.id,onClick={selectedProfile=p.id},label={Text(p.name)}) }
                    }
                    LazyColumn(Modifier.weight(2f).fillMaxHeight().background(Panel,RoundedCornerShape(16.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        item { Text("قفل القنوات",color=White,fontWeight=FontWeight.Bold) }
                        items(channels.take(300),key={it.id}) { c ->
                            Row(Modifier.fillMaxWidth().clickable { val id=selectedProfile ?: return@clickable; val next=c.id !in locked; locked=if(next) locked+c.id else locked-c.id; scope.launch { try { api.setChannelLocked(userId,id,c.id,next) } catch(e:Exception){message=e.message ?: "تعذر حفظ القفل"} } }.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(c.name,color=White,fontSize=11.sp,maxLines=1)
                                Checkbox(checked=c.id in locked,onCheckedChange={v-> val id=selectedProfile ?: return@Checkbox; locked=if(v) locked+c.id else locked-c.id; scope.launch { try { api.setChannelLocked(userId,id,c.id,v) } catch(e:Exception){message=e.message ?: "تعذر حفظ القفل"} } })
                            }
                        }
                    }
                }
            }
            "sports" -> {
                Column(Modifier.fillMaxWidth().weight(1f).background(Panel,RoundedCornerShape(16.dp)).padding(14.dp)) {
                    Text("المباريات القادمة",color=White,fontSize=17.sp,fontWeight=FontWeight.Bold)
                    if(matches.isEmpty()) Text("لا توجد مباريات مجدولة في قاعدة البيانات حالياً. يلزم تزويد جدول المباريات بمصدر بيانات قبل تشغيل التنبيهات.",color=Muted,fontSize=12.sp)
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(matches,key={it.id}) { match ->
                            Row(Modifier.fillMaxWidth().background(PanelRaised,RoundedCornerShape(10.dp)).padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(match.home+"  ×  "+match.away,color=White,fontWeight=FontWeight.Bold); Text(match.competition+" · "+match.startsAt,color=Muted,fontSize=10.sp) }
                                TextButton(onClick={scope.launch { try { api.addMatchReminder(userId,selectedProfile,match.id); message="تم حفظ تذكير المباراة." } catch(e:Exception){message=e.message ?: "تعذر حفظ التذكير"} }}) { Text("ذكّرني",color=LocalVelnixAccent.current) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    accentArgb: Int,
    onAccentArgb: (Int) -> Unit,
    autoReconnect: Boolean,
    onAutoReconnect: (Boolean) -> Unit,
    dataSaver: Boolean,
    onDataSaver: (Boolean) -> Unit,
    showLogos: Boolean,
    onShowLogos: (Boolean) -> Unit,
    lowRamDevice: Boolean,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val memory = remember {
        context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    }
    val storage = remember {
        StatFs(context.filesDir.absolutePath)
    }
    val freeMb = storage.availableBytes / (1024L * 1024L)
    val totalMb = storage.totalBytes / (1024L * 1024L)

    Column(
        Modifier.fillMaxSize().background(AppBg).padding(horizontal = 34.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("إعدادات VELNIX", color = White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                Text("تحكم بالتشغيل والأداء ومساحة التخزين", color = Muted, fontSize = 12.sp)
            }
            TextButton(onClick = onBack) { Text("رجوع", color = White) }
        }



        Column(
            Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("لون واجهة VELNIX", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("اختَر لون التحديد والإضاءة للقوائم والبطاقات.", color = Muted, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    0xFFE50914.toInt(), 0xFF20B8E8.toInt(), 0xFF8B5CF6.toInt(),
                    0xFF16A878.toInt(), 0xFFE49B32.toInt(), 0xFFE45D9B.toInt(), 0xFF6485FF.toInt()
                ).forEach { colorValue ->
                    val selectedColor = accentArgb == colorValue
                    Box(
                        Modifier.size(if (selectedColor) 34.dp else 29.dp).clip(CircleShape)
                            .background(Color(colorValue))
                            .border(if (selectedColor) 3.dp else 1.dp, if (selectedColor) White else Color.White.copy(alpha = 0.35f), CircleShape)
                            .clickable { onAccentArgb(colorValue) }
                            .focusable()
                    )
                }
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().background(Panel, RoundedCornerShape(18.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("التشغيل والأداء", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                SettingSwitch(
                    title = "إعادة الاتصال تلقائياً",
                    description = "يحاول استرجاع البث عند انقطاعه",
                    checked = autoReconnect,
                    onCheckedChange = onAutoReconnect
                )
                HorizontalDivider(color = Line)
                SettingSwitch(
                    title = "توفير البيانات",
                    description = "يحدّ جودة الفيديو القصوى إلى SD لتقليل استهلاك الشبكة والموارد",
                    checked = dataSaver,
                    onCheckedChange = onDataSaver
                )
                HorizontalDivider(color = Line)
                SettingSwitch(
                    title = "إظهار شعارات القنوات",
                    description = "عطّلها إذا كان جهاز التلفاز ضعيفاً أو بطيئاً",
                    checked = showLogos,
                    onCheckedChange = onShowLogos
                )
                Spacer(Modifier.height(8.dp))
                Text("إدارة البيانات", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Button(
                    onClick = onClearCache,
                    colors = ButtonDefaults.buttonColors(containerColor = LocalVelnixAccent.current, contentColor = White),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("مسح ذاكرة صور القنوات") }
                OutlinedButton(onClick = onClearHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("مسح سجل المشاهدة", color = White)
                }
            }

            Column(
                Modifier.weight(1f).fillMaxHeight().background(Panel, RoundedCornerShape(18.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("تشخيص الجهاز", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                DiagnosticRow("الجهاز", Build.MANUFACTURER + " " + Build.MODEL)
                DiagnosticRow("إصدار Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                DiagnosticRow("ذاكرة التطبيق", memory.memoryClass.toString() + " MB")
                DiagnosticRow("تصنيف الجهاز", if (lowRamDevice) "ذاكرة محدودة" else "ذاكرة عادية")
                DiagnosticRow("التخزين المتاح", freeMb.toString() + " MB")
                DiagnosticRow("سعة قسم البيانات", totalMb.toString() + " MB")
                DiagnosticRow("نسخة VELNIX", "2.5.0")
                Spacer(Modifier.height(8.dp))
                Text(
                    "ملاحظة: توفير البيانات يحدد جودة الفيديو إلى SD، وقد لا يغيّر الجودة إذا كان مصدر البث لا يوفّر أكثر من مسار واحد.",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(description, color = Muted, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun ProfileScreen(
    current: ProfileInfo,
    imageLoader: ImageLoader,
    saving: Boolean,
    saveError: String,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var displayName by remember(current.displayName) { mutableStateOf(current.displayName) }
    var avatar by remember(current.avatarId) { mutableStateOf(current.avatarId) }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(AppBg).padding(horizontal = 36.dp, vertical = 22.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark(42)
                Column {
                    Text("ملفي الشخصي", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("إدارة الاسم والصورة المرتبطين بحساب VELNIX", color = Muted, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onBack) { Text("رجوع", color = White) }
        }
        Spacer(Modifier.height(17.dp))
        Row(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AsyncImage(
                model = avatarUrl(avatar),
                imageLoader = imageLoader,
                contentDescription = "الصورة المختارة",
                modifier = Modifier.size(78.dp).clip(CircleShape).border(3.dp, LocalVelnixAccent.current, CircleShape),
                contentScale = ContentScale.Crop
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(displayName.ifBlank { "مستخدم VELNIX" }, color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("الصورة الشخصية تظهر بجانب اسم حسابك", color = Muted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it.take(40) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("اسم العرض") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(13.dp))
        Text("اختَر صورة من مكتبة VELNIX", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(9.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            items(AvatarOptions, key = { it.id }) { item ->
                val active = avatar == item.id
                Column(
                    Modifier.fillMaxWidth()
                        .border(if (active) 2.dp else 1.dp, if (active) LocalVelnixAccent.current else Line, RoundedCornerShape(15.dp))
                        .background(if (active) LocalVelnixAccent.current.copy(alpha = 0.12f) else Panel, RoundedCornerShape(15.dp))
                        .clickable { avatar = item.id }
                        .focusable()
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    AsyncImage(
                        model = avatarUrl(item.id),
                        imageLoader = imageLoader,
                        contentDescription = item.label,
                        modifier = Modifier.size(62.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Text(item.label, color = if (active) White else Muted, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        if (saveError.isNotBlank()) {
            Text(saveError, color = Color(0xFFFF8A8A), fontSize = 12.sp)
        }
        Button(
            onClick = { onSave(displayName.trim(), avatar) },
            enabled = !saving && displayName.trim().length >= 2,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LocalVelnixAccent.current, contentColor = White)
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(19.dp), color = White, strokeWidth = 2.dp)
            else Text("حفظ التغييرات", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NativePlayer(
    api: VelnixApi,
    channel: Channel,
    url: String,
    streamType: String,
    imageLoader: ImageLoader,
    lowRamDevice: Boolean,
    autoReconnect: Boolean,
    dataSaver: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epg by remember { mutableStateOf<List<EpgProgram>>(emptyList()) }
    var epgLoading by remember { mutableStateOf(false) }
    var epgVisible by remember { mutableStateOf(false) }
    var epgMessage by remember { mutableStateOf("") }
    var playbackStatus by remember(url, streamType) { mutableStateOf("جارٍ تجهيز البث…") }
    var playing by remember(url, streamType) { mutableStateOf(true) }
    var cropVideo by remember(url, streamType) { mutableStateOf(false) }
    val retryHandler = remember { Handler(Looper.getMainLooper()) }
    BackHandler(onBack = onBack)

    val player = remember(url, streamType) {
        val loadControl = DefaultLoadControl.Builder()
            // Keep a useful live buffer without allowing it to consume most of a TV box's RAM.
            .setBufferDurationsMs(12_000, 30_000, 2_000, 5_000)
            .setTargetBufferBytes(if (lowRamDevice) 16 * 1024 * 1024 else 32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val trackSelector = DefaultTrackSelector(context).apply {
            if (dataSaver) {
                parameters = buildUponParameters().setMaxVideoSizeSd().build()
            }
        }
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val mediaItem = MediaItem.Builder().setUri(url).apply {
            when {
                streamType.contains("dash", true) || url.contains(".mpd", true) -> setMimeType(MimeTypes.APPLICATION_MPD)
                streamType.contains("hls", true) || streamType.contains("m3u8", true) || url.contains(".m3u8", true) -> setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }.build()

        ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                val exo = this
                var retryCount = 0
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (!autoReconnect) {
                            playbackStatus = "توقف البث. اضغط إعادة الاتصال للمحاولة."
                            return
                        }
                        if (retryCount >= 5) {
                            playbackStatus = "تعذر استمرار البث. افحص الاتصال ثم اضغط إعادة الاتصال."
                            return
                        }
                        playbackStatus = "انقطع الاتصال، جارٍ استرجاع البث…"
                        val delayMs = minOf(2_000L * (1L shl retryCount.coerceAtMost(3)), 15_000L)
                        retryCount++
                        retryHandler.postDelayed({
                            exo.seekToDefaultPosition()
                            exo.prepare()
                            exo.playWhenReady = true
                        }, delayMs)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> playbackStatus = "جارٍ تحميل البث…"
                            Player.STATE_READY -> {
                                retryCount = 0
                                playbackStatus = ""
                            }
                            Player.STATE_ENDED -> playbackStatus = "انتهى مصدر البث."
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                    }
                })
                setMediaItem(mediaItem)
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) {
        onDispose {
            retryHandler.removeCallbacksAndMessages(null)
            player.release()
        }
    }

    Column(Modifier.fillMaxSize().background(AppBg)) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark(42)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(channel.name, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("بث مباشر  ·  VELNIX PLAYER", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (playbackStatus.isBlank()) Color(0xFF42D392) else LocalVelnixAccent.current))
                TextButton(onClick = onBack) { Text("إغلاق المشغّل", color = White) }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    (ctx.getSystemService(android.content.Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater)
                        .inflate(R.layout.velnix_player_view, null) as PlayerView
                },
                update = { view ->
                    view.player = player
                    view.keepScreenOn = true
                    view.isFocusable = true
                    view.isFocusableInTouchMode = true
                    view.controllerShowTimeoutMs = 6000
                    view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    view.useController = true
                },
                modifier = Modifier.fillMaxSize()
            )
            if (playbackStatus.isNotBlank()) {
                Text(
                    playbackStatus,
                    color = White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center)
                        .background(Panel.copy(alpha = 0.96f), RoundedCornerShape(13.dp))
                        .border(1.dp, Line, RoundedCornerShape(13.dp))
                        .padding(horizontal = 22.dp, vertical = 15.dp)
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerAction(if (playing) "إيقاف مؤقت" else "استمرار", true) {
                if (player.isPlaying) player.pause() else player.play()
            }
            PlayerAction("دليل البرامج", epgVisible) {
                epgVisible = !epgVisible
                if (epgVisible && epg.isEmpty() && !epgLoading) {
                    scope.launch {
                        epgLoading = true
                        try {
                            epg = api.epg(channel)
                            epgMessage = if (epg.isEmpty()) "لا توجد بيانات برامج متاحة لهذه القناة حالياً." else ""
                        } catch (e: Exception) {
                            epgMessage = e.message ?: "تعذر تحميل دليل البرامج"
                        } finally {
                            epgLoading = false
                        }
                    }
                }
            }
            PlayerAction("جودة الصورة", false) {
                TrackSelectionDialogBuilder(context, "جودة الصورة", player, C.TRACK_TYPE_VIDEO).build().show()
            }
            PlayerAction("الصوت", false) {
                TrackSelectionDialogBuilder(context, "مسار الصوت", player, C.TRACK_TYPE_AUDIO).build().show()
            }
            PlayerAction("الترجمة", false) {
                TrackSelectionDialogBuilder(context, "الترجمة", player, C.TRACK_TYPE_TEXT).build().show()
            }
            PlayerAction(if (cropVideo) "ملاءمة الشاشة" else "ملء الشاشة", false) {
                cropVideo = !cropVideo
                player.videoScalingMode = if (cropVideo) C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING else C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
            PlayerAction("إعادة الاتصال", false) {
                playbackStatus = "جارٍ إعادة الاتصال…"
                retryHandler.removeCallbacksAndMessages(null)
                player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
            }
        }

        if (epgVisible) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 205.dp)
                    .background(Panel).verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("دليل البرامج · EPG", color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (epgLoading) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = LocalVelnixAccent.current, strokeWidth = 2.dp)
                } else if (epg.isEmpty()) {
                    Text(epgMessage.ifBlank { "لا توجد برامج مدرجة حالياً." }, color = Muted, fontSize = 11.sp)
                } else {
                    epg.take(10).forEach { item ->
                        val active = System.currentTimeMillis() in item.startsAt until item.endsAt
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (active) LocalVelnixAccent.current.copy(alpha = 0.13f) else PanelRaised, RoundedCornerShape(10.dp))
                                .border(1.dp, if (active) LocalVelnixAccent.current.copy(alpha = 0.55f) else Line, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.startsAt)) +
                                    " – " +
                                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.endsAt)),
                                color = LocalVelnixAccent.current,
                                fontSize = 10.sp
                            )
                            Text(item.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            if (item.description.isNotBlank()) Text(item.description, color = Muted, fontSize = 10.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerAction(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = if (active) LocalVelnixAccent.current else White),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}

private data class AvatarOption(val id: String, val label: String, val file: String)

data class Category(val id: String, val title: String)

val CATEGORIES = listOf(
    Category("all", "كل القنوات"),
    Category("sports", "الرياضة"),
    Category("bein", "beIN SPORTS"),
    Category("arabic", "القنوات العربية"),
    Category("movies", "أفلام ومسلسلات"),
    Category("news", "الأخبار"),
    Category("kids", "الأطفال"),
    Category("documentaries", "وثائقيات"),
    Category("music", "موسيقى وراديو"),
    Category("variety", "منوعات"),
    Category("world", "قنوات عالمية")
)

data class Channel(
    val id: String,
    val name: String,
    val english: String,
    val category: String,
    val categoryLabel: String,
    val logo: String?,
    val provider: String?,
    val quality: String = "",
    val streamIds: List<String> = listOf(id),
    val externalId: String? = null,
    val streamType: String = "hls"
)

data class ProfileInfo(val displayName: String, val avatarId: String)

fun categoryFor(group: String, name: String): String {
    val both = (group + " " + name).lowercase()
    return when {
        Regex("bein[\\s._-]*sp.{0,3}rts|بي\\s*ان\\s*سبورت|بين\\s*سبورت").containsMatchIn(both) -> "bein"
        Regex("sport|deport|dazn|espn|eurosport|football|soccer|nba|tennis|ufc|boxing|champions league|premier league|sport event").containsMatchIn(both) -> "sports"
        Regex("news|info|actualité|actualite|inform|اخبار").containsMatchIn(group.lowercase()) || Regex("news|al jazeera|alarabiya|al hadath|sky news|france info|cnn|bbc news|fox news|msnbc").containsMatchIn(name.lowercase()) -> "news"
        Regex("kids|junior|cartoon|infantil|kinder|crianc|enfant").containsMatchIn(group.lowercase()) || Regex("kids|cartoon|nick|disney junior|baby tv|boomerang|gulli|spacetoon").containsMatchIn(name.lowercase()) -> "kids"
        Regex("doc|document|nature|science|wissensch|natuur").containsMatchIn(group.lowercase()) || Regex("documentary|discovery|nat geo|national geographic|history channel|animal planet|bbc earth").containsMatchIn(name.lowercase()) -> "documentaries"
        Regex("cinema|movie|film|series|cine|prime video").containsMatchIn(group.lowercase()) || Regex("movies|cinema|ciné|film|series|hbo|cinemax|showtime movies|osn movies").containsMatchIn(name.lowercase()) -> "movies"
        Regex("radio|music|musique|musica|music").containsMatchIn(group.lowercase()) || Regex("radio|music|mtv|mezzo|trace urban|vh1|rotana music|موسيقى").containsMatchIn(name.lowercase()) -> "music"
        Regex("arabic|middle east|moyen orient|maghreb|maroc|quran|islamic|arab|mbc group|bein movies").containsMatchIn(group.lowercase()) || Regex("iraq|iraqia|mbc|osn|shahid|rotana|al arabiya|al jazeera|quran|alkass|abudhabi|abu dhabi|oman|kuwait|saudi|syria|lebanon|egypt|morocco|tunisia|jordan|palestine|arab").containsMatchIn(name.lowercase()) -> "arabic"
        Regex("general|network|tv|relax|generalista|generali").containsMatchIn(group.lowercase()) -> "world"
        else -> "variety"
    }
}
