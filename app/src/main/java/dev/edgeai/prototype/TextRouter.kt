package dev.edgeai.prototype

import java.math.BigDecimal

enum class Route { LOCAL, CLOUD, BLOCKED, NEED_INPUT }

enum class Reason {
    EXACT_ARITHMETIC, INVALID_ARITHMETIC, CURRENT_INFORMATION,
    UNSUPPORTED_LOCALLY, PRIVATE_REQUEST, OFFLINE, BUDGET_DISABLED,
    EMPTY_INPUT, INPUT_TOO_LONG
}

data class RoutingPolicy(
    val cloudAllowed: Boolean = false,
    val networkAvailable: Boolean = false,
    val cloudBudgetEnabled: Boolean = false
)

data class Decision(
    val route: Route,
    val reasons: List<Reason>,
    val localAnswer: String? = null
)

class TextRouter {
    private val expression = Regex(
        """(?:what\s+is\s+|calculate\s+)?([+-]?\d{1,18}(?:\.\d{1,9})?)\s*([+*/x\-\u00d7\u00f7])\s*([+-]?\d{1,18}(?:\.\d{1,9})?)\s*\??""",
        RegexOption.IGNORE_CASE
    )
    private val freshness = Regex(
        """\b(latest|current|today|recent|research|sources|internet)\b""",
        RegexOption.IGNORE_CASE
    )

    fun decide(question: String, policy: RoutingPolicy): Decision {
        val input = question.trim()
        if (input.isEmpty()) return Decision(Route.NEED_INPUT, listOf(Reason.EMPTY_INPUT))
        if (input.length > MAX_INPUT_LENGTH) {
            return Decision(Route.NEED_INPUT, listOf(Reason.INPUT_TOO_LONG))
        }
        val match = expression.matchEntire(input)
        if (match != null) {
            val left = BigDecimal(match.groupValues[1])
            val right = BigDecimal(match.groupValues[3])
            val answer = try {
                when (match.groupValues[2].lowercase()) {
                    "+" -> left.add(right)
                    "-" -> left.subtract(right)
                    "*", "x", "\u00d7" -> left.multiply(right)
                    else -> left.divide(right)
                }.stripTrailingZeros().toPlainString()
            } catch (_: ArithmeticException) {
                return Decision(Route.LOCAL, listOf(Reason.INVALID_ARITHMETIC))
            }
            return Decision(Route.LOCAL, listOf(Reason.EXACT_ARITHMETIC), answer)
        }
        val requirement = if (freshness.containsMatchIn(input)) {
            Reason.CURRENT_INFORMATION
        } else {
            Reason.UNSUPPORTED_LOCALLY
        }
        val blockers = buildList {
            if (!policy.cloudAllowed) add(Reason.PRIVATE_REQUEST)
            if (!policy.networkAvailable) add(Reason.OFFLINE)
            if (!policy.cloudBudgetEnabled) add(Reason.BUDGET_DISABLED)
        }
        return Decision(
            if (blockers.isEmpty()) Route.CLOUD else Route.BLOCKED,
            listOf(requirement) + blockers
        )
    }

    companion object {
        const val MAX_INPUT_LENGTH = 4096
    }
}