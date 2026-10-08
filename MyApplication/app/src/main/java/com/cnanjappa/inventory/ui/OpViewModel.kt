package com.cnanjappa.inventory.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.container
import com.cnanjappa.inventory.data.Code
import com.cnanjappa.inventory.data.OpResult
import com.cnanjappa.inventory.data.newOpId
import kotlinx.coroutines.launch

@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (Container, SavedStateHandle) -> VM): VM {
    val c = LocalContext.current.container
    return viewModel { create(c, createSavedStateHandle()) }
}

/**
 * Base for screens that change stock. Each action kind keeps its operation id in the saved state
 * until it finishes, so a retry after failure (or after process death) reuses the same id and the
 * database returns the original outcome instead of applying the change twice.
 */
abstract class OpViewModel(val c: Container, val handle: SavedStateHandle) : ViewModel() {
    val repo get() = c.repo
    var busy by mutableStateOf(false)
        private set
    var msg by mutableStateOf<Msg?>(null)

    protected fun op(key: String, block: suspend (String) -> OpResult, onDone: (OpResult) -> Unit) {
        if (busy) return
        busy = true
        val id = handle.get<String>(key) ?: newOpId().also { handle[key] = it }
        viewModelScope.launch {
            val r = block(id)
            if (r.code != Code.FAILED) handle.remove<String>(key)
            busy = false
            onDone(r)
        }
    }

    /** After process death: resolves an action that may have been in flight before showing anything new. */
    protected fun recover(key: String, onCommitted: (OpResult) -> Unit) {
        val id = handle.get<String>(key) ?: return
        msg = Msg(Tone.INFO, "Checking your last action…")
        viewModelScope.launch {
            val r = repo.outcome(id)
            if (r != null && r.code != Code.FAILED) {
                handle.remove<String>(key)
                msg = null
                onCommitted(r)
            } else {
                msg = Msg(Tone.ERROR, "Could not save. Try again")
            }
        }
    }
}

fun failMsg(code: Code): Msg = Msg(
    Tone.ERROR,
    when (code) {
        Code.NO_STOCK -> "No pieces available"
        Code.ARCHIVED -> "This item is archived"
        Code.NOT_FOUND -> "Product not found"
        Code.NO_SALE -> "No sale found for this item"
        Code.LIMIT -> "This sale is already returned"
        Code.STALE -> "Stock changed. Check the count again"
        Code.DUPLICATE -> "This item already exists"
        Code.LABEL_TAKEN -> "Label belongs to another item"
        Code.INVALID -> "Check the quantity"
        else -> "Could not save. Try again"
    },
)
