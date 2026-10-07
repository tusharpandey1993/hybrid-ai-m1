package dev.edgeai.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRouterTest {
    private fun decision(scores: List<RouteScore>) = AiRouteDecision(scores, 1.0, 0.5, 100, 128)

    @Test fun exposesRealAlternativesAndMargin() {
        val result = decision(AiRoute.entries.mapIndexed { index, route ->
            RouteScore(route, listOf(0.6f, 0.2f, 0.1f, 0.05f, 0.05f)[index])
        })
        assertEquals(AiRoute.LOCAL_CODE, result.route)
        assertEquals(0.6f, result.confidence, 0.00001f)
        assertEquals(0.4f, result.margin, 0.00001f)
        assertEquals(5, result.scores.size)
    }

    @Test fun taxonomyIsExactlyFiveDescribedCapabilities() {
        assertEquals(AiRoute.entries.map { it.name }, RoutingTaxonomy.descriptions.keys.toList())
        assertTrue(RoutingTaxonomy.descriptions.values.all { it.isNotBlank() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnnormalizedScores() {
        decision(AiRoute.entries.map { RouteScore(it, 0.1f) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonfiniteScores() {
        decision(AiRoute.entries.map { RouteScore(it, Float.NaN) })
    }
}