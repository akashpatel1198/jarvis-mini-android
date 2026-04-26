package com.akash.jarvismini

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    fun start(outputFile: File) {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        recorder = rec
        currentFile = outputFile
    }

    fun stop(): File? {
        val file = currentFile
        recorder?.let {
            try {
                it.stop()
            } catch (_: RuntimeException) {
                // MediaRecorder.stop() throws if recording was too short to flush an MPEG-4 atom.
                // Treat as a soft failure — return null so caller can decide what to show.
                file?.delete()
                it.release()
                recorder = null
                currentFile = null
                return null
            }
            it.release()
        }
        recorder = null
        currentFile = null
        return file
    }
}
