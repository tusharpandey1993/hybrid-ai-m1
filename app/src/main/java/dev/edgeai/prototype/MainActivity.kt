package dev.edgeai.prototype

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: RouterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        val question = findViewById<EditText>(R.id.question)
        val cloudAllowed = findViewById<CheckBox>(R.id.cloud_allowed)
        val budgetEnabled = findViewById<CheckBox>(R.id.budget_enabled)
        val offline = findViewById<CheckBox>(R.id.offline)
        val result = findViewById<TextView>(R.id.result)
        question.doAfterTextChanged { viewModel.clearResult() }
        listOf(cloudAllowed, budgetEnabled, offline).forEach { checkbox ->
            checkbox.setOnCheckedChangeListener { _, _ -> viewModel.clearResult() }
        }
        findViewById<Button>(R.id.submit).setOnClickListener {
            viewModel.submit(question.text.toString(), RoutingPolicy(
                cloudAllowed = cloudAllowed.isChecked,
                networkAvailable = !offline.isChecked && hasValidatedNetwork(),
                cloudBudgetEnabled = budgetEnabled.isChecked
            ))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val decision = state.decision
                    result.text = if (decision == null) "" else {
                        val response = when (decision.route) {
                            Route.LOCAL -> decision.localAnswer ?: getString(R.string.arithmetic_error)
                            Route.CLOUD -> getString(R.string.cloud_not_connected)
                            Route.BLOCKED -> getString(R.string.cloud_blocked)
                            Route.NEED_INPUT -> getString(R.string.input_required)
                        }
                        val reasons = decision.reasons.joinToString("\n") { reason ->
                            getString(when (reason) {
                                Reason.EXACT_ARITHMETIC -> R.string.exact_arithmetic
                                Reason.INVALID_ARITHMETIC -> R.string.invalid_arithmetic
                                Reason.CURRENT_INFORMATION -> R.string.current_information
                                Reason.UNSUPPORTED_LOCALLY -> R.string.unsupported_locally
                                Reason.PRIVATE_REQUEST -> R.string.private_request
                                Reason.OFFLINE -> R.string.no_network
                                Reason.BUDGET_DISABLED -> R.string.no_budget
                                Reason.EMPTY_INPUT -> R.string.empty_input
                                Reason.INPUT_TOO_LONG -> R.string.input_too_long
                            })
                        }
                        getString(R.string.result_format, decision.route.name,
                            response, reasons, state.elapsedMicros)
                    }
                }
            }
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}