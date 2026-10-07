package com.piagent.launcher.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * Settings → Tools → Pi Agent.
 *
 * Delegates the form itself to [PiSettingsForm]; this class only implements the
 * platform lifecycle contract.
 */
class PiSettingsConfigurable(
    private val settings: PiSettings = PiSettings.getInstance()
) : Configurable {

    private var form: PiSettingsForm? = null

    override fun getDisplayName(): String = "Pi Agent"

    override fun createComponent(): JComponent =
        PiSettingsForm(settings.state, PiSettingsForm.loadModelOptions())
            .also { form = it }
            .panel

    override fun isModified(): Boolean =
        form?.toState()?.let { it != settings.state } ?: false

    override fun apply() {
        form?.toState()?.let { settings.loadState(it) }
    }

    override fun reset() {
        form?.populate(settings.state)
    }

    override fun disposeUIResources() {
        form = null
    }
}
