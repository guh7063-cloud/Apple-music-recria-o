package com.hoppe.applelocalplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

private val Bg = Color(0xFF09090D)
private val CardBg = Color(0xFF1B1B22)
private val Accent = Color(0xFFFF375F)
private val Muted = Color(0xFFAAAAB5)

data class Song(val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { PlayerScreen() }
    }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    @Composable
    private fun PlayerScreen() {
        var songs by remember { mutableStateOf(emptyList<Song>()) }
        var query by remember { mutableStateOf("") }
        var current by remember { mutableIntStateOf(-1) }
        var playing by remember { mutableStateOf(false) }
        var permissionGranted by remember { mutableStateOf(false) }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
            if (granted) songs = loadSongs()
        }
        LaunchedEffect(Unit) {
            permissionGranted = ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED
            if (permissionGranted) songs = loadSongs()
        }
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = CardBg)) {
            Surface(Modifier.fillMaxSize(), color = Bg) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(30.dp))
                    Text("MUSIC", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text("Sua biblioteca.", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Toda a sua música, no seu ritmo.", color = Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(22.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Buscar músicas", color = Muted) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                        singleLine = true, shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = CardBg, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tocadas no dispositivo", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { songs = loadSongs() }) { Icon(Icons.Default.Refresh, "Atualizar", tint = Accent) }
                    }
                    if (!permissionGranted) {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.LibraryMusic, null, tint = Accent, modifier = Modifier.size(52.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Encontre suas músicas", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Permita o acesso aos arquivos de áudio do celular.", color = Muted)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { launcher.launch(permission) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Permitir acesso") }
                        }
                    } else {
                        val filtered = songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
                        Text("${filtered.size} músicas", color = Muted, fontSize = 12.sp)
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(filtered) { _, song ->
                                Row(
                                    Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(15.dp)).clickable {
                                        val index = songs.indexOf(song)
                                        if (index >= 0) {
                                            current = index
                                            player?.setMediaItems(songs.map { MediaItem.fromUri(it.uri) }, index, 0L)
                                            player?.prepare(); player?.play(); playing = true
                                        }
                                    }.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(48.dp).background(Color(0xFF30202A), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(26.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = {
                                        val index = songs.indexOf(song)
                                        if (index >= 0) { current = index; player?.setMediaItems(songs.map { MediaItem.fromUri(it.uri) }, index, 0L); player?.prepare(); player?.play(); playing = true }
                                    }) { Icon(Icons.Default.PlayArrow, "Tocar", tint = Accent) }
                                }
                            }
                        }
                        if (songs.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("Nenhum áudio encontrado neste dispositivo.", color = Muted)
                            }
                        }
                    }
                    if (current in songs.indices) {
                        Row(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(18.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Album, null, tint = Accent, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(songs[current].title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(songs[current].artist, color = Muted, fontSize = 12.sp)
                            }
                            IconButton(onClick = {
                                if (player?.isPlaying == true) { player?.pause(); playing = false }
                                else { player?.play(); playing = true }
                            }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Accent) }
                            IconButton(onClick = { player?.seekToNextMediaItem(); current = (current + 1).coerceAtMost(songs.lastIndex); playing = true }) {
                                Icon(Icons.Default.SkipNext, "Próxima", tint = Color.White)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    private fun loadSongs(): List<Song> {
        val list = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.IS_MUSIC)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                list.add(Song(cursor.getString(titleCol) ?: "Faixa sem título", cursor.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Artista desconhecido", ContentUris.withAppendedId(collection, id)))
            }
        }
        return list
    }
}
