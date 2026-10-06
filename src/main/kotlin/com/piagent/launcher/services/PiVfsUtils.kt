package com.piagent.launcher.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.io.FileUtil

/**
 * Shared helpers deciding whether a VFS change is something the Pi plugin
 * should react to.
 *
 * Guarding the watchers with these checks is what keeps a `go build` /
 * `gofmt -w ./...` / `git checkout` inside the project from opening hundreds
 * of editors (and, on GoLand 2026.2, from triggering the platform's
 * read/write-lock deadlock around [EditorHistoryManager] and index reparse).
 */
object PiVfsUtils {

    /**
     * A change is relevant only when it is a real, text content change of a
     * file that belongs to the project's content (not an excluded directory,
     * not ignored by VCS/file-type rules, not binary).
     *
     * Safe to call from a background thread (VFS change listener thread).
     */
    fun isRelevantChange(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory) return false

        val basePath = project.basePath ?: return false
        if (!FileUtil.isAncestor(basePath, file.path, false)) return false

        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        if (!fileIndex.isInContent(file)) return false

        if (FileTypeRegistry.getInstance().isFileIgnored(file)) return false
        if (file.fileType.isBinary) return false

        return true
    }

    fun runOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }
}
