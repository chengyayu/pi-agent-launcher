package com.piagent.launcher.infrastructure

import javax.swing.JComponent

/**
 * The subset of a terminal tab that the plugin needs.
 *
 * Declaring it as an interface keeps the reflection-heavy JetBrains terminal
 * API out of the application layer and makes the session service testable with
 * a fake.
 */
interface PiTerminal {

    /** The Swing component hosting the terminal, used for focusing. */
    val component: JComponent

    /** True while the underlying process is still attached. */
    fun isProcessAlive(): Boolean

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
