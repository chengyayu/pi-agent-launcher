package com.piagent.launcher.infrastructure

import javax.swing.JComponent

/**
 * The subset of a terminal tab that the plugin needs.
 *
 * Declaring it as an interface keeps the reflection-heavy JetBrains terminal API
 * out of the application layer and makes the session logic testable with a fake.
 *
 * Note there is deliberately no "is the process still running?" member.
 * For pty4j-backed local terminals the `TtyConnector` exposes a short-lived
 * spawn helper rather than the interactive shell, so `isConnected()` is always
 * false and any liveness probe reports bogus exits. See `PiSession` for how the
 * plugin handles that.
 */
interface PiTerminal {

    /** The Swing component hosting the terminal, used for focusing. */
    val component: JComponent

    /** Type [text] into the terminal, optionally submitting it. */
    fun send(text: String, submit: Boolean)

    /** True when the component is still attached to the UI hierarchy. */
    fun isAttached(): Boolean
}

/**
 * Creates (or focuses) the terminal tab Pi runs in.
 */
interface PiTerminalProvider {

    /**
     * Open a terminal tab named [tabName] rooted at [workingDirectory].
     * Implementations are expected to focus it.
     */
    fun createTerminalTab(workingDirectory: String, tabName: String): PiTerminal

    /** Bring the Pi tab to the front, if it exists. */
    fun focusTerminalTab(tabName: String)

    /** The currently created tab, if any. */
    fun currentTerminal(): PiTerminal?
}
