/*
 * VionBoard — VionDictionaryFallback.kt
 * Graceful fallback to built-in dictionary if .dict files are corrupted.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import android.content.Context
import java.io.File

/**
 * VionBoard dictionary fallback handler.
 *
 * When a .dict file is corrupted or missing:
 *  1. Log the error for debugging
 *  2. Fall back to the built-in dictionary
 *  3. Notify the user (optional)
 *  4. Attempt recovery on next app restart
 */
object VionDictionaryFallback {

    private const val CORRUPTED_DICT_DIR = "vion_corrupted_dicts"

    /**
     * Checks if a dictionary file is valid.
     * Returns true if the file exists and is readable, false otherwise.
     */
    fun isDictionaryValid(dictFile: File?): Boolean {
        return dictFile != null && dictFile.exists() && dictFile.canRead() && dictFile.length() > 0
    }

    /**
     * Handles a corrupted dictionary file.
     * Moves it to a backup directory and logs the error.
     */
    fun handleCorruptedDictionary(context: Context, dictFile: File): Boolean {
        return try {
            val corruptedDir = File(context.filesDir, CORRUPTED_DICT_DIR).apply { mkdirs() }
            val backupFile = File(corruptedDir, dictFile.name + ".corrupted")
            dictFile.renameTo(backupFile)
            VionCrashRecovery.logCrash(
                context,
                "dict_corrupted",
                "Dictionary file corrupted: ${dictFile.name}. Moved to backup."
            )
            true
        } catch (e: Exception) {
            VionCrashRecovery.logCrash(
                context,
                "dict_backup_failed",
                "Failed to backup corrupted dictionary: ${e.message}",
                e
            )
            false
        }
    }

    /**
     * Retrieves all corrupted dictionary files.
     * Useful for diagnostics and recovery.
     */
    fun getCorruptedDictionaries(context: Context): List<File> {
        return try {
            val corruptedDir = File(context.filesDir, CORRUPTED_DICT_DIR)
            corruptedDir.listFiles()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Clears all corrupted dictionary backups.
     */
    fun clearCorruptedDictionaries(context: Context) {
        try {
            File(context.filesDir, CORRUPTED_DICT_DIR).deleteRecursively()
        } catch (_: Exception) {
            // Silently fail
        }
    }

    /**
     * Attempts to recover a corrupted dictionary from backup.
     * Returns true if recovery was successful.
     */
    fun recoverDictionary(context: Context, originalFile: File): Boolean {
        return try {
            val corruptedDir = File(context.filesDir, CORRUPTED_DICT_DIR)
            val backupFile = File(corruptedDir, originalFile.name + ".corrupted")

            if (!backupFile.exists()) return false

            backupFile.renameTo(originalFile)
            VionCrashRecovery.logCrash(
                context,
                "dict_recovered",
                "Dictionary recovered from backup: ${originalFile.name}"
            )
            true
        } catch (e: Exception) {
            VionCrashRecovery.logCrash(
                context,
                "dict_recovery_failed",
                "Failed to recover dictionary: ${e.message}",
                e
            )
            false
        }
    }
}
