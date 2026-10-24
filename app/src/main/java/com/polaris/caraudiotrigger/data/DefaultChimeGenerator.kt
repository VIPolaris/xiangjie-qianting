package com.polaris.caraudiotrigger.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

object DefaultChimeGenerator {
    private const val SAMPLE_RATE = 44_100
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    fun write(file: File) {
        file.parentFile?.mkdirs()
        val notes = listOf(
            Note(659.25, 0.24),
            Note(0.0, 0.06),
            Note(880.0, 0.36),
        )
        val samples = notes.sumOf { (it.durationSeconds * SAMPLE_RATE).toInt() }
        val dataSize = samples * 2

        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.writeAscii("RIFF")
            output.writeLittleEndian(36 + dataSize, 4)
            output.writeAscii("WAVE")
            output.writeAscii("fmt ")
            output.writeLittleEndian(16, 4)
            output.writeLittleEndian(1, 2)
            output.writeLittleEndian(CHANNELS, 2)
            output.writeLittleEndian(SAMPLE_RATE, 4)
            output.writeLittleEndian(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8, 4)
            output.writeLittleEndian(CHANNELS * BITS_PER_SAMPLE / 8, 2)
            output.writeLittleEndian(BITS_PER_SAMPLE, 2)
            output.writeAscii("data")
            output.writeLittleEndian(dataSize, 4)

            notes.forEach { note -> writeNote(output, note) }
        }
    }

    private fun writeNote(output: BufferedOutputStream, note: Note) {
        val sampleCount = (note.durationSeconds * SAMPLE_RATE).toInt()
        repeat(sampleCount) { index ->
            val progress = index.toDouble() / sampleCount.coerceAtLeast(1)
            val envelope = when {
                progress < 0.08 -> progress / 0.08
                progress > 0.82 -> (1.0 - progress) / 0.18
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val wave = if (note.frequencyHz == 0.0) {
                0.0
            } else {
                sin(2.0 * PI * note.frequencyHz * index / SAMPLE_RATE)
            }
            val sample = (wave * envelope * Short.MAX_VALUE * 0.34).toInt()
            output.write(sample and 0xFF)
            output.write((sample shr 8) and 0xFF)
        }
    }

    private fun BufferedOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun BufferedOutputStream.writeLittleEndian(value: Int, byteCount: Int) {
        repeat(byteCount) { index -> write((value shr (index * 8)) and 0xFF) }
    }

    private data class Note(val frequencyHz: Double, val durationSeconds: Double)
}

