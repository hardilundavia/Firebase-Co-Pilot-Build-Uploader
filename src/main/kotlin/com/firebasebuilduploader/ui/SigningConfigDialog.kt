package com.firebasebuilduploader.ui

import com.firebasebuilduploader.model.SigningConfigData
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Dialog for creating a new keystore or linking an existing one, then
 * injecting a release signingConfig into app/build.gradle.
 *
 * Mimics Android Studio's native "Generate Signed Bundle / APK" → key store
 * step, with two modes: "Create new..." and "Choose existing...".
 */
class SigningConfigDialog(private val project: Project) : DialogWrapper(project) {

    // ── Mode toggle ──────────────────────────────────────────────────────
    private val createNewRadio = JBRadioButton("Create new keystore", true)
    private val useExistingRadio = JBRadioButton("Use existing keystore")

    // ── Shared fields ────────────────────────────────────────────────────
    private val keystorePathField = TextFieldWithBrowseButton()
    private val keystorePasswordField = JPasswordField()
    private val keyAliasField = JTextField()
    private val keyPasswordField = JPasswordField()
    private val sameAsStorePasswordCheck = JCheckBox("Use same password for key as keystore")

    // ── "Create new" only fields ────────────────────────────────────────
    private val confirmKeystorePasswordField = JPasswordField()
    private val confirmKeyPasswordField = JPasswordField()
    private val validityField = JTextField("25")
    private val firstLastNameField = JTextField()
    private val orgUnitField = JTextField()
    private val orgField = JTextField()
    private val cityField = JTextField()
    private val stateField = JTextField()
    private val countryCodeField = JTextField()

    // ── Error labels (inline, below each field) ─────────────────────────
    private val errorLabels = mutableMapOf<JComponent, JBLabel>()

    // ── Layout containers that get swapped based on mode ────────────────
    private val createOnlyPanel = JPanel(GridBagLayout())
    private val centerPanel = JPanel(BorderLayout())

    init {
        title = "Generate Signing Configuration"
        setOKButtonText("OK")
        init()

        // Default keystore path suggestion: <project>/app/release.jks
        val projectDir = project.basePath
        if (projectDir != null) {
            keystorePathField.text = "$projectDir/app/release.jks"
        }

        keystorePathField.addBrowseFolderListener(
            "Select Keystore File",
            "Choose a .jks or .keystore file",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        sameAsStorePasswordCheck.addActionListener {
            keyPasswordField.isEnabled = !sameAsStorePasswordCheck.isSelected
            if (sameAsStorePasswordCheck.isSelected) {
                keyPasswordField.text = String(keystorePasswordField.password)
            }
        }
        keystorePasswordField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = syncKeyPassword()
            override fun removeUpdate(e: DocumentEvent?) = syncKeyPassword()
            override fun changedUpdate(e: DocumentEvent?) = syncKeyPassword()
            private fun syncKeyPassword() {
                if (sameAsStorePasswordCheck.isSelected) {
                    keyPasswordField.text = String(keystorePasswordField.password)
                }
            }
        })

        val modeListener = { updateMode() }
        createNewRadio.addActionListener { modeListener() }
        useExistingRadio.addActionListener { modeListener() }

        updateMode()
    }

    // ── UI construction ──────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 12))
        root.preferredSize = Dimension(520, 460)

        // Mode selector
        val modeGroup = ButtonGroup().apply { add(createNewRadio); add(useExistingRadio) }
        val modePanel = JPanel(GridLayout(2, 1, 0, 4)).apply {
            border = JBUI.Borders.empty(4, 0)
            add(createNewRadio)
            add(useExistingRadio)
        }
        root.add(modePanel, BorderLayout.NORTH)

        // Keystore path row (shared)
        val pathPanel = formRow("Key store path:", keystorePathField)
        root.add(pathPanel, BorderLayout.PAGE_START)

        centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
        root.add(centerPanel, BorderLayout.CENTER)

        // Shared lower section: passwords + alias
        val sharedPanel = JPanel(GridBagLayout())
        var row = 0
        addFormField(sharedPanel, row++, "Key store password:", keystorePasswordField)
        addFormField(sharedPanel, row++, "Confirm password:", confirmKeystorePasswordField)
        addFormField(sharedPanel, row++, "Key alias:", keyAliasField)
        addFormField(sharedPanel, row++, "Key password:", keyPasswordField)
        addFormField(sharedPanel, row++, "Confirm key password:", confirmKeyPasswordField)
        sharedPanel.add(sameAsStorePasswordCheck, gbc(0, row++, 2))

        createOnlyPanel.layout = GridBagLayout()
        var r = 0
        addFormField(createOnlyPanel, r++, "Validity (years):", validityField)
        createOnlyPanel.add(JBLabel("Certificate").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc(0, r++, 2))
        addFormField(createOnlyPanel, r++, "First and Last Name:", firstLastNameField)
        addFormField(createOnlyPanel, r++, "Organizational Unit:", orgUnitField)
        addFormField(createOnlyPanel, r++, "Organization:", orgField)
        addFormField(createOnlyPanel, r++, "City or Locality:", cityField)
        addFormField(createOnlyPanel, r++, "State or Province:", stateField)
        addFormField(createOnlyPanel, r++, "Country Code (XX):", countryCodeField)

        val combined = JPanel()
        combined.layout = BoxLayout(combined, BoxLayout.Y_AXIS)
        combined.add(sharedPanel)
        combined.add(createOnlyPanel)

        centerPanel.add(combined)

        return root
    }

    private fun formRow(label: String, field: JComponent): JPanel {
        val panel = JPanel(BorderLayout(8, 0))
        panel.border = JBUI.Borders.empty(4, 0)
        val l = JBLabel(label)
        l.preferredSize = Dimension(140, l.preferredSize.height)
        panel.add(l, BorderLayout.WEST)

        val wrapper = JPanel(BorderLayout())
        wrapper.add(field, BorderLayout.CENTER)
        val errLabel = JBLabel(" ").apply {
            foreground = JBColor.RED
            font = font.deriveFont(11f)
        }
        errorLabels[field] = errLabel
        wrapper.add(errLabel, BorderLayout.SOUTH)
        panel.add(wrapper, BorderLayout.CENTER)
        return panel
    }

    private fun addFormField(panel: JPanel, row: Int, label: String, field: JComponent) {
        val l = JBLabel(label)
        panel.add(l, gbc(0, row, 1, weightx = 0.0).apply { insets = Insets(4, 0, 0, 8) })

        val wrapper = JPanel(BorderLayout())
        wrapper.add(field, BorderLayout.CENTER)
        val errLabel = JBLabel(" ").apply {
            foreground = JBColor.RED
            font = font.deriveFont(11f)
        }
        errorLabels[field] = errLabel
        wrapper.add(errLabel, BorderLayout.SOUTH)

        panel.add(wrapper, gbc(1, row, 1).apply { insets = Insets(4, 0, 0, 0) })
    }

    private fun gbc(x: Int, y: Int, w: Int, weightx: Double = 1.0): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x; gridy = y; gridwidth = w
            this.weightx = weightx
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }

    // ── Mode switching ───────────────────────────────────────────────────

    private fun updateMode() {
        val isNew = createNewRadio.isSelected
        confirmKeystorePasswordField.isVisible = isNew
        confirmKeystorePasswordField.parent?.let { it.parent?.let { p -> } }
        errorLabels[confirmKeystorePasswordField]?.parent?.isVisible = isNew
        confirmKeystorePasswordField.isEnabled = isNew

        confirmKeyPasswordField.isEnabled = isNew
        errorLabels[confirmKeyPasswordField]?.parent?.isVisible = isNew

        createOnlyPanel.isVisible = isNew

        if (isNew) {
            keystorePathField.addBrowseFolderListener(
                "Save Keystore As",
                "Choose where to save the new keystore file",
                project,
                FileChooserDescriptorFactory.createSingleFileDescriptor()
            )
        }

        clearAllErrors()
        pack()
    }

    // ── Validation ────────────────────────────────────────────────────────

    private fun clearAllErrors() {
        errorLabels.values.forEach { it.text = " " }
    }

    private fun setError(field: JComponent, message: String?) {
        errorLabels[field]?.text = message ?: " "
    }

    /** Returns true if all fields are valid; populates inline error labels otherwise. */
    private fun validateForm(): Boolean {
        clearAllErrors()
        var valid = true

        val ksPath = keystorePathField.text.trim()
        if (ksPath.isBlank()) {
            setError(keystorePathField, "Key store path is required."); valid = false
        } else if (createNewRadio.isSelected) {
            if (!ksPath.endsWith(".jks") && !ksPath.endsWith(".keystore")) {
                setError(keystorePathField, "Path must end with .jks or .keystore."); valid = false
            }
        } else {
            val f = java.io.File(ksPath)
            if (!f.exists() || !f.isFile) {
                setError(keystorePathField, "File does not exist."); valid = false
            }
        }

        val ksPass = String(keystorePasswordField.password)
        if (ksPass.isBlank()) {
            setError(keystorePasswordField, "Key store password is required."); valid = false
        } else if (ksPass.length < 6) {
            setError(keystorePasswordField, "Password must be at least 6 characters."); valid = false
        }

        if (createNewRadio.isSelected) {
            val confirm = String(confirmKeystorePasswordField.password)
            if (confirm != ksPass) {
                setError(confirmKeystorePasswordField, "Passwords do not match."); valid = false
            }
        }

        val alias = keyAliasField.text.trim()
        if (alias.isBlank()) {
            setError(keyAliasField, "Key alias is required."); valid = false
        }

        val keyPass = String(keyPasswordField.password)
        if (keyPass.isBlank()) {
            setError(keyPasswordField, "Key password is required."); valid = false
        } else if (keyPass.length < 6) {
            setError(keyPasswordField, "Password must be at least 6 characters."); valid = false
        }

        if (createNewRadio.isSelected) {
            val confirmKey = String(confirmKeyPasswordField.password)
            if (confirmKey != keyPass) {
                setError(confirmKeyPasswordField, "Passwords do not match."); valid = false
            }

            val validity = validityField.text.trim().toIntOrNull()
            if (validity == null || validity <= 0) {
                setError(validityField, "Enter a valid number of years."); valid = false
            }

            if (firstLastNameField.text.trim().isBlank()) {
                setError(firstLastNameField, "Required for certificate generation."); valid = false
            }

            val cc = countryCodeField.text.trim()
            if (cc.isNotBlank() && !Regex("""^[A-Za-z]{2}$""").matches(cc)) {
                setError(countryCodeField, "Use a 2-letter country code (e.g. US)."); valid = false
            }
        }

        return valid
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        // Use inline field errors instead of a single top banner; only block OK.
        return if (validateForm()) null
        else com.intellij.openapi.ui.ValidationInfo("Please fix the highlighted fields.")
    }

    // ── Result extraction ───────────────────────────────────────────────

    /** Call after showAndGet() returns true. */
    fun toSigningConfigData(): SigningConfigData {
        val isNew = createNewRadio.isSelected
        return SigningConfigData(
            isNewKeystore = isNew,
            keystorePath = keystorePathField.text.trim(),
            keystorePassword = String(keystorePasswordField.password),
            keyAlias = keyAliasField.text.trim(),
            keyPassword = String(keyPasswordField.password),
            validityYears = validityField.text.trim().toIntOrNull() ?: 25,
            firstAndLastName = firstLastNameField.text.trim(),
            organizationalUnit = orgUnitField.text.trim(),
            organization = orgField.text.trim(),
            city = cityField.text.trim(),
            state = stateField.text.trim(),
            countryCode = countryCodeField.text.trim().uppercase()
        )
    }
}