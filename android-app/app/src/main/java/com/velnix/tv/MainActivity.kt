package com.velnix.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.app.PictureInPictureParams
import android.util.Rational
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val Navy = Color(0xFF080D16)
private val Panel = Color(0xFF111B29)
private val Gold = Color(0xFF39D5E8)
private val Muted = Color(0xFF9BA8B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8,13,22)
        window.navigationBarColor = android.graphics.Color.rgb(8,13,22)
        setContent { VelnixApp(this) }
    }
}

@Composable
private fun VelnixApp(activity: ComponentActivity) {
    val api = remember { VelnixApi(activity) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(api.session()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var codeDialog by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var codeBusy by remember { mutableStateOf(false) }
    var codeMessage by remember { mutableStateOf("") }
    var accountDialog by remember { mutableStateOf(false) }
    var accountDetails by remember { mutableStateOf<AccountInfo?>(null) }
    var accountName by remember { mutableStateOf("") }
    var accountMessage by remember { mutableStateOf("") }
    var accountBusy by remember { mutableStateOf(false) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var category by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(api.loadFavorites()) }
    var recent by remember { mutableStateOf(api.loadRecent()) }
    var streamType by remember { mutableStateOf("hls") }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var beinAccess by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("جارٍ الاتصال بدليل القنوات…") }
    var introVisible by remember { mutableStateOf(true) }
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= 700
    val tv = activity.packageManager.hasSystemFeature("android.software.leanback")

    fun refreshCatalog() {
        scope.launch {
            busy = true
            try {
                channels = api.channels()
                status = "تم تحميل ${channels.size} قناة فعالة"
                if (session != null) beinAccess = api.checkBein()
            } catch (e: Exception) { status = e.message ?: "تعذر تحميل القنوات" }
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        delay(2400)
        introVisible = false
        refreshCatalog()
    }
    LaunchedEffect(session?.userId) {
        if (session != null) {
            try {
                favorites = api.syncFavorites(favorites).also { api.saveFavorites(it) }
                recent = api.syncRecent(recent).also { api.saveRecent(it) }
            } catch (_: Exception) { /* Keep the encrypted local cache available offline. */ }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, background = Navy, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(Modifier.fillMaxSize(), color = Navy) {
            if (introVisible) {
                IntroScreen()
            } else if (selected != null && streamUrl != null) {
                NativePlayer(activity, api, selected!!, streamUrl!!, streamType, onBack = { selected = null; streamUrl = null })
            } else if (session == null) {
                AuthScreen(email, { email = it }, password, { password = it }, signup,
                    { signup = !signup; error = "" }, busy, error,
                    onForgot = {
                        if (email.isBlank()) error = "اكتب بريدك الإلكتروني أولاً"
                        else scope.launch {
                            busy = true
                            try {
                                api.resetPassword(email)
                                error = "إذا كان البريد مسجلاً، سيصلك رابط إعادة تعيين كلمة المرور."
                            } catch (e: Exception) { error = e.message ?: "تعذر إرسال رابط الاستعادة" }
                            finally { busy = false }
                        }
                    },
                    onSubmit = {
                        scope.launch {
                            busy = true; error = ""
                            try {
                                session = if (signup) api.signUp(email.trim(), password) else api.signIn(email.trim(), password)
                                beinAccess = api.checkBein()
                                refreshCatalog()
                            } catch (e: Exception) { error = e.message ?: "تعذر تسجيل الدخول" }
                            busy = false
                        }
                    })
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 22.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("VELNIX", color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                            Text("PREMIUM LIVE TV", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment=Alignment.End) {
                                Text(session?.userEmail ?: "", color = Muted, fontSize = 11.sp)
                                Text(if (beinAccess) "beIN SPORTS مفعّل" else "beIN SPORTS غير مفعّل", color = if(beinAccess) Gold else Muted, fontSize = 10.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = { codeDialog = true; codeMessage = "" }) { Text("تفعيل كود", color = Gold) }
                            TextButton(onClick = {
                                accountDialog = true; accountMessage = ""; accountBusy = true
                                scope.launch {
                                    try {
                                        accountDetails = api.accountInfo()
                                        accountName = accountDetails?.displayName.orEmpty()
                                    } catch (e: Exception) { accountMessage = e.message ?: "تعذر تحميل بيانات الحساب" }
                                    finally { accountBusy = false }
                                }
                            }) { Text("حسابي", color = Gold) }
                            TextButton(onClick = { refreshCatalog() }) { Text("تحديث", color = Gold) }
                            TextButton(onClick = { api.signOut(); session = null }) { Text("خروج") }
                        }
                    }
                    Row(Modifier.fillMaxSize()) {
                        if (wide || tv) {
                            LazyColumn(Modifier.width(if (tv) 230.dp else 205.dp).fillMaxHeight().background(Panel).padding(12.dp)) {
                                item { Text("الأقسام", color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp)) }
                                items(CATEGORIES) { item ->
                                    CategoryButton(item, category == item.id) { category = item.id }
                                }
                                item { Divider(color = Muted.copy(alpha=.2f), modifier = Modifier.padding(vertical=8.dp)) }
                                item { CategoryButton(Category("favorites","☆ المفضلة"), category=="favorites") { category="favorites" } }
                                item { CategoryButton(Category("recent","◷ شوهدت مؤخراً"), category=="recent") { category="recent" } }
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal=if(wide) 20.dp else 12.dp)) {
                            if (!wide && !tv) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("الأقسام", color = Gold, fontWeight = FontWeight.Bold)
                                    TextButton(onClick = { category = "favorites" }) { Text("☆ المفضلة") }
                                    TextButton(onClick = { category = "recent" }) { Text("◷ الأخيرة") }
                                }
                                Row(Modifier.fillMaxWidth().heightIn(max=54.dp)) {
                                    LazyRowCategories(category) { category = it }
                                }
                            }
                            OutlinedTextField(
                                value = query, onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth().padding(vertical=10.dp),
                                placeholder = { Text("ابحث عن قناة…", color = Muted) },
                                singleLine = true, shape = RoundedCornerShape(14.dp)
                            )
                            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatCard("القنوات", channels.size.toString(), Modifier.weight(1f))
                                StatCard("المفضلة", favorites.size.toString(), Modifier.weight(1f))
                                StatCard("آخر مشاهدة", recent.size.toString(), Modifier.weight(1f))
                            }
                            Text(status, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom=8.dp))
                            val grouped = remember(channels, category, query, favorites, recent, beinAccess) {
                                channels.filter { c ->
                                    val catOk = category == "all" || c.category == category ||
                                        (category == "favorites" && c.id in favorites) ||
                                        (category == "recent" && c.id in recent)
                                    val gateOk = c.category != "bein" || beinAccess
                                    catOk && gateOk && (query.isBlank() || c.name.contains(query, true) || c.english.contains(query, true))
                                }.sortedBy { it.name.lowercase() }
                            }
                            if (category == "bein" && !beinAccess) {
                                Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
                                    Text("قسم beIN SPORTS مقفول. يلزم كود تفعيل مرتبط بحسابك.", color=Gold)
                                }
                            } else if (busy && channels.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) { CircularProgressIndicator(color=Gold) }
                            } else if (wide || tv) {
                                LazyVerticalGrid(columns=GridCells.Adaptive(if(tv) 260.dp else 220.dp), contentPadding=PaddingValues(bottom=24.dp), horizontalArrangement=Arrangement.spacedBy(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    items(grouped) { c ->
                                        ChannelTile(c, c.id in favorites, {
                                            val updated = if(c.id in favorites) favorites-c.id else favorites+c.id
                                            favorites = updated
                                            api.saveFavorites(updated)
                                            scope.launch { try { api.setFavorite(c.id, c.id in updated) } catch (_: Exception) { } }
                                        }) {
                                            scope.launch {
                                                busy=true
                                                try {
                                                    if (c.category == "bein" && !beinAccess) throw Exception("قسم beIN SPORTS مقفول")
                                                    val source = api.stream(c.streamIds.firstOrNull() ?: c.id, c.streamType)
                                                    streamUrl = source.url; streamType = source.type; selected=c
                                                    recent = (listOf(c.id)+recent.filterNot { it==c.id }).take(30)
                                                    api.saveRecent(recent)
                                                    try { api.recordWatch(c.id) } catch (_: Exception) { }
                                                } catch(e:Exception) { error=e.message ?: "تعذر تجهيز البث" }
                                                busy=false
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(contentPadding=PaddingValues(bottom=24.dp), verticalArrangement=Arrangement.spacedBy(9.dp)) {
                                    items(grouped) { c ->
                                        ChannelTile(c, c.id in favorites, {
                                            val updated = if(c.id in favorites) favorites-c.id else favorites+c.id
                                            favorites = updated
                                            api.saveFavorites(updated)
                                            scope.launch { try { api.setFavorite(c.id, c.id in updated) } catch (_: Exception) { } }
                                        }) {
                                            scope.launch {
                                                busy=true
                                                try {
                                                    if (c.category == "bein" && !beinAccess) throw Exception("قسم beIN SPORTS مقفول")
                                                    val source = api.stream(c.streamIds.firstOrNull() ?: c.id)
                                                    streamUrl = source.url; streamType = source.type; selected=c
                                                    recent = (listOf(c.id)+recent.filterNot { it==c.id }).take(30)
                                                    api.saveRecent(recent)
                                                } catch(e:Exception) { error=e.message ?: "تعذر تجهيز البث" }
                                                busy=false
                                            }
                                        }
                                    }
                                }
                            }
                            if (error.isNotBlank()) Text(error, color=Color(0xFFFF8A80), modifier=Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
        if (accountDialog) {
            AlertDialog(
                onDismissRequest = { if (!accountBusy) accountDialog = false },
                containerColor = Panel,
                title = { Text("حساب VELNIX", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(session?.userEmail.orEmpty(), color = Muted, fontSize = 12.sp)
                        if (accountBusy) CircularProgressIndicator(color = Gold, modifier = Modifier.size(24.dp))
                        accountDetails?.let { info ->
                            Text("الباقة: ${info.planCode}", color = Gold, fontWeight = FontWeight.Bold)
                            Text("الحالة: ${info.status}", color = Color.White)
                            Text("الانتهاء: ${info.expiresAt.ifBlank { "غير محدد" }}", color = Muted, fontSize = 12.sp)
                        }
                        OutlinedTextField(value = accountName, onValueChange = { accountName = it }, label = { Text("اسم العرض") }, singleLine = true)
                        if (accountMessage.isNotBlank()) Text(accountMessage, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !accountBusy && accountName.isNotBlank(), onClick = {
                        scope.launch {
                            accountBusy = true; accountMessage = ""
                            try {
                                api.updateDisplayName(accountName)
                                accountDetails = api.accountInfo()
                                accountMessage = "تم تحديث اسم العرض"
                            } catch (e: Exception) { accountMessage = e.message ?: "تعذر حفظ الاسم" }
                            finally { accountBusy = false }
                        }
                    }) { Text("حفظ الاسم", color = Gold) }
                },
                dismissButton = { TextButton(onClick = { accountDialog = false }) { Text("إغلاق", color = Muted) } }
            )
        }
        if (codeDialog) {
            AlertDialog(
                onDismissRequest = { if (!codeBusy) codeDialog = false },
                containerColor = Panel,
                title = { Text("تفعيل كود الاشتراك", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("أدخل كود VELNIX المرتبط بحسابك لتفعيل صلاحية المشاهدة.", color = Muted, fontSize = 13.sp)
                        OutlinedTextField(value = codeInput, onValueChange = { codeInput = it.uppercase() }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("VELNIX-XXXX-XXXX-XXXX") })
                        if (codeMessage.isNotBlank()) Text(codeMessage, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !codeBusy && codeInput.isNotBlank(), onClick = {
                        scope.launch {
                            codeBusy = true; codeMessage = ""
                            try {
                                val activation = api.activateAnyCode(codeInput)
                                beinAccess = api.checkBein()
                                codeDialog = false; codeInput = ""
                                status = "تم تفعيل الاشتراك: $activation"
                                refreshCatalog()
                            } catch (e: Exception) { codeMessage = e.message ?: "تعذر تفعيل الكود" }
                            finally { codeBusy = false }
                        }
                    }) { Text(if (codeBusy) "جارٍ التفعيل…" else "تفعيل", color = Gold) }
                },
                dismissButton = { TextButton(enabled = !codeBusy, onClick = { codeDialog = false }) { Text("إلغاء", color = Muted) } }
            )
        }
    }
}


@Composable
private fun IntroScreen() {
    val transition = rememberInfiniteTransition(label = "intro")
    val pulse by transition.animateFloat(initialValue = 0.92f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1500), repeatMode = RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxSize().background(Navy), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
        Box(Modifier.size((86*pulse).dp), contentAlignment=Alignment.Center) {
            Surface(Modifier.size(82.dp).rotate(45f), color=Color.Transparent, border=androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha=.85f)), shape=RoundedCornerShape(2.dp)) {}
            Surface(Modifier.size(66.dp).rotate(45f), color=Color.Transparent, border=androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha=.25f)), shape=RoundedCornerShape(2.dp)) {}
            Text("V", color=Color(0xFFF0D9AD), fontSize=43.sp, fontWeight=FontWeight.Black)
        }
        Spacer(Modifier.height(28.dp))
        Text("CINEMATIC ENTERTAINMENT", color=Gold.copy(alpha=.85f), fontSize=10.sp, letterSpacing=4.sp)
        Spacer(Modifier.height(8.dp))
        Text("VELNIX", color=Color(0xFFF4E6CA), fontSize=42.sp, fontWeight=FontWeight.Black, letterSpacing=8.sp)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.width(180.dp).height(1.dp).background(Gold.copy(alpha=.7f)))
        Spacer(Modifier.height(13.dp))
        Text("ENTERTAINMENT · REDEFINED", color=Muted, fontSize=10.sp, letterSpacing=2.sp)
    }
}

@Composable private fun LazyRowCategories(selected: String, onSelect:(String)->Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        items(CATEGORIES) { c -> CategoryButton(c, selected==c.id) { onSelect(c.id) } }
    }
}
@Composable private fun CategoryButton(c: Category, active:Boolean, onClick:()->Unit) {
    TextButton(onClick=onClick, modifier=Modifier.padding(vertical=2.dp), colors=ButtonDefaults.textButtonColors(contentColor=if(active) Gold else Color.White)) {
        Text(c.title, fontWeight=if(active) FontWeight.Bold else FontWeight.Normal)
    }
}
@Composable private fun ChannelTile(c:Channel, favorite:Boolean, onFavorite:()->Unit, onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(15.dp)).clickable(onClick=onClick).padding(13.dp), verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(Navy, RoundedCornerShape(12.dp)), contentAlignment=Alignment.Center) {
            if (c.logo != null) {
                AsyncImage(model=c.logo, contentDescription=c.name, modifier=Modifier.fillMaxSize().padding(5.dp), contentScale=ContentScale.Fit)
            } else {
                Text(c.name.take(2).uppercase(), color=Gold, fontWeight=FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color=Color.White, fontWeight=FontWeight.SemiBold, maxLines=1)
            Text(c.quality.ifBlank { c.categoryLabel }, color=Muted, fontSize=11.sp)
        }
        TextButton(onClick=onFavorite) { Text(if(favorite) "★" else "☆", color=Gold, fontSize=20.sp) }
    }
}
@Composable private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable private fun AuthScreen(email:String,onEmail:(String)->Unit,password:String,onPassword:(String)->Unit,signup:Boolean,onSwitch:()->Unit,busy:Boolean,error:String,onForgot:()->Unit,onSubmit:()->Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
        Text("VELNIX", color=Gold, fontSize=36.sp, fontWeight=FontWeight.Black, letterSpacing=4.sp)
        Text("PREMIUM LIVE TV", color=Muted, fontSize=11.sp, letterSpacing=3.sp, modifier=Modifier.padding(bottom=30.dp))
        Text(if(signup) "إنشاء حساب جديد" else "مرحباً بعودتك", color=Color.White, fontSize=23.sp, fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(email,onEmail,modifier=Modifier.fillMaxWidth(0.9f),label={Text("البريد الإلكتروني")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password,onPassword,modifier=Modifier.fillMaxWidth(0.9f),label={Text("كلمة المرور")},singleLine=true,visualTransformation=PasswordVisualTransformation())
        if(error.isNotBlank()) Text(error,color=Color(0xFFFF8A80),modifier=Modifier.padding(10.dp))
        Button(onClick=onSubmit,enabled=!busy && email.isNotBlank() && password.length>=6,modifier=Modifier.fillMaxWidth(0.9f).padding(top=12.dp),colors=ButtonDefaults.buttonColors(containerColor=Gold,contentColor=Navy)) {
            Text(if(busy) "يرجى الانتظار…" else if(signup) "إنشاء الحساب" else "تسجيل الدخول")
        }
        TextButton(onClick=onSwitch) { Text(if(signup) "لدي حساب بالفعل" else "إنشاء حساب جديد",color=Gold) }
        if (!signup) TextButton(onClick=onForgot, enabled=!busy) { Text("نسيت كلمة المرور؟", color=Muted) }
    }
}
@Composable private fun NativePlayer(activity: ComponentActivity, api:VelnixApi,channel:Channel,url:String,streamType:String,onBack:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var epg by remember { mutableStateOf<List<EpgProgram>>(emptyList()) }
    var epgLoading by remember { mutableStateOf(false) }
    var epgVisible by remember { mutableStateOf(false) }
    var epgMessage by remember { mutableStateOf("") }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var resizeMode by remember { mutableStateOf(0) }
    var immersive by remember { mutableStateOf(false) }
    val player= remember(url, streamType) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 90000, 5000, 12000)
            .build()
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
                .build()
        }
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val mediaItem = MediaItem.Builder().setUri(url).apply {
            when {
                streamType.contains("dash", true) || url.contains(".mpd", true) -> setMimeType(MimeTypes.APPLICATION_MPD)
                streamType.contains("hls", true) || streamType.contains("m3u8", true) || url.contains(".m3u8", true) -> setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }.build()
        ExoPlayer.Builder(context, renderersFactory).setTrackSelector(trackSelector).setLoadControl(loadControl).build().apply {
            val exo = this
            var retryCount = 0
            val retryHandler = Handler(Looper.getMainLooper())
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (retryCount >= 5) return
                    retryCount++
                    retryHandler.postDelayed({
                        exo.seekToDefaultPosition()
                        exo.prepare()
                        exo.playWhenReady = true
                    }, 3500L)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) retryCount = 0
                }
            })
            setMediaItem(mediaItem)
            playWhenReady=true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose {
        activity.window.decorView.systemUiVisibility = 0
        player.release()
    } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onBack) { Text("رجوع",color=Gold) }
            Text(channel.name,color=Color.White,fontWeight=FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory={ ctx ->
                (ctx.getSystemService(android.content.Context.LAYOUT_INFLATER_SERVICE) as android.view.LayoutInflater)
                    .inflate(R.layout.velnix_player_view, null) as PlayerView
            },
                update={ view -> playerView = view; view.player=player; view.keepScreenOn=true; view.controllerShowTimeoutMs=5500 },
                modifier=Modifier.fillMaxSize())
            Text("VELNIX", color=Gold.copy(alpha=.42f), fontSize=11.sp, fontWeight=FontWeight.Black, letterSpacing=2.sp, modifier=Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal=4.dp, vertical=5.dp), horizontalArrangement=Arrangement.SpaceEvenly, verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={
                epgVisible = !epgVisible
                if (epgVisible && epg.isEmpty()) scope.launch {
                    epgLoading = true
                    try { epg = api.epg(channel); epgMessage = if (epg.isEmpty()) "لا توجد بيانات برامج متاحة لهذه القناة حالياً" else "" }
                    catch (e: Exception) { epgMessage = e.message ?: "تعذر تحميل دليل البرامج" }
                    epgLoading = false
                }
            }) { Text("دليل البرامج", color=Gold) }
            TextButton(onClick={ TrackSelectionDialogBuilder(context, "جودة الصورة", player, C.TRACK_TYPE_VIDEO).build().show() }) { Text("الجودة", color=Gold) }
            TextButton(onClick={ TrackSelectionDialogBuilder(context, "مسار الصوت", player, C.TRACK_TYPE_AUDIO).build().show() }) { Text("الصوت", color=Gold) }
            TextButton(onClick={ TrackSelectionDialogBuilder(context, "الترجمة", player, C.TRACK_TYPE_TEXT).build().show() }) { Text("الترجمة", color=Gold) }
            TextButton(onClick={ player.seekToDefaultPosition(); player.prepare(); player.playWhenReady=true }) { Text("إعادة الاتصال", color=Gold) }
            TextButton(onClick={
                resizeMode = (resizeMode + 1) % 3
                playerView?.resizeMode = when (resizeMode) {
                    0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            }) { Text(listOf("ملاءمة", "تكبير", "تمديد")[resizeMode], color=Gold) }
            TextButton(onClick={
                immersive = !immersive
                activity.window.decorView.systemUiVisibility = if (immersive)
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                else 0
            }) { Text(if (immersive) "إظهار القوائم" else "ملء الشاشة", color=Gold) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                TextButton(onClick={
                    activity.enterPictureInPictureMode(
                        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                    )
                }) { Text("نافذة عائمة", color=Gold) }
            }
        }
        if (epgVisible) {
            Column(Modifier.fillMaxWidth().heightIn(max=220.dp).background(Panel).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("دليل البرامج · EPG", color=Gold, fontWeight=FontWeight.Bold)
                if (epgLoading) CircularProgressIndicator(color=Gold, modifier=Modifier.size(24.dp))
                else if (epg.isEmpty()) Text(epgMessage.ifBlank { "لا توجد برامج مدرجة حالياً" }, color=Muted, fontSize=12.sp)
                else epg.take(12).forEach { item ->
                    val active = System.currentTimeMillis() in item.startsAt until item.endsAt
                    Column(Modifier.fillMaxWidth().background(if (active) Gold.copy(alpha=.12f) else Navy, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.startsAt)) + " – " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.endsAt)), color=Gold, fontSize=10.sp)
                        Text(item.title, color=Color.White, fontWeight=FontWeight.SemiBold, fontSize=13.sp)
                        if (item.description.isNotBlank()) Text(item.description, color=Muted, fontSize=11.sp, maxLines=2)
                    }
                }
            }
        }
        Text("مشغل VELNIX المحسّن · استعادة تلقائية عند انقطاع البث",color=Muted,fontSize=10.sp,modifier=Modifier.padding(10.dp))
    }
}

data class Category(val id:String,val title:String)
val CATEGORIES = listOf(
    Category("all","كل القنوات"), Category("sports","الرياضة"), Category("bein","beIN SPORTS"),
    Category("arabic","القنوات العربية"), Category("movies","أفلام ومسلسلات"), Category("news","الأخبار"),
    Category("kids","الأطفال"), Category("documentaries","وثائقيات"), Category("music","موسيقى وراديو"),
    Category("variety","منوعات"), Category("world","قنوات عالمية")
)
data class Channel(val id:String,val name:String,val english:String,val category:String,val categoryLabel:String,val logo:String?,val provider:String?,val quality:String="",val streamIds:List<String> = listOf(id),val externalId:String? = null,val streamType:String = "hls")
data class UserSession(val accessToken:String,val refreshToken:String,val userEmail:String,val userId:String = "")
data class AccountInfo(val displayName:String,val planCode:String,val status:String,val expiresAt:String)

fun categoryFor(group:String,name:String):String {
    val both=(group+" "+name).lowercase()
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
