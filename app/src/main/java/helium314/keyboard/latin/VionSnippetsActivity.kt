// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit

class VionSnippetsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SnippetsPanel(
                onType = { text ->
                    startService(
                        Intent(this, LatinIME::class.java)
                            .setAction(SNIPPETS_DONE_ACTION)
                            .putExtra(SNIPPETS_TEXT_KEY, text)
                    )
                    finish()
                },
                onDismiss = { finish() }
            )
        }
    }

    companion object {
        const val SNIPPETS_DONE_ACTION = "helium314.keyboard.latin.SNIPPETS_DONE_ACTION"
        const val SNIPPETS_TEXT_KEY = "snippets_text"

        val BUILTIN: Map<String, List<String>> = linkedMapOf(
            "Git" to listOf(
                "git status", "git add .", "git commit -m \"\"", "git push", "git pull",
                "git log --oneline", "git diff", "git stash", "git checkout -b ",
                "git clone ", "git reset --hard HEAD", "git merge ",
            ),
            "Termux" to listOf(
                "pkg update && pkg upgrade", "pkg install ", "termux-setup-storage",
                "cd ~", "ls -la", "chmod +x ", "./", "python3 ", "pip install ",
                "nano ", "cat ", "grep -r \"\" .",
            ),
            "Markdown" to listOf(
                "**bold**", "*italic*", "# Heading", "## Heading 2", "- [ ] ",
                "```\n\n```", "[text](url)", "> quote", "---",
                "| col | col |\n|-----|-----|\n| val | val |", "![alt](url)",
            ),
            "Symbols" to listOf(
                "→", "←", "↑", "↓", "✓", "✗", "✕",
                "©", "™", "®", "°", "…", "•",
                "≠", "≥", "≤", "±", "×", "÷",
                "€", "£", "¥", "α", "β", "π", "∞", "∑", "√",
            ),
            "Prompts" to listOf(
                "Summarize the following in 3 bullet points:",
                "Fix grammar and rewrite professionally:",
                "Translate to Spanish:",
                "Translate to French:",
                "Explain this like I'm 5:",
                "Write a commit message for the following changes:",
                "Find bugs in this code and explain them:",
                "Refactor this code for readability:",
                "Write unit tests for this function:",
                "Continue the following:",
                "Make this shorter and more concise:",
                "Make this more formal:",
                "Make this more casual and friendly:",
                "Give me 5 alternatives for:",
                "What are the pros and cons of:",
                "Step-by-step instructions for:",
            ),
        )

        val CATEGORIES: List<String> = BUILTIN.keys.toList()

        fun loadCustom(prefs: android.content.SharedPreferences, cat: String): List<String> {
            val raw = prefs.getString("custom_$cat", "") ?: ""
            return if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
        }

        fun saveCustom(prefs: android.content.SharedPreferences, cat: String, list: List<String>) {
            prefs.edit { putString("custom_$cat", list.joinToString("\n")) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnippetsPanel(onType: (String) -> Unit, onDismiss: () -> Unit) {
    val BG      = Color(0xFF1B2B1B)
    val SURFACE = Color(0xFF243524)
    val ORANGE  = Color(0xFFFF8C00)
    val TEXT    = Color(0xFFE8F5E9)
    val MUTED   = Color(0xFF8BA88B)

    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences("vion_snippets", Context.MODE_PRIVATE) }

    var selectedTab    by remember { mutableStateOf(0) }
    var showAddDialog  by remember { mutableStateOf(false) }
    var newSnippetText by remember { mutableStateOf("") }

    val categories = VionSnippetsActivity.CATEGORIES

    val customSnippets: Map<String, SnapshotStateList<String>> = remember {
        categories.associateWith { cat ->
            VionSnippetsActivity.loadCustom(prefs, cat).toMutableStateList()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .background(BG, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false) {}
        ) {
            // Handle bar
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.width(40.dp).height(4.dp).background(MUTED, RoundedCornerShape(2.dp)))
            }

            // Category tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor   = BG,
                contentColor     = ORANGE,
                edgePadding      = 8.dp,
            ) {
                categories.forEachIndexed { idx, cat ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick  = { selectedTab = idx },
                        text = {
                            Text(
                                cat,
                                color    = if (selectedTab == idx) ORANGE else MUTED,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            val cat     = categories[selectedTab]
            val builtin = VionSnippetsActivity.BUILTIN[cat] ?: emptyList()
            val custom  = customSnippets[cat] ?: emptyList<String>()

            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(builtin) { snippet ->
                    SnippetChip(
                        text      = snippet,
                        isCustom  = false,
                        orange    = ORANGE,
                        surface   = SURFACE,
                        textColor = TEXT,
                        onClick   = { onType(snippet) },
                        onDelete  = null,
                    )
                }
                items(custom, key = { it }) { snippet ->
                    SnippetChip(
                        text      = snippet,
                        isCustom  = true,
                        orange    = ORANGE,
                        surface   = SURFACE,
                        textColor = TEXT,
                        onClick   = { onType(snippet) },
                        onDelete  = {
                            customSnippets[cat]?.remove(snippet)
                            VionSnippetsActivity.saveCustom(
                                prefs, cat,
                                customSnippets[cat]?.toList() ?: emptyList()
                            )
                        },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showAddDialog = true }) {
                    Text("+ Add snippet", color = ORANGE, fontSize = 13.sp)
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newSnippetText = "" },
            title     = { Text("Add snippet", color = TEXT) },
            text      = {
                OutlinedTextField(
                    value         = newSnippetText,
                    onValueChange = { newSnippetText = it },
                    placeholder   = { Text("Type or paste snippet…", color = MUTED) },
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = ORANGE,
                        unfocusedBorderColor = MUTED,
                        focusedTextColor     = TEXT,
                        unfocusedTextColor   = TEXT,
                        cursorColor          = ORANGE,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 5,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val currentCat = categories[selectedTab]
                    if (newSnippetText.isNotBlank()) {
                        customSnippets[currentCat]?.add(newSnippetText.trim())
                        VionSnippetsActivity.saveCustom(
                            prefs, currentCat,
                            customSnippets[currentCat]?.toList() ?: emptyList()
                        )
                    }
                    showAddDialog  = false
                    newSnippetText = ""
                }) { Text("Add", color = ORANGE) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newSnippetText = "" }) {
                    Text("Cancel", color = MUTED)
                }
            },
            containerColor = SURFACE,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnippetChip(
    text: String,
    isCustom: Boolean,
    orange: Color,
    surface: Color,
    textColor: Color,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var showDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface, RoundedCornerShape(8.dp))
            .border(
                1.dp,
                if (isCustom) orange.copy(alpha = 0.35f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .then(
                if (onDelete != null)
                    Modifier.combinedClickable(
                        onClick     = onClick,
                        onLongClick = { showDelete = !showDelete }
                    )
                else
                    Modifier.clickable(onClick = onClick)
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text       = text,
            color      = textColor,
            fontSize   = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines   = 3,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f),
        )
        if (showDelete && onDelete != null) {
            TextButton(
                onClick        = { onDelete(); showDelete = false },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("✕", color = Color(0xFFFF4444), fontSize = 14.sp)
            }
        }
    }
}
          confirmButton = {
                TextButton(onClick = {
                    val currentCat = categories[selectedTab]
                    if (newSnippetText.isNotBlank()) {
                        customSnippets[currentCat]?.add(newSnippetText.trim())
                        VionSnippetsActivity.saveCustom(
                            prefs, currentCat,
                            customSnippets[currentCat]?.toList() ?: emptyList()
                        )
                    }
                    showAddDialog  = false
                    newSnippetText = ""
                }) { Text("Add", color = ORANGE) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newSnippetText = "" }) {
                    Text("Cancel", color = MUTED)
                }
            },
            containerColor = SURFACE,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnippetChip(
    text: String,
    isCustom: Boolean,
    orange: Color,
    surface: Color,
    textColor: Color,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var showDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface, RoundedCornerShape(8.dp))
            .border(
                1.dp,
                if (isCustom) orange.copy(alpha = 0.35f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .then(
                if (onDelete != null)
                    Modifier.combinedClickable(
                        onClick     = onClick,
                        onLongClick = { showDelete = !showDelete }
                    )
                else
                    Modifier.clickable(onClick = onClick)
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text       = text,
            color      = textColor,
            fontSize   = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f),
        )
        if (showDelete && onDelete != null) {
            TextButton(
                onClick        = { onDelete(); showDelete = false },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("✕", color = Color(0xFFFF4444), fontSize = 14.sp)
            }
        }
    }
}
