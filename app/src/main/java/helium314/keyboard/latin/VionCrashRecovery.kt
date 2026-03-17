/*
 * VionBoard — VionCrashRecovery.kt
 * Auto-backup before writes and crash recovery with AI-ready logs.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VionBoard crash recovery system.
 *
 * Features:
 *  - Auto-backup before every vault write
 *  - Timestamped crash logs with context
 *  - Recovery from last known good state
 *  - AI-ready structured logs for debugging
 */
object VionCrashRecovery {

    private const val BACKUP_DIR = "vion_backups"
    private const val CRASH_LOG_DIR = "vion_crash_logs"
    private const val MAX_BACKUPS = 5
    private const val MAX_LOGS = 10

    /**
     * Creates a backup of a file before modification.
     * Returns the backup file path on success, null on failure.
     */
    fun createBackupBeforeWrite(context: Context, sourceFile: File): File? {
        return try {
            val backupDir = File(context.filesDir, BACKUP_DIR).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
            val backupFile = File(backupDir, "${sourceFile.name}.$timestamp.backup")

            sourceFile.copyTo(backupFile, overwrite = true)
            cleanOldBackups(backupDir)
            backupFile
        } catch (e: Exception) {
            logCrash(context, "backup_failed", e.message ?: "Unknown error")
            null
        }
    }

    /**
     * Recovers from the last backup if the main file is corrupted.
     * Returns true if recovery was successful.
     */
    fun recoverFromBackup(context: Context, targetFile: File): Boolean {
        return try {
            val backupDir = File(context.filesDir, BACKUP_DIR)
            if (!backupDir.exists()) return false

            // Find the most recent backup
            val backups = backupDir.listFiles { f ->
                f.name.startsWith(targetFile.name) && f.name.endsWith(".backup")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            if (backups.isEmpty()) return false

            val latestBackup = backups.first()
            latestBackup.copyTo(targetFile, overwrite = true)
            logCrash(context, "recovery_success", "Recovered from ${latestBackup.name}")
            true
        } catch (e: Exception) {
            logCrash(context, "recovery_failed", e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Logs a crash event with structured context for AI analysis.
     * Format: [timestamp] [severity] [event] [message] [stack_trace]
     */
    fun logCrash(
        context: Context,
        event: String,
        message: String,
        throwable: Throwable? = null
    ) {
        try {
            val logDir = File(context.filesDir, CRASH_LOG_DIR).apply { mkdirs() }
            val logFile = File(logDir, "crash_log.txt")

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val stackTrace = throwable?.stackTraceToString() ?: ""
            val logEntry = buildString {
                append("[$timestamp] ")
                append("[EVENT: $event] ")
                append("[MESSAGE: $message] ")
                if (stackTrace.isNotEmpty()) {
                    append("[STACK_TRACE: $stackTrace] ")
                }
                append("\n")
            }

            logFile.appendText(logEntry)
            cleanOldLogs(logDir)
        } catch (_: Exception) {
            // Silently fail to avoid recursive errors
        }
    }

    /**
     * Retrieves the crash log for debugging.
     * Returns the entire log file content as a string.
     */
    fun getCrashLog(context: Context): String {
        return try {
            val logFile = File(context.filesDir, "$CRASH_LOG_DIR/crash_log.txt")
            if (logFile.exists()) logFile.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Clears all crash logs and backups.
     */
    fun clearLogs(context: Context) {
        try {
            File(context.filesDir, CRASH_LOG_DIR).deleteRecursively()
            File(context.filesDir, BACKUP_DIR).deleteRecursively()
        } catch (_: Exception) {
            // Silently fail
        }
    }

    private fun cleanOldBackups(backupDir: File) {
        try {
            val backups = backupDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            backups.drop(MAX_BACKUPS).forEach { it.delete() }
        } catch (_: Exception) {
            // Silently fail
        }
    }

    private fun cleanOldLogs(logDir: File) {
        try {
            val logs = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            logs.drop(MAX_LOGS).forEach { it.delete() }
        } catch (_: Exception) {
            // Silently fail
        }
    }
}
