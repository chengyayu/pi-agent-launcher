package com.piagent.launcher.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalesces a burst of VFS change notifications into a single callback.
 *
 * Pi (and the tools it runs, e.g. `gofmt`) writes a file many times in quick
 * succession. Feeding every one of those events straight into
 * `FileEditorManager.openFile` / `DiffManager.showDiff` was able to open
 * dozens of editors and eventually freeze the IDE. This debouncer:
 *
 *  - accumulates changed paths for [delayMs],
 *  - then delivers the unique set to [onFlush] **on the EDT**,
 *  - and never lets more than one flush be scheduled at a time.
 *
 * All methods are thread-safe: [submit] is called from the background VFS
 * listener thread, [onFlush] runs on the EDT.
 */
class PiChangeDebouncer(
    private val delayMs: Int,
    private val onFlush: (List<String>) -> Unit
) : Disposable {

    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val disposed = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pi-change-debouncer").apply { isDaemon = true }
    }

    @Volatile
    private var scheduled: ScheduledFuture<*>? = null

    fun submit(filePath: String) {
        if (disposed.get()) return

        val scheduleNew = pending.isEmpty()
        pending.add(filePath)

        if (scheduleNew && scheduled?.isDone != false) {
            synchronized(this) {
                if (disposed.get()) return
                val current = scheduled
                if (current == null || current.isDone) {
                    scheduled = scheduler.schedule({ flush() }, delayMs.toLong(), TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun flush() {
        if (disposed.get()) return

        synchronized(this) { scheduled = null }

        if (pending.isEmpty()) return
        val paths = pending.toList()
        pending.clear()

        ApplicationManager.getApplication().invokeLater {
            if (!disposed.get()) {
                onFlush(paths)
            }
        }
    }

    fun clear() {
        pending.clear()
    }

    override fun dispose() {
        disposed.set(true)
        synchronized(this) {
            scheduled?.cancel(false)
            scheduled = null
        }
        scheduler.shutdownNow()
        pending.clear()
    }
}
