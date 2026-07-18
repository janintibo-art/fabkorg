package com.beatbox.midirecorder.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Un événement MIDI horodaté (temps relatif au début de l'enregistrement, en nanosecondes). */
data class RecordedEvent(
    val timeNanos: Long,
    val status: Int,
    val data1: Int,
    val data2: Int
) {
    val channel: Int get() = status and 0x0F
    val command: Int get() = status and 0xF0
}

/** État visible d'une piste (un canal MIDI = une piste, comme sur une Electribe). */
data class TrackUiState(
    val channel: Int,
    val eventCount: Int = 0,
    val lastEventUptimeMs: Long = 0L,
    val lastNote: Int = -1,
    val muted: Boolean = false,
    val soloed: Boolean = false
)

/** État d'une piste "par note" : mode ER-1, où chaque son = un numéro de note. */
data class NoteTrackUiState(
    val note: Int,
    val eventCount: Int = 0,
    val lastEventUptimeMs: Long = 0L,
    val muted: Boolean = false,
    val soloed: Boolean = false
)

class MidiEngine(private val context: Context) {

    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private var device: MidiDevice? = null
    private val openOutputs = mutableListOf<MidiOutputPort>()  // sorties appareil -> nous
    private var inputPort: MidiInputPort? = null               // entrée appareil <- nous (lecture)
    private var playJob: Job? = null
    private var metronomeJob: Job? = null

    /** Nom de la dernière machine connectée : sert à la reconnexion automatique. */
    private var lastDeviceName: String? = null

    /** Événements enregistrés, par canal (0..15). */
    val trackEvents: Array<MutableList<RecordedEvent>> = Array(16) { mutableListOf() }

    // ----- État observable par l'UI -----
    val devices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    val connectedName = MutableStateFlow<String?>(null)
    val statusMessage = MutableStateFlow<String?>(null)
    val isRecording = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val elapsedNanos = MutableStateFlow(0L)
    val tracks = MutableStateFlow(List(16) { TrackUiState(channel = it) })
    val bpm = MutableStateFlow(120)
    val revision = MutableStateFlow(0)
    val overdub = MutableStateFlow(false)

    /** Mode ER-1 : true = une piste par NOTE (kick, snare, hi-hat…), false = une piste par canal. */
    val noteMode = MutableStateFlow(false)
    /** Pistes dynamiques du mode ER-1, une par numéro de note rencontré, triées. */
    val noteTracks = MutableStateFlow<List<NoteTrackUiState>>(emptyList())

    /** Dernier instant (uptime ms) où un octet MIDI est arrivé : alimente la LED "signal". */
    val lastSignalMs = MutableStateFlow(0L)
    /** Battement du métronome : bascule true/false à chaque temps. */
    val metroTick = MutableStateFlow(false)
    /** Compte à rebours avant enregistrement (4..1, puis 0). */
    val countIn = MutableStateFlow(0)

    private var recordStartNanos = 0L
    private var recordedLengthNanos = 0L
    val lengthNanos: Long get() = recordedLengthNanos

    init {
        refreshDevices()
        midiManager.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                refreshDevices()
                // Reconnexion auto si c'est la machine qu'on utilisait.
                if (device == null && lastDeviceName != null &&
                    deviceLabel(info) == lastDeviceName
                ) {
                    connect(info)
                }
            }
            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                refreshDevices()
                if (device?.info == info) {
                    statusMessage.value = "Appareil débranché — reconnexion possible"
                    closePorts()
                    connectedName.value = null
                }
            }
        }, handler)
    }

    fun refreshDevices() {
        devices.value = midiManager.devices.toList()
    }

    fun deviceLabel(info: MidiDeviceInfo): String {
        val b = info.properties
        return b.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: b.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "Appareil MIDI ${info.id}"
    }

    fun connect(info: MidiDeviceInfo) {
        closePorts()
        statusMessage.value = "Connexion…"
        midiManager.openDevice(info, { opened ->
            if (opened == null) {
                statusMessage.value = "Échec : autorisation USB refusée ?"
                return@openDevice
            }
            device = opened
            // Ouvre TOUS les ports de sortie (certaines machines en exposent plusieurs).
            var opts = 0
            for (p in 0 until info.outputPortCount) {
                opened.openOutputPort(p)?.let { port ->
                    port.connect(recordReceiver)
                    openOutputs.add(port)
                    opts++
                }
            }
            if (info.inputPortCount > 0) {
                inputPort = opened.openInputPort(0)
            }
            lastDeviceName = deviceLabel(info)
            connectedName.value = lastDeviceName
            statusMessage.value = when {
                opts == 0 -> "Connecté, mais aucune sortie MIDI détectée"
                opts == 1 -> "Connecté · 1 port d'écoute"
                else -> "Connecté · $opts ports d'écoute"
            }
        }, handler)
    }

    /** Scanne et appaire une machine Bluetooth MIDI (Volca, certains Korg). */
    fun connectBluetooth(btDevice: android.bluetooth.BluetoothDevice) {
        closePorts()
        statusMessage.value = "Appairage Bluetooth…"
        midiManager.openBluetoothDevice(btDevice, { opened ->
            if (opened == null) {
                statusMessage.value = "Bluetooth : appairage impossible"
                return@openBluetoothDevice
            }
            device = opened
            val info = opened.info
            for (p in 0 until info.outputPortCount) {
                opened.openOutputPort(p)?.let { port ->
                    port.connect(recordReceiver); openOutputs.add(port)
                }
            }
            if (info.inputPortCount > 0) inputPort = opened.openInputPort(0)
            lastDeviceName = deviceLabel(info)
            connectedName.value = lastDeviceName
            statusMessage.value = "Connecté en Bluetooth"
        }, handler)
    }

    fun disconnect() {
        lastDeviceName = null          // coupe la reconnexion auto
        stopPlayback()
        closePorts()
        connectedName.value = null
        statusMessage.value = "Déconnecté"
    }

    private fun closePorts() {
        openOutputs.forEach {
            runCatching { it.disconnect(recordReceiver) }
            runCatching { it.close() }
        }
        openOutputs.clear()
        runCatching { inputPort?.close() }
        inputPort = null
        runCatching { device?.close() }
        device = null
    }

    // ----- Enregistrement -----

    /** Lance un compte à rebours de 4 temps, puis démarre l'enregistrement. */
    fun startRecordingWithCountIn() {
        if (isRecording.value) return
        scope.launch {
            val beatMs = 60_000L / bpm.value.coerceIn(20, 300)
            for (b in 4 downTo 1) {
                countIn.value = b
                metroTick.value = !metroTick.value
                delay(beatMs)
            }
            countIn.value = 0
            beginRecording()
        }
    }

    fun startRecording() { beginRecording() }

    private fun beginRecording() {
        if (!overdub.value) clearAll()
        recordStartNanos = System.nanoTime()
        isRecording.value = true
        startMetronome()
        scope.launch {
            while (isRecording.value) {
                elapsedNanos.value = System.nanoTime() - recordStartNanos
                delay(33)
            }
        }
    }

    fun stopRecording() {
        if (!isRecording.value) return
        isRecording.value = false
        stopMetronome()
        val len = System.nanoTime() - recordStartNanos
        if (len > recordedLengthNanos) recordedLengthNanos = len
        elapsedNanos.value = recordedLengthNanos
    }

    fun clearAll() {
        trackEvents.forEach { it.clear() }
        tracks.value = List(16) { TrackUiState(channel = it) }
        noteTracks.value = emptyList()
        recordedLengthNanos = 0L
        elapsedNanos.value = 0L
        revision.value++
    }

    fun toggleMute(channel: Int) {
        tracks.value = tracks.value.map {
            if (it.channel == channel) it.copy(muted = !it.muted) else it
        }
    }

    fun toggleSolo(channel: Int) {
        tracks.value = tracks.value.map {
            if (it.channel == channel) it.copy(soloed = !it.soloed) else it
        }
    }

    /** Une piste s'entend si elle n'est pas mutée ET (aucune piste en solo OU elle est en solo). */
    private fun audibleChannels(): Set<Int> {
        val list = tracks.value
        val anySolo = list.any { it.soloed }
        return list.filter { !it.muted && (!anySolo || it.soloed) }.map { it.channel }.toSet()
    }

    // ----- Mode ER-1 (pistes par note) -----

    fun toggleNoteMode() { noteMode.value = !noteMode.value }

    fun toggleNoteMute(note: Int) {
        noteTracks.value = noteTracks.value.map {
            if (it.note == note) it.copy(muted = !it.muted) else it
        }
    }

    fun toggleNoteSolo(note: Int) {
        noteTracks.value = noteTracks.value.map {
            if (it.note == note) it.copy(soloed = !it.soloed) else it
        }
    }

    private fun audibleNotes(): Set<Int> {
        val list = noteTracks.value
        val anySolo = list.any { it.soloed }
        return list.filter { !it.muted && (!anySolo || it.soloed) }.map { it.note }.toSet()
    }

    /** Toutes les frappes (note-on) d'un numéro de note, tous canaux confondus, pour la timeline. */
    fun snapshotNoteEvents(note: Int): List<RecordedEvent> =
        trackEvents.flatMap { list ->
            synchronized(list) {
                list.filter { it.command == 0x90 && it.data1 == note && it.data2 > 0 }
            }
        }

    fun toggleOverdub() { overdub.value = !overdub.value }

    // ----- Métronome visuel -----

    private fun startMetronome() {
        stopMetronome()
        metronomeJob = scope.launch {
            val beatMs = 60_000L / bpm.value.coerceIn(20, 300)
            while (isRecording.value) {
                metroTick.value = !metroTick.value
                delay(beatMs)
            }
        }
    }

    private fun stopMetronome() { metronomeJob?.cancel(); metronomeJob = null }

    /** Récepteur qui analyse le flux d'octets MIDI brut (gestion du "running status"). */
    private val recordReceiver = object : MidiReceiver() {
        private var runningStatus = 0
        private val pending = IntArray(2)
        private var pendingCount = 0
        private var needed = 0

        private fun dataBytesFor(status: Int): Int = when (status and 0xF0) {
            0xC0, 0xD0 -> 1
            else -> 2
        }

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (count > 0) lastSignalMs.value = SystemClock.uptimeMillis() // LED "signal"
            if (!isRecording.value) return
            val now = System.nanoTime() - recordStartNanos
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                when {
                    b >= 0xF8 -> { /* temps réel : ignoré */ }
                    b >= 0xF0 -> { runningStatus = 0; pendingCount = 0 }
                    b >= 0x80 -> { runningStatus = b; pendingCount = 0; needed = dataBytesFor(b) }
                    else -> {
                        if (runningStatus != 0) {
                            pending[pendingCount++] = b
                            if (pendingCount == needed) {
                                commit(now, runningStatus, pending[0], if (needed == 2) pending[1] else 0)
                                pendingCount = 0
                            }
                        }
                    }
                }
                i++
            }
        }

        private fun commit(t: Long, status: Int, d1: Int, d2: Int) {
            val ev = RecordedEvent(t, status, d1, d2)
            val ch = ev.channel
            synchronized(trackEvents[ch]) { trackEvents[ch].add(ev) }
            handler.post {
                tracks.value = tracks.value.map {
                    if (it.channel == ch) it.copy(
                        eventCount = it.eventCount + 1,
                        lastEventUptimeMs = SystemClock.uptimeMillis(),
                        lastNote = if (ev.command == 0x90 && ev.data2 > 0) d1 else it.lastNote
                    ) else it
                }
                // Mode ER-1 : chaque note frappée crée/alimente sa propre piste.
                if (ev.command == 0x90 && ev.data2 > 0) {
                    val now = SystemClock.uptimeMillis()
                    val cur = noteTracks.value
                    val existing = cur.find { it.note == d1 }
                    noteTracks.value = if (existing != null) {
                        cur.map {
                            if (it.note == d1) it.copy(eventCount = it.eventCount + 1, lastEventUptimeMs = now)
                            else it
                        }
                    } else {
                        (cur + NoteTrackUiState(note = d1, eventCount = 1, lastEventUptimeMs = now))
                            .sortedBy { it.note }
                    }
                }
                revision.value++
            }
        }
    }

    // ----- Lecture -----

    fun startPlayback() {
        val port = inputPort ?: run {
            statusMessage.value = "Pas d'entrée MIDI pour la lecture"
            return
        }
        if (recordedLengthNanos == 0L) return
        stopPlayback()
        val byNote = noteMode.value
        val audible = audibleChannels()
        val audNotes = if (byNote) audibleNotes() else emptySet()
        val all = trackEvents
            .flatMapIndexed { ch, list ->
                if (!byNote && ch !in audible) emptyList()
                else synchronized(list) { list.toList() }
            }
            .filter { ev ->
                // En mode note : on filtre les note-on/note-off par note audible,
                // les autres messages (CC, NRPN, pitch bend…) passent toujours.
                !byNote || (ev.command != 0x90 && ev.command != 0x80) || ev.data1 in audNotes
            }
            .sortedBy { it.timeNanos }
        if (all.isEmpty()) return
        isPlaying.value = true
        playJob = scope.launch {
            val start = System.nanoTime()
            val buf = ByteArray(3)
            for (ev in all) {
                val target = start + ev.timeNanos
                val waitMs = (target - System.nanoTime()) / 1_000_000
                if (waitMs > 0) delay(waitMs)
                if (!isPlaying.value) break
                buf[0] = ev.status.toByte(); buf[1] = ev.data1.toByte(); buf[2] = ev.data2.toByte()
                val len = if (ev.command == 0xC0 || ev.command == 0xD0) 2 else 3
                runCatching { port.send(buf, 0, len) }
                elapsedNanos.value = ev.timeNanos
            }
            allNotesOff()
            isPlaying.value = false
            elapsedNanos.value = recordedLengthNanos
        }
    }

    fun stopPlayback() {
        isPlaying.value = false
        playJob?.cancel(); playJob = null
        allNotesOff()
    }

    private fun allNotesOff() {
        val port = inputPort ?: return
        val buf = ByteArray(3)
        for (ch in 0..15) {
            buf[0] = (0xB0 or ch).toByte(); buf[1] = 123.toByte(); buf[2] = 0
            runCatching { port.send(buf, 0, 3) }
        }
    }

    fun release() { disconnect() }
}
