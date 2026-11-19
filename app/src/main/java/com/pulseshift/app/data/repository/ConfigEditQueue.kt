package com.pulseshift.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Orders coalesced lighting edits and operations that must see those edits. */
internal class ConfigEditQueue(scope: CoroutineScope, private val debounceMs: Long = 120) {
    private sealed interface Work
    private data class Edit(val key: String, val apply: suspend () -> Boolean) : Work
    private data class Barrier(val apply: suspend (Set<String>) -> Unit) : Work
    private data object Reset : Work
    private val work = Channel<Work>(Channel.UNLIMITED)

    init {
        scope.launch {
            val pending = linkedMapOf<String, Edit>()
            val failed = linkedSetOf<String>()
            suspend fun flush() {
                for ((key, edit) in pending) {
                    val applied = try {
                        edit.apply()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                    if (applied) failed.remove(key) else failed.add(key)
                }
                pending.clear()
            }
            while (true) {
                val next = if (pending.isEmpty()) work.receive()
                    else withTimeoutOrNull(debounceMs) { work.receive() }
                when (next) {
                    is Edit -> {
                        pending.remove(next.key)
                        pending[next.key] = next
                    }
                    is Barrier -> {
                        flush()
                        next.apply(failed)
                    }
                    Reset -> {
                        pending.clear()
                        failed.clear()
                    }
                    null -> flush()
                }
            }
        }
    }

    fun edit(key: String, apply: suspend () -> Boolean) {
        check(work.trySend(Edit(key, apply)).isSuccess)
    }

    fun reset() {
        check(work.trySend(Reset).isSuccess)
    }

    suspend fun <T> afterEdits(apply: suspend (Set<String>) -> T): T {
        val result = CompletableDeferred<T>()
        work.send(Barrier { failed ->
            try {
                result.complete(apply(failed.toSet()))
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        })
        return result.await()
    }
}
