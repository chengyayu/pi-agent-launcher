package com.piagent.launcher.infrastructure

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * User-facing notifications, so services do not depend on the notification API
 * directly (and can be exercised with a no-op implementation).
 */
interface PiNotifier {
    fun info(message: String)
    fun warn(title: String, content: String, actionTitle: String? = null, onAction: (() -> Unit)? = null)
    fun error(message: String)
}

class IdePiNotifier(private val project: Project) : PiNotifier {

    override fun info(message: String) = notify(message, NotificationType.INFORMATION)

    override fun error(message: String) = notify(message, NotificationType.ERROR)

    override fun warn(
        title: String,
        content: String,
        actionTitle: String?,
        onAction: (() -> Unit)?
    ) {
        val notification = group().createNotification(title, content, NotificationType.WARNING)
        if (actionTitle != null && onAction != null) {
            notification.addAction(object : NotificationAction(actionTitle) {
                override fun actionPerformed(e: AnActionEvent, n: Notification) {
                    n.expire()
                    onAction()
                }
            })
        }
        notification.notify(project)
    }

    private fun notify(message: String, type: NotificationType) {
        group().createNotification(message, type).notify(project)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)

    private companion object {
        const val GROUP_ID = "Pi Agent"
    }
}
