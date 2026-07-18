package com.beatbox.midirecorder.midi

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Écrit un fichier .mid (SMF format 1) : une piste de tempo + une piste par canal utilisé.
 * Résolution : 480 ticks par noire, tempo pris depuis le BPM de l'application.
 */
object MidiFileWriter {

    private const val PPQ = 480

    fun write(file: File, trackEvents: Array<MutableList<RecordedEvent>>, bpm: Int) {
        val usedChannels = trackEvents.indices.filter { trackEvents[it].isNotEmpty() }
        val nTracks = 1 + usedChannels.size
        val out = ByteArrayOutputStream()

        // En-tête MThd
        out.write("MThd".toByteArray())
        out.writeInt32(6)
        out.writeInt16(1)          // format 1
        out.writeInt16(nTracks)
        out.writeInt16(PPQ)

        // Piste 0 : tempo
        val tempoTrack = ByteArrayOutputStream()
        tempoTrack.writeVarLen(0)
        val usPerQuarter = 60_000_000 / bpm.coerceIn(20, 300)
        tempoTrack.write(byteArrayOf(0xFF.toByte(), 0x51, 0x03))
        tempoTrack.write((usPerQuarter shr 16) and 0xFF)
        tempoTrack.write((usPerQuarter shr 8) and 0xFF)
        tempoTrack.write(usPerQuarter and 0xFF)
        tempoTrack.writeEndOfTrack()
        out.writeChunk("MTrk", tempoTrack.toByteArray())

        // Conversion nanos -> ticks
        val nanosPerTick = (usPerQuarter * 1000L) / PPQ

        for (ch in usedChannels) {
            val trk = ByteArrayOutputStream()
            var lastTick = 0L
            val events = synchronized(trackEvents[ch]) { trackEvents[ch].sortedBy { it.timeNanos } }
            for (ev in events) {
                val tick = ev.timeNanos / nanosPerTick
                trk.writeVarLen((tick - lastTick).coerceAtLeast(0))
                lastTick = tick
                trk.write(ev.status)
                trk.write(ev.data1)
                if (ev.command != 0xC0 && ev.command != 0xD0) trk.write(ev.data2)
            }
            trk.writeEndOfTrack()
            out.writeChunk("MTrk", trk.toByteArray())
        }

        file.writeBytes(out.toByteArray())
    }

    // ----- utilitaires binaires -----

    private fun ByteArrayOutputStream.writeInt32(v: Int) {
        write((v shr 24) and 0xFF); write((v shr 16) and 0xFF)
        write((v shr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt16(v: Int) {
        write((v shr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeVarLen(value: Long) {
        var v = value
        var buffer = v and 0x7F
        v = v shr 7
        while (v > 0) {
            buffer = (buffer shl 8) or 0x80 or (v and 0x7F)
            v = v shr 7
        }
        while (true) {
            write((buffer and 0xFF).toInt())
            if (buffer and 0x80L != 0L) buffer = buffer shr 8 else break
        }
    }

    private fun ByteArrayOutputStream.writeEndOfTrack() {
        writeVarLen(0)
        write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
    }

    private fun ByteArrayOutputStream.writeChunk(tag: String, data: ByteArray) {
        write(tag.toByteArray())
        writeInt32(data.size)
        write(data)
    }
}
