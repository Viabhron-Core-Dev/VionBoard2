// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

class VionVaultActivity : ComponentActivity() {

    companion object {
        const val VAULT_DONE_ACTION = "helium314.keyboard.latin.VAULT_DONE_ACTION"
        const val VAULT_TEXT_KEY    = "vault_text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContent {
            VionVaultScreen(
                onTypeText = { text -> typeText(text) },
                onClose    = { finish() }
            )
        }
    }

    private fun typeText(text: String) {
        startService(
            Intent(this, LatinIME::class.java)
                .setAction(VAULT_DONE_ACTION)
                .putExtra(VAULT_TEXT_KEY, text)
        )
        finish()
    }
}

// ── Colours ────────────────────────────────────────────────────────────────────
private val VaultBg      = Color(0xFF1B2B1B)
private val VaultSurface = Color(0xFF243424)
private val VaultAccent  = Color(0xFFFF8C00)
private val VaultText    = Color(0xFFE8F5E8)
private val VaultSubText = Color(0xFF9DB89D)
private val VaultError   = Color(0xFFFF5555)

// ── Root screen ────────────────────────────────────────────────────────────────
@Composable
fun VionVaultScreen(
    onTypeText: (String) -> Unit,
    onClose: () -> Unit
) {
    val context  = LocalContext.current
    val vaultFile = remember { VionVaultManager.loadVaultFile(context) }
    // Use a state so the unlock screen can trigger a recompose into the entry list
    var unlocked by remember { mutableStateOf(VionVaultManager.isUnlocked) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(VaultBg)
    ) {
        when {
            vaultFile == null -> NoVaultScreen(onClose = onClose)
            !unlocked         -> UnlockScreen(
                vaultFile = vaultFile,
                onUnlocked = { unlocked = true },
                onClose    = onClose
            )
            else              -> VaultEntryList(
                onTypeText = onTypeText,
                onClose    = onClose
            )
        }
    }
}

// ── No vault configured ────────────────────────────────────────────────────────
@Composable
fun NoVaultScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("No vault file configured",
            color = VaultText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Go to VionBoard Settings → Vault to select your .kdbx file.",
            color = VaultSubText, fontSize = 13.sp)
        TextButton(onClick = onClose) {
            Text("Close", color = VaultAccent)
        }
    }
}

// ── Unlock screen ──────────────────────────────────────────────────────────────
@Composable
fun UnlockScreen(
    vaultFile: File,
    onUnlocked: () -> Unit,
    onClose: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var error    by remember { mutableStateOf(false) }
    var loading  by remember { mutableStateOf(false) }

    fun tryUnlock() {
        if (password.isBlank()) return
        loading = true
        val ok = VionVaultManager.unlock(vaultFile, password)
        loading = false
        if (ok) onUnlocked() else { error = true; password = "" }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔒  Vault locked",
                color = VaultText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose) {
                Text("✕", color = VaultSubText, fontSize = 16.sp)
            }
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = false },
            placeholder = { Text("Master password", color = VaultSubText) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { tryUnlock() }),
            isError = error,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = VaultAccent,
                unfocusedBorderColor = VaultSubText,
                focusedTextColor     = VaultText,
                unfocusedTextColor   = VaultText,
                cursorColor          = VaultAccent,
                errorBorderColor     = VaultError
            )
        )
        if (error) {
            Text("Wrong password or invalid file.", color = VaultError, fontSize = 12.sp)
        }
        Button(
            onClick  = { tryUnlock() },
            enabled  = password.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = VaultAccent)
        ) {
            Text(
                if (loading) "Unlocking…" else "Unlock",
                color = Color.Black, fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── Entry list ─────────────────────────────────────────────────────────────────
@Composable
fun VaultEntryList(
    onTypeText: (String) -> Unit,
    onClose: () -> Unit
) {
    var query         by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<VionVaultEntry?>(null) }

    val entries = remember(query) {
        if (query.isBlank()) VionVaultManager.entries
        else VionVaultManager.search(query)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VaultSurface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔑  Vault", color = VaultAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { VionVaultManager.lock() }) {
                    Text("Lock", color = VaultSubText, fontSize = 12.sp)
                }
                TextButton(onClick = onClose) {
                    Text("✕", color = VaultSubText, fontSize = 16.sp)
                }
            }
        }

        // Search
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; selectedEntry = null },
            placeholder = { Text("Search entries…", color = VaultSubText) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = VaultAccent,
                unfocusedBorderColor = VaultSubText,
                focusedTextColor     = VaultText,
                unfocusedTextColor   = VaultText,
                cursorColor          = VaultAccent
            )
        )

        // Action chips for selected entry
        selectedEntry?.let { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VaultSurface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (entry.username.isNotBlank()) VaultChip("Username") { onTypeText(entry.username) }
                if (entry.password.isNotBlank()) VaultChip("Password") { onTypeText(entry.password) }
                if (entry.url.isNotBlank())      VaultChip("URL")      { onTypeText(entry.url) }
            }
        }

        // List
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No entries found", color = VaultSubText, fontSize = 13.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(entries, key = { it.title + it.username }) { entry ->
                    VaultEntryRow(
                        entry      = entry,
                        isSelected = entry == selectedEntry,
                        onClick    = { selectedEntry = if (selectedEntry == entry) null else entry }
                    )
                }
            }
        }
    }
}

// ── Entry row ──────────────────────────────────────────────────────────────────
@Composable
fun VaultEntryRow(
    entry: VionVaultEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) VaultSurface else VaultBg)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = entry.title.ifBlank { "(no title)" },
                    color      = VaultText,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (entry.username.isNotBlank()) {
                    Text(text = entry.username, color = VaultSubText, fontSize = 12.sp)
                }
            }
            if (isSelected) {
                Text("›", color = VaultAccent, fontSize = 20.sp)
            }
        }
        HorizontalDivider(color = VaultSurface, thickness = 0.5.dp)
    }
}

// ── Action chip ────────────────────────────────────────────────────────────────
@Composable
fun VaultChip(label: String, onClick: () -> Unit) {
    Surface(
        shape  = RoundedCornerShape(16.dp),
        color  = VaultAccent,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text     = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color    = Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
