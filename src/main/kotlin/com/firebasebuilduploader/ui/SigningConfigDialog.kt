package com.firebasebuilduploader.ui

import com.firebasebuilduploader.model.SigningConfigData
import com.firebasebuilduploader.services.SigningConfigService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Main signing config dialog — "Use existing keystore" mode only.
 *
 * "Create New Keystore…" opens [CreateKeystoreDialog] as a sub-dialog.
 * On success, it autofills ALL four fields (path, passwords, alias) here,
 * then THIS dialog's OK triggers the actual Gradle injection.
 */
class SigningConfigDialog(private val project: Project) : DialogWrapper(project) {

    // ── Fields ───────────────────────────────────────────────────────────────
    private val keystorePathField     = TextFieldWithBrowseButton()
    private val keystorePasswordField = JPasswordField()
    private val keyAliasField         = JTextField()
    private val keyPasswordField      = JPasswordField()

    private val sameAsStorePasswordCheck =
        JCheckBox("Use same password for key as keystore")

    // ── Inline error labels ──────────────────────────────────────────────────
    private val errorLabels = mutableMapOf<JComponent, JBLabel>()

    init {
        title = "Generate Signing Configuration"
        setOKButtonText("OK")
        init()

        keystorePathField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileDescriptor()
                    .withTitle("Select Keystore File")
                    .withDescription("Choose a .jks, .keystore, or .p12 file"),
                project
            )
        )

        // "Same password" checkbox wiring
        sameAsStorePasswordCheck.addActionListener {
            keyPasswordField.isEnabled = !sameAsStorePasswordCheck.isSelected
            if (sameAsStorePasswordCheck.isSelected)
                keyPasswordField.text = String(keystorePasswordField.password)
        }
        keystorePasswordField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?)  = sync()
            override fun removeUpdate(e: DocumentEvent?)  = sync()
            override fun changedUpdate(e: DocumentEvent?) = sync()
            private fun sync() {
                if (sameAsStorePasswordCheck.isSelected)
                    keyPasswordField.text = String(keystorePasswordField.password)
            }
        })
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(GridBagLayout())
        root.preferredSize = Dimension(520, 0)
        var row = 0

        // "Create New Keystore" button — top-right
        val createNewButton = JButton("Create New Keystore…").apply {
            addActionListener { openCreateKeystoreDialog() }
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        buttonRow.add(createNewButton)
        root.add(buttonRow, gbc(0, row++, 2).apply { insets = Insets(0, 0, 12, 0) })

        addFormField(root, row++, "Key store path:",     keystorePathField)
        addFormField(root, row++, "Key store password:", keystorePasswordField)
        addFormField(root, row++, "Key alias:",          keyAliasField)
        addFormField(root, row++, "Key password:",       keyPasswordField)

        root.add(sameAsStorePasswordCheck, gbc(0, row++, 2).apply {
            insets = Insets(4, 0, 0, 0)
        })

        return root
    }

    // ── "Create New Keystore" sub-dialog ─────────────────────────────────────

    private fun openCreateKeystoreDialog() {
        val dlg = CreateKeystoreDialog(project)
        if (dlg.showAndGet()) {
            val data = dlg.getResult()
            // Autofill ALL four fields — mirrors Android Studio behavior
            keystorePathField.text          = data.keystorePath
            keystorePasswordField.text      = data.keystorePassword
            keyAliasField.text              = data.keyAlias
            keyPasswordField.text           = data.keyPassword
            // Uncheck "same password" so fields stay independently editable
            sameAsStorePasswordCheck.isSelected = false
            keyPasswordField.isEnabled          = true
        }
    }

    // ── Form helpers ─────────────────────────────────────────────────────────

    private fun addFormField(panel: JPanel, row: Int, label: String, field: JComponent) {
        panel.add(
            JBLabel(label),
            gbc(0, row, 1, weightx = 0.0).apply { insets = Insets(4, 0, 0, 8) }
        )
        val wrapper  = JPanel(BorderLayout())
        val errLabel = JBLabel(" ").apply {
            foreground = JBColor.RED
            font       = font.deriveFont(11f)
        }
        errorLabels[field] = errLabel
        wrapper.add(field,    BorderLayout.CENTER)
        wrapper.add(errLabel, BorderLayout.SOUTH)
        panel.add(wrapper, gbc(1, row, 1).apply { insets = Insets(4, 0, 0, 0) })
    }

    private fun gbc(x: Int, y: Int, w: Int, weightx: Double = 1.0) =
        GridBagConstraints().apply {
            gridx = x; gridy = y; gridwidth = w
            this.weightx = weightx
            fill   = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }

    // ── Validation ───────────────────────────────────────────────────────────

    private fun clearAllErrors() = errorLabels.values.forEach { it.text = " " }

    private fun setError(field: JComponent, message: String) {
        errorLabels[field]?.text = message
    }

    private fun validateForm(): Boolean {
        clearAllErrors()
        var valid = true

        val ksPath = keystorePathField.text.trim()
        when {
            ksPath.isBlank() -> {
                setError(keystorePathField, "Key store path is required."); valid = false
            }
            !java.io.File(ksPath).let { it.exists() && it.isFile } -> {
                setError(keystorePathField, "File does not exist."); valid = false
            }
        }

        val ksPass = String(keystorePasswordField.password)
        if (ksPass.isBlank()) {
            setError(keystorePasswordField, "Key store password is required."); valid = false
        } else if (ksPass.length < 6) {
            setError(keystorePasswordField, "Password must be at least 6 characters."); valid = false
        }

        if (keyAliasField.text.trim().isBlank()) {
            setError(keyAliasField, "Key alias is required."); valid = false
        }

        val keyPass = String(keyPasswordField.password)
        if (keyPass.isBlank()) {
            setError(keyPasswordField, "Key password is required."); valid = false
        } else if (keyPass.length < 6) {
            setError(keyPasswordField, "Password must be at least 6 characters."); valid = false
        }

        return valid
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        if (validateForm()) null
        else com.intellij.openapi.ui.ValidationInfo("Please fix the highlighted fields.")

    // ── OK — THIS is where Gradle injection happens ───────────────────────────

    override fun doOKAction() {
        if (!validateForm()) return

        val data = SigningConfigData(
            isNewKeystore    = false,
            keystorePath     = keystorePathField.text.trim(),
            keystorePassword = String(keystorePasswordField.password),
            keyAlias         = keyAliasField.text.trim(),
            keyPassword      = String(keyPasswordField.password)
        )

        val service = project.service<SigningConfigService>()
        val error   = service.applySigningConfig(data)
        if (error != null) {
            JOptionPane.showMessageDialog(
                contentPanel, error,
                "Signing Config Failed", JOptionPane.ERROR_MESSAGE
            )
            return
        }

        super.doOKAction()
    }

    // ── Result ───────────────────────────────────────────────────────────────

    /** Call after showAndGet() returns true. */
    fun toSigningConfigData(): SigningConfigData = SigningConfigData(
        isNewKeystore    = false,
        keystorePath     = keystorePathField.text.trim(),
        keystorePassword = String(keystorePasswordField.password),
        keyAlias         = keyAliasField.text.trim(),
        keyPassword      = String(keyPasswordField.password)
    )
}