package com.piagent.launcher.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.piagent.launcher.domain.PiCommandLine
import com.piagent.launcher.infrastructure.IdePiNotifier
import com.piagent.launcher.infrastructure.IdeTerminalProvider
import com.piagent.launcher.infrastructure.PiNotifier
import com.piagent.launcher.infrastructure.PiTerminalProvider
import com.piagent.launcher.settings.PiSettings
import com.piagent.launcher.settings.toLaunchOptions

/**
 * Owns the lifecycle of the Pi session: one terminal tab, started once, with the
 * start command derived from the user's settings.
 *
 * Responsibilities are intentionally narrow; terminal plumbing lives in
 * [PiTerminalProvider], command rendering in [PiCommandLine], and exit detection
 * in [PiProcessMonitor].
 */
@Service(Service.Level.PROJECT)
class PiSessionService(
    private val project: Project,
    private val terminals: PiTerminalProvider = IdeTerminalProvider(project),
    private val notifier: PiNotifier = IdePiNotifier(project)
) : Disposable {

    private val logger = Logger.getInstance(PiSessionService::class.java)
    private val state = PiSessionState(PiSessionState.publisherFor(project))

    private var monitor: PiProcessMonitor? = null

    val status: PiSessionStatus get() = state.status

    fun isRunning(): Boolean = state.status == PiSessionStatus.RUNNING

    /**
     * Start Pi, or focus it when it is already running.
     */
    fun launch() {
        if (isRunning() && isTerminalAttached()) {
            terminals.focusTerminalTab(TAB_NAME)
            return
        }

        if (isRunning()) {
            logger.info("Pi terminal is gone; restarting")
            reset()
        }

        try {
            notifier.info("Starting Pi...")
            val terminal = terminals.createTerminalTab(workingDirectory(), TAB_NAME)
            state.markRunning(terminal)
            PiCommandDispatcher().submit(terminal, PiCommandLine.render(settingsLaunchOptions()))
            monitor = PiProcessMonitor(terminal, onExit = ::onProcessExited).also {
                Disposer.register(this, it)
                it.start()
            }
        } catch (e: Exception) {
            logger.error("Failed to launch Pi", e)
            state.markIdle()
            notifier.error("Failed to start Pi: ${e.message}")
        }
    }

    /** Send [text] to Pi and submit it. */
    fun submitText(text: String) {
        state.terminal?.send(text, submit = true)
    }

    /** Type [text] into Pi's prompt without submitting it. */
    fun insertText(text: String) {
        state.terminal?.send(text, submit = false)
    }

    fun focus() {
        terminals.focusTerminalTab(TAB_NAME)
    }

    /** Drop all session state so the next [launch] starts fresh. */
    fun reset() {
        monitor?.let {
            Disposer.dispose(it)
            monitor = null
        }
        state.markIdle()
    }

    private fun onProcessExited() {
        reset()
        notifier.warn(
            title = "Pi process exited unexpectedly",
            content = "Click to restart Pi",
            actionTitle = "Restart Pi",
            onAction = ::launch
        )
    }

    private fun isTerminalAttached(): Boolean =
        state.terminal?.isAttached() == true

    private fun workingDirectory(): String =
        project.basePath ?: System.getProperty("user.home")

    private fun settingsLaunchOptions() =
        PiSettings.getInstance().state.toLaunchOptions()

    override fun dispose() {
        reset()
    }

    companion object {
        const val TAB_NAME = "Pi"

        fun getInstance(project: Project): PiSessionService = project.service()
    }
}
