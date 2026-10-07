package com.piagent.launcher.infrastructure

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.JComponent

/**
 * [PiTerminalProvider] backed by the IDE's Terminal tool window.
 *
 * The terminal API is reached through reflection because it has changed shape
 * across platform versions; keeping all of that in one place means the rest of
 * the plugin never sees a `Class.forName`.
 */
class IdeTerminalProvider(private val project: Project) : PiTerminalProvider {

    private val logger = Logger.getInstance(IdeTerminalProvider::class.java)

    @Volatile
    private var created: PiTerminal? = null

    override fun currentTerminal(): PiTerminal? = created

    override fun createTerminalTab(workingDirectory: String, tabName: String): PiTerminal {
        val manager = terminalToolWindowManager()
        val widget = manager.javaClass
            .getMethod("createLocalShellWidget", String::class.java, String::class.java)
            .invoke(manager, workingDirectory, tabName)
            ?: error("createLocalShellWidget returned null")
        val terminal = ReflectiveTerminal(widget)
        created = terminal
        focusTerminalTab(tabName)
        return terminal
    }

    override fun focusTerminalTab(tabName: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW)
            ?: return

        toolWindow.show {
            val contentManager = toolWindow.contentManager
            contentManager.contents
                .firstOrNull { it.displayName == tabName }
                ?.let { contentManager.setSelectedContent(it, true) }

            created?.component?.requestFocusInWindow()
        }
    }

    private fun terminalToolWindowManager(): Any {
        val managerClass = Class.forName(TERMINAL_MANAGER_CLASS)
        return managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            ?: error("TerminalToolWindowManager.getInstance returned null")
    }

    /**
     * Thin adapter over `ShellTerminalWidget`; every member is resolved lazily so
     * a platform rename surfaces as a logged warning rather than a crash.
     */
    private class ReflectiveTerminal(private val widget: Any) : PiTerminal {

        private val logger = Logger.getInstance(ReflectiveTerminal::class.java)

        override val component: JComponent by lazy {
            widget.javaClass.getMethod("getComponent").invoke(widget) as JComponent
        }

        override fun isAttached(): Boolean = component.parent != null

        override fun isProcessAlive(): Boolean = try {
            val connector = widget.javaClass.getMethod("getTtyConnector").invoke(widget)
            if (connector == null) {
                false
            } else {
                connector.javaClass.getMethod("isConnected").invoke(connector) as Boolean
            }
        } catch (e: Exception) {
            logger.debug("Terminal process state unavailable", e)
            false
        }

        override fun send(text: String, submit: Boolean) {
            try {
                if (submit) {
                    widget.javaClass.getMethod("executeCommand", String::class.java)
                        .invoke(widget, text)
                } else {
                    val starter = widget.javaClass.getMethod("getTerminalStarter").invoke(widget)
                        ?: return
                    starter.javaClass
                        .getMethod("sendString", String::class.java, Boolean::class.javaPrimitiveType)
                        .invoke(starter, text, false)
                }
            } catch (e: Exception) {
                logger.warn("Failed to send text to the Pi terminal: ${e.message}")
            }
        }
    }

    private companion object {
        const val TERMINAL_MANAGER_CLASS = "org.jetbrains.plugins.terminal.TerminalToolWindowManager"
        const val TERMINAL_TOOL_WINDOW = "Terminal"
    }
}
