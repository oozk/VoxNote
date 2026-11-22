package com.offlinetranscriber.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinetranscriber.AppContainer
import com.offlinetranscriber.data.ModelEntity
import com.offlinetranscriber.engine.ModelCatalogue
import com.offlinetranscriber.util.DeviceInfo
import com.offlinetranscriber.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class ModelTask(val label: String, val fraction: Float? = null)
data class ModelMessage(val text: String, val isError: Boolean)

class ModelsViewModel(val c: AppContainer) : ViewModel() {
    val task = MutableStateFlow<ModelTask?>(null)
    val message = MutableStateFlow<ModelMessage?>(null)
    private val cancel = AtomicBoolean(false)

    fun import(uri: Uri, inPlace: Boolean, name: String?, notes: String?) = viewModelScope.launch {
        cancel.set(false)
        task.value = ModelTask("Checking file…")
        val r = runCatching {
            if (inPlace) c.models.registerInPlace(uri, name, notes)
            else c.models.importCopy(uri, name, { cancel.get() }) { done, total ->
                task.value = ModelTask("Copying model… ${Format.bytes(done)} of ${Format.bytes(total)}", if (total > 0) done.toFloat() / total else null)
            }
        }
        r.onSuccess { id ->
            task.value = ModelTask("Verifying with the inference engine…")
            val m = c.models.get(id)!!
            c.models.verify(m).onSuccess { message.value = ModelMessage("Model added. $it", false) }
                .onFailure { message.value = ModelMessage("Model added, but the engine could not load it now: ${it.message}", true) }
        }.onFailure { message.value = ModelMessage(it.message ?: "Import failed", true) }
        task.value = null
    }

    fun cancelImport() = cancel.set(true)

    fun verify(m: ModelEntity) = viewModelScope.launch {
        task.value = ModelTask("Loading ${m.displayName}…")
        c.models.verify(m).onSuccess { message.value = ModelMessage(it, false) }.onFailure { message.value = ModelMessage("Could not load: ${it.message}", true) }
        task.value = null
    }
}

@Composable
fun ModelsScreen() {
    val app = LocalApp.current
    val c = app.appVm.container
    val ctx = LocalContext.current
    val vm: ModelsViewModel = viewModel(factory = vmFactory { ModelsViewModel(it) })
    val scope = rememberCoroutineScope()
    val models by c.models.models.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val task by vm.task.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var registerUri by remember { mutableStateOf<Uri?>(null) }
    var removing by remember { mutableStateOf<ModelEntity?>(null) }
    val mem = remember { DeviceInfo.memory(ctx) }
    val free = remember(models) { c.storage.freeBytes() }
    val used = remember(models) { c.models.modelsStorageBytes() }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it, false, null, null) } }
    val registerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> registerUri = uri }

    fun openBrowser(url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: ActivityNotFoundException) { scope.launch { app.snackbar.showSnackbar("No web browser installed") } }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Models")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            task?.let { t ->
                SectionCard(t.label) {
                    if (t.fraction != null) LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (t.label.startsWith("Copying")) TextButton(onClick = vm::cancelImport) { Text("Cancel") }
                }
            }
            message?.let { m -> InfoBanner(m.text, warning = m.isError) }

            SectionCard("This device") {
                Text("Memory (RAM): ${Format.bytes(mem.totalBytes)} total, ${Format.bytes(mem.availableBytes)} free now")
                Text("Storage: ${Format.bytes(free)} free · models use ${Format.bytes(used)}")
                Text(DeviceInfo.recommendation(mem.totalBytes), style = MaterialTheme.typography.bodySmall)
                Text("Engine: ${c.engines.systemInfo()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            BigButton("Import a model file", Icons.Filled.FileOpen, { importPicker.launch(arrayOf("*/*")) }, enabled = task == null)
            BigButton("Use a model file without copying", Icons.Filled.Link, { registerPicker.launch(arrayOf("*/*")) }, enabled = task == null, secondary = true)
            Text("Import copies the file into the app (safest). \"Without copying\" saves storage but the file must stay where it is.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Installed models", style = MaterialTheme.typography.titleMedium)
            if (models.isEmpty()) Text("None yet.")
            models.forEach { m ->
                val isDefault = settings?.defaultModelId == m.id
                SectionCard {
                    Row {
                        Text(m.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (isDefault) StatusChip("Default", MaterialTheme.colorScheme.primary)
                        if (m.role == "diarize") StatusChip("Speaker model", MaterialTheme.colorScheme.tertiary)
                    }
                    Text("Engine: ${m.engine}" + if (m.languages.isNotBlank()) " · languages: ${m.languages.split(',').let { l -> if (l.size > 6) "${l.size}" else l.joinToString(", ") }}" else "",
                        style = MaterialTheme.typography.bodySmall)
                    Text("${Format.bytes(m.sizeBytes)} · ${m.format}" + listOfNotNull(m.sizeClass, m.quantization,
                        m.multilingual?.let { if (it) "multilingual" else "English-only" }).joinToString(" · ", prefix = " · "),
                        style = MaterialTheme.typography.bodySmall)
                    when {
                        !m.compatible -> StatusChip("Not compatible", MaterialTheme.colorScheme.error)
                        m.verified == 1 -> StatusChip("Compatible · verified", Color(0xFF2E7D32))
                        m.verified == -1 -> StatusChip("Header OK · failed to load", MaterialTheme.colorScheme.error)
                        else -> StatusChip("Compatible (header) · not verified yet", MaterialTheme.colorScheme.tertiary)
                    }
                    (m.verifyMessage ?: m.compatibilityMessage).let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (m.contentUri != null) Text("Used in place (not copied).", style = MaterialTheme.typography.bodySmall)
                    m.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!isDefault && m.compatible && m.role == "asr") TextButton(onClick = { scope.launch { c.models.setDefault(m.id) } }) { Text("Make default") }
                        TextButton(enabled = task == null, onClick = { vm.verify(m) }) { Text("Verify") }
                        TextButton(onClick = { removing = m }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            Text("Get models from Hugging Face (optional)", style = MaterialTheme.typography.titleMedium)
            InfoBanner("VoxNote has no internet access. These buttons open your web browser to download a file; " +
                "afterwards tap \"Import a model file\" and pick it from Downloads. Nothing is downloaded without you.")
            ModelCatalogue.models.forEach { cm ->
                SectionCard {
                    Text(cm.title, fontWeight = FontWeight.SemiBold)
                    Text("${cm.fileName} · ~${cm.approxSizeMb} MB download · ~${cm.approxRamMb} MB RAM" + (if (cm.englishOnly) " · English only" else "") + " · ${cm.engine}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(cm.note, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { openBrowser(cm.downloadUrl) }) { Text("Download in browser") }
                        TextButton(onClick = { openBrowser(cm.pageUrl) }) { Text("Model page") }
                    }
                }
            }
            BigButton("Open the whisper.cpp model list", Icons.Filled.OpenInBrowser, { openBrowser(ModelCatalogue.REPOSITORY_URL) }, secondary = true)
            Text("Two engines are built in: whisper.cpp for Whisper GGML files (ggml-*.bin) and transcribe.cpp for GGUF speech models " +
                "(Parakeet, Whisper GGUF, Moonshine, Canary, SenseVoice, …) and the Sortformer speaker model. Compatibility is read from the " +
                "file itself and confirmed by loading it; language models (LLMs), safetensors and PyTorch files are rejected with an explanation.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    registerUri?.let { uri ->
        var name by remember { mutableStateOf(c.storage.displayName(uri)?.removeSuffix(".bin") ?: "") }
        var notes by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { registerUri = null }, title = { Text("Register model") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true)
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") })
                    Text("The file stays in its current folder. If you move or delete it, the model stops working.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.import(uri, true, name, notes.ifBlank { null }); registerUri = null }) { Text("Register") } },
            dismissButton = { TextButton(onClick = { registerUri = null }) { Text("Cancel") } })
    }
    removing?.let { m ->
        ConfirmDialog("Remove ${m.displayName}?", if (m.filePath != null) "The model file (${Format.bytes(m.sizeBytes)}) will be deleted from this device."
            else "The model will be unregistered. Your original file is not deleted.", "Remove",
            onConfirm = { scope.launch { c.models.remove(m) } }, onDismiss = { removing = null }, destructive = true)
    }
}
