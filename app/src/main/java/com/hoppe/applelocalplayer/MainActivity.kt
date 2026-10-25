package com.hoppe.applelocalplayer

import android.Manifest
import android.content.ContentUris
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF09090D)
private val Panel = Color(0xFF191922)
private val Accent = Color(0xFFFF375F)
private val Muted = Color(0xFFA5A5B3)
private val White = Color(0xFFF8F8FC)

data class Song(val title: String, val artist: String, val album: String, val duration: Long, val uri: Uri)
data class YoutubeVideo(val id: String, val title: String, val channel: String, val description: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 9, 13)
        window.navigationBarColor = android.graphics.Color.rgb(9, 9, 13)
        setContent { PlayerScreen() }
    }

    @Composable
    private fun PlayerScreen() {
        var songs by remember { mutableStateOf(emptyList<Song>()) }
        var query by remember { mutableStateOf("") }
        var ytApiKey by remember { mutableStateOf(loadYoutubeKey()) }
        var ytKeyInput by remember { mutableStateOf(loadYoutubeKey()) }
        var ytQuery by remember { mutableStateOf("") }
        var ytResults by remember { mutableStateOf(emptyList<YoutubeVideo>()) }
        var ytSelected by remember { mutableStateOf<YoutubeVideo?>(null) }
        var ytLoading by remember { mutableStateOf(false) }
        var ytStatus by remember { mutableStateOf("Informe sua chave da YouTube Data API para pesquisar.") }
        val scope = rememberCoroutineScope()
        var section by remember { mutableStateOf("Músicas") }
        var selectedPlaylist by remember { mutableStateOf("") }
        var favorites by remember { mutableStateOf(loadFavorites()) }
        var playlists by remember { mutableStateOf(loadPlaylists()) }
        var playlistDialogSong by remember { mutableStateOf<Song?>(null) }
        var showCreatePlaylist by remember { mutableStateOf(false) }
        var newPlaylistName by remember { mutableStateOf("") }
        var showTimer by remember { mutableStateOf(false) }
        var timerMinutes by remember { mutableIntStateOf(0) }
        var timerEndsAt by remember { mutableLongStateOf(0L) }
        var timerRemaining by remember { mutableLongStateOf(0L) }
        var granted by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaController?>(null) }
        var index by remember { mutableIntStateOf(-1) }
        var playing by remember { mutableStateOf(false) }
        var pos by remember { mutableLongStateOf(0L) }
        var dur by remember { mutableLongStateOf(0L) }
        var shuffle by remember { mutableStateOf(false) }
        var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }

        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            if (it) songs = loadSongs()
        }
        LaunchedEffect(Unit) {
            granted = ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED
            if (granted) songs = loadSongs()
        }
        DisposableEffect(Unit) {
            val token = SessionToken(this@MainActivity, ComponentName(this@MainActivity, MusicPlaybackService::class.java))
            val future: ListenableFuture<MediaController> = MediaController.Builder(this@MainActivity, token).buildAsync()
            future.addListener({ runCatching { player = future.get() } }, ContextCompat.getMainExecutor(this@MainActivity))
            onDispose { MediaController.releaseFuture(future) }
        }
        DisposableEffect(player) {
            val p = player ?: return@DisposableEffect onDispose { }
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    index = p.currentMediaItemIndex
                    dur = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                }
                override fun onIsPlayingChanged(value: Boolean) { playing = value }
                override fun onRepeatModeChanged(value: Int) { repeat = value }
                override fun onShuffleModeEnabledChanged(value: Boolean) { shuffle = value }
            }
            p.addListener(listener)
            onDispose { p.removeListener(listener) }
        }
        LaunchedEffect(player) {
            while (true) {
                player?.let {
                    pos = it.currentPosition.coerceAtLeast(0L)
                    dur = it.duration.takeIf { d -> d > 0 && d != C.TIME_UNSET } ?: 0L
                    index = it.currentMediaItemIndex
                    playing = it.isPlaying
                    shuffle = it.shuffleModeEnabled
                    repeat = it.repeatMode
                }
                delay(400)
            }
        }
        LaunchedEffect(timerEndsAt) {
            if (timerEndsAt > 0L) {
                while (System.currentTimeMillis() < timerEndsAt) {
                    timerRemaining = (timerEndsAt - System.currentTimeMillis()).coerceAtLeast(0L)
                    delay(1000)
                }
                player?.pause()
                timerEndsAt = 0L
                timerMinutes = 0
                timerRemaining = 0L
            }
        }

        val current = songs.getOrNull(index)
        val queueUris = (0 until (player?.mediaItemCount ?: 0)).mapNotNull { i ->
            val uri = player?.getMediaItemAt(i)?.localConfiguration?.uri
            songs.firstOrNull { it.uri == uri }
        }
        val playlistSongs = songs.filter { playlists[selectedPlaylist]?.contains(it.uri.toString()) == true }
        val filtered = when (section) {
            "Favoritas" -> songs.filter { favorites.contains(it.uri.toString()) }
            "Artistas" -> songs.filter { query.isBlank() || it.artist.contains(query, true) }
            "Álbuns" -> songs.filter { query.isBlank() || it.album.contains(query, true) }
            "Playlists" -> if (selectedPlaylist.isBlank()) emptyList() else playlistSongs.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
            else -> songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
            Surface(Modifier.fillMaxSize(), color = Bg) {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF321A28), Bg))).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("THREE EIGHTY FIVE MUSIC", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                                Text("Sua música.", color = White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Ouça do seu jeito.", color = Muted)
                            }
                            Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(46.dp))
                        }
                        Spacer(Modifier.height(15.dp))
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Buscar faixa, artista ou álbum", color = Muted) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) }, singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Panel, focusedTextColor = White, unfocusedTextColor = White, cursorColor = Accent, focusedContainerColor = Panel, unfocusedContainerColor = Panel)
                        )
                    }

                    Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("Músicas", "Artistas", "Álbuns", "Favoritas", "Playlists", "Fila", "Explorar").forEach { name ->
                            TextButton(onClick = { section = name; if (name != "Playlists") selectedPlaylist = "" }, contentPadding = PaddingValues(horizontal = 5.dp), colors = ButtonDefaults.textButtonColors(contentColor = if (section == name) Accent else Muted)) {
                                Text(name, fontSize = 10.sp, fontWeight = if (section == name) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }

                    if (!granted) {
                        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.LibraryMusic, null, tint = Accent, modifier = Modifier.size(58.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Sua biblioteca começa aqui", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("Permita o acesso às músicas salvas no celular.", color = Muted)
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { launcher.launch(permission) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Encontrar minhas músicas") }
                        }
                    } else if (section == "Explorar") {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text("Explorar no YouTube", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Reprodução pelo player oficial. Requer internet e não toca em segundo plano.", color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = ytKeyInput, onValueChange = { ytKeyInput = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Chave da YouTube Data API") }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Accent, unfocusedBorderColor = Panel))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { ytApiKey = ytKeyInput.trim(); saveYoutubeKey(ytApiKey); ytStatus = if (ytApiKey.isBlank()) "Informe sua chave da API." else "Chave salva neste dispositivo." }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Salvar chave") }
                                Spacer(Modifier.width(8.dp))
                                Text("A chave fica salva localmente.", color = Muted, fontSize = 10.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = ytQuery, onValueChange = { ytQuery = it }, modifier = Modifier.weight(1f), label = { Text("Música ou artista") }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Accent, unfocusedBorderColor = Panel))
                                IconButton(enabled = ytApiKey.isNotBlank() && ytQuery.isNotBlank() && !ytLoading, onClick = { scope.launch { ytLoading = true; ytStatus = "Pesquisando…"; try { ytResults = searchYoutube(ytQuery, ytApiKey); ytStatus = if (ytResults.isEmpty()) "Nenhum resultado encontrado." else "${ytResults.size} resultados"; } catch (e: Exception) { ytStatus = "Falha na pesquisa. Confira a chave da API, a internet e a cota."; ytResults = emptyList() } finally { ytLoading = false } } }) { Icon(Icons.Default.Search, "Pesquisar no YouTube", tint = Accent) }
                            }
                            Text(ytStatus, color = Muted, fontSize = 12.sp)
                            ytSelected?.let { video ->
                                Text(video.title, color = White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                key(video.id) { AndroidView(factory = { context -> WebView(context).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient(); val html = """<!doctype html><html><body style="margin:0;background:#000"><iframe width="100%" height="220" src="https://www.youtube-nocookie.com/embed/${video.id}?playsinline=1&autoplay=1" title="YouTube player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe></body></html>"""; loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "UTF-8", null) } }, modifier = Modifier.fillMaxWidth().height(220.dp)) }
                            }
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(ytResults) { video -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable { ytSelected = video }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(video.title, color = White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(video.channel, color = Accent, fontSize = 11.sp); Text(video.description, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }; IconButton(onClick = { ytSelected = video }) { Icon(Icons.Default.PlayCircle, "Tocar no player oficial", tint = Accent) } } }
                            }
                        }
                    } else if (section == "Fila") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Fila de reprodução", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(queueUris.size.toString() + " faixas", color = Muted, fontSize = 12.sp)
                        }
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(queueUris) { song ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(if (current?.uri == song.uri) Color(0xFF321A28) else Panel).clickable { playSongs(queueUris, song, player) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.QueueMusic, null, tint = Accent, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, color = White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (current?.uri == song.uri) Icon(Icons.Default.GraphicEq, "Tocando", tint = Accent)
                                }
                            }
                        }
                    } else if (section == "Playlists" && selectedPlaylist.isBlank()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Suas playlists", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { showCreatePlaylist = true }) { Icon(Icons.Default.Add, "Criar playlist", tint = Accent) }
                        }
                        if (playlists.isEmpty()) {
                            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.QueueMusic, null, tint = Accent, modifier = Modifier.size(52.dp))
                                Text("Crie sua primeira playlist", color = White, fontWeight = FontWeight.Bold)
                                Text("Organize suas músicas do seu jeito.", color = Muted)
                                Button(onClick = { showCreatePlaylist = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Nova playlist") }
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(playlists.keys.toList().sorted()) { name ->
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel).clickable { selectedPlaylist = name }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.QueueMusic, null, tint = Accent, modifier = Modifier.size(34.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(name, color = White, fontWeight = FontWeight.SemiBold)
                                            Text("${playlists[name]?.size ?: 0} faixas", color = Muted, fontSize = 12.sp)
                                        }
                                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                                    }
                                }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (section == "Playlists") {
                                IconButton(onClick = { selectedPlaylist = "" }) { Icon(Icons.Default.ArrowBack, "Voltar às playlists", tint = Accent) }
                            }
                            Text(if (section == "Músicas") "Sua biblioteca" else if (section == "Playlists") selectedPlaylist else section, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(filtered.size.toString() + " faixas", color = Muted, fontSize = 12.sp)
                            IconButton(onClick = { songs = loadSongs() }) { Icon(Icons.Default.Refresh, "Atualizar", tint = Accent) }
                        }
                        if (section == "Artistas" || section == "Álbuns") {
                            val groups = if (section == "Artistas") filtered.groupBy { it.artist }.toSortedMap() else filtered.groupBy { it.album }.toSortedMap()
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(groups.keys.toList()) { group ->
                                    val first = groups[group]?.firstOrNull()
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel).clickable { playSongs(songs, first, player) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (section == "Artistas") Icons.Default.Person else Icons.Default.Album, null, tint = Accent, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(group.ifBlank { "Desconhecido" }, color = White, fontWeight = FontWeight.SemiBold)
                                            Text((groups[group]?.size ?: 0).toString() + " faixas", color = Muted, fontSize = 12.sp)
                                        }
                                        Icon(Icons.Default.PlayArrow, null, tint = Accent)
                                    }
                                }
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(filtered, key = { it.uri.toString() }) { song ->
                                    val fav = favorites.contains(song.uri.toString())
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(if (current?.uri == song.uri) Color(0xFF321A28) else Panel).clickable { playSongs(if (section == "Playlists") filtered else songs, song, player) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(49.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF30202A)), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(27.dp))
                                        }
                                        Spacer(Modifier.width(11.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(song.title, color = White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(song.album, color = Muted.copy(alpha = .75f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = {
                                            val updated = favorites.toMutableSet()
                                            if (fav) updated.remove(song.uri.toString()) else updated.add(song.uri.toString())
                                            favorites = updated
                                            saveFavorites(updated)
                                        }) { Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (fav) "Remover favorito" else "Favoritar", tint = if (fav) Accent else Muted) }
                                        IconButton(onClick = { playlistDialogSong = song }) { Icon(Icons.Default.PlaylistAdd, "Adicionar à playlist", tint = Muted) }
                                        IconButton(onClick = { playSongs(if (section == "Playlists") filtered else songs, song, player) }) { Icon(Icons.Default.PlayArrow, "Tocar", tint = Accent) }
                                    }
                                }
                            }
                        }
                    }

                    if (current != null) {
                        Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Album, null, tint = Accent, modifier = Modifier.size(35.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(current.title, color = White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(current.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "Anterior", tint = White) }
                                IconButton(onClick = { if (player?.isPlaying == true) player?.pause() else player?.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Accent) }
                                IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Próxima", tint = White) }
                            }
                            Slider(value = if (dur > 0) pos.toFloat().coerceIn(0f, dur.toFloat()) else 0f, onValueChange = { pos = it.toLong() }, onValueChangeFinished = { player?.seekTo(pos) }, valueRange = 0f..(dur.coerceAtLeast(1L).toFloat()), modifier = Modifier.fillMaxWidth().height(24.dp), colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0xFF343440)))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(pos), color = Muted, fontSize = 10.sp)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { player?.shuffleModeEnabled = !(player?.shuffleModeEnabled ?: false) }) { Icon(Icons.Default.Shuffle, "Aleatório", tint = if (shuffle) Accent else Muted) }
                                IconButton(onClick = { player?.repeatMode = when (player?.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }) { Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repetição", tint = if (repeat == Player.REPEAT_MODE_OFF) Muted else Accent) }
                                IconButton(onClick = { showTimer = true }) { Icon(Icons.Default.Timer, "Temporizador", tint = if (timerEndsAt > 0L) Accent else Muted) }
                                Text(formatTime(dur), color = Muted, fontSize = 10.sp)
                            }
                            if (timerEndsAt > 0L) Text("Temporizador: ${timerRemaining / 60000} min restantes", color = Accent, fontSize = 11.sp)
                        }
                    }

                    NavigationBar(containerColor = Bg, tonalElevation = 0.dp) {
                        listOf("Músicas", "Artistas", "Álbuns", "Favoritas", "Playlists").forEach { name ->
                            NavigationBarItem(selected = section == name, onClick = { section = name; selectedPlaylist = "" }, icon = { Icon(when (name) { "Artistas" -> Icons.Default.Person; "Álbuns" -> Icons.Default.Album; "Favoritas" -> Icons.Default.Favorite; "Playlists" -> Icons.Default.QueueMusic; else -> Icons.Default.LibraryMusic }, null) }, label = { Text(when (name) { "Músicas" -> "Biblioteca"; "Favoritas" -> "Favoritos"; else -> name }, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Panel))
                        }
                    }
                }
            }
        }

        if (showCreatePlaylist) {
            AlertDialog(onDismissRequest = { showCreatePlaylist = false }, title = { Text("Nova playlist") }, text = {
                OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text("Nome da playlist") }, singleLine = true)
            }, confirmButton = {
                TextButton(onClick = {
                    val name = newPlaylistName.trim()
                    if (name.isNotEmpty()) {
                        val updated = playlists.toMutableMap()
                        updated.putIfAbsent(name, emptySet())
                        playlists = updated
                        savePlaylists(updated)
                        selectedPlaylist = name
                        section = "Playlists"
                        newPlaylistName = ""
                        showCreatePlaylist = false
                    }
                }) { Text("Criar") }
            }, dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancelar") } })
        }

        playlistDialogSong?.let { song ->
            AlertDialog(onDismissRequest = { playlistDialogSong = null }, title = { Text("Adicionar à playlist") }, text = {
                Column {
                    if (playlists.isEmpty()) Text("Você ainda não tem playlists.", color = Muted)
                    playlists.keys.sorted().forEach { name ->
                        TextButton(onClick = {
                            val updated = playlists.toMutableMap()
                            updated[name] = (updated[name] ?: emptySet()) + song.uri.toString()
                            playlists = updated
                            savePlaylists(updated)
                            playlistDialogSong = null
                        }, modifier = Modifier.fillMaxWidth()) { Text(name, color = White) }
                    }
                    TextButton(onClick = { playlistDialogSong = null; showCreatePlaylist = true }) { Text("+ Criar playlist", color = Accent) }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { playlistDialogSong = null }) { Text("Fechar") } })
        }

        if (showTimer) {
            AlertDialog(onDismissRequest = { showTimer = false }, title = { Text("Temporizador de sono") }, text = {
                Column {
                    Text("Pausar a música automaticamente após:", color = Muted)
                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                        TextButton(onClick = {
                            timerMinutes = minutes
                            timerEndsAt = System.currentTimeMillis() + minutes * 60_000L
                            showTimer = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("$minutes minutos", color = White) }
                    }
                    if (timerEndsAt > 0L) TextButton(onClick = { timerEndsAt = 0L; timerMinutes = 0; timerRemaining = 0L; showTimer = false }) { Text("Cancelar temporizador", color = Accent) }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { showTimer = false }) { Text("Fechar") } })
        }
    }

    private fun playSongs(all: List<Song>, selected: Song?, p: MediaController?) {
        if (selected == null || p == null) return
        val i = all.indexOfFirst { it.uri == selected.uri }
        if (i < 0) return
        val items = all.map { MediaItem.Builder().setUri(it.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).setAlbumTitle(it.album).build()).build() }
        p.setMediaItems(items, i, 0L)
        p.prepare()
        p.play()
    }

    private fun loadSongs(): List<Song> {
        val out = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.IS_MUSIC)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val d = c.getLong(duration)
                if (d > 0) out.add(Song(c.getString(title)?.takeIf { it.isNotBlank() } ?: "Faixa sem título", c.getString(artist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artista desconhecido", c.getString(album)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Álbum desconhecido", d, ContentUris.withAppendedId(collection, c.getLong(id))))
            }
        }
        return out
    }

    private fun loadYoutubeKey(): String = getSharedPreferences("player_preferences", MODE_PRIVATE).getString("youtube_api_key", "") ?: ""
    private fun saveYoutubeKey(value: String) { getSharedPreferences("player_preferences", MODE_PRIVATE).edit().putString("youtube_api_key", value).apply() }

    private suspend fun searchYoutube(query: String, apiKey: String): List<YoutubeVideo> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=15&q=$encoded&key=${URLEncoder.encode(apiKey, "UTF-8")}")
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 12000; readTimeout = 12000 }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("YouTube API returned $code: $body")
            val root = JSONObject(body); val items = root.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).mapNotNull { i -> val item = items.optJSONObject(i) ?: return@mapNotNull null; val id = item.optJSONObject("id")?.optString("videoId").orEmpty(); val snippet = item.optJSONObject("snippet") ?: return@mapNotNull null; if (id.isBlank()) null else YoutubeVideo(id, snippet.optString("title", "Vídeo"), snippet.optString("channelTitle", "Canal"), snippet.optString("description", "")) }
        } finally { connection.disconnect() }
    }

    private fun loadFavorites(): Set<String> = getSharedPreferences("player_preferences", MODE_PRIVATE).getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    private fun saveFavorites(values: Set<String>) { getSharedPreferences("player_preferences", MODE_PRIVATE).edit().putStringSet("favorites", values.toSet()).apply() }

    private fun loadPlaylists(): Map<String, Set<String>> {
        val prefs = getSharedPreferences("player_preferences", MODE_PRIVATE)
        val names = prefs.getStringSet("playlist_names", emptySet())?.toSet() ?: emptySet()
        return names.associateWith { prefs.getStringSet("playlist_$it", emptySet())?.toSet() ?: emptySet() }
    }
    private fun savePlaylists(values: Map<String, Set<String>>) {
        val prefs = getSharedPreferences("player_preferences", MODE_PRIVATE).edit()
        prefs.putStringSet("playlist_names", values.keys.toSet())
        values.forEach { (name, uris) -> prefs.putStringSet("playlist_$name", uris.toSet()) }
        prefs.apply()
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
