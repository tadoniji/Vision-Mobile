package com.vision.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// --- TMDB API ---
const val TMDB_API_KEY = "0e6cf9686697496bc8cafef543dd11fe"

data class TmdbResult(val results: List<MediaItem>)
data class MediaItem(
    val id: Int, val title: String?, val name: String?, val poster_path: String?,
    val media_type: String?, val overview: String?, val release_date: String?, val first_air_date: String?
)
data class Season(val id: Int, val name: String, val season_number: Int, val episode_count: Int)
data class TvShowDetail(val id: Int, val name: String, val seasons: List<Season>)
data class Episode(val id: Int, val name: String, val episode_number: Int, val still_path: String?)
data class SeasonDetail(val episodes: List<Episode>)

interface TmdbApi {
    @GET("search/multi") suspend fun search(@Query("api_key") key: String, @Query("query") query: String): TmdbResult
    @GET("tv/{series_id}") suspend fun getTvDetails(@Path("series_id") id: Int, @Query("api_key") key: String): TvShowDetail
    @GET("tv/{series_id}/season/{season_number}") suspend fun getSeasonDetails(@Path("series_id") id: Int, @Path("season_number") season: Int, @Query("api_key") key: String): SeasonDetail
}

val retrofit = Retrofit.Builder().baseUrl("https://api.themoviedb.org/3/").addConverterFactory(GsonConverterFactory.create()).build()
val tmdbApi = retrofit.create(TmdbApi::class.java)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VisionApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionApp() {
    val navController = rememberNavController()
    var videoUrl by remember { mutableStateOf<String?>(null) }
    
    // Config State
    var source1Url by remember { mutableStateOf("https://anime-sama.fr") }
    var source2Url by remember { mutableStateOf("https://vostfree.tv") }
    var source3Url by remember { mutableStateOf("https://www.adkami.com") }
    var showGlobalSettings by remember { mutableStateOf(false) }

    // Scraper State
    var scrapeTargetUrl by remember { mutableStateOf<String?>(null) }
    var isScraping by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("") }
    var currentEpisodeNum by remember { mutableIntStateOf(1) }
    var currentSeasonNum by remember { mutableIntStateOf(1) }
    var attemptIndex by remember { mutableIntStateOf(0) }

    val startScraping: (String, Int, Int) -> Unit = { title, season, episode ->
        currentTitle = title
        currentSeasonNum = season
        currentEpisodeNum = episode
        attemptIndex = 0
        val slug = title.lowercase().replace(" ", "-").replace(":", "").replace("'", "")
        scrapeTargetUrl = "$source1Url/catalogue/$slug/saison$season/vostfr/episode$episode"
        isScraping = true
    }

    val tryNextAttempt: () -> Unit = {
        attemptIndex++
        val slug = currentTitle.lowercase().replace(" ", "-").replace(":", "").replace("'", "")
        when (attemptIndex) {
            1 -> scrapeTargetUrl = "$source2Url/search/$slug-episode-$currentEpisodeNum"
            2 -> scrapeTargetUrl = "$source3Url/recherche?query=$slug"
            3 -> { // FALLBACK YANDEX
                val query = Uri.encode("$currentTitle saison $currentSeasonNum episode $currentEpisodeNum stream vf gratuit")
                scrapeTargetUrl = "https://yandex.com/search/?text=$query"
            }
            else -> {
                isScraping = false
                scrapeTargetUrl = null
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0F172A), surface = Color(0xFF1E293B), primary = Color(0xFF38BDF8))) {
        if (showGlobalSettings) {
            AlertDialog(
                onDismissRequest = { showGlobalSettings = false },
                title = { Text("Sources") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = source1Url, onValueChange = { source1Url = it }, label = { Text("Source 1") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = source2Url, onValueChange = { source2Url = it }, label = { Text("Source 2") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = source3Url, onValueChange = { source3Url = it }, label = { Text("Source 3") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { Button(onClick = { showGlobalSettings = false }) { Text("OK") } }
            )
        }

        if (scrapeTargetUrl != null) {
            HeadlessScraper(
                url = scrapeTargetUrl!!,
                onVideoFound = { url ->
                    videoUrl = url
                    scrapeTargetUrl = null
                    isScraping = false
                    navController.navigate("player")
                },
                onError = { tryNextAttempt() }
            )
        }

        NavHost(navController = navController, startDestination = "home") {
            composable("home") { HomeScreen(navController, onOpenSettings = { showGlobalSettings = true }) }
            composable("detail/{type}/{id}/{title}/{poster}") { backStackEntry ->
                val type = backStackEntry.arguments?.getString("type") ?: "movie"
                val id = backStackEntry.arguments?.getString("id")?.toInt() ?: 0
                val title = Uri.decode(backStackEntry.arguments?.getString("title") ?: "")
                val poster = Uri.decode(backStackEntry.arguments?.getString("poster") ?: "")
                
                DetailScreen(navController, type, id, title, poster, onPlayEpisode = { ep, s -> startScraping(title, s, ep.episode_number) })
                
                if (isScraping) {
                    AlertDialog(
                        onDismissRequest = { isScraping = false; scrapeTargetUrl = null },
                        title = { Text(if (attemptIndex < 3) "Recherche (Source ${attemptIndex + 1})..." else "Recherche Web (Yandex)...") },
                        text = { 
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Tentative d'extraction en cours...", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            if (attemptIndex < 3) {
                                TextButton(onClick = { attemptIndex = 2; tryNextAttempt() }) { 
                                    Icon(Icons.Default.Search, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Forcer Yandex") 
                                }
                            }
                        },
                        dismissButton = { TextButton(onClick = { isScraping = false; scrapeTargetUrl = null }) { Text("Annuler") } }
                    )
                }
            }
            composable("player") { videoUrl?.let { VideoPlayerScreen(it) { navController.popBackStack() } } }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HeadlessScraper(url: String, onVideoFound: (String) -> Unit, onError: () -> Unit) {
    var found by remember { mutableStateOf(false) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                addJavascriptInterface(object {
                    @JavascriptInterface fun processVideo(src: String) { if (!found && src.isNotEmpty()) { found = true; post { onVideoFound(src) } } }
                    @JavascriptInterface fun reportError() { post { onError() } }
                }, "VisionApp")
                webViewClient = object : WebViewClient() {
                    override fun onLoadResource(view: WebView?, resourceUrl: String?) {
                        if (!found && resourceUrl != null && (resourceUrl.contains(".mp4") || resourceUrl.contains(".m3u8") || resourceUrl.contains("sibnet.net") || resourceUrl.contains("sendvid"))) {
                            found = true; onVideoFound(resourceUrl)
                        }
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript("(function() { var c=0; function s() { if(window.f)return; var v=document.querySelector('video'); if(v&&v.src&&v.src.startsWith('http')){window.f=true;VisionApp.processVideo(v.src);return;} var f=document.querySelectorAll('iframe'); for(var i=0;i<f.length;i++){var r=f[i].src; if(r.includes('sibnet')||r.includes('sendvid')||r.includes('myvi')){window.f=true;VisionApp.processVideo(r);return;}} if(c++<30)setTimeout(s,500); else VisionApp.reportError(); } s(); })();", null)
                    }
                }
            }
        },
        update = { if (it.url != url) { found = false; it.loadUrl(url) } },
        modifier = Modifier.size(1.dp)
    )
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun VideoPlayerScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(PlayerMediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { player = exoPlayer } }, modifier = Modifier.fillMaxSize())
        IconButton(onClick = onClose, modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, onOpenSettings: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("VISION", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            IconButton(onClick = onOpenSettings) { Text("⚙️") }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Rechercher...") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
            scope.launch { loading = true; try { results = withContext(Dispatchers.IO) { tmdbApi.search(TMDB_API_KEY, query).results.filter { it.media_type != "person" } } } catch(e:Exception){} finally { loading = false }
        }))
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                Card(modifier = Modifier.fillMaxWidth().height(100.dp).clickable { navController.navigate("detail/${item.media_type}/${item.id}/${Uri.encode(item.title ?: item.name)}/${Uri.encode(item.poster_path ?: "")}") }) {
                    Row {
                        AsyncImage(model = "https://image.tmdb.org/t/p/w200${item.poster_path}", contentDescription = null, modifier = Modifier.width(70.dp).fillMaxHeight(), contentScale = ContentScale.Crop)
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(item.title ?: item.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text((item.release_date ?: item.first_air_date ?: "").take(4), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(navController: NavController, type: String, id: Int, title: String, posterPath: String, onPlayEpisode: (Episode, Int) -> Unit) {
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<Season?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    LaunchedEffect(id) { if (type == "tv") try { seasons = withContext(Dispatchers.IO) { tmdbApi.getTvDetails(id, TMDB_API_KEY).seasons.filter { it.season_number > 0 } }; if(seasons.isNotEmpty()) selectedSeason = seasons[0] } catch(e:Exception){} }
    LaunchedEffect(selectedSeason) { selectedSeason?.let { try { episodes = withContext(Dispatchers.IO) { tmdbApi.getSeasonDetails(id, it.season_number, TMDB_API_KEY).episodes } } catch(e:Exception){} } }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            AsyncImage(model = "https://image.tmdb.org/t/p/w780$posterPath", contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            Text(title, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        }
        if (type == "tv") {
            LazyRow(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { s -> FilterChip(selected = s == selectedSeason, onClick = { selectedSeason = s }, label = { Text("Saison ${s.season_number}") }) }
            }
            LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                items(episodes) { ep ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedSeason?.let { onPlayEpisode(ep, it.season_number) } }) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = "https://image.tmdb.org/t/p/w300${ep.still_path}", contentDescription = null, modifier = Modifier.width(100.dp).height(60.dp), contentScale = ContentScale.Crop)
                            Text("Ep ${ep.episode_number}: ${ep.name}", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
