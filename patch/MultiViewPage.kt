package com.salar.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private val MultiGold = Color(0xFFD6AD62)
private val MultiPanel = Color(0xFF171B26)
private val MultiTextMain = Color(0xFFF5F1E8)
private val MultiSecondaryText = Color.LightGray
private val MultiMutedText = Color.Gray

@Composable
fun MultiViewPage(channels: List<Channel>) {
    val context = LocalContext.current
    val tvChannels = remember(channels) {
        channels.filter { it.url.isNotBlank() && it.kind != "رادیو" }
            .distinctBy { it.url }
            .sortedBy { it.name.lowercase() }
    }
    val players = remember { List(4) { ExoPlayer.Builder(context).build() } }
    val selectedChannels = remember { mutableStateListOf<Channel?>(null, null, null, null) }
    var visibleSlots by remember { mutableIntStateOf(2) }
    var activeAudioSlot by remember { mutableIntStateOf(0) }
    var choosingSlot by remember { mutableIntStateOf(-1) }
    var fullScreenSlot by remember { mutableIntStateOf(-1) }
    var channelQuery by remember { mutableStateOf("") }
    val filteredChannels = remember(tvChannels, channelQuery) {
        val query = channelQuery.trim()
        if (query.isEmpty()) tvChannels else tvChannels.filter { channel ->
            channel.name.contains(query, ignoreCase = true) ||
                channel.country.contains(query, ignoreCase = true) ||
                channel.groupTitle.contains(query, ignoreCase = true)
        }
    }

    DisposableEffect(players) {
        onDispose {
            players.forEach { player ->
                player.playWhenReady = false
                player.release()
            }
        }
    }
    LaunchedEffect(activeAudioSlot) {
        players.forEachIndexed { index, player ->
            player.volume = if (index == activeAudioSlot) 1f else 0f
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("پخش هم‌زمان شبکه‌ها", color = MultiGold, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("هر پنجره پخش‌کننده مستقل دارد؛ صدای یک پنجره در هر لحظه فعال است.",
                    color = MultiSecondaryText, fontSize = 11.sp)
            }
            if (fullScreenSlot >= 0) {
                IconButton(onClick = { fullScreenSlot = -1 }) {
                    Icon(Icons.Default.ArrowBack, "بازگشت", tint = MultiGold)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = visibleSlots == 2, onClick = { visibleSlots = 2 }, label = { Text("۲ شبکه") })
                    FilterChip(selected = visibleSlots == 4, onClick = { visibleSlots = 4 }, label = { Text("۴ شبکه") })
                }
            }
        }

        if (fullScreenSlot >= 0) {
            MultiViewTile(
                slot = fullScreenSlot, channel = selectedChannels[fullScreenSlot],
                player = players[fullScreenSlot], isAudioActive = activeAudioSlot == fullScreenSlot,
                onChoose = { choosingSlot = fullScreenSlot }, onAudio = { activeAudioSlot = fullScreenSlot },
                onFullscreen = { fullScreenSlot = -1 }, modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else if (visibleSlots == 2) {
            for (slot in 0..1) {
                MultiViewTile(
                    slot = slot, channel = selectedChannels[slot], player = players[slot],
                    isAudioActive = activeAudioSlot == slot, onChoose = { choosingSlot = slot },
                    onAudio = { activeAudioSlot = slot }, onFullscreen = { fullScreenSlot = slot },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        } else {
            for (row in 0..1) {
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in 0..1) {
                        val slot = row * 2 + column
                        MultiViewTile(
                            slot = slot, channel = selectedChannels[slot], player = players[slot],
                            isAudioActive = activeAudioSlot == slot, onChoose = { choosingSlot = slot },
                            onAudio = { activeAudioSlot = slot }, onFullscreen = { fullScreenSlot = slot },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }
        Text(
            "اجرای هم‌زمان ۴ جریان به اینترنت و توان پردازشی گوشی وابسته است؛ بعضی منابع نیز اتصال هم‌زمان را محدود می‌کنند.",
            color = MultiMutedText, fontSize = 10.sp
        )
    }

    if (choosingSlot >= 0) {
        val targetSlot = choosingSlot
        AlertDialog(
            onDismissRequest = { choosingSlot = -1 },
            title = { Text("انتخاب شبکه برای پنجره ${targetSlot + 1}") },
            text = {
                if (tvChannels.isEmpty()) {
                    Text("هنوز شبکه تلویزیونی در فهرست وجود ندارد.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = channelQuery,
                            onValueChange = { channelQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("جستجوی نام شبکه") },
                            placeholder = { Text("نام فارسی یا انگلیسی، کشور یا دسته") }
                        )
                        LazyColumn(modifier = Modifier.heightIn(max = 390.dp)) {
                            items(filteredChannels, key = { it.url }) { channel ->
                                val alreadyInAnotherTile = selectedChannels.withIndex().any {
                                    it.index != targetSlot && it.value?.url == channel.url
                                }
                                TextButton(
                                    enabled = !alreadyInAnotherTile,
                                    onClick = {
                                        selectedChannels[targetSlot] = channel
                                        val player = players[targetSlot]
                                        player.stop()
                                        player.clearMediaItems()
                                        player.setMediaItem(MediaItem.fromUri(XtreamCredentialVault.resolve(context, channel.url)))
                                        player.prepare()
                                        player.playWhenReady = true
                                        channelQuery = ""
                                        choosingSlot = -1
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(channel.name, color = if (alreadyInAnotherTile) MultiMutedText else MultiTextMain,
                                            fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (alreadyInAnotherTile) "این شبکه در پنجره دیگری انتخاب شده است"
                                            else listOf(channel.country, channel.groupTitle).filter { it.isNotBlank() }.joinToString(" • "),
                                            color = MultiMutedText, fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                            if (filteredChannels.isEmpty()) {
                                item { Text("شبکه‌ای با این عبارت پیدا نشد.", color = MultiMutedText) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingSlot = -1 }) { Text("بستن") } }
        )
    }
}

@Composable
private fun MultiViewTile(
    slot: Int,
    channel: Channel?,
    player: ExoPlayer,
    isAudioActive: Boolean,
    onChoose: () -> Unit,
    onAudio: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MultiPanel)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(channel?.name ?: "پنجره ${slot + 1}", color = MultiTextMain, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = onAudio, modifier = Modifier.size(32.dp)) {
                    Icon(if (isAudioActive) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "انتخاب صدای فعال", tint = if (isAudioActive) MultiGold else MultiMutedText)
                }
                IconButton(onClick = onFullscreen, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "تمام‌صفحه", tint = MultiGold)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                contentAlignment = Alignment.Center) {
                if (channel != null) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                        update = { it.player = player },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("برای انتخاب شبکه، دکمه پایین را بزنید", color = Color.White, fontSize = 12.sp)
                }
            }
            TextButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                Text(if (channel == null) "انتخاب شبکه" else "تغییر شبکه", color = MultiGold, fontSize = 11.sp)
            }
        }
    }
}
