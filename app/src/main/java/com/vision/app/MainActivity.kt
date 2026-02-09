package com.vision.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// --- TMDB API ---
const val TMDB_API_KEY = "0e6cf9686697496bc8cafef543dd11fe"

data class Genre(val id: Int, val name: String)
data class TmdbResult(val results: List<MediaItem>)
data class MediaItem(
    val id: Int, val title: String?, val name: String?, val poster_path: String?,
    val media_type: String?, val overview: String?, val release_date: String?, val first_air_date: String?
)
data class Season(val id: Int, val name: String, val season_number: Int, val episode_count: Int)
data class TvShowDetail(val id: Int, val name: String, val seasons: List<Season>, val genres: List<Genre>)
data class MovieDetail(val id: Int, val title: String, val genres: List<Genre>)
data class Episode(val id: Int, val name: String, val episode_number: Int, val still_path: String?)
data class SeasonDetail(val episodes: List<Episode>)

data class VideoSource(val siteName: String, val url: String, val pageUrl: String, val title: String = "")

interface TmdbApi {
    @GET("search/multi") suspend fun search(@Query("api_key") key: String, @Query("query") query: String): TmdbResult
    @GET("tv/{series_id}") suspend fun getTvDetails(@Path("series_id") id: Int, @Query("api_key") key: String): TvShowDetail
    @GET("movie/{movie_id}") suspend fun getMovieDetails(@Path("movie_id") id: Int, @Query("api_key") key: String): MovieDetail
    @GET("tv/{series_id}/season/{season_number}") suspend fun getSeasonDetails(@Path("series_id") id: Int, @Path("season_number") season: Int, @Query("api_key") key: String): SeasonDetail
    @GET("trending/all/week") suspend fun getTrending(@Query("api_key") key: String): TmdbResult
    @GET("movie/now_playing") suspend fun getNowPlaying(@Query("api_key") key: String): TmdbResult
}

val retrofit: Retrofit = Retrofit.Builder().baseUrl("https://api.themoviedb.org/3/").addConverterFactory(GsonConverterFactory.create()).build()
val tmdbApi: TmdbApi = retrofit.create(TmdbApi::class.java)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VisionApp() }
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionApp() {
    val navController = rememberNavController()
    var videoUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vision_prefs", Context.MODE_PRIVATE) }
    
    // Persistence State
    var globalQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var trendingContent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var nowPlayingContent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    // Config State
    var source1Url by remember { mutableStateOf(prefs.getString("source1", "https://anime-sama.fr") ?: "https://anime-sama.fr") }
    var source2Url by remember { mutableStateOf(prefs.getString("source2", "https://vostfree.tv") ?: "https://vostfree.tv") }
    var source3Url by remember { mutableStateOf(prefs.getString("source3", "https://www.adkami.com") ?: "https://www.adkami.com") }
    var showGlobalSettings by remember { mutableStateOf(false) }

    // Scraper State
    var scrapeTargetUrl by remember { mutableStateOf<String?>(null) }
    var isScraping by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("") }
    var currentEpisodeNum by remember { mutableIntStateOf(1) }
    var currentSeasonNum by remember { mutableIntStateOf(1) }
    var attemptIndex by remember { mutableIntStateOf(0) }
    var discoveredSources by remember { mutableStateOf<List<VideoSource>>(emptyList()) }
    var excludedUrls = remember { mutableSetOf<String>() }
    
    // Auto Navigation
    var isAutoNavMode by remember { mutableStateOf(false) }
    var currentResultIndex by remember { mutableIntStateOf(-1) }
    var autoNavTimer by remember { mutableIntStateOf(15) }

    var currentCrawlingUrl by remember { mutableStateOf<String?>(null) }
    var scrapingStatus by remember { mutableStateOf("Initialisation...") }
    var isScrapingError by remember { mutableStateOf(false) }
    
    // Captcha & visibility state
    var showScraperWebView by remember { mutableStateOf(false) }

    val tryNextAttempt: () -> Unit = {
        isScrapingError = false
        attemptIndex++
        val slug = currentTitle.lowercase().replace(" ", "-").replace(":", "").replace("'", "")
        when (attemptIndex) {
            1 -> scrapeTargetUrl = "$source2Url/search/$slug-episode-$currentEpisodeNum"
            2 -> scrapeTargetUrl = "$source3Url/recherche?query=$slug"
            3 -> {
                val query = Uri.encode("$currentTitle saison $currentSeasonNum episode $currentEpisodeNum stream vf vostfr")
                scrapeTargetUrl = "https://yandex.com/search/?text=$query"
            }
            else -> {
                isScraping = false
                scrapeTargetUrl = null
            }
        }
        scrapingStatus = "Recherche Source ${attemptIndex + 1}..."
        currentCrawlingUrl = scrapeTargetUrl
    }

    val startScraping: (String, Int, Int, Boolean) -> Unit = { title, season, episode, isAnime ->
        currentTitle = title
        currentSeasonNum = season
        currentEpisodeNum = episode
        isScrapingError = false
        val slug = title.lowercase().replace(" ", "-").replace(":", "").replace("'", "")
        
        if (isAnime) {
            attemptIndex = 0
            scrapeTargetUrl = "$source1Url/catalogue/$slug"
            scrapingStatus = "Analyse d'Anime-Sama..."
        } else {
            attemptIndex = 1
            scrapeTargetUrl = "$source2Url/search/$slug-episode-$currentEpisodeNum"
            scrapingStatus = "Recherche Source 2..."
        }
        currentCrawlingUrl = scrapeTargetUrl
        isScraping = true
    }

    val startAutoNav = {
        if (discoveredSources.isNotEmpty()) {
            isAutoNavMode = true
            currentResultIndex = 0
            videoUrl = null
            scrapeTargetUrl = discoveredSources[0].pageUrl
            isScraping = true
            navController.navigate("player")
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0F172A), surface = Color(0xFF1E293B), primary = Color(0xFF38BDF8))) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                    confirmButton = { 
                        Button(onClick = { 
                            prefs.edit().putString("source1", source1Url).putString("source2", source2Url).putString("source3", source3Url).apply()
                            showGlobalSettings = false 
                        }) { Text("OK") } 
                    }
                )
            }

            if (isScraping && scrapeTargetUrl != null) {
                // On n'affiche l'AlertDialog QUE si on n'est pas en mode Auto-Nav (car l'Auto-Nav a sa propre UI dans le player)
                // OU si un Captcha est détecté (auquel cas la WebView prend le dessus)
                if (!showScraperWebView && !isAutoNavMode) {
                    AlertDialog(
                        onDismissRequest = { isScraping = false; scrapeTargetUrl = null },
                        title = { Text(if (attemptIndex < 3) "Recherche Source ${attemptIndex + 1}" else "Recherche Yandex") },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                if (!isScrapingError) CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(scrapingStatus, textAlign = TextAlign.Center, color = if(isScrapingError) Color.Red else Color.Unspecified)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(currentCrawlingUrl ?: "", style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.Gray)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { if (attemptIndex < 3) tryNextAttempt() else { isScraping = false; scrapeTargetUrl = null } }) {
                                Text(if (attemptIndex < 3) "Passer à la suivante" else "Fermer")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { isScraping = false; scrapeTargetUrl = null }) { Text("Annuler") }
                        }
                    )
                }

                LaunchedEffect(isAutoNavMode, scrapeTargetUrl, isScraping) {
                    if (isAutoNavMode && isScraping && !isScrapingError) {
                        autoNavTimer = 15
                        while (autoNavTimer > 0 && isScraping) {
                            delay(1000)
                            autoNavTimer--
                        }
                        if (isScraping && autoNavTimer <= 0) {
                            if (currentResultIndex < discoveredSources.size - 1) {
                                currentResultIndex++
                                videoUrl = null
                                scrapeTargetUrl = discoveredSources[currentResultIndex].pageUrl
                                scrapingStatus = "Source suivante (${currentResultIndex + 1}/${discoveredSources.size})..."
                            } else {
                                isScraping = false; scrapingStatus = "Fin des résultats."
                            }
                        }
                    }
                }

                if (attemptIndex < 3 || isAutoNavMode) {
                    HeadlessScraper(
                        url = scrapeTargetUrl!!,
                        isVisible = showScraperWebView,
                        onVisibilityChange = { showScraperWebView = it },
                        onUrlChange = { currentCrawlingUrl = it },
                        onStatusChange = { scrapingStatus = it },
                        onVideoFound = { url ->
                            videoUrl = url
                            isScraping = false
                            showScraperWebView = false
                            // Si pas auto-nav, on navigue vers le player (en auto-nav on y est déjà)
                            if (!isAutoNavMode) navController.navigate("player")
                        },
                        onError = { 
                            isScrapingError = true
                            scrapingStatus = "Aucun lecteur trouvé."
                            if (isAutoNavMode) autoNavTimer = 2 
                        }
                    )
                } else {
                    SearchScraper(
                        searchUrl = scrapeTargetUrl!!,
                        targetTitle = currentTitle,
                        excludedUrls = excludedUrls,
                        onUrlChange = { currentCrawlingUrl = it },
                        onStatusChange = { scrapingStatus = it },
                        onFinished = { sources ->
                            discoveredSources = sources
                            scrapeTargetUrl = null
                            isScraping = false
                            navController.navigate("results")
                        }
                    )
                }
            }

            NavHost(navController = navController, startDestination = "home") {
                composable("home") { 
                    HomeScreen(
                        navController = navController, onOpenSettings = { showGlobalSettings = true },
                        initialQuery = globalQuery, initialResults = searchResults,
                        initialTrending = trendingContent, initialNowPlaying = nowPlayingContent,
                        onStateUpdate = { q, r, t, np -> globalQuery = q; searchResults = r; trendingContent = t; nowPlayingContent = np }
                    ) 
                }
                composable("detail/{type}/{id}/{title}/{poster}") { backStackEntry ->
                    val type = backStackEntry.arguments?.getString("type") ?: "movie"
                    val id = backStackEntry.arguments?.getString("id")?.toInt() ?: 0
                    val title = Uri.decode(backStackEntry.arguments?.getString("title") ?: "")
                    val poster = Uri.decode(backStackEntry.arguments?.getString("poster") ?: "")
                    DetailScreen(
                        navController, type, id, title, poster, 
                        onPlayEpisode = { ep, s, isAnime -> startScraping(title, s, ep.episode_number, isAnime) },
                        onSyncSeason = { /* ... */ },
                        hasSeasonSynced = false
                    )
                }
                composable("player") { 
                    UniversalPlayerScreen(
                        url = videoUrl,
                        isSearching = isScraping,
                        scrapingStatus = scrapingStatus,
                        autoNavTimer = autoNavTimer,
                        isAutoNav = isAutoNavMode,
                        currentSourceIndex = currentResultIndex,
                        totalSources = discoveredSources.size,
                        onValidate = { isAutoNavMode = false },
                        onNext = { 
                            if (currentResultIndex < discoveredSources.size - 1) {
                                currentResultIndex++
                                videoUrl = null
                                scrapeTargetUrl = discoveredSources[currentResultIndex].pageUrl
                                isScraping = true
                            } else {
                                isAutoNavMode = false
                                navController.popBackStack()
                            }
                        },
                        onClose = { 
                            navController.popBackStack()
                            isAutoNavMode = false
                            isScraping = false
                            scrapeTargetUrl = null
                        }
                    ) 
                }
                composable("results") { 
                    Column {
                        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { navController.popBackStack() }) { Icon(Icons.Filled.ArrowBack, null); Text("Retour") }
                            Button(onClick = { startAutoNav() }) { Icon(Icons.Filled.PlayArrow, null); Text("Auto-Nav") }
                        }
                        ResultsScreen(
                            sources = discoveredSources, 
                            onSelect = { source -> 
                                scrapeTargetUrl = source.pageUrl
                                attemptIndex = 0
                                isScraping = true
                                navController.navigate("player")
                            }, 
                            onBack = { navController.popBackStack() }
                        )
                        Button(
                            onClick = { 
                                excludedUrls.addAll(discoveredSources.map { it.pageUrl })
                                attemptIndex = 3
                                tryNextAttempt()
                            },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Filled.Search, null)
                            Text("Relancer (exclure ces résultats)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UniversalPlayerScreen(
    url: String?, 
    isSearching: Boolean,
    scrapingStatus: String,
    autoNavTimer: Int,
    isAutoNav: Boolean, 
    currentSourceIndex: Int,
    totalSources: Int,
    onValidate: () -> Unit, 
    onNext: () -> Unit, 
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isSearching) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text("SearchingPlayer...", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(scrapingStatus, color = Color.Gray)
                if (isAutoNav) {
                    Text("Auto-skip dans ${autoNavTimer}s", color = Color.Red.copy(0.7f), style = MaterialTheme.typography.labelSmall)
                }
            }
        } else if (url != null) {
            val isEmbed = remember(url) {
                val u = url.lowercase()
                u.contains("sibnet") || u.contains("sendvid") || u.contains("myvi") || u.contains("vk.com") || u.contains("ok.ru") || u.contains("embed") || u.contains("player") || u.contains("youtube")
            }
            if (isEmbed) EmbedPlayerScreen(url, onClose = {}) else VideoPlayerScreen(url, onClose = {})
        } else {
            Text("Aucune source trouvée.", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        // Overlay Controls
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onClose, modifier = Modifier.background(Color.Black.copy(0.5f), RoundedCornerShape(8.dp))) {
                Icon(Icons.Filled.ArrowBack, null, tint = Color.White)
            }
            
            if (isAutoNav) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Source ${currentSourceIndex + 1} / $totalSources", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onValidate, colors = ButtonDefaults.buttonColors(containerColor = Color.Green.copy(0.7f))) {
                            Icon(Icons.Filled.Check, null); Text("C'est bon !")
                        }
                        Button(onClick = onNext, colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(0.7f))) {
                            Icon(Icons.Filled.SkipNext, null); Text("Suivant")
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedPlayerScreen(url: String, onClose: () -> Unit) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean { return false }
                }
                val embedUrl = if (url.contains("youtube.com/watch?v=")) url.replace("watch?v=", "embed/")
                else if (url.contains("youtu.be/")) url.replace("youtu.be/", "youtube.com/embed/")
                else url
                val html = "<html><body style='margin:0;padding:0;background:black;'><iframe src='$embedUrl' width='100%' height='100%' frameborder='0' allowfullscreen allow='autoplay; fullscreen'></iframe></body></html>"
                loadDataWithBaseURL(embedUrl, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun VideoPlayerScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember { 
        ExoPlayer.Builder(context).build().apply { 
            setMediaItem(PlayerMediaItem.Builder().setUri(url).setMimeType(if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null).build())
            prepare(); playWhenReady = true 
        } 
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    AndroidView(factory = { PlayerView(it).apply { player = exoPlayer } }, modifier = Modifier.fillMaxSize())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController, onOpenSettings: () -> Unit,
    initialQuery: String, initialResults: List<MediaItem>,
    initialTrending: List<MediaItem>, initialNowPlaying: List<MediaItem>,
    onStateUpdate: (String, List<MediaItem>, List<MediaItem>, List<MediaItem>) -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf(initialResults) }
    var trending by remember { mutableStateOf(initialTrending) }
    var nowPlaying by remember { mutableStateOf(initialNowPlaying) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (trending.isEmpty()) {
            try {
                trending = withContext(Dispatchers.IO) { tmdbApi.getTrending(TMDB_API_KEY).results }
                nowPlaying = withContext(Dispatchers.IO) { tmdbApi.getNowPlaying(TMDB_API_KEY).results }
                onStateUpdate(query, results, trending, nowPlaying)
            } catch (e: Exception) {}
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("VISION", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, null) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it; if(it.isEmpty()) { results = emptyList(); onStateUpdate(it, emptyList(), trending, nowPlaying) } }, 
            label = { Text("Rechercher...") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                scope.launch {
                    loading = true
                    try { 
                        results = withContext(Dispatchers.IO) { tmdbApi.search(TMDB_API_KEY, query).results.filter { it.media_type != "person" } }
                        onStateUpdate(query, results, trending, nowPlaying)
                    } catch (e: Exception) {} finally { loading = false }
                }
            })
        )
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        
        if (query.isEmpty() && results.isEmpty()) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(16.dp)); Text("Tendances", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(trending) { MediaPosterItem(it) { navController.navigate("detail/${it.media_type ?: "movie"}/${it.id}/${Uri.encode(it.title ?: it.name)}/${Uri.encode(it.poster_path ?: "")}") } } }
                Spacer(Modifier.height(24.dp)); Text("Dernières sorties", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(nowPlaying) { MediaPosterItem(it) { navController.navigate("detail/movie/${it.id}/${Uri.encode(it.title ?: it.name)}/${Uri.encode(it.poster_path ?: "")}") } } }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                items(results) { item ->
                    Card(modifier = Modifier.fillMaxWidth().height(100.dp).clickable { navController.navigate("detail/${item.media_type ?: "movie"}/${item.id}/${Uri.encode(item.title ?: item.name)}/${Uri.encode(item.poster_path ?: "")}") }) {
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
}

@Composable
fun MediaPosterItem(item: MediaItem, onClick: () -> Unit) {
    Column(modifier = Modifier.width(120.dp).clickable { onClick() }) {
        AsyncImage(model = "https://image.tmdb.org/t/p/w342${item.poster_path}", contentDescription = null, modifier = Modifier.height(180.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(4.dp))
        Text(item.title ?: item.name ?: "", style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(navController: NavController, type: String, id: Int, title: String, posterPath: String, onPlayEpisode: (Episode, Int, Boolean) -> Unit, onSyncSeason: (Int) -> Unit, hasSeasonSynced: Boolean) {
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<Season?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var isAnime by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        try {
            if (type == "tv") {
                val details = withContext(Dispatchers.IO) { tmdbApi.getTvDetails(id, TMDB_API_KEY) }
                seasons = details.seasons.filter { it.season_number > 0 }
                isAnime = details.genres.any { it.id == 16 }; if (seasons.isNotEmpty()) selectedSeason = seasons[0]
            } else {
                val details = withContext(Dispatchers.IO) { tmdbApi.getMovieDetails(id, TMDB_API_KEY) }
                isAnime = details.genres.any { it.id == 16 }
            }
        } catch (e: Exception) {}
    }
    LaunchedEffect(selectedSeason) { selectedSeason?.let { try { episodes = withContext(Dispatchers.IO) { tmdbApi.getSeasonDetails(id, it.season_number, TMDB_API_KEY).episodes } } catch(e:Exception){} } }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            AsyncImage(model = "https://image.tmdb.org/t/p/w780$posterPath", contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)))
            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Filled.ArrowBack, null, tint = Color.White) }
            Text(title, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        }
        if (type == "tv") {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(seasons) { s -> FilterChip(selected = s == selectedSeason, onClick = { selectedSeason = s }, label = { Text("Saison ${s.season_number}") }) } }
            }
            LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                items(episodes) { ep ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedSeason?.let { onPlayEpisode(ep, it.season_number, isAnime) } }) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = "https://image.tmdb.org/t/p/w300${ep.still_path}", contentDescription = null, modifier = Modifier.width(100.dp).height(60.dp), contentScale = ContentScale.Crop)
                            Text("Ep ${ep.episode_number}: ${ep.name}", modifier = Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        } else { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(onClick = { onPlayEpisode(Episode(0, "Movie", 1, null), 1, isAnime) }) { Icon(Icons.Filled.Search, null); Spacer(Modifier.width(8.dp)); Text("Rechercher le film") } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(sources: List<VideoSource>, onSelect: (VideoSource) -> Unit, onBack: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sources) { s ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onSelect(s) }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall)
                    Text(s.siteName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HeadlessScraper(
    url: String,
    isVisible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    onUrlChange: (String) -> Unit,
    onStatusChange: (String) -> Unit,
    onVideoFound: (String) -> Unit,
    onError: () -> Unit
) {
    var found by remember { mutableStateOf(false) }
    
    Box(modifier = if (isVisible) Modifier.fillMaxSize().background(Color.Black) else Modifier.size(1.dp)) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                    
                    addJavascriptInterface(object {
                        @JavascriptInterface fun processVideo(src: String) { 
                            if (!found && src.isNotEmpty()) { 
                                found = true
                                post { onVideoFound(src) } 
                            } 
                        }
                        @JavascriptInterface fun reportError() { post { onError() } }
                        @JavascriptInterface fun onCaptchaDetected() { post { onVisibilityChange(true) } }
                    }, "VisionApp")

                    webViewClient = object : WebViewClient() {
                        override fun onLoadResource(view: WebView?, resourceUrl: String?) {
                            if (!found && resourceUrl != null && (resourceUrl.contains(".mp4") || resourceUrl.contains(".m3u8") || resourceUrl.contains("sibnet.net") || resourceUrl.contains("sendvid"))) {
                                found = true; onVideoFound(resourceUrl)
                            }
                        }
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            url?.let { onUrlChange(it) }
                            if (isVisible) onVisibilityChange(false)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let { onUrlChange(it) }
                            view?.evaluateJavascript("""
                                (function() {
                                    var html = document.body.innerHTML.toLowerCase();
                                    if (html.includes('captcha') || html.includes('robot') || document.querySelector('.g-recaptcha') || document.querySelector('#captcha')) {
                                        VisionApp.onCaptchaDetected();
                                        return;
                                    }

                                    var c=0;
                                    function s() {
                                        if(window.f) return;
                                        
                                        var v=document.querySelector('video');
                                        if(v && v.src && v.src.startsWith('http')) {
                                            window.f=true; VisionApp.processVideo(v.src); return;
                                        }
                                        
                                        var src=document.querySelector('source');
                                        if(src && src.src) {
                                            window.f=true; VisionApp.processVideo(src.src); return;
                                        }

                                        var f=document.querySelectorAll('iframe');
                                        for(var i=0; i<f.length; i++) {
                                            var r=f[i].src;
                                            if(r.includes('sibnet') || r.includes('sendvid') || r.includes('myvi') || r.includes('vk.com') || r.includes('ok.ru') || r.includes('player') || r.includes('embed')) {
                                                window.f=true; VisionApp.processVideo(r); return;
                                            }
                                        }

                                        if(c++ < 40) setTimeout(s, 800);
                                        else VisionApp.reportError();
                                    }
                                    s();
                                })();
                            """.trimIndent(), null)
                        }
                    }
                }
            },
            update = { if (it.url != url) { found = false; it.loadUrl(url) } },
            modifier = Modifier.fillMaxSize()
        )
        
        if (isVisible) {
            Box(modifier = Modifier.fillMaxWidth().background(Color.Red.copy(0.8f)).padding(8.dp).align(Alignment.TopCenter)) {
                Text("Captcha détecté ! Veuillez le résoudre.", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun SearchScraper(searchUrl: String, targetTitle: String, excludedUrls: Set<String>, onUrlChange: (String) -> Unit, onStatusChange: (String) -> Unit, onFinished: (List<VideoSource>) -> Unit) {
    var finishedCalled by remember { mutableStateOf(false) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                addJavascriptInterface(object {
                    @JavascriptInterface fun onResults(titles: Array<String>, urls: Array<String>) {
                        post {
                            val list = mutableListOf<VideoSource>()
                            for (i in titles.indices) {
                                if (!excludedUrls.contains(urls[i])) {
                                    list.add(VideoSource(Uri.parse(urls[i]).host ?: "Site", "", urls[i], titles[i]))
                                }
                            }
                            val firstWord = targetTitle.split(" ")[0].lowercase()
                            val filtered = list.filter { it.title.lowercase().contains(firstWord) }
                            if (!finishedCalled) { finishedCalled = true; onFinished(if(filtered.isNotEmpty()) filtered else list) }
                        }
                    }
                }, "VisionSearch")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript("(function(){ var t=[]; var u=[]; document.querySelectorAll('a').forEach(a=>{ var txt=a.innerText; if(a.href.startsWith('http') && txt.length>5){ t.push(txt); u.push(a.href); }}); VisionSearch.onResults(t, u); })();", null)
                    }
                }
                loadUrl(searchUrl)
            }
        },
        modifier = Modifier.size(1.dp)
    )
}
