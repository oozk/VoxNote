package com.offlinetranscriber.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.offlinetranscriber.AppContainer
import com.offlinetranscriber.OfflineTranscriberApp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Creates a ViewModel that receives the [AppContainer]. */
inline fun <reified VM : ViewModel> vmFactory(crossinline create: (AppContainer) -> VM) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as OfflineTranscriberApp
        return create(app.container) as T
    }
}

data class BusyState(val message: String, val fraction: Float? = null)

class AppViewModel(val container: AppContainer) : ViewModel() {
    private val _routes = Channel<String>(Channel.BUFFERED)
    val routes = _routes.receiveAsFlow()
    val busy = MutableStateFlow<BusyState?>(null)
    val messages = Channel<String>(Channel.BUFFERED)

    fun navigate(route: String) { _routes.trySend(route) }

    fun importSharedAudio(uri: Uri) = importAudio(uri)

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            busy.value = BusyState("Importing audio…")
            runCatching {
                container.transcriptions.importAudio(uri) { done, total ->
                    busy.value = BusyState("Importing audio…", if (total > 0) done.toFloat() / total else null)
                }
            }.onSuccess { id -> navigate("transcribe?job=$id") }
                .onFailure { messages.trySend("Could not import audio: ${it.message}") }
            busy.value = null
        }
    }
}
