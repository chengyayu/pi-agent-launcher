package com.piagent.launcher.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.piagent.launcher.domain.PiCommandLine
import com.piagent.launcher.domain.PiLaunchOptions
import com.piagent.launcher.infrastructure.PiNotifier
import com.piagent.launcher.infrastructure.PiTerminalProvider

/**
 * The Pi session logic: one terminal tab, started once, with the start command
 * derived from the current settings.
 *
 * This is a plain class rather than an IntelliJ service on purpose. Services may
 * only have a `(Project)` / `(Project, CoroutineScope)` constructor, and keeping
 * the collaborators here also means the behaviour can be driven with fakes in
 * tests - hence the absence of any `Project` dependency.
 *
 * There is intentionally **no** process watching. Beyond being unwanted noise, it
 * cannot work: for pty4j-backed local terminals the `TtyConnector` wraps a
 * short-lived spawn helper rather than the interactive shell, so any "is it still
 * alive?" probe reports an immediate exit. The session is therefore considered
 * running from the moment its tab is created, and the next click on the toolbar
 * button simply focuses that tab.
 */
internal class PiSession(
    private val workingDirectory: () -> String,
    private val publishStatus: (PiSessionStatus) -> Unit,
    private val terminals: PiTerminalProvider,
    private val notifier: PiNotifier,
    private val launchOptions: () -> PiLaunchOptions,
    private val commandDispatcher: PiCommandDispatcher = PiCommandDispatcher()
) : Disposable {

    private val logger = Logger.getInstance(PiSession::class.java)
    private val state = PiSessionState(publishStatus)

    val status: PiSessionStatus get() = state.status

    fun isRunning(): Boolean = state.status == PiSessionStatus.RUNNING

    /** Start Pi, or focus it when it is already running. */
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
            val terminal = terminals.createTerminalTab(workingDirectory(), TAB_NAME)
            state.markRunning(terminal)
            commandDispatcher.submit(terminal, PiCommandLine.render(launchOptions()))
        } catch (e: Exception) {
            // Handled: the user gets a notification, so this is a warning rather
            // than an IDE internal error.
            logger.warn("Failed to launch Pi", e)
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
        state.markIdle()
    }

    private fun isTerminalAttached(): Boolean = state.terminal?.isAttached() == true

    override fun dispose() = reset()

    companion object {
        const val TAB_NAME = "Pi"
    }
}
