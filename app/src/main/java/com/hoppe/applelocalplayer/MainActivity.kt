package com.hoppe.applelocalplayer

import android.Manifest
import android.content.ContentUris
import android.content.ComponentName
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private val Bg = Color(0xFF09090D)
private val Panel = Color(0xFF191922)
private val Accent = Color(0xFFFF375F)
private val Muted = Color(0xFFA5A5B3)
private val White = Color(0xFFF8F8FC)

data class Song(val title: String, val artist: String, val album: String, val duration: Long, val uri: Uri)

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); window.statusBarColor = android.graphics.Color.rgb(9,9,13); window.navigationBarColor = android.graphics.Color.rgb(9,9,13); setContent { PlayerScreen() } }
 @Composable private fun PlayerScreen() {
  var songs by remember { mutableStateOf(emptyList<Song>()) }; var query by remember { mutableStateOf("") }; var section by remember { mutableStateOf("Músicas") }
  var favorites by remember { mutableStateOf(loadFavorites()) }; var granted by remember { mutableStateOf(false) }; var player by remember { mutableStateOf<MediaController?>(null) }
  var index by remember { mutableIntStateOf(-1) }; var playing by remember { mutableStateOf(false) }; var pos by remember { mutableLongStateOf(0L) }; var dur by remember { mutableLongStateOf(0L) }
  var shuffle by remember { mutableStateOf(false) }; var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
  val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) songs = loadSongs() }
  LaunchedEffect(Unit) { granted = ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED; if (granted) songs = loadSongs() }
  DisposableEffect(Unit) {
   val token = SessionToken(this@MainActivity, ComponentName(this@MainActivity, MusicPlaybackService::class.java)); val future: ListenableFuture<MediaController> = MediaController.Builder(this@MainActivity, token).buildAsync()
   future.addListener({ runCatching { player = future.get() } }, ContextCompat.getMainExecutor(this@MainActivity)); onDispose { MediaController.releaseFuture(future) }
  }
  DisposableEffect(player) {
   val p = player ?: return@DisposableEffect onDispose { }
   val listener = object : Player.Listener {
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { index = p.currentMediaItemIndex; dur = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L }
    override fun onIsPlayingChanged(value: Boolean) { playing = value }
    override fun onRepeatModeChanged(value: Int) { repeat = value }
    override fun onShuffleModeEnabledChanged(value: Boolean) { shuffle = value }
   }; p.addListener(listener); onDispose { p.removeListener(listener) }
  }
  LaunchedEffect(player) { while (true) { player?.let { pos = it.currentPosition.coerceAtLeast(0L); dur = it.duration.takeIf { d -> d > 0 && d != C.TIME_UNSET } ?: 0L; index = it.currentMediaItemIndex; playing = it.isPlaying }; delay(400) } }
  val current = songs.getOrNull(index)
  val filtered = when(section) { "Favoritas" -> songs.filter { favorites.contains(it.uri.toString()) }; "Artistas" -> songs.filter { query.isBlank() || it.artist.contains(query,true) }; "Álbuns" -> songs.filter { query.isBlank() || it.album.contains(query,true) }; else -> songs.filter { it.title.contains(query,true) || it.artist.contains(query,true) || it.album.contains(query,true) } }
  MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
   Surface(Modifier.fillMaxSize(), color = Bg) { Column(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF321A28), Bg))).padding(start=20.dp,end=20.dp,top=18.dp,bottom=14.dp)) {
     Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("THREE EIGHTY FIVE MUSIC", color=Accent, fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=3.sp); Text("Sua música.", color=White, fontSize=32.sp, fontWeight=FontWeight.ExtraBold); Text("Ouça do seu jeito.", color=Muted) }; Icon(Icons.Default.GraphicEq,null,tint=Accent,modifier=Modifier.size(46.dp)) }
     Spacer(Modifier.height(15.dp))
     OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Buscar faixa, artista ou álbum",color=Muted)},leadingIcon={Icon(Icons.Default.Search,null,tint=Muted)},singleLine=true,shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Accent,unfocusedBorderColor=Panel,focusedTextColor=White,unfocusedTextColor=White,cursorColor=Accent,focusedContainerColor=Panel,unfocusedContainerColor=Panel))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.SpaceEvenly) { listOf("Músicas","Artistas","Álbuns","Favoritas").forEach { name -> TextButton(onClick={section=name},colors=ButtonDefaults.textButtonColors(contentColor=if(section==name) Accent else Muted)) { Text(name,fontSize=12.sp,fontWeight=if(section==name) FontWeight.Bold else FontWeight.Normal) } } }
    if (!granted) { Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) { Icon(Icons.Default.LibraryMusic,null,tint=Accent,modifier=Modifier.size(58.dp)); Spacer(Modifier.height(12.dp)); Text("Sua biblioteca começa aqui",color=White,fontSize=21.sp,fontWeight=FontWeight.Bold); Text("Permita o acesso às músicas salvas no celular.",color=Muted); Spacer(Modifier.height(18.dp)); Button(onClick={launcher.launch(permission)},colors=ButtonDefaults.buttonColors(containerColor=Accent)) { Text("Encontrar minhas músicas") } } }
    else {
     Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically) { Text(if(section=="Músicas") "Sua biblioteca" else section,color=White,fontSize=19.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.weight(1f)); Text(filtered.size.toString()+" faixas",color=Muted,fontSize=12.sp); IconButton(onClick={songs=loadSongs()}) { Icon(Icons.Default.Refresh,"Atualizar",tint=Accent) } }
     if(section=="Artistas" || section=="Álbuns") {
      val groups = if(section=="Artistas") filtered.groupBy{it.artist}.toSortedMap() else filtered.groupBy{it.album}.toSortedMap()
      LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { items(groups.keys.toList()) { group -> val first=groups[group]?.firstOrNull(); Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel).clickable { playSongs(songs,first,player) }.padding(14.dp),verticalAlignment=Alignment.CenterVertically) { Icon(if(section=="Artistas") Icons.Default.Person else Icons.Default.Album,null,tint=Accent,modifier=Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(group.ifBlank{"Desconhecido"},color=White,fontWeight=FontWeight.SemiBold); Text((groups[group]?.size ?: 0).toString()+" faixas",color=Muted,fontSize=12.sp) }; Icon(Icons.Default.PlayArrow,null,tint=Accent) } } }
     } else {
      LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { items(filtered,key={it.uri.toString()}) { song -> val fav=favorites.contains(song.uri.toString()); Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(if(current?.uri==song.uri) Color(0xFF321A28) else Panel).clickable { playSongs(songs,song,player) }.padding(10.dp),verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(49.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF30202A)),contentAlignment=Alignment.Center) { Icon(Icons.Default.MusicNote,null,tint=Accent,modifier=Modifier.size(27.dp)) }; Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text(song.title,color=White,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis); Text(song.artist,color=Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis); Text(song.album,color=Muted.copy(alpha=.75f),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }; IconButton(onClick={val updated=favorites.toMutableSet(); if(fav) updated.remove(song.uri.toString()) else updated.add(song.uri.toString()); favorites=updated; saveFavorites(updated)}) { Icon(if(fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,if(fav) "Remover favorito" else "Favoritar",tint=if(fav) Accent else Muted) }; IconButton(onClick={playSongs(songs,song,player)}) { Icon(Icons.Default.PlayArrow,"Tocar",tint=Accent) } } } }
     }
    }
    if(current!=null) { Column(Modifier.fillMaxWidth().background(Panel).clickable { }.padding(horizontal=14.dp,vertical=8.dp)) { Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.Album,null,tint=Accent,modifier=Modifier.size(35.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(current.title,color=White,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis); Text(current.artist,color=Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }; IconButton(onClick={player?.seekToPreviousMediaItem()}) { Icon(Icons.Default.SkipPrevious,"Anterior",tint=White) }; IconButton(onClick={if(player?.isPlaying==true) player?.pause() else player?.play()}) { Icon(if(playing) Icons.Default.Pause else Icons.Default.PlayArrow,"Play/Pause",tint=Accent) }; IconButton(onClick={player?.seekToNextMediaItem()}) { Icon(Icons.Default.SkipNext,"Próxima",tint=White) } }; LinearProgressIndicator(progress={if(dur>0) (pos.toFloat()/dur).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth().height(2.dp),color=Accent,trackColor=Color(0xFF343440)) } }
    NavigationBar(containerColor=Bg,tonalElevation=0.dp) { listOf("Músicas","Artistas","Álbuns","Favoritas").forEach { name -> NavigationBarItem(selected=section==name,onClick={section=name},icon={Icon(when(name){"Artistas"->Icons.Default.Person;"Álbuns"->Icons.Default.Album;"Favoritas"->Icons.Default.Favorite;else->Icons.Default.LibraryMusic},null)},label={Text(when(name){"Músicas"->"Biblioteca";"Favoritas"->"Favoritos";else->name},fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Accent,selectedTextColor=Accent,indicatorColor=Panel)) } }
   } }
  }
 }
 private fun playSongs(all:List<Song>,selected:Song?,p:MediaController?) { if(selected==null||p==null)return; val i=all.indexOfFirst{it.uri==selected.uri}; if(i<0)return; val items=all.map{MediaItem.Builder().setUri(it.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).setAlbumTitle(it.album).build()).build()}; p.setMediaItems(items,i,0L); p.prepare(); p.play() }
 private fun loadSongs():List<Song> { val out=mutableListOf<Song>(); val collection=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; val projection=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM,MediaStore.Audio.Media.DURATION,MediaStore.Audio.Media.IS_MUSIC); val selection="${MediaStore.Audio.Media.IS_MUSIC} != 0"; contentResolver.query(collection,projection,selection,null,"${MediaStore.Audio.Media.TITLE} ASC")?.use{c-> val id=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val title=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val artist=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val album=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM); val duration=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION); while(c.moveToNext()){val d=c.getLong(duration); if(d>0) out.add(Song(c.getString(title)?.takeIf{it.isNotBlank()}?:"Faixa sem título",c.getString(artist)?.takeIf{it.isNotBlank()&&it!="<unknown>"}?:"Artista desconhecido",c.getString(album)?.takeIf{it.isNotBlank()&&it!="<unknown>"}?:"Álbum desconhecido",d,ContentUris.withAppendedId(collection,c.getLong(id))))} }; return out }
 private fun loadFavorites():Set<String> = getSharedPreferences("player_preferences",MODE_PRIVATE).getStringSet("favorites",emptySet())?.toSet()?:emptySet()
 private fun saveFavorites(values:Set<String>) { getSharedPreferences("player_preferences",MODE_PRIVATE).edit().putStringSet("favorites",values.toSet()).apply() }
}
