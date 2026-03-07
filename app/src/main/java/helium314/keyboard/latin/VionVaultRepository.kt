// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.net.Uri
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.BasicField
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.io.File

/**
 * Flat representation of a KeePass entry for use in VionBoard panels.
 * All fields are plaintext strings — decryption handled by kotpass on database open.
 */
data class VionVaultEntry(
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String,
)

/**
 * VionVaultRepository — handles all .kdbx file operations.
 *
 * Two modes:
 *  - Stored internally: user imports a .kdbx into VionBoard's private files dir
 *  - External: open directly from a Uri without copying (one-shot)
 *
 * Reading uses kotpass (app.keemobile:kotpass), pure Kotlin, KDBX 3.x + 4.x.
 */
object VionVaultRepository {

    private const val VAULT_DIR = "vaults"

    fun getVaultDir(context: Context): File =
        File(context.filesDir, VAULT_DIR).also { it.mkdirs() }

    /** List all .kdbx files stored in VionBoard's private storage. */
    fun listStoredVaults(context: Context): List<File> =
        getVaultDir(context).listFiles { f -> f.extension == "kdbx" }?.toList() ?: emptyList()

    /**
     * Copy a .kdbx from a content Uri (file picker result) into VionBoard's private storage.
     * [fileName] is the display name from the picker; sanitized before saving.
     */
    fun importVault(context: Context, uri: Uri, fileName: String): File {
        val safeName = fileName
            .removeSuffix(".kdbx")
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .ifEmpty { "vault" }
        val dest = File(getVaultDir(context), "$safeName.kdbx")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }

    /** Delete a stored vault file. */
    fun removeVault(file: File) {
        file.delete()
    }

    /**
     * Open a stored .kdbx file with [password] and return all entries across all groups.
     * Throws on wrong password, missing file, or corrupt/unsupported format.
     */
    fun openVault(file: File, password: String): List<VionVaultEntry> {
        val credentials = Credentials.from(EncryptedValue.fromString(password))
        val database = file.inputStream().use { stream ->
            KeePassDatabase.decode(stream, credentials)
        }
        return flattenEntries(database.content.group)
    }

    /**
     * Open a .kdbx directly from a Uri without importing it.
     * Useful for one-shot access to an external file.
     */
    fun openVaultFromUri(context: Context, uri: Uri, password: String): List<VionVaultEntry> {
        val credentials = Credentials.from(EncryptedValue.fromString(password))
        val database = context.contentResolver.openInputStream(uri)?.use { stream ->
            KeePassDatabase.decode(stream, credentials)
        } ?: error("Cannot open URI: $uri")
        return flattenEntries(database.content.group)
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun flattenEntries(group: Group): List<VionVaultEntry> {
        val result = mutableListOf<VionVaultEntry>()
        group.entries.forEach { result.add(it.toVionEntry()) }
        group.groups.forEach { result.addAll(flattenEntries(it)) }
        return result
    }

    private fun Entry.toVionEntry() = VionVaultEntry(
        title    = fields.str("Title"),
        username = fields.str("UserName"),
        password = fields.str("Password"),
        url      = fields.str("URL"),
        notes    = fields.str("Notes"),
    )

    /** Safely extract a string value from a BasicField map regardless of field variance. */
    private fun Map<String, BasicField<*>>.str(key: String): String =
        this[key]?.content?.toString() ?: ""
}
