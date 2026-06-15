package com.firebasebuilduploader.ui

import com.firebasebuilduploader.model.SigningConfigData
import com.firebasebuilduploader.services.SigningConfigService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Sub-dialog for creating a brand-new .jks / .p12 keystore.
 *
 * Responsibility: ONLY generate the .jks file via keytool.
 * It does NOT touch build.gradle — that is SigningConfigDialog's job.
 *
 * On OK success → [getResult] returns the data so SigningConfigDialog
 * can auto-fill its fields and later inject Gradle config on its own OK.
 */
class CreateKeystoreDialog(private val project: Project) : DialogWrapper(project) {

    // ── Fields ───────────────────────────────────────────────────────────────
    private val keystorePathField          = TextFieldWithBrowseButton()
    private val keystorePasswordField      = JPasswordField()
    private val keystorePasswordConfField  = JPasswordField()   // confirm
    private val keyAliasField              = JTextField()
    private val keyPasswordField           = JPasswordField()
    private val keyPasswordConfField       = JPasswordField()   // confirm
    private val sameAsStorePasswordCheck   = JCheckBox("Use same password for key as keystore")
    private val validityField              = JTextField("25")
    private val firstLastNameField         = JTextField()
    private val orgUnitField               = JTextField()
    private val orgField                   = JTextField()
    private val cityField                  = JTextField()
    private val stateField                 = JTextField()
    private val countryCodeField           = JTextField()

    // ── Inline error labels ──────────────────────────────────────────────────
    private val errorLabels = mutableMapOf<JComponent, JBLabel>()

    // ── Result set after successful keystore generation ──────────────────────
    private lateinit var result: SigningConfigData

    init {
        title = "Create New Keystore"
        setOKButtonText("Create")
        init()

        project.basePath?.let { keystorePathField.text = "$it/app/release.jks" }

        keystorePathField.addBrowseFolderListener(
            "Choose Keystore Save Location",
            "The new .jks file will be written here",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        // "Same password" checkbox wiring —————————————————————————————————————
        // When checked: disable both key password fields and mirror store password
        sameAsStorePasswordCheck.addActionListener {
            val same = sameAsStorePasswordCheck.isSelected
            keyPasswordField.isEnabled     = !same
            keyPasswordConfField.isEnabled = !same
            if (same) {
                keyPasswordField.text     = String(keystorePasswordField.password)
                keyPasswordConfField.text = String(keystorePasswordConfField.password)
            }
        }

        // Mirror store-password → key-password fields when checkbox is on
        val storeSync = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?)  = sync()
            override fun removeUpdate(e: DocumentEvent?)  = sync()
            override fun changedUpdate(e: DocumentEvent?) = sync()
            private fun sync() {
                if (!sameAsStorePasswordCheck.isSelected) return
                keyPasswordField.text     = String(keystorePasswordField.password)
                keyPasswordConfField.text = String(keystorePasswordConfField.password)
            }
        }
        keystorePasswordField.document.addDocumentListener(storeSync)
        keystorePasswordConfField.document.addDocumentListener(storeSync)
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(GridBagLayout())
        root.preferredSize = Dimension(520, 0)   // fixed width; height is dynamic
        var row = 0

        addFormField(root, row++, "Key store path:",              keystorePathField)
        addFormField(root, row++, "Key store password:",          keystorePasswordField)
        addFormField(root, row++, "Confirm key store password:",  keystorePasswordConfField)
        addFormField(root, row++, "Key alias:",                   keyAliasField)
        addFormField(root, row++, "Key password:",                keyPasswordField)
        addFormField(root, row++, "Confirm key password:",        keyPasswordConfField)

        root.add(sameAsStorePasswordCheck, gbc(0, row++, 2).apply {
            insets = Insets(4, 0, 8, 0)
        })

        addFormField(root, row++, "Validity (years):", validityField)

        // Certificate section header
        root.add(
            JBLabel("Certificate").apply { font = font.deriveFont(Font.BOLD) },
            gbc(0, row++, 2).apply { insets = Insets(12, 0, 4, 0) }
        )

        addFormField(root, row++, "First and Last Name:", firstLastNameField)
        addFormField(root, row++, "Organizational Unit:", orgUnitField)
        addFormField(root, row++, "Organization:",        orgField)
        addFormField(root, row++, "City or Locality:",    cityField)
        addFormField(root, row++, "State or Province:",   stateField)
        addFormField(root, row++, "Country Code (XX):",   countryCodeField)

        return root
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

        // Path
        val ksPath = keystorePathField.text.trim()
        when {
            ksPath.isBlank() -> {
                setError(keystorePathField, "Key store path is required."); valid = false
            }
            !ksPath.endsWith(".jks") && !ksPath.endsWith(".keystore") && !ksPath.endsWith(".p12") -> {
                setError(keystorePathField, "Path must end with .jks, .keystore, or .p12."); valid = false
            }
        }

        // Keystore password + confirm
        val ksPass     = String(keystorePasswordField.password)
        val ksPassConf = String(keystorePasswordConfField.password)
        when {
            ksPass.isBlank() -> {
                setError(keystorePasswordField, "Key store password is required."); valid = false
            }
            ksPass.length < 6 -> {
                setError(keystorePasswordField, "Password must be at least 6 characters."); valid = false
            }
            ksPass != ksPassConf -> {
                setError(keystorePasswordConfField, "Passwords do not match."); valid = false
            }
        }

        // Key alias
        if (keyAliasField.text.trim().isBlank()) {
            setError(keyAliasField, "Key alias is required."); valid = false
        }

        // Key password + confirm (only validate independently when checkbox is off)
        val keyPass     = String(keyPasswordField.password)
        val keyPassConf = String(keyPasswordConfField.password)
        if (!sameAsStorePasswordCheck.isSelected) {
            when {
                keyPass.isBlank() -> {
                    setError(keyPasswordField, "Key password is required."); valid = false
                }
                keyPass.length < 6 -> {
                    setError(keyPasswordField, "Password must be at least 6 characters."); valid = false
                }
                keyPass != keyPassConf -> {
                    setError(keyPasswordConfField, "Passwords do not match."); valid = false
                }
            }
        }

        // Validity
        val validity = validityField.text.trim().toIntOrNull()
        if (validity == null || validity <= 0) {
            setError(validityField, "Enter a valid number of years."); valid = false
        }

        // Certificate
        if (firstLastNameField.text.trim().isBlank()) {
            setError(firstLastNameField, "Required for certificate generation."); valid = false
        }
        val cc = countryCodeField.text.trim()
        if (cc.isNotBlank() && !Regex("""^[A-Za-z]{2}$""").matches(cc)) {
            setError(countryCodeField, "Use a 2-letter country code (e.g. US)."); valid = false
        }

        return valid
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        if (validateForm()) null
        else com.intellij.openapi.ui.ValidationInfo("Please fix the highlighted fields.")

    // ── OK — generate .jks ONLY; no Gradle changes ───────────────────────────

    override fun doOKAction() {
        if (!validateForm()) return

        val data = SigningConfigData(
            isNewKeystore      = true,
            keystorePath       = keystorePathField.text.trim(),
            keystorePassword   = String(keystorePasswordField.password),
            keyAlias           = keyAliasField.text.trim(),
            keyPassword        = if (sameAsStorePasswordCheck.isSelected)
                String(keystorePasswordField.password)
            else
                String(keyPasswordField.password),
            validityYears      = validityField.text.trim().toIntOrNull() ?: 25,
            firstAndLastName   = firstLastNameField.text.trim(),
            organizationalUnit = orgUnitField.text.trim(),
            organization       = orgField.text.trim(),
            city               = cityField.text.trim(),
            state              = stateField.text.trim(),
            countryCode        = countryCodeField.text.trim().uppercase()
        )

        // Only generate the .jks — NOT applySigningConfig (no Gradle injection here)
        val service = project.service<SigningConfigService>()
        val error   = service.generateKeystoreOnly(data)
        if (error != null) {
            JOptionPane.showMessageDialog(
                contentPanel, error,
                "Keystore Generation Failed", JOptionPane.ERROR_MESSAGE
            )
            return   // keep dialog open
        }

        result = data
        super.doOKAction()   // dismiss only on success
    }

    // ── Result ───────────────────────────────────────────────────────────────

    /** Call after showAndGet() returns true. */
    fun getResult(): SigningConfigData = result
}