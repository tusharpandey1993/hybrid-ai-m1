package dev.edgeai.prototype

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ModelStatus { LOADING, READY, RUNNING, UNAVAILABLE }
enum class RouterFailure { LOAD, INVALID_INPUT, INFERENCE }

data class GlinerUiState(
    val status: ModelStatus = ModelStatus.LOADING,
    val decision: AiRouteDecision? = null,
    val failure: RouterFailure? = null,
    val loadMillis: Double = 0.0
)

class GlinerViewModel(private val router: AiRouter) : ViewModel() {
    private val mutableState = MutableStateFlow(GlinerUiState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L

    init {
        load()
    }

    fun load() {
        val request = ++generation
        operation?.cancel()
        mutableState.update { GlinerUiState() }
        operation = viewModelScope.launch {
            val started = System.nanoTime()
            try {
                router.initialize()
                if (generation == request) {
                    mutableState.update {
                        it.copy(status = ModelStatus.READY,
                            loadMillis = (System.nanoTime() - started) / 1_000_000.0)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == request) {
                    mutableState.update {
                        it.copy(status = ModelStatus.UNAVAILABLE, failure = RouterFailure.LOAD)
                    }
                }
            }
        }
    }

    fun submit(input: String) {
        if (mutableState.value.status != ModelStatus.READY) return
        if (input.isBlank() || input.length > 4096) {
            mutableState.update { it.copy(decision = null, failure = RouterFailure.INVALID_INPUT) }
            return
        }
        val request = ++generation
        mutableState.update { it.copy(status = ModelStatus.RUNNING, decision = null, failure = null) }
        operation = viewModelScope.launch {
            try {
                val decision = router.route(input)
                if (generation == request) {
                    mutableState.update { it.copy(status = ModelStatus.READY, decision = decision) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == request) {
                    mutableState.update {
                        it.copy(status = ModelStatus.READY, failure = RouterFailure.INFERENCE)
                    }
                }
            }
        }
    }

    fun clearResult() {
        if (mutableState.value.status == ModelStatus.RUNNING) {
            ++generation
            operation?.cancel()
            mutableState.update { it.copy(status = ModelStatus.READY, decision = null, failure = null) }
        } else {
            mutableState.update { it.copy(decision = null, failure = null) }
        }
    }

    override fun onCleared() {
        ++generation
        router.close()
        super.onCleared()
    }
}