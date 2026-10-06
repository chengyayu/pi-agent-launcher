package com.piagent.launcher.services

import com.piagent.launcher.settings.PiSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches for file changes made by Pi.
 * - Auto-opens modified files in the editor
 * - Shows notification when Pi finishes modifying files
 *
 * VFS change notifications arrive on a background thread and fire once per
 * write. Opening an editor from that thread (and doing it for every file the
 * project touches, e.g. during `go build` / indexing) is what used to freeze
 * the IDE, so all editor work is debounced and dispatched to the EDT here.
 */
@Service(Service.Level.PROJECT)
class PiFileWatcher(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(PiFileWatcher::class.java)
    private val modifiedFiles = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var isWatching = false
    private var connection: MessageBusConnection? = null

    private val debouncer = PiChangeDebouncer(AUTO_OPEN_DEBOUNCE_MS) { paths ->
        openChangedFiles(paths)
    }

    fun startWatching() {
        if (isWatching) return
        isWatching = true
        modifiedFiles.clear()
        debouncer.clear()

        // Disconnect previous listener if any
        connection?.disconnect()

        connection = project.messageBus.connect(this).also { conn ->
            conn.subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        if (!isWatching) return
                        val autoOpen = PiSettings.getInstance().state.autoOpenFiles
                        for (event in events) {
                            if (event !is VFileContentChangeEvent) continue
                            val file = event.file
                            if (!PiVfsUtils.isRelevantChange(project, file)) continue

                            modifiedFiles.add(file.path)
                            if (autoOpen) {
                                debouncer.submit(file.path)
                            }
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

        val settings = PiSettings.getInstance().state
        if (settings.showNotifications && modifiedFiles.isNotEmpty()) {
            showCompletionNotification()
        }

        modifiedFiles.clear()
    }

    /**
     * Runs on the EDT. Opens at most [MAX_AUTO_OPEN_FILES] files per batch so
     * a project-wide rewrite cannot flood the editor with hundreds of tabs.
     */
    private fun openChangedFiles(paths: List<String>) {
        if (!isWatching) return

        // Never touch the editor model while the IDE is indexing: it is both
        // useless (the files are not yet available) and the exact window in
        // which GoLand's lock deadlock is triggered.
        if (DumbService.getInstance(project).isDumb) return

        val fileEditorManager = FileEditorManager.getInstance(project)
        var opened = 0
        for (path in paths) {
            if (opened >= MAX_AUTO_OPEN_FILES) break

            val file = LocalFileSystem.getInstance().findFileByPath(path) ?: continue
            if (!file.isValid) continue
            if (fileEditorManager.isFileOpen(file)) continue

            fileEditorManager.openFile(file, false)
            opened++
        }
    }

    private fun showCompletionNotification() {
        val count = modifiedFiles.size
        val message = if (count == 1) {
            "Pi modified 1 file"
        } else {
            "Pi modified $count files"
        }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Agent")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    override fun dispose() {
        isWatching = false
        connection?.disconnect()
        connection = null
        debouncer.dispose()
        modifiedFiles.clear()
    }

    companion object {
        /** Wait for pi to stop writing before touching the editor. */
        private const val AUTO_OPEN_DEBOUNCE_MS = 600

        /** Hard cap on auto-opened editors per write burst. */
        private const val MAX_AUTO_OPEN_FILES = 10

        fun getInstance(project: Project): PiFileWatcher = project.service()
    }
}
