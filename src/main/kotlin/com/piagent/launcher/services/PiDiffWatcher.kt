package com.piagent.launcher.services

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
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
 * Watches for file changes made by Pi and shows diff previews in the IDE.
 *
 * The diff is a heavyweight UI operation that must run on the EDT and that
 * used to be invoked directly from the background VFS listener for every
 * single write. Pi writes a file repeatedly while editing, so the IDE ended
 * up opening a new "Before Pi / After Pi" diff on every keystroke-sized
 * change — a diff storm that (on GoLand 2026.2) walks straight into the
 * platform's read/write-lock deadlock.
 *
 * Now changes are debounced, filtered, and shown at most once per file.
 */
@Service(Service.Level.PROJECT)
class PiDiffWatcher(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(PiDiffWatcher::class.java)
    private val snapshots = ConcurrentHashMap<String, String>()

    /** Files whose diff has already been shown, so we do not re-open it. */
    private val shownFiles = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var isWatching = false
    private var connection: MessageBusConnection? = null

    private val debouncer = PiChangeDebouncer(DIFF_DEBOUNCE_MS) { paths ->
        paths.forEach { showDiffOnEdt(it) }
    }

    fun startWatching() {
        if (isWatching) return
        isWatching = true

        snapshotOpenFiles()
        debouncer.clear()

        connection?.disconnect()

        connection = project.messageBus.connect(this).also { conn ->
            conn.subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        if (!isWatching) return
                        for (event in events) {
                            if (event !is VFileContentChangeEvent) continue
                            handleFileChange(event.file)
                        }
                    }
                }
            )
        }

        logger.info("Pi diff watcher started, tracking ${snapshots.size} files")
    }

    fun stopWatching() {
        isWatching = false
        connection?.disconnect()
        connection = null
        debouncer.clear()
        snapshots.clear()
        shownFiles.clear()
    }

    fun snapshotFile(filePath: String) {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return
        snapshots[filePath] = document.text
    }

    /**
     * Show the committed snapshot against the current file content.
     * Always dispatched to the EDT.
     */
    fun showDiff(filePath: String) {
        PiVfsUtils.runOnEdt { showDiffOnEdt(filePath) }
    }

    private fun showDiffOnEdt(filePath: String) {
        if (!isWatching) return
        if (DumbService.getInstance(project).isDumb) return

        val originalContent = snapshots[filePath] ?: return
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        if (!vFile.isValid || vFile.isDirectory || vFile.fileType.isBinary) return

        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return
        val newContent = document.text

        if (originalContent == newContent) return

        // One diff per file: re-showing on every subsequent content change is
        // what turned normal edits into a UI freeze.
        if (!shownFiles.add(filePath)) return

        val diffContentFactory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Pi Agent: Changes to ${vFile.name}",
            diffContentFactory.create(originalContent),
            diffContentFactory.create(project, document),
            "Before Pi",
            "After Pi"
        )

        DiffManager.getInstance().showDiff(project, request)
    }

    private fun snapshotOpenFiles() {
        val projectPath = project.basePath ?: return
        val fileDocManager = FileDocumentManager.getInstance()
        fileDocManager.unsavedDocuments.forEach { doc ->
            val vFile = fileDocManager.getFile(doc)
            if (vFile != null && vFile.path.startsWith(projectPath)) {
                snapshots[vFile.path] = doc.text
            }
        }
    }

    private fun handleFileChange(file: VirtualFile) {
        if (!PiVfsUtils.isRelevantChange(project, file)) return

        val filePath = file.path
        if (!snapshots.containsKey(filePath)) return
        if (shownFiles.contains(filePath)) return

        debouncer.submit(filePath)
    }

    override fun dispose() {
        isWatching = false
        connection?.disconnect()
        connection = null
        debouncer.dispose()
        snapshots.clear()
        shownFiles.clear()
    }

    companion object {
        /** Wait for pi to stop writing before opening the diff editor. */
        private const val DIFF_DEBOUNCE_MS = 600

        fun getInstance(project: Project): PiDiffWatcher = project.service()
    }
}
