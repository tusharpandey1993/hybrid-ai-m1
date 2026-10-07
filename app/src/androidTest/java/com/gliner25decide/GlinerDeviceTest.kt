package com.gliner25decide

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.edgeai.prototype.AiRoute
import dev.edgeai.prototype.GlinerAiRouter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlinerDeviceTest {
    @Test fun cpuInputsAndDecisionsMatchPublishedOracle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.assets.open("gate_fixtures.json").bufferedReader().use { it.readText() }
        val fixtures = DecideGateFixtures.parse(JSONObject(root)).take(3)
        DecideClassifier(context).use { classifier ->
            fixtures.forEach { fixture ->
                val window = fixture.windows.first()
                val inputs = classifier.inspectInputs(fixture.text, fixture.tasks, window)
                assertArrayEquals(fixture.inputIds, inputs.encoded.inputIds)
                assertArrayEquals(fixture.labelPositions, inputs.encoded.labelPositions)
                val result = classifier.classify(fixture.text, fixture.tasks,
                    DecideClassifier.Backend.CPU, window)
                assertEquals(fixture.official.map { it.task to it.labels },
                    result.decisions.map { it.task to it.labels })
                val scores = result.decisions.flatMap { it.probabilities.toList() }
                assertEquals(fixture.oracleProbabilities.size, scores.size)
                scores.forEachIndexed { index, probability ->
                    assertTrue(probability.isFinite())
                    assertEquals(fixture.oracleProbabilities[index], probability, 0.02f)
                }
            }
        }
    }

    @Test fun fiveRouteAdapterRunsWithoutInternetPermission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty()
        assertFalse("android.permission.INTERNET" in permissions)
        GlinerAiRouter(context).use { router ->
            router.initialize()
            val decision = router.route("What is 27 * 14?")
            assertEquals(AiRoute.LOCAL_CODE, decision.route)
            assertEquals(0.35068205f, decision.confidence, 0.02f)
            val rows = JSONArray()
            decision.scores.forEach {
                rows.put(JSONObject().put("route", it.route.name).put("score", it.probability))
            }
            File(context.filesDir, "gliner-smoke.json").writeText(
                JSONObject().put("route", decision.route.name).put("scores", rows)
                    .put("total_ms", decision.elapsedMillis).put("graph_ms", decision.graphMillis)
                    .put("encoded_tokens", decision.encodedTokens).put("window", decision.window)
                    .put("backend", "CPU").toString(2)
            )
        }
    }
}