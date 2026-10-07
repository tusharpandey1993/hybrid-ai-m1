package dev.edgeai.prototype

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RouterUiState(val decision: Decision? = null, val elapsedMicros: Long = 0)

class RouterViewModel : ViewModel() {
    private val router = TextRouter()
    private val mutableState = MutableStateFlow(RouterUiState())
    val state = mutableState.asStateFlow()

    fun submit(question: String, policy: RoutingPolicy) {
        val started = System.nanoTime()
        val decision = router.decide(question, policy)
        val elapsed = (System.nanoTime() - started) / 1000
        mutableState.update { RouterUiState(decision, elapsed) }
    }

    fun clearResult() {
        mutableState.update { RouterUiState() }
    }
}