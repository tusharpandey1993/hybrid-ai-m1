package dev.edgeai.prototype

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class GlinerActivity : ComponentActivity() {
    private val model: GlinerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == GlinerViewModel::class.java)
                return modelClass.cast(GlinerViewModel(GlinerAiRouter(applicationContext)))
                    ?: error("Cannot create router ViewModel")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_gliner)
        val root = findViewById<android.view.View>(R.id.gliner_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        val input = findViewById<EditText>(R.id.gliner_question)
        val status = findViewById<TextView>(R.id.gliner_status)
        val result = findViewById<TextView>(R.id.gliner_result)
        val submit = findViewById<Button>(R.id.gliner_submit)
        val retry = findViewById<Button>(R.id.gliner_retry)
        input.doAfterTextChanged { model.clearResult() }
        submit.setOnClickListener { model.submit(input.text.toString()) }
        retry.setOnClickListener { model.load() }
        findViewById<Button>(R.id.open_calculator).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.open_voice_lab).setOnClickListener {
            startActivity(Intent(this, dev.edgeai.prototype.voice.debug.VoiceLabActivity::class.java))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    status.setText(when (state.status) {
                        ModelStatus.LOADING -> R.string.model_loading
                        ModelStatus.READY -> R.string.model_ready
                        ModelStatus.RUNNING -> R.string.model_running
                        ModelStatus.UNAVAILABLE -> R.string.model_unavailable
                    })
                    submit.isEnabled = state.status == ModelStatus.READY
                    retry.isEnabled = state.status == ModelStatus.UNAVAILABLE
                    val decision = state.decision
                    result.text = if (decision != null) {
                        val scores = decision.scores.joinToString("\n") {
                            getString(R.string.route_score, it.route.name, it.probability * 100)
                        }
                        getString(R.string.gliner_result_format,
                            decision.route.name, scores, decision.margin * 100,
                            decision.elapsedMillis, decision.graphMillis,
                            decision.encodedTokens, decision.window, state.loadMillis)
                    } else when (state.failure) {
                        RouterFailure.LOAD -> getString(R.string.model_files_unavailable)
                        RouterFailure.INVALID_INPUT -> getString(R.string.input_required)
                        RouterFailure.INFERENCE -> getString(R.string.inference_failed)
                        null -> ""
                    }
                }
            }
        }
    }
}