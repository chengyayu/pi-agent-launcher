package com.piagent.launcher.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coalesces a burst of VFS change notifications into a single callback.
 *
 * Pi (and the tools it runs, e.g. `gofmt`) writes files many times in quick
 * succession, and a single session may touch many files. Feeding every one of
 * those events straight into `FileEditorManager.openFile` /
 * `DiffManager.showDiff` was able to open dozens of editors and eventually
 * freeze the IDE. This debouncer:
 *
 *  - accumulates changed paths for [delayMs],
 *  - then delivers the unique set to [onFlush] **on the EDT**,
 *  - and never lets more than one flush be scheduled at a time.
 *
 * [onFlush] may return paths it could not handle yet (for example because the
 * IDE is indexing). Those are queued again with a longer delay instead of being
 * dropped, which matters because a single unscheduled future would otherwise
 * silence that file for the rest of the session.
 *
 * Thread-safety: [submit] is called from the background VFS listener thread,
 * [onFlush] runs on the EDT. The pending set is swapped out atomically so a
 * path submitted while a flush is draining can never be lost.
 */
class PiChangeDebouncer(
    private val delayMs: Int,
    private val retryDelayMs: Long = delayMs * 5L,
    private val onFlush: (List<String>) -> List<String>
) : Disposable {

    private val pending = AtomicReference<MutableSet<String>>(mutableSetOf())
    private val disposed = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pi-change-debouncer").apply { isDaemon = true }
    }

    @Volatile
    private var scheduled: ScheduledFuture<*>? = null

    @Volatile
    private var normalDelay = true

    fun submit(filePath: String) = submitAll(listOf(filePath), normal = true)

    private fun submitAll(paths: Collection<String>, normal: Boolean) {
        if (disposed.get() || paths.isEmpty()) return

        synchronized(this) {
            if (disposed.get()) return

            // Add first: a flush that is draining right now must never be able
            // to clear these paths without also scheduling a follow-up flush.
            pending.get().addAll(paths)

            if (!normal) normalDelay = false

            val current = scheduled
            if (current == null || current.isDone) {
                val delay = if (normalDelay) delayMs.toLong() else retryDelayMs
                scheduled = scheduler.schedule({ flush() }, delay, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun flush() {
        if (disposed.get()) return

        val batch: List<String>
        synchronized(this) {
            scheduled = null
            normalDelay = true
            val drained = pending.getAndSet(mutableSetOf())
            batch = drained.toList()
        }

        if (batch.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            if (disposed.get()) return@invokeLater
            val unhandled = try {
                onFlush(batch)
            } catch (_: Exception) {
                emptyList()
            }
            if (unhandled.isNotEmpty()) {
                submitAll(unhandled, normal = false)
            }
        }
    }

    fun clear() {
        synchronized(this) {
            pending.getAndSet(mutableSetOf()).clear()
        }
    }

    override fun dispose() {
        disposed.set(true)
        synchronized(this) {
            scheduled?.cancel(false)
            scheduled = null
            pending.getAndSet(mutableSetOf()).clear()
        }
        scheduler.shutdownNow()
    }
}
