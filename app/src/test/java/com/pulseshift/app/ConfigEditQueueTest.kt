package com.pulseshift.app

import com.pulseshift.app.data.repository.ConfigEditQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigEditQueueTest {
    @Test
    fun saveImmediatelyFlushesLatestEditsAndWaitsForTheirAcknowledgements() = runTest {
        val queue = ConfigEditQueue(backgroundScope)
        val seen = mutableListOf<String>()
        val startupAck = CompletableDeferred<Boolean>()
        queue.edit("brightness") { error("Superseded value must not be sent") }
        queue.edit("brightness") { seen.add("brightness=107") }
        queue.edit("startup") {
            seen.add("loop=1")
            startupAck.await()
        }
        val save = async {
            queue.afterEdits {
                assertTrue(it.isEmpty())
                seen.add("save")
            }
        }
        runCurrent()
        assertEquals(listOf("brightness=107", "loop=1"), seen)
        assertFalse(save.isCompleted)
        queue.edit("brightness") { seen.add("brightness=198") }
        startupAck.complete(true)
        assertTrue(save.await())
        queue.afterEdits { assertTrue(it.isEmpty()) }
        assertEquals(listOf("brightness=107", "loop=1", "save", "brightness=198"), seen)
    }

    @Test
    fun failedEditRemainsVisibleToSaveUntilRetriedOrDisconnected() = runTest {
        val queue = ConfigEditQueue(backgroundScope)
        queue.edit("brightness") { false }
        assertEquals(setOf("brightness"), queue.afterEdits { it })
        assertEquals(setOf("brightness"), queue.afterEdits { it })
        queue.edit("brightness") { true }
        assertEquals(emptySet<String>(), queue.afterEdits { it })
        queue.edit("startup") { false }
        assertEquals(setOf("startup"), queue.afterEdits { it })
        queue.reset()
        assertEquals(emptySet<String>(), queue.afterEdits { it })
    }
}
