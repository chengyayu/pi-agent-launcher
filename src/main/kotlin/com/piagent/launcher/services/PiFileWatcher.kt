package com.piagent.launcher.services

import com.piagent.launcher.settings.PiSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection

/**
 * Watches for file changes made by Pi.
 *
 * The plugin deliberately does **not** open diffs or notifications for those
 * changes: a single Pi session can rewrite dozens of files, and surfacing all
 * of them as editor tabs is unusable. Use `git diff` to review the work.
 *
 * The only thing left here is the optional "auto-open files" convenience, which
 * is off by default and, when enabled, is debounced, filtered, capped and
 * dispatched to the EDT.
 */
@Service(Service.Level.PROJECT)
class PiFileWatcher(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(PiFileWatcher::class.java)

    @Volatile
    private var isWatching = false
    private var connection: MessageBusConnection? = null

    private val debouncer = PiChangeDebouncer(AUTO_OPEN_DEBOUNCE_MS) { paths ->
        // During indexing the editor model must not be touched; report those
        // paths back so the debouncer retries them instead of dropping them.
        if (DumbService.getInstance(project).isDumb) {
            paths
        } else {
            openChangedFiles(paths)
            emptyList()
        }
    }

    fun startWatching() {
        if (isWatching) return
        isWatching = true
        debouncer.clear()

        connection?.disconnect()

        connection = project.messageBus.connect(this).also { conn ->
            conn.subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        if (!isWatching) return
                        if (!PiSettings.getInstance().state.autoOpenFiles) return

                        for (event in events) {
                            if (event !is VFileContentChangeEvent) continue
                            val file = event.file
                            if (!PiVfsUtils.isRelevantChange(project, file)) continue
                            debouncer.submit(file.path)
                        }
                    }
                }
            )
        }

        logger.info("Pi file watcher started")
    }

    fun stopWatching() {
        if (!isWatching) return
        isWatching = false
        connection?.disconnect()
        connection = null
        debouncer.clear()
    }

    /**
     * Runs on the EDT. Opens at most [MAX_AUTO_OPEN_FILES] files per batch so a
     * project-wide rewrite cannot flood the editor with hundreds of tabs.
     */
    private fun openChangedFiles(paths: List<String>) {
        if (!isWatching) return

        val fileEditorManager = FileEditorManager.getInstance(project)
        var opened = 0
        for (path in paths) {
            if (opened >= MAX_AUTO_OPEN_FILES) break

            val file: VirtualFile = LocalFileSystem.getInstance().findFileByPath(path) ?: continue
            if (!file.isValid) continue
            if (fileEditorManager.isFileOpen(file)) continue

            fileEditorManager.openFile(file, false)
            opened++
        }
    }

    override fun dispose() {
        isWatching = false
        connection?.disconnect()
        connection = null
        debouncer.dispose()
    }

    companion object {
        /** Wait for pi to stop writing before touching the editor. */
        private const val AUTO_OPEN_DEBOUNCE_MS = 600

        /** Hard cap on auto-opened editors per write burst. */
        private const val MAX_AUTO_OPEN_FILES = 10

        fun getInstance(project: Project): PiFileWatcher = project.service()
    }
}
