package dev.edgeai.prototype

import java.io.Closeable

enum class AiRoute { LOCAL_CODE, LOCAL_RAG, LOCAL_LLM, CLOUD_LLM, WEB_TOOL }

data class RouteScore(val route: AiRoute, val probability: Float)

data class AiRouteDecision(
    val scores: List<RouteScore>,
    val elapsedMillis: Double,
    val graphMillis: Double,
    val encodedTokens: Int,
    val window: Int
) {
    init {
        require(scores.size == AiRoute.entries.size)
        require(scores.map { it.route }.toSet() == AiRoute.entries.toSet())
        require(scores.all { it.probability.isFinite() && it.probability in 0f..1f })
        require(kotlin.math.abs(scores.sumOf { it.probability.toDouble() } - 1.0) < 0.0001)
        require(scores.zipWithNext().all { (first, second) -> first.probability >= second.probability })
    }

    val route: AiRoute get() = scores.first().route
    val confidence: Float get() = scores.first().probability
    val margin: Float get() = confidence - scores[1].probability
}

interface AiRouter : Closeable {
    suspend fun initialize()
    suspend fun route(input: String): AiRouteDecision
}

object RoutingTaxonomy {
    val descriptions: Map<String, String> = linkedMapOf(
        "LOCAL_CODE" to "Exact calculation or simple deterministic text transformation using local code.",
        "LOCAL_RAG" to "Answer grounded in a document, textbook, notes, or other knowledge stored on the device.",
        "LOCAL_LLM" to "General explanation, rewriting, translation, or simple coding help without current external facts.",
        "CLOUD_LLM" to "Difficult multi-step reasoning, complex code analysis, or synthesis beyond a small local language model.",
        "WEB_TOOL" to "Look up current external information, search the web, or perform an API or tool action."
    )
}