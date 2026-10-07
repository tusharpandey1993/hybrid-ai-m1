package dev.edgeai.prototype

import android.content.Context
import com.gliner25decide.Activation
import com.gliner25decide.DecideClassifier
import com.gliner25decide.Task
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

class GlinerAiRouter(context: Context) : AiRouter {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val closed = AtomicBoolean(false)
    private var classifier: DecideClassifier? = null
    private val tasks = listOf(Task(
        name = "route",
        labels = RoutingTaxonomy.descriptions.keys.toList(),
        labelDescriptions = RoutingTaxonomy.descriptions,
        activation = Activation.SOFTMAX
    ))

    override suspend fun initialize() = withContext(worker) {
        check(!closed.get()) { "Router is closed" }
        if (classifier == null) {
            val candidate = DecideClassifier(appContext)
            classifier = candidate
            try {
                candidate.warmUp("What is 25 * 4?", tasks, DecideClassifier.Backend.CPU)
            } catch (failure: Exception) {
                classifier = null
                candidate.close()
                throw failure
            }
        }
    }

    override suspend fun route(input: String): AiRouteDecision = withContext(worker) {
        check(!closed.get()) { "Router is closed" }
        require(input.isNotBlank() && input.length <= 4096) { "Invalid input length" }
        val model = checkNotNull(classifier) { "Model is not loaded" }
        val started = System.nanoTime()
        val result = model.classify(input, tasks, DecideClassifier.Backend.CPU)
        val scores = result.decisions.single().probabilities.mapIndexed { index, probability ->
            RouteScore(AiRoute.valueOf(tasks.single().labels[index]), probability)
        }.sortedByDescending { it.probability }
        AiRouteDecision(
            scores = scores,
            elapsedMillis = (System.nanoTime() - started) / 1_000_000.0,
            graphMillis = result.timing.graphMs,
            encodedTokens = result.encodedTokens,
            window = result.window
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            worker.executor.execute {
                try {
                    classifier?.close()
                    classifier = null
                } finally {
                    worker.close()
                }
            }
        }
    }
}