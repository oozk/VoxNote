package com.offlinetranscriber.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranscriber.data.DuplicateKeywordException
import com.offlinetranscriber.data.KeywordEntity
import com.offlinetranscriber.vocab.BuiltInCategories
import com.offlinetranscriber.vocab.ImportAction
import com.offlinetranscriber.vocab.ImportPreview
import com.offlinetranscriber.vocab.KeywordExport
import com.offlinetranscriber.vocab.KeywordImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VocabularyScreen() {
    val app = LocalApp.current
    val c = app.appVm.container
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val keywords by remember(query) { c.vocabulary.search(query) }.collectAsStateWithLifecycle(emptyList())
    val total by c.vocabulary.count.collectAsStateWithLifecycle(0)
    val counts by c.vocabulary.categoryCounts.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<KeywordEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var manageCategories by remember { mutableStateOf(false) }
    var exportCsv by remember { mutableStateOf(true) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/*")) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                val rows = c.vocabulary.exportRows()
                val text = if (exportCsv) KeywordExport.toCsv(rows) else KeywordExport.toTxt(rows)
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
                rows.size
            }.onSuccess { app.snackbar.showSnackbar("Exported $it keywords") }.onFailure { app.snackbar.showSnackbar("Export failed: ${it.message}") }
        }
    }

    BackHandler(selection.isNotEmpty()) { selection = emptySet() }
    val visible = keywords.filter { filter == null || it.category == filter }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(if (selection.isEmpty()) "My Vocabulary" else "${selection.size} selected") {
            if (selection.isNotEmpty()) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete selected") }
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Paste many keywords") }, onClick = { menu = false; app.go("vocabulary/bulk") })
                DropdownMenuItem(text = { Text("Import from TXT or CSV file") }, onClick = { menu = false; app.go("vocabulary/bulk") })
                DropdownMenuItem(text = { Text("Export as CSV") }, onClick = { menu = false; exportCsv = true; exporter.launch("vocabulary.csv") })
                DropdownMenuItem(text = { Text("Export as TXT") }, onClick = { menu = false; exportCsv = false; exporter.launch("vocabulary.txt") })
                DropdownMenuItem(text = { Text("Manage categories") }, onClick = { menu = false; manageCategories = true })
                if (visible.isNotEmpty()) DropdownMenuItem(text = { Text("Select all shown") }, onClick = { menu = false; selection = visible.map { it.id }.toSet() })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.padding(end = 10.dp))
                        Text("ADD KEYWORD", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    BigButton("Paste many at once", Icons.Filled.ContentPaste, { app.go("vocabulary/bulk") }, secondary = true)
                    if (total == 0) InfoBanner("Add names, companies, products, acronyms and technical words that you want spelled correctly. " +
                        "They help the transcriber spell them right, and the app will suggest corrections when it gets them wrong.")
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search $total keywords") })
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(filter == null, { filter = null }, label = { Text("All ($total)") }) }
                        items(counts.sortedBy { it.category }) { cc ->
                            FilterChip(filter == cc.category, { filter = if (filter == cc.category) null else cc.category }, label = { Text("${cc.category} (${cc.count})") })
                        }
                    }
                }
            }
            var lastCategory: String? = null
            visible.forEach { k ->
                if (k.category != lastCategory) {
                    lastCategory = k.category
                    item(key = "h_${k.category}") {
                        Text(k.category, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
                    }
                }
                item(key = k.id) {
                    val selected = k.id in selection
                    Row(Modifier.fillMaxWidth().combinedClickable(
                        onClick = { if (selection.isNotEmpty()) selection = if (selected) selection - k.id else selection + k.id else editing = k },
                        onLongClick = { selection = selection + k.id },
                    ).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selection.isNotEmpty()) Checkbox(selected, { selection = if (selected) selection - k.id else selection + k.id })
                        Column(Modifier.weight(1f)) {
                            Text(k.phrase, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            val alts = k.alternativeList
                            if (alts.isNotEmpty()) Text("Also: " + alts.joinToString(", "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { scope.launch { c.vocabulary.setPriority(k, !k.priority) } }) {
                            Icon(if (k.priority) Icons.Filled.Star else Icons.Filled.StarBorder,
                                if (k.priority) "Important (always used)" else "Mark as important",
                                tint = if (k.priority) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (visible.isEmpty() && total > 0) item { Text("No keywords match.", Modifier.padding(16.dp)) }
        }
    }

    if (adding || editing != null) KeywordEditor(editing, onDismiss = { adding = false; editing = null })
    if (confirmDelete) ConfirmDialog("Delete ${selection.size} keywords?", "This cannot be undone.", "Delete",
        onConfirm = { scope.launch { c.vocabulary.delete(selection.toList()); selection = emptySet() } },
        onDismiss = { confirmDelete = false }, destructive = true)
    if (manageCategories) CategoryManager { manageCategories = false }
}

/** The simple "add / edit keyword" form. */
@Composable
fun KeywordEditor(existing: KeywordEntity?, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val c = app.appVm.container
    val scope = rememberCoroutineScope()
    val categories by c.vocabulary.categoryList.collectAsStateWithLifecycle(emptyList())
    var phrase by remember { mutableStateOf(existing?.phrase ?: "") }
    var alternatives by remember { mutableStateOf(existing?.alternativeList?.joinToString("\n") ?: "") }
    var pronunciation by remember { mutableStateOf(existing?.pronunciation ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: BuiltInCategories.CUSTOM) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var priority by remember { mutableStateOf(existing?.priority ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var duplicate by remember { mutableStateOf<KeywordEntity?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var savedCount by remember { mutableStateOf(0) }

    LaunchedEffect(phrase) {
        duplicate = if (phrase.isBlank()) null else c.vocabulary.findDuplicate(phrase, existing?.id)
    }

    fun save(addAnother: Boolean) = scope.launch {
        try {
            c.vocabulary.save(existing?.id, phrase, KeywordImport.splitAlternatives(alternatives.replace('\n', ';')),
                category, pronunciation, notes, priority)
            if (addAnother) {
                savedCount++
                phrase = ""; alternatives = ""; pronunciation = ""; notes = ""; error = null
            } else onDismiss()
        } catch (e: DuplicateKeywordException) {
            error = e.message
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (existing == null) "Add keyword" else "Edit keyword", style = MaterialTheme.typography.headlineSmall)
                if (savedCount > 0) Text("$savedCount added. Type the next one.", color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(phrase, { phrase = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Keyword or phrase *") },
                    placeholder = { Text("e.g. Thermo Fisher") }, singleLine = true, isError = duplicate != null,
                    supportingText = { duplicate?.let { Text("Already in your vocabulary as \"${it.phrase}\" (${it.category})") } })
                OutlinedTextField(alternatives, { alternatives = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    label = { Text("Alternative spellings (optional)") },
                    placeholder = { Text("One per line, e.g.\nThermofisher\nThermo fisher") },
                    supportingText = { Text("Ways the transcriber might write it wrongly. These are corrected to the keyword.") })
                Chooser("Category", categories.map { it.name } + "＋ New category…", category, { it }, {
                    if (it.startsWith("＋")) newCategory = true else category = it
                })
                OutlinedTextField(pronunciation, { pronunciation = it }, Modifier.fillMaxWidth(), label = { Text("Pronunciation hint (optional)") },
                    placeholder = { Text("e.g. sounds like \"SHE-mad-zoo\"") }, singleLine = true,
                    supportingText = { Text("A reminder for you; it is not used by the speech model.") })
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes (optional)") })
                LabeledSwitch("Important", "Always include this word when transcribing, even with a very large vocabulary.", priority, { priority = it })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    if (existing == null) TextButton(enabled = phrase.isNotBlank() && duplicate == null, onClick = { save(true) }) { Text("Save & add another") }
                    Button(enabled = phrase.isNotBlank() && duplicate == null, onClick = { save(false) }) { Text("Save") }
                }
                if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete this keyword", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (newCategory) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newCategory = false }, title = { Text("New category") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { scope.launch { category = c.vocabulary.addCategory(name); newCategory = false } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newCategory = false }) { Text("Cancel") } })
    }
    if (confirmDelete && existing != null) ConfirmDialog("Delete \"${existing.phrase}\"?", "This cannot be undone.", "Delete",
        onConfirm = { scope.launch { c.vocabulary.delete(listOf(existing.id)); onDismiss() } }, onDismiss = { confirmDelete = false }, destructive = true)
}

@Composable
private fun CategoryManager(onDismiss: () -> Unit) {
    val c = LocalApp.current.appVm.container
    val scope = rememberCoroutineScope()
    val cats by c.vocabulary.categoryList.collectAsStateWithLifecycle(emptyList())
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Categories") },
        text = {
            LazyColumn {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, label = { Text("New category") })
                        IconButton(enabled = name.isNotBlank(), onClick = { scope.launch { c.vocabulary.addCategory(name); name = "" } }) { Icon(Icons.Filled.Add, "Add") }
                    }
                }
                items(cats, key = { it.name }) { cat ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(cat.name, Modifier.weight(1f).padding(vertical = 12.dp))
                        if (!cat.builtIn) IconButton(onClick = { scope.launch { c.vocabulary.deleteCategory(cat.name) } }) {
                            Icon(Icons.Filled.Delete, "Delete category (keywords move to Custom)")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

/** Paste many keywords at once, or import a TXT/CSV file; shows a preview with duplicate detection before adding. */
@Composable
fun BulkAddScreen() {
    val app = LocalApp.current
    val c = app.appVm.container
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val categories by c.vocabulary.categoryList.collectAsStateWithLifecycle(emptyList())
    var text by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var working by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    decodeText(bytes)
                }
            }.onSuccess { text = it; fileName = c.storage.displayName(uri); preview = null }
                .onFailure { app.snackbar.showSnackbar("Could not read file: ${it.message}") }
        }
    }

    fun check() = scope.launch {
        working = true
        val parsed = withContext(Dispatchers.Default) { KeywordImport.parseAuto(text, fileName) }
        preview = c.vocabulary.preview(parsed)
        working = false
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Add many keywords", showBack = true)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Type or paste one keyword per line.", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(text, { text = it; preview = null; if (it.isEmpty()) fileName = null }, Modifier.fillMaxWidth().heightIn(min = 220.dp),
                placeholder = { Text("Thermo Fisher\nMass Spectrometry\nChromatography\nHPLC\nGC-MS\nAgilent Technologies") },
                label = { Text(fileName?.let { "From file: $it" } ?: "Keywords") })
            BigButton("Import a TXT or CSV file", Icons.Filled.FileOpen, {
                picker.launch(arrayOf("text/plain", "text/csv", "text/comma-separated-values", "application/csv", "text/*", "application/vnd.ms-excel"))
            }, secondary = true)
            Chooser("Category for new keywords", listOf<String?>(null) + categories.map { it.name }, category,
                { it ?: "Choose automatically (acronyms, companies, otherwise Custom)" }, { category = it })
            Text("Optional extras on a line: Keyword | alternative; alternative | Category. CSV columns: keyword, alternatives, category, pronunciation, notes. " +
                "Lines starting with # are ignored.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val p = preview
            if (p == null) {
                BigButton(if (working) "Checking…" else "Check list", null, { check() }, enabled = text.isNotBlank() && !working)
            } else {
                SectionCard("Ready to add") {
                    Text("${p.toAdd} new keywords", fontWeight = FontWeight.SemiBold)
                    if (p.toMerge > 0) Text("${p.toMerge} existing keywords get new alternative spellings")
                    if (p.duplicates > 0) Text("${p.duplicates} duplicates will be skipped")
                    if (p.invalid > 0) Text("${p.invalid} empty or invalid lines will be skipped")
                    p.items.filter { it.action != ImportAction.ADD }.take(8).forEach {
                        Text("• ${it.keyword.phrase}: ${it.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                    p.items.filter { it.action == ImportAction.ADD }.take(12).let { list ->
                        if (list.isNotEmpty()) Text("New: " + list.joinToString(", ") { it.keyword.phrase } + if (p.toAdd > 12) ", …" else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                BigButton(if (working) "Adding…" else "Add ${p.toAdd + p.toMerge} keywords", Icons.Filled.Add, {
                    scope.launch {
                        working = true
                        val r = c.vocabulary.commit(p, category)
                        working = false
                        app.snackbar.showSnackbar("Added ${r.added} keywords" + (if (r.merged > 0) ", updated ${r.merged}" else "") + (if (r.skipped > 0) ", skipped ${r.skipped}" else ""))
                        app.nav.popBackStack()
                    }
                }, enabled = !working && (p.toAdd + p.toMerge) > 0)
            }
        }
    }
}

/** UTF-8 by default; honours UTF-16 byte-order marks (Excel "Unicode text" exports). */
internal fun decodeText(bytes: ByteArray): String = when {
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    else -> String(bytes, Charsets.UTF_8).removePrefix("﻿")
}
