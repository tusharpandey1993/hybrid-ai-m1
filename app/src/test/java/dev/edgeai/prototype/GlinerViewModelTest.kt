package dev.edgeai.prototype

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlinerViewModelTest {
    private class FakeRouter : AiRouter {
        var failLoad = false
        var closed = false
        var pending: CompletableDeferred<AiRouteDecision>? = null
        override suspend fun initialize() {
            check(!failLoad)
        }
        override suspend fun route(input: String): AiRouteDecision = pending?.await() ?: result()
        override fun close() { closed = true }
    }

    @Test fun loadsPublishesAndReleasesRouter() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val router = FakeRouter()
            val model = GlinerViewModel(router)
            store.put("router", model)
            advanceUntilIdle()
            assertEquals(ModelStatus.READY, model.state.value.status)
            model.submit("What is 25 * 4?")
            advanceUntilIdle()
            assertEquals(AiRoute.LOCAL_CODE, model.state.value.decision?.route)
            store.clear()
            assertTrue(router.closed)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun ignoresStaleResultAfterInputChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val router = FakeRouter().apply { pending = CompletableDeferred() }
            val model = GlinerViewModel(router)
            store.put("router", model)
            advanceUntilIdle()
            model.submit("First request")
            advanceUntilIdle()
            assertEquals(ModelStatus.RUNNING, model.state.value.status)
            model.clearResult()
            router.pending?.complete(result())
            advanceUntilIdle()
            assertNull(model.state.value.decision)
            assertEquals(ModelStatus.READY, model.state.value.status)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun unavailableModelCanRetryAndRejectsBlankInput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val router = FakeRouter().apply { failLoad = true }
            val model = GlinerViewModel(router)
            store.put("router", model)
            advanceUntilIdle()
            assertEquals(ModelStatus.UNAVAILABLE, model.state.value.status)
            model.submit("Ignored while unavailable")
            assertNull(model.state.value.decision)
            router.failLoad = false
            model.load()
            advanceUntilIdle()
            model.submit(" ")
            assertEquals(RouterFailure.INVALID_INPUT, model.state.value.failure)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    companion object {
        private fun result() = AiRouteDecision(
            AiRoute.entries.mapIndexed { index, route ->
                RouteScore(route, listOf(0.6f, 0.2f, 0.1f, 0.05f, 0.05f)[index])
            }, 1.0, 0.5, 100, 128
        )
    }
}