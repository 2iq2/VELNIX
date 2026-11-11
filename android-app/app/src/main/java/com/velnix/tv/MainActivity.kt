package com.velnix.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

private val Navy = Color(0xFF080D16)
private val Panel = Color(0xFF111B29)
private val Gold = Color(0xFFC9A96E)
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
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var category by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(setOf<String>()) }
    var recent by remember { mutableStateOf(listOf<String>()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var beinAccess by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("جارٍ الاتصال بدليل القنوات…") }
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
    LaunchedEffect(Unit) { refreshCatalog() }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, background = Navy, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(Modifier.fillMaxSize(), color = Navy) {
            if (selected != null && streamUrl != null) {
                NativePlayer(api, selected!!, streamUrl!!, onBack = { selected = null; streamUrl = null })
            } else if (session == null) {
                AuthScreen(email, { email = it }, password, { password = it }, signup,
                    { signup = !signup; error = "" }, busy, error,
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
                            Text(session?.userEmail ?: "", color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.width(12.dp))
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
                                        ChannelTile(c, c.id in favorites, { favorites = if(c.id in favorites) favorites-c.id else favorites+c.id; api.saveFavorites(favorites) }) {
                                            scope.launch {
                                                busy=true
                                                try {
                                                    if (c.category == "bein" && !beinAccess) throw Exception("قسم beIN SPORTS مقفول")
                                                    streamUrl = api.stream(c.streamIds.firstOrNull() ?: c.id); selected=c
                                                    recent = (listOf(c.id)+recent.filterNot { it==c.id }).take(30)
                                                } catch(e:Exception) { error=e.message ?: "تعذر تجهيز البث" }
                                                busy=false
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(contentPadding=PaddingValues(bottom=24.dp), verticalArrangement=Arrangement.spacedBy(9.dp)) {
                                    items(grouped) { c ->
                                        ChannelTile(c, c.id in favorites, { favorites = if(c.id in favorites) favorites-c.id else favorites+c.id; api.saveFavorites(favorites) }) {
                                            scope.launch {
                                                busy=true
                                                try {
                                                    if (c.category == "bein" && !beinAccess) throw Exception("قسم beIN SPORTS مقفول")
                                                    streamUrl = api.stream(c.streamIds.firstOrNull() ?: c.id); selected=c
                                                    recent = (listOf(c.id)+recent.filterNot { it==c.id }).take(30)
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
    }
}

@Composable private fun LazyRowCategories(selected: String, onSelect:(String)->Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        items(CATEGORIES) { c -> CategoryButton(c, selected==c.id, onSelect) }
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
            Text(c.name.take(2).uppercase(), color=Gold, fontWeight=FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color=Color.White, fontWeight=FontWeight.SemiBold, maxLines=1)
            Text(c.quality.ifBlank { c.categoryLabel }, color=Muted, fontSize=11.sp)
        }
        TextButton(onClick=onFavorite) { Text(if(favorite) "★" else "☆", color=Gold, fontSize=20.sp) }
    }
}
@Composable private fun AuthScreen(email:String,onEmail:(String)->Unit,password:String,onPassword:(String)->Unit,signup:Boolean,onSwitch:()->Unit,busy:Boolean,error:String,onSubmit:()->Unit) {
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
    }
}
@Composable private fun NativePlayer(api:VelnixApi,channel:Channel,url:String,onBack:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val player= remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady=true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onBack) { Text("رجوع",color=Gold) }
            Text(channel.name,color=Color.White,fontWeight=FontWeight.Bold)
        }
        AndroidView(factory={ ctx -> PlayerView(ctx).apply { this.player=player; useController=true } },modifier=Modifier.fillMaxWidth().weight(1f))
        Text("إذا توقف المصدر، قد يعتمد الاستمرار على جودة المصدر واتصال الشبكة والجهاز.",color=Muted,fontSize=11.sp,modifier=Modifier.padding(12.dp))
    }
}

data class Category(val id:String,val title:String)
val CATEGORIES = listOf(
    Category("all","كل القنوات"), Category("sports","الرياضة"), Category("bein","beIN SPORTS"),
    Category("arabic","القنوات العربية"), Category("movies","أفلام ومسلسلات"), Category("news","الأخبار"),
    Category("kids","الأطفال"), Category("documentaries","وثائقيات"), Category("music","موسيقى وراديو"),
    Category("variety","منوعات"), Category("world","قنوات عالمية")
)
data class Channel(val id:String,val name:String,val english:String,val category:String,val categoryLabel:String,val logo:String?,val provider:String?,val quality:String="",val streamIds:List<String> = listOf(id))
data class UserSession(val accessToken:String,val refreshToken:String,val userEmail:String)

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
