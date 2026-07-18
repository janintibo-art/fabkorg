package com.beatbox.midirecorder

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatbox.midirecorder.midi.MidiEngine
import com.beatbox.midirecorder.midi.MidiFileWriter
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ----- Palette dynamique : console sombre OU rose ER-1 (comme la machine du pote) -----
private var pinkMode by mutableStateOf(false)

private val Bg: Color get() = if (pinkMode) Color(0xFF190A11) else Color(0xFF0B0C10)
private val Panel: Color get() = if (pinkMode) Color(0xFF261019) else Color(0xFF14161C)
private val PanelLine: Color get() = if (pinkMode) Color(0xFF43202F) else Color(0xFF232733)
private val Ink: Color get() = if (pinkMode) Color(0xFFFFE9F2) else Color(0xFFE8EAF0)
private val InkDim: Color get() = if (pinkMode) Color(0xFFB37E96) else Color(0xFF7A8194)
private val RecRed = Color(0xFFFF3B4A)
private val PlayGreen = Color(0xFF3DDC84)
private val Amber: Color get() = if (pinkMode) Color(0xFFFF4F9A) else Color(0xFFFF9F1C)

/** 16 teintes, une par canal, pour repérer chaque piste d'un coup d'œil. */
private val ChannelColors = List(16) { i ->
    Color.hsv(hue = (i * 360f / 16f), saturation = 0.72f, value = 0.95f)
}

class MainActivity : ComponentActivity() {

    private lateinit var engine: MidiEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = MidiEngine(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel)) {
                AppScreen(engine, onExport = { exportMidi() })
            }
        }
    }

    private fun exportMidi() {
        val hasEvents = engine.trackEvents.any { it.isNotEmpty() }
        if (!hasEvents) {
            Toast.makeText(this, "Rien à exporter : enregistrez d'abord.", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "take_$stamp.mid")
        runCatching {
            MidiFileWriter.write(file, engine.trackEvents, engine.bpm.value)
        }.onSuccess {
            Toast.makeText(this, "Exporté : ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "Échec de l'export : ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}

@Composable
private fun AppScreen(engine: MidiEngine, onExport: () -> Unit) {
    val devices by engine.devices.collectAsState()
    val connected by engine.connectedName.collectAsState()
    val recording by engine.isRecording.collectAsState()
    val playing by engine.isPlaying.collectAsState()
    val elapsed by engine.elapsedNanos.collectAsState()
    val tracks by engine.tracks.collectAsState()
    val bpm by engine.bpm.collectAsState()
    val revision by engine.revision.collectAsState()
    val status by engine.statusMessage.collectAsState()
    val lastSignal by engine.lastSignalMs.collectAsState()
    val metro by engine.metroTick.collectAsState()
    val countIn by engine.countIn.collectAsState()
    val overdubOn by engine.overdub.collectAsState()
    val noteMode by engine.noteMode.collectAsState()
    val noteTracks by engine.noteTracks.collectAsState()

    // Horloge UI pour faire pâlir les LED d'activité.
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = android.os.SystemClock.uptimeMillis()
            delay(50)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp)
    ) {
        Header(devices.size, connected, status, lastSignal, nowMs, engine)
        Spacer(Modifier.height(10.dp))
        Transport(
            recording = recording,
            playing = playing,
            elapsedNanos = elapsed,
            bpm = bpm,
            connected = connected != null,
            metroOn = metro,
            countIn = countIn,
            overdubOn = overdubOn,
            onRec = { if (recording) engine.stopRecording() else engine.startRecordingWithCountIn() },
            onPlay = { if (playing) engine.stopPlayback() else engine.startPlayback() },
            onClear = { engine.clearAll() },
            onExport = onExport,
            onOverdub = { engine.toggleOverdub() },
            onBpm = { engine.bpm.value = it.coerceIn(20, 300) }
        )
        Spacer(Modifier.height(10.dp))
        // Sélecteur de mode : par canaux (classique) ou par notes (ER-1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PISTES", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
            )
            ModeChip("CANAUX", selected = !noteMode) { if (noteMode) engine.toggleNoteMode() }
            Spacer(Modifier.width(6.dp))
            ModeChip("ER-1", selected = noteMode) { if (!noteMode) engine.toggleNoteMode() }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (noteMode) {
                if (noteTracks.isEmpty()) {
                    item {
                        Text(
                            "Mode ER-1 : chaque son (kick, snare, hi-hat…) aura sa piste.\nLance REC et joue un pattern : les pistes apparaîtront ici.",
                            color = InkDim, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
                items(noteTracks, key = { it.note }) { t ->
                    NoteTrackRow(
                        state = t,
                        color = ChannelColors[t.note % 16],
                        engine = engine,
                        lengthNanos = maxOf(engine.lengthNanos, elapsed, 1L),
                        nowMs = nowMs,
                        revision = revision,
                        onMute = { engine.toggleNoteMute(t.note) },
                        onSolo = { engine.toggleNoteSolo(t.note) }
                    )
                }
            } else {
                items(tracks, key = { it.channel }) { t ->
                    TrackRow(
                        state = t,
                        color = ChannelColors[t.channel],
                        events = engine.trackEvents[t.channel],
                        lengthNanos = maxOf(engine.lengthNanos, elapsed, 1L),
                        nowMs = nowMs,
                        revision = revision,
                        onMute = { engine.toggleMute(t.channel) },
                        onSolo = { engine.toggleSolo(t.channel) }
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun Header(
    deviceCount: Int,
    connected: String?,
    status: String?,
    lastSignal: Long,
    nowMs: Long,
    engine: MidiEngine
) {
    var menuOpen by remember { mutableStateOf(false) }
    // LED "signal" : brille fort si un octet MIDI est arrivé il y a moins de 150 ms.
    val signalOn = nowMs - lastSignal < 150
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "FAB — LA GROSSE BASSE",
                    color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                // Pastille "signal reçu"
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (signalOn) PlayGreen else PanelLine)
                )
            }
            Text(
                status ?: connected ?: "Aucun appareil connecté",
                color = if (connected != null) PlayGreen else InkDim,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }
        // Interrupteur thème rose ER-1
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(if (pinkMode) Color(0xFFFF4F9A) else PanelLine)
                .clickable { pinkMode = !pinkMode }
        ) {
            Text("R", color = if (pinkMode) Color(0xFF190A11) else InkDim,
                fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
        Box {
            OutlinedButton(
                onClick = { engine.refreshDevices(); menuOpen = true },
                shape = RoundedCornerShape(10.dp),
                border = ButtonDefaults.outlinedButtonBorder,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
            ) {
                Text(if (connected != null) "Changer" else "Connecter ($deviceCount)", fontSize = 13.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val list = engine.devices.value
                if (list.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Aucun appareil USB/BT détecté") },
                        onClick = { menuOpen = false }
                    )
                }
                list.forEach { info ->
                    DropdownMenuItem(
                        text = { Text(engine.deviceLabel(info)) },
                        onClick = { engine.connect(info); menuOpen = false }
                    )
                }
                if (connected != null) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Déconnecter", color = RecRed) },
                        onClick = { engine.disconnect(); menuOpen = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun Transport(
    recording: Boolean,
    playing: Boolean,
    elapsedNanos: Long,
    bpm: Int,
    connected: Boolean,
    metroOn: Boolean,
    countIn: Int,
    overdubOn: Boolean,
    onRec: () -> Unit,
    onPlay: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onOverdub: () -> Unit,
    onBpm: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .border(1.dp, PanelLine, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // Point de métronome + compteur (ou compte à rebours)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (metroOn) Amber else PanelLine)
            )
            Spacer(Modifier.width(14.dp))
            if (countIn > 0) {
                Text(
                    "· $countIn ·",
                    color = Amber, fontSize = 40.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            } else {
                val totalMs = elapsedNanos / 1_000_000
                val min = totalMs / 60_000
                val sec = (totalMs / 1000) % 60
                val cent = (totalMs / 10) % 100
                Text(
                    String.format(Locale.US, "%02d:%02d.%02d", min, sec, cent),
                    color = if (recording) RecRed else Ink,
                    fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // REC
            PadButton(
                label = if (recording) "STOP" else "REC",
                active = recording,
                activeColor = RecRed,
                enabled = connected || recording,
                modifier = Modifier.weight(1f),
                onClick = onRec
            )
            // PLAY
            PadButton(
                label = if (playing) "STOP" else "PLAY",
                active = playing,
                activeColor = PlayGreen,
                enabled = connected && !recording,
                modifier = Modifier.weight(1f),
                onClick = onPlay
            )
            // BPM + Tap
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("BPM", color = InkDim, fontSize = 10.sp, letterSpacing = 2.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Stepper("−") { onBpm(bpm - 1) }
                    Text(
                        "$bpm", color = Amber, fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    Stepper("+") { onBpm(bpm + 1) }
                }
                Spacer(Modifier.height(4.dp))
                // Tap tempo : moyenne des intervalles entre les 4 derniers taps
                val taps = remember { mutableStateListOf<Long>() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PanelLine)
                        .clickable {
                            val now = System.currentTimeMillis()
                            if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                            taps.add(now)
                            while (taps.size > 4) taps.removeAt(0)
                            if (taps.size >= 2) {
                                val gaps = taps.zipWithNext { a, b -> b - a }
                                val avg = gaps.average()
                                if (avg > 0) onBpm((60000.0 / avg).toInt())
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text("TAP", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClear) { Text("Effacer", color = InkDim) }
            // Overdub : réenregistre par-dessus sans effacer les autres pistes
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (overdubOn) PlayGreen else PanelLine)
                    .clickable(onClick = onOverdub)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "OVERDUB",
                    color = if (overdubOn) Bg else InkDim,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExport) { Text("Exporter .mid", color = Amber) }
        }
    }
}

@Composable
private fun Stepper(symbol: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(PanelLine)
            .clickable(onClick = onClick)
    ) {
        Text(symbol, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PadButton(
    label: String,
    active: Boolean,
    activeColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = when {
        active -> activeColor
        enabled -> PanelLine
        else -> PanelLine.copy(alpha = 0.4f)
    }
    val fg = when {
        active -> Bg
        enabled -> Ink
        else -> InkDim
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Black,
            letterSpacing = 3.sp, fontFamily = FontFamily.Monospace
        )
    }
}

private val NoteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private fun noteName(n: Int): String =
    if (n < 0) "—" else "${NoteNames[n % 12]}${n / 12 - 1}"

/** Nom probable du son pour une note de batterie (mapping type GM, réglable sur l'ER-1). */
private fun drumHint(n: Int): String = when (n) {
    35, 36 -> "Kick"
    37 -> "Rimshot"
    38, 40 -> "Snare"
    39 -> "Clap"
    41, 43, 45, 47, 48, 50 -> "Tom"
    42 -> "HH fermé"
    44 -> "HH pédale"
    46 -> "HH ouvert"
    49, 57 -> "Crash"
    51, 59 -> "Ride"
    54 -> "Tambourin"
    56 -> "Cowbell"
    70 -> "Maracas"
    else -> "Perc"
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Amber else PanelLine)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (selected) Bg else InkDim,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun NoteTrackRow(
    state: com.beatbox.midirecorder.midi.NoteTrackUiState,
    color: Color,
    engine: MidiEngine,
    lengthNanos: Long,
    nowMs: Long,
    revision: Int,
    onMute: () -> Unit,
    onSolo: () -> Unit
) {
    val sinceMs = nowMs - state.lastEventUptimeMs
    val ledAlpha = if (state.lastEventUptimeMs == 0L) 0.15f
    else (1f - (sinceMs / 400f)).coerceIn(0.15f, 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = ledAlpha))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(78.dp)) {
            Text(
                drumHint(state.note),
                color = if (state.muted) InkDim else Ink,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            Text(
                "${noteName(state.note)} · ${state.eventCount} evt",
                color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
        ) {
            @Suppress("UNUSED_EXPRESSION") revision
            drawLine(
                color = PanelLine,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 1.5f
            )
            for (ev in engine.snapshotNoteEvents(state.note)) {
                val x = (ev.timeNanos.toFloat() / lengthNanos.toFloat()) * size.width
                val h = (ev.data2 / 127f) * (size.height * 0.9f)
                drawLine(
                    color = if (state.muted) color.copy(alpha = 0.25f) else color,
                    start = Offset(x, size.height / 2 + h / 2),
                    end = Offset(x, size.height / 2 - h / 2),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state.soloed) PlayGreen else PanelLine)
                .clickable(onClick = onSolo)
        ) {
            Text("S", color = if (state.soloed) Bg else InkDim,
                fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state.muted) Amber else PanelLine)
                .clickable(onClick = onMute)
        ) {
            Text("M", color = if (state.muted) Bg else InkDim,
                fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun TrackRow(
    state: com.beatbox.midirecorder.midi.TrackUiState,
    color: Color,
    events: List<com.beatbox.midirecorder.midi.RecordedEvent>,
    lengthNanos: Long,
    nowMs: Long,
    revision: Int,
    onMute: () -> Unit,
    onSolo: () -> Unit
) {
    val sinceMs = nowMs - state.lastEventUptimeMs
    val ledAlpha = if (state.lastEventUptimeMs == 0L) 0.15f
    else (1f - (sinceMs / 400f)).coerceIn(0.15f, 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // LED d'activité
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = ledAlpha))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(78.dp)) {
            Text(
                String.format(Locale.US, "CH %02d", state.channel + 1),
                color = if (state.muted) InkDim else Ink,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            Text(
                "${state.eventCount} evt · ${noteName(state.lastNote)}",
                color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
        // Mini timeline des frappes enregistrées
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
        ) {
            // 'revision' force le redessin quand de nouveaux événements arrivent
            @Suppress("UNUSED_EXPRESSION") revision
            drawLine(
                color = PanelLine,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 1.5f
            )
            val snapshot = synchronized(events) { events.toList() }
            for (ev in snapshot) {
                if (ev.command != 0x90 || ev.data2 == 0) continue
                val x = (ev.timeNanos.toFloat() / lengthNanos.toFloat()) * size.width
                val h = (ev.data2 / 127f) * (size.height * 0.9f)
                drawLine(
                    color = if (state.muted) color.copy(alpha = 0.25f) else color,
                    start = Offset(x, size.height / 2 + h / 2),
                    end = Offset(x, size.height / 2 - h / 2),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // Bouton SOLO
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state.soloed) PlayGreen else PanelLine)
                .clickable(onClick = onSolo)
        ) {
            Text(
                "S",
                color = if (state.soloed) Bg else InkDim,
                fontSize = 13.sp, fontWeight = FontWeight.Black
            )
        }
        Spacer(Modifier.width(6.dp))
        // Bouton MUTE
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state.muted) Amber else PanelLine)
                .clickable(onClick = onMute)
        ) {
            Text(
                "M",
                color = if (state.muted) Bg else InkDim,
                fontSize = 13.sp, fontWeight = FontWeight.Black
            )
        }
    }
}
