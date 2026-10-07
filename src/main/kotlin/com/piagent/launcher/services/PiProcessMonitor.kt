package com.piagent.launcher.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.piagent.launcher.infrastructure.PiTerminal
import javax.swing.Timer

/**
 * Detects that the Pi process has exited on its own and reports it once.
 *
 * Pi takes a moment to start, so polling begins after [startupGraceMillis] and
 * stops after the first observed exit or once disposed.
 */
internal class PiProcessMonitor(
    private val terminal: PiTerminal,
    private val onExit: () -> Unit,
    private val startupGraceMillis: Int = STARTUP_GRACE_MILLIS,
    private val pollIntervalMillis: Int = POLL_INTERVAL_MILLIS
) : Disposable {

    private val logger = Logger.getInstance(PiProcessMonitor::class.java)

    private var pollTimer: Timer? = null
    private var startupTimer: Timer? = null

    @Volatile
    private var finished = false

    fun start() {
        val startTimer = Timer(startupGraceMillis) {
            if (!finished) startPolling()
        }
        startTimer.isRepeats = false
        startupTimer = startTimer
        startTimer.start()
    }

    private fun startPolling() {
        if (finished) return

        val timer = Timer(pollIntervalMillis) {
            if (!finished && !terminal.isProcessAlive()) {
                finished = true
                stopTimers()
                logger.info("Pi process exited")
                onExit()
            }
        }
        timer.isRepeats = true
        pollTimer = timer
        timer.start()
    }

    private fun stopTimers() {
        startupTimer?.stop()
        pollTimer?.stop()
        startupTimer = null
        pollTimer = null
    }

    override fun dispose() {
        finished = true
        stopTimers()
    }

    private companion object {
        const val STARTUP_GRACE_MILLIS = 5_000
        const val POLL_INTERVAL_MILLIS = 2_000
    }
}

/**
 * Submits the Pi start command to a freshly created terminal tab.
 *
 * The terminal component exists before its shell is attached, and the widget
 * offers no readiness signal, so the command is sent after a short settle delay.
 * The delay is deliberately small: Pi is interactive, and a user typing during
 * it would only race with the very first command.
 */
internal class PiCommandDispatcher(
    private val settleMillis: Int = SETTLE_MILLIS,
    private val logger: Logger = Logger.getInstance(PiCommandDispatcher::class.java)
) {
    fun submit(terminal: PiTerminal, command: String, onSent: () -> Unit = {}) {
        val timer = Timer(settleMillis) {
            terminal.send(command, submit = true)
            logger.info("Sent start command to Pi terminal")
            onSent()
        }
        timer.isRepeats = false
        timer.start()
    }

    private companion object {
        const val SETTLE_MILLIS = 300
    }
}
