package dev.edgeai.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRouterTest {
    private val router = TextRouter()
    private val enabled = RoutingPolicy(true, true, true)

    @Test fun multiplicationExampleIsLocalEvenOffline() {
        val decision = router.decide("What is 25 \u00d7 4?", RoutingPolicy())
        assertEquals(Route.LOCAL, decision.route)
        assertEquals("100", decision.localAnswer)
        assertEquals(listOf(Reason.EXACT_ARITHMETIC), decision.reasons)
    }

    @Test fun supportedOperatorsAndExactDecimals() {
        mapOf("0.1 + 0.2" to "0.3", "10 / 4" to "2.5", "-5 - -2" to "-3",
            "Calculate 6 x 7" to "42", "8 \u00f7 2" to "4", "25 * 4" to "100")
            .forEach { (question, answer) ->
                assertEquals(answer, router.decide(question, RoutingPolicy()).localAnswer)
            }
    }

    @Test fun researchExampleRequiresCloud() {
        val decision = router.decide(
            "Research the latest changes to Android and compare multiple current sources.", enabled
        )
        assertEquals(Route.CLOUD, decision.route)
        assertEquals(listOf(Reason.CURRENT_INFORMATION), decision.reasons)
        assertNull(decision.localAnswer)
    }

    @Test fun unknownIsNotAssumedSimple() {
        assertEquals(listOf(Reason.UNSUPPORTED_LOCALLY),
            router.decide("Explain quantization", enabled).reasons)
        assertEquals(Route.CLOUD, router.decide("Hi", enabled).route)
    }

    @Test fun doesNotAnswerOnlyArithmeticSubstring() {
        listOf("25 * 4 and explain Android", "25 * 4 + 2", "2e3 * 4", "What is 25 times 4?")
            .forEach { assertEquals(Route.CLOUD, router.decide(it, enabled).route) }
    }

    @Test fun privateRequestNeverAuthorizesCloud() {
        val decision = router.decide("Explain my private notes", enabled.copy(cloudAllowed = false))
        assertEquals(Route.BLOCKED, decision.route)
        assertTrue(Reason.PRIVATE_REQUEST in decision.reasons)
    }

    @Test fun offlineNeverAuthorizesCloud() {
        val decision = router.decide("Latest Android changes", enabled.copy(networkAvailable = false))
        assertEquals(Route.BLOCKED, decision.route)
        assertTrue(Reason.OFFLINE in decision.reasons)
    }

    @Test fun disabledBudgetNeverAuthorizesCloud() {
        val decision = router.decide("Explain Kotlin", enabled.copy(cloudBudgetEnabled = false))
        assertEquals(Route.BLOCKED, decision.route)
        assertTrue(Reason.BUDGET_DISABLED in decision.reasons)
    }

    @Test fun reportsAllBlockers() {
        assertEquals(listOf(Reason.UNSUPPORTED_LOCALLY, Reason.PRIVATE_REQUEST,
            Reason.OFFLINE, Reason.BUDGET_DISABLED),
            router.decide("Explain Kotlin", RoutingPolicy()).reasons)
    }

    @Test fun invalidDivisionDoesNotSpendCloudMoney() {
        listOf("1 / 0", "1 / 3").forEach {
            val decision = router.decide(it, enabled)
            assertEquals(Route.LOCAL, decision.route)
            assertEquals(listOf(Reason.INVALID_ARITHMETIC), decision.reasons)
            assertNull(decision.localAnswer)
        }
    }

    @Test fun validatesEmptyAndOversizedInput() {
        assertEquals(listOf(Reason.EMPTY_INPUT), router.decide("  ", enabled).reasons)
        assertEquals(Route.NEED_INPUT,
            router.decide("a".repeat(TextRouter.MAX_INPUT_LENGTH + 1), enabled).route)
    }

    @Test fun everyPolicyCombinationRespectsHardGates() {
        for (allowed in listOf(false, true)) {
            for (online in listOf(false, true)) {
                for (budget in listOf(false, true)) {
                    val policy = RoutingPolicy(allowed, online, budget)
                    assertEquals(if (allowed && online && budget) Route.CLOUD else Route.BLOCKED,
                        router.decide("Explain Android", policy).route)
                    assertEquals(Route.LOCAL, router.decide("25 * 4", policy).route)
                }
            }
        }
    }
}