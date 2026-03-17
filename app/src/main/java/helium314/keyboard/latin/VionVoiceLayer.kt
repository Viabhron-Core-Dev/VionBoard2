/*
 * VionBoard — VionVoiceLayer.kt
 * Offline voice recording and optional Vosk transcription.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VionBoard voice layer.
 *
 * Features:
 *  - Offline voice recording (no cloud upload)
 *  - Optional Vosk transcription (when available)
 *  - Automatic cleanup of old recordings
 *  - Privacy-first design
 *
 * Note: Vosk integration is optional and requires separate installation.
 * Without Vosk, this provides raw audio recording only.
 */
object VionVoiceLayer {

    private const val VOICE_DIR = "vion_voice"
    private const val MAX_RECORDINGS = 10
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null

    /**
     * Starts recording voice input.
     * Returns true on success, false on failure.
     */
    fun startRecording(context: Context): Boolean {
        return try {
            // Stop any existing recording
            stopRecording()

            val voiceDir = File(context.filesDir, VOICE_DIR).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            currentRecordingFile = File(voiceDir, "voice_$timestamp.wav")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(128000)
                setOutputFile(currentRecordingFile!!.absolutePath)
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            VionCrashRecovery.logCrash(context, "voice_recording_failed", e.message ?: "Unknown error", e)
            mediaRecorder = null
            currentRecordingFile = null
            false
        }
    }

    /**
     * Stops recording and returns the audio file.
     * Returns the File on success, null on failure.
     */
    fun stopRecording(): File? {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            val file = currentRecordingFile
            currentRecordingFile = null
            file
        } catch (e: Exception) {
            mediaRecorder = null
            currentRecordingFile = null
            null
        }
    }

    /**
     * Transcribes an audio file using Vosk (if available).
     * Returns the transcribed text, or null if Vosk is not available.
     *
     * Note: This is a placeholder for Vosk integration.
     * Actual implementation requires the Vosk library.
     */
    fun transcribeWithVosk(audioFile: File): String? {
        return try {
            // Placeholder: Check if Vosk is available
            val voskClass = Class.forName("org.vosk.Recognizer")
            if (voskClass != null) {
                // Vosk is available — implement transcription here
                // This is a stub for now
                null
            } else {
                null
            }
        } catch (_: ClassNotFoundException) {
            // Vosk not available
            null
        }
    }

    /**
     * Retrieves all recorded audio files.
     */
    fun getRecordings(context: Context): List<File> {
        return try {
            val voiceDir = File(context.filesDir, VOICE_DIR)
            voiceDir.listFiles()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Deletes an audio file.
     */
    fun deleteRecording(file: File): Boolean {
        return file.delete()
    }

    /**
     * Clears all voice recordings.
     */
    fun clearRecordings(context: Context) {
        try {
            File(context.filesDir, VOICE_DIR).deleteRecursively()
        } catch (_: Exception) {
            // Silently fail
        }
    }

    /**
     * Cleans up old recordings, keeping only the most recent ones.
     */
    fun cleanupOldRecordings(context: Context) {
        try {
            val voiceDir = File(context.filesDir, VOICE_DIR)
            val recordings = voiceDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            recordings.drop(MAX_RECORDINGS).forEach { it.delete() }
        } catch (_: Exception) {
            // Silently fail
        }
    }
}
