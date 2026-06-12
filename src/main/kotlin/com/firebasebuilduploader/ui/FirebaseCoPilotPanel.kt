package com.firebasebuilduploader.ui

import com.firebasebuilduploader.model.*
import com.firebasebuilduploader.services.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import org.jetbrains.plugins.gradle.util.GradleConstants

class FirebaseCoPilotPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val signingConfigSvc = project.service<SigningConfigService>()
    private var hasReleaseSigningConfig = false

    // ── Services ───────────────────────────────────────────────────────────
    private val flavorDetector = project.service<GradleFlavorDetectorService>()
    private val buildSvc       = project.service<BuildService>()
    private val firebaseSvc    = project.service<FirebaseDistributionService>()
    private val settings       = ApplicationManager.getApplication().service<FirebaseCoPilotSettingsService>()

    // ── State ──────────────────────────────────────────────────────────────
    private var projectConfig      = AndroidProjectConfig(project.name, false)
    private var parsedAccount      : FirebaseServiceAccount? = null
    private var phase              = BuildPhase.IDLE
    private val scope              = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // FIX 3: Store whether the Firebase App Distribution plugin is present.
    // Used by BuildService to decide whether to pass -x appDistributionUploadXxx.
    // Defaulting to false ensures simple projects never get the "-x" flag.
    private var hasFirebasePlugin  = false

    // ── Controls ───────────────────────────────────────────────────────────
    private val flavorCombo        = ComboBox<String>()
    private val buildTypeCombo     = ComboBox<String>(arrayOf("debug", "release"))
    private lateinit var flavorRowPanel: JPanel

    private val saField            = TextFieldWithBrowseButton()
    private val saInfoLabel        = JLabel(" ")
    private val appIdField         = JTextField().apply {
        font        = Font("SansSerif", Font.PLAIN, 12)
        toolTipText = "Firebase App ID (e.g. 1:123456789:android:abc123)"
    }
    private lateinit var appIdRowPanel: JPanel

    private val templateCombo      = ComboBox<String>()
    private val noteArea           = JBTextArea(5, 20).apply {
        lineWrap = true; wrapStyleWord = true
    }

    private val progressBar        = JProgressBar(0, 100).apply {
        isStringPainted = false
        preferredSize   = Dimension(0, 6)
        maximumSize     = Dimension(Int.MAX_VALUE, 6)
        isVisible       = false
    }
    private val statusLabel        = JLabel("Ready").apply {
        font = Font(font.name, Font.PLAIN, 11); foreground = JBColor.GRAY
    }
    private val logArea            = JBTextArea().apply {
        isEditable = false; font = Font("Monospaced", Font.PLAIN, 11)
    }
    private val logScrollPane      = JBScrollPane(logArea).apply {
        isVisible = false; preferredSize = Dimension(0, 150); border = JBUI.Borders.empty()
    }
    private val consoleLinkLabel   = JLabel().apply { isVisible = false }
    private val downloadLinkLabel  = JLabel().apply { isVisible = false }
    private val linkPanel          = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false; isVisible = false
        add(consoleLinkLabel)
        add(JLabel("   ").apply { isOpaque = false })
        add(downloadLinkLabel)
    }

    private val deployBtn          = JButton("▶  Build & Deploy to Firebase").apply {
        font = Font(font.name, Font.BOLD, 13)
        background = Color(0x1A73E8); foreground = Color.WHITE
        isBorderPainted = false; isOpaque = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(0, 38)
        isVisible = false
    }
    private val buildOnlyBtn       = JButton("🔨  Build Only").apply {
        font = Font(font.name, Font.BOLD, 13)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(0, 38)
    }

    private val signingWarningLabel = JLabel(
        "<html><a href=''>⚠ No signing configuration found for 'release'. Click to configure.</a></html>"
    ).apply {
        foreground = JBColor(Color(0xB36A00), Color(0xE0A030))
        font = Font(font.name, Font.PLAIN, 11)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                openSigningConfigDialog()
            }
        })
    }

    private val syncListener = object : ExternalSystemTaskNotificationListener {
        override fun onSuccess(id: ExternalSystemTaskId) {
            if (id.type != ExternalSystemTaskType.RESOLVE_PROJECT) return
            if (id.projectSystemId != GradleConstants.SYSTEM_ID) return

            ApplicationManager.getApplication().invokeLater {
                // Reuses the existing refresh-button logic: re-detects flavors/build
                // types via flavorDetector.detectProjectConfig() and repopulates
                // flavorCombo / buildTypeCombo while preserving the selection.
                loadProject()
                refreshSigningWarning()
                statusLabel.text = "Project synced — ready"
            }
        }
    }

    // FIX 1 & 2: Keep a reference to the footer button panel so we can
    // switch its layout when deployBtn visibility changes.
    private lateinit var buttonPanel: JPanel

    // Card / separator references for show/hide
    private lateinit var firebaseCard:     JPanel
    private lateinit var firebaseSep:      JPanel
    private lateinit var releaseNotesCard: JPanel
    private lateinit var releaseNotesSep:  JPanel

    private data class DeployInput(
        val serviceAccountPath: String,
        val account:            FirebaseServiceAccount,
        val releaseNotes:       String,
        val flavor:             String?,
        val buildType:          String,
        val appId:              String
    )

    init {
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(syncListener)
        buildUI()
        loadProject()
        restoreSettings()
        wireEvents()
        refreshSigningWarning()
    }

    // =========================================================================
    // UI Construction
    // =========================================================================

    private fun buildUI() {
        background = UIUtil.getPanelBackground()

        val body = JPanel(GridBagLayout())
        body.background = UIUtil.getPanelBackground()
        body.border     = EmptyBorder(0, 0, 8, 0)
        var row = 0

        body.add(makeHeader(),
            gbc(row++, fill = GridBagConstraints.HORIZONTAL, weightx = 1.0))
        body.add(makeSep(4), gbc(row++))

        body.add(makeSectionCard("1", "Build Setup", makeBuildSetupContent()),
            gbc(row++, fill = GridBagConstraints.HORIZONTAL, weightx = 1.0, insets = Insets(0,8,0,8)))

        firebaseSep  = makeSep(6)
        firebaseCard = makeSectionCard("2", "Firebase Service Account", makeFirebaseContent())
        body.add(firebaseSep,  gbc(row++))
        body.add(firebaseCard, gbc(row++, fill = GridBagConstraints.HORIZONTAL, weightx = 1.0, insets = Insets(0,8,0,8)))

        releaseNotesSep  = makeSep(6)
        releaseNotesCard = makeSectionCard("3", "Release Notes", makeReleaseNotesContent())
        body.add(releaseNotesSep,  gbc(row++))
        body.add(releaseNotesCard, gbc(row++, fill = GridBagConstraints.HORIZONTAL, weightx = 1.0, insets = Insets(0,8,0,8)))

        body.add(makeSep(10), gbc(row++))
        body.add(progressBar, gbc(row++, fill = GridBagConstraints.HORIZONTAL, weightx = 1.0, insets = Insets(0,8,0,8)))
        body.add(makeSep(4),  gbc(row++))
        body.add(statusLabel, gbc(row++, fill = GridBagConstraints.HORIZONTAL, weightx = 1.0, insets = Insets(0,12,0,8)))
        body.add(makeSep(4),  gbc(row++))
        body.add(logScrollPane, gbc(row++, fill = GridBagConstraints.BOTH, weightx = 1.0, weighty = 1.0, insets = Insets(0,8,0,8)))
        body.add(makeSep(6),  gbc(row++))
        body.add(linkPanel,   gbc(row++, fill = GridBagConstraints.HORIZONTAL, weightx = 1.0, insets = Insets(0,12,0,8)))

        body.add(JPanel().apply { isOpaque = false },
            GridBagConstraints().apply { gridy = row; weighty = 1.0; fill = GridBagConstraints.VERTICAL })

        val scroll = JBScrollPane(body)
        scroll.border = null
        scroll.verticalScrollBarPolicy   = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        add(scroll, BorderLayout.CENTER)

        // ── Sticky footer ──────────────────────────────────────────────────
        // FIX 1 & 2: Use BorderLayout for the button row.
        //   - buildOnlyBtn lives in CENTER → always stretches to fill available width.
        //   - deployBtn lives in EAST with a fixed preferred width → sits to the right.
        //   When deployBtn is hidden, CENTER expands to full width automatically.
        //   When both are shown they share the row: LEFT half = buildOnlyBtn, RIGHT half = deployBtn.
        val footer = JPanel(BorderLayout())
        footer.background = UIUtil.getPanelBackground()
        footer.border = JBUI.Borders.compound(
            MatteBorder(1, 0, 0, 0, JBColor.border()),
            EmptyBorder(10, 12, 10, 12)
        )

        buttonPanel = JPanel(BorderLayout(8, 0))
        buttonPanel.isOpaque = false

        // buildOnlyBtn always in CENTER — fills full width when deployBtn is hidden
        buttonPanel.add(buildOnlyBtn, BorderLayout.CENTER)

        // deployBtn in EAST — give it a concrete preferred width so both buttons
        // share the row equally when visible (each ~half of container width).
        deployBtn.preferredSize = Dimension(220, 38)
        buttonPanel.add(deployBtn, BorderLayout.EAST)

        // When deployBtn is hidden its EAST slot collapses and buildOnlyBtn
        // expands to fill CENTER = full width. No manual column juggling needed.
        deployBtn.addPropertyChangeListener("visible") {
            buttonPanel.revalidate()
            buttonPanel.repaint()
        }

        footer.add(buttonPanel, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }

    // ── Header ────────────────────────────────────────────────────────────

    private fun makeHeader(): JPanel {
        val p = JPanel(BorderLayout(8, 0))
        p.background = Color(0x1B3A5C)
        p.border     = EmptyBorder(14, 16, 14, 12)
        val left = JPanel(GridLayout(2, 1, 0, 2)).also { it.isOpaque = false }
        JLabel("🚀  Firebase Co-Pilot: Build Uploader").also {
            it.font = Font("SansSerif", Font.BOLD, 15); it.foreground = Color.WHITE; left.add(it)
        }
        JLabel("Firebase App Distribution  ·  ${project.name}").also {
            it.font = Font("SansSerif", Font.PLAIN, 10); it.foreground = Color(0x8FBFDF); left.add(it)
        }
        val refreshBtn = JButton(AllIcons.Actions.Refresh).also {
            it.toolTipText = "Re-scan project for flavors & build types"
            it.isBorderPainted = false; it.isContentAreaFilled = false; it.isFocusPainted = false
            it.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            it.addActionListener { loadProject() }
        }
        p.add(left, BorderLayout.CENTER)
        p.add(refreshBtn, BorderLayout.EAST)
        return p
    }

    // ── Section Card ──────────────────────────────────────────────────────

    private fun makeSectionCard(number: String, title: String, content: JPanel): JPanel {
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).also { it.isOpaque = false }
        JLabel(number).also {
            it.font = Font("SansSerif", Font.BOLD, 10); it.foreground = Color.WHITE
            it.background = Color(0x1A73E8); it.isOpaque = true
            it.border = EmptyBorder(1, 6, 2, 6); titleRow.add(it)
        }
        JLabel(title).also {
            it.font = Font("SansSerif", Font.BOLD, 12); it.foreground = JBColor.foreground(); titleRow.add(it)
        }
        val wrapper = JPanel(GridBagLayout())
        wrapper.background = UIUtil.getPanelBackground()
        wrapper.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1), EmptyBorder(10, 12, 12, 12)
        )
        wrapper.add(titleRow, GridBagConstraints().apply {
            gridx = 0; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0,0,8,0)
        })
        wrapper.add(content, GridBagConstraints().apply {
            gridx = 0; gridy = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        })
        return wrapper
    }

    // ── Step 1: Build Setup ───────────────────────────────────────────────

    private fun makeBuildSetupContent(): JPanel {
        val p = JPanel(GridBagLayout()).also { it.isOpaque = false }
        flavorRowPanel = makeFormRow("Flavor", flavorCombo)
        flavorRowPanel.isVisible = false
        p.add(flavorRowPanel, GridBagConstraints().apply {
            gridx = 0; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0, 0, 6, 0)
        })
        p.add(makeFormRow("Build Type", buildTypeCombo), GridBagConstraints().apply {
            gridx = 0; gridy = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        })
        // NEW: contextual warning, hidden by default
        p.add(signingWarningLabel, GridBagConstraints().apply {
            gridx = 0; gridy = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 0, 0)
        })

        // NEW: re-check signing status whenever the build type changes
        buildTypeCombo.addActionListener { refreshSigningWarning() }

        return p
    }

    private fun refreshSigningWarning() {
        val isRelease = (buildTypeCombo.selectedItem as? String) == "release"
        if (!isRelease) {
            signingWarningLabel.isVisible = false
            return
        }
        scope.launch {
            hasReleaseSigningConfig = withContext(Dispatchers.IO) {
                signingConfigSvc.hasReleaseSigningConfig()
            }
            signingWarningLabel.isVisible = !hasReleaseSigningConfig
        }
    }

    private fun openSigningConfigDialog(): Boolean {
        val dialog = SigningConfigDialog(project)
        val ok = dialog.showAndGet()
        if (!ok) return hasReleaseSigningConfig

        val data = dialog.toSigningConfigData()
        statusLabel.text = "Applying signing configuration…"

        val error = signingConfigSvc.applySigningConfig(data) {
            // onSyncStarted callback — UI feedback that sync has begun
            statusLabel.text = "Signing config applied — syncing Gradle…"
        }

        return if (error != null) {
            JOptionPane.showMessageDialog(
                this, error, "Signing Configuration Error", JOptionPane.ERROR_MESSAGE
            )
            false
        } else {
            hasReleaseSigningConfig = true
            signingWarningLabel.isVisible = false
            true
        }
    }

    /**
     * Wraps deploy/build entry points so that selecting "release" without a
     * signing config force-opens the dialog first. Returns true if the caller
     * should proceed, false if the user needs to resolve signing first.
     */
    private fun ensureSigningConfigBeforeProceeding(): Boolean {
        val isRelease = (buildTypeCombo.selectedItem as? String) == "release"
        if (!isRelease || hasReleaseSigningConfig) return true
        return openSigningConfigDialog()
    }

    // ── Step 2: Firebase ──────────────────────────────────────────────────

    private fun makeFirebaseContent(): JPanel {
        val p = JPanel(GridBagLayout()).also { it.isOpaque = false }

        saField.addBrowseFolderListener(
            "Select Firebase Service Account JSON",
            "Choose the service account JSON from Firebase Console → Project Settings → Service Accounts",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        )
        saField.textField.font = Font("SansSerif", Font.PLAIN, 12)
        p.add(saField, GridBagConstraints().apply {
            gridx = 0; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0,0,6,0)
        })

        saInfoLabel.font = Font("SansSerif", Font.PLAIN, 11)
        p.add(saInfoLabel, GridBagConstraints().apply {
            gridx = 0; gridy = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0,0,4,0)
        })

        appIdRowPanel = makeFormRow("App ID", appIdField)
        appIdRowPanel.isVisible = false
        p.add(appIdRowPanel, GridBagConstraints().apply {
            gridx = 0; gridy = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4,0,4,0)
        })

        JLabel("Firebase App ID — Firebase Console → Project Settings → Your Apps").also {
            it.name = "appIdHint"; it.font = Font("SansSerif", Font.PLAIN, 10)
            it.foreground = JBColor(Color(0x999999), Color(0x777777)); it.isVisible = false
            p.add(it, GridBagConstraints().apply {
                gridx = 0; gridy = 3; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0,0,4,0)
            })
        }
        JLabel("Download from: Firebase Console → Project Settings → Service Accounts").also {
            it.font = Font("SansSerif", Font.PLAIN, 10)
            it.foreground = JBColor(Color(0x999999), Color(0x777777))
            p.add(it, GridBagConstraints().apply {
                gridx = 0; gridy = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            })
        }
        return p
    }

    // ── Step 3: Release Notes ─────────────────────────────────────────────

    private fun makeReleaseNotesContent(): JPanel {
        val p = JPanel(GridBagLayout()).also { it.isOpaque = false }
        p.add(makeFormRow("Template", templateCombo), GridBagConstraints().apply {
            gridx = 0; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0,0,8,0)
        })
        JLabel("Release notes").also {
            it.font = Font("SansSerif", Font.PLAIN, 11)
            it.foreground = JBColor(Color(0x666666), Color(0x999999))
            p.add(it, GridBagConstraints().apply {
                gridx = 0; gridy = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0,0,4,0)
            })
        }
        noteArea.background = UIUtil.getTextFieldBackground()
        noteArea.border     = EmptyBorder(6,8,6,8)
        val noteScroll = JBScrollPane(noteArea).also {
            it.preferredSize = Dimension(0, 90)
            it.border = JBUI.Borders.customLine(JBColor.border(), 1)
        }
        p.add(noteScroll, GridBagConstraints().apply {
            gridx = 0; gridy = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        })
        return p
    }

    // ── Form Row helpers ──────────────────────────────────────────────────

    private fun makeFormRow(labelText: String, combo: ComboBox<*>): JPanel {
        val row = JPanel(GridBagLayout()).also { it.isOpaque = false }
        val lbl = JLabel(labelText).also {
            it.font = Font("SansSerif", Font.PLAIN, 12)
            it.foreground = JBColor(Color(0x444444), Color(0xBBBBBB))
            it.preferredSize = Dimension(90, 28); it.minimumSize = Dimension(90, 28)
        }
        combo.font = Font("SansSerif", Font.PLAIN, 12); combo.preferredSize = Dimension(0, 28)
        row.add(lbl, GridBagConstraints().apply {
            gridx = 0; gridy = 0; weightx = 0.0; fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.WEST; insets = Insets(0,0,0,8)
        })
        row.add(combo, GridBagConstraints().apply {
            gridx = 1; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        })
        return row
    }

    private fun makeFormRow(labelText: String, field: JTextField): JPanel {
        val row = JPanel(GridBagLayout()).also { it.isOpaque = false }
        val lbl = JLabel(labelText).also {
            it.font = Font("SansSerif", Font.PLAIN, 12)
            it.foreground = JBColor(Color(0x444444), Color(0xBBBBBB))
            it.preferredSize = Dimension(90, 28); it.minimumSize = Dimension(90, 28)
        }
        field.preferredSize = Dimension(0, 28)
        row.add(lbl, GridBagConstraints().apply {
            gridx = 0; gridy = 0; weightx = 0.0; fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.WEST; insets = Insets(0,0,0,8)
        })
        row.add(field, GridBagConstraints().apply {
            gridx = 1; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        })
        return row
    }

    // =========================================================================
    // Data Loading
    // =========================================================================

    private fun loadProject() {
        statusLabel.text = "Scanning project..."
        appendLog("🔍 Scanning project...")
        scope.launch(Dispatchers.IO) {
            try {
                val cfg                   = flavorDetector.detectProjectConfig()
                val detectedFirebasePlugin = detectFirebaseAppDistribution()

                ApplicationManager.getApplication().invokeLater {
                    projectConfig     = cfg
                    // FIX 3: Store the result so startBuildOnly/startBuildAndDeploy can pass it to BuildService
                    hasFirebasePlugin = detectedFirebasePlugin

                    val previousFlavor    = flavorCombo.selectedItem as? String
                    val previousBuildType = buildTypeCombo.selectedItem as? String

                    flavorCombo.removeAllItems()
                    cfg.flavors.forEach { flavorCombo.addItem(it.name) }
                    if (previousFlavor != null && cfg.flavors.any { it.name == previousFlavor })
                        flavorCombo.selectedItem = previousFlavor

                    flavorRowPanel.isVisible = cfg.hasFlavors
                    flavorRowPanel.parent?.revalidate()

                    buildTypeCombo.removeAllItems()
                    cfg.buildTypes.forEach { buildTypeCombo.addItem(it) }
                    if (previousBuildType != null && cfg.buildTypes.contains(previousBuildType))
                        buildTypeCombo.selectedItem = previousBuildType

                    // Show/hide all Firebase-related sections together
                    firebaseSep.isVisible      = detectedFirebasePlugin
                    firebaseCard.isVisible     = detectedFirebasePlugin
                    releaseNotesSep.isVisible  = detectedFirebasePlugin
                    releaseNotesCard.isVisible = detectedFirebasePlugin
                    appIdRowPanel.isVisible    = detectedFirebasePlugin
                    (appIdRowPanel.parent?.components
                        ?.firstOrNull { it is JLabel && (it as JLabel).name == "appIdHint" } as? JLabel)
                        ?.isVisible = detectedFirebasePlugin
                    deployBtn.isVisible        = detectedFirebasePlugin

                    firebaseCard.parent?.revalidate()
                    firebaseCard.parent?.repaint()

                    if (cfg.hasFlavors) {
                        val names = cfg.flavors.joinToString(", ") { it.name }
                        appendLog("✅ Detected ${cfg.flavors.size} flavor(s): $names")
                        statusLabel.text = "Detected ${cfg.flavors.size} flavor(s): $names"
                    } else {
                        appendLog("ℹ️ Simple project — no product flavors detected")
                        statusLabel.text = "Simple project (no flavors)"
                    }
                    appendLog(
                        if (detectedFirebasePlugin) "✅ Firebase App Distribution detected"
                        else "ℹ️ Firebase App Distribution not detected"
                    )
                    logScrollPane.isVisible = true
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Scan failed"
                    appendLog("❌ Error scanning project: ${e.message}")
                    logScrollPane.isVisible = true
                }
            }
        }
    }

    private fun detectFirebaseAppDistribution(): Boolean {
        val projectDir = java.io.File(project.basePath ?: return false)
        for (rel in listOf("app/build.gradle.kts", "app/build.gradle", "build.gradle.kts", "build.gradle")) {
            val f = java.io.File(projectDir, rel)
            if (!f.exists()) continue
            try {
                val text = f.readText()
                if (text.contains("firebase-appdistribution") ||
                    text.contains("firebaseAppDistribution") ||
                    text.contains("com.google.firebase.appdistribution")) return true
            } catch (_: Exception) { }
        }
        return false
    }

    private fun restoreSettings() {
        if (settings.lastServiceAccountPath.isNotBlank()) {
            saField.text = settings.lastServiceAccountPath
            tryParseServiceAccount(settings.lastServiceAccountPath)
        }
        if (settings.lastAppId.isNotBlank()) appIdField.text = settings.lastAppId
        rebuildTemplates()
    }

    private fun rebuildTemplates() {
        templateCombo.removeAllItems()
        templateCombo.addItem("— select a template —")
        settings.savedReleaseNotes.forEach { templateCombo.addItem(it) }
    }

    // =========================================================================
    // Event Wiring
    // =========================================================================

    private fun wireEvents() {
        saField.textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?)  = onSaPathChanged()
            override fun removeUpdate(e: DocumentEvent?)  = onSaPathChanged()
            override fun changedUpdate(e: DocumentEvent?) = Unit
        })
        templateCombo.addActionListener {
            val sel = templateCombo.selectedItem as? String ?: return@addActionListener
            if (!sel.startsWith("—")) noteArea.text = sel
        }
        deployBtn.addActionListener {
            if (phase == BuildPhase.IDLE || phase == BuildPhase.FAILED || phase == BuildPhase.UPLOAD_COMPLETE)
                startBuildAndDeploy()
        }
        buildOnlyBtn.addActionListener {
            if (phase == BuildPhase.IDLE || phase == BuildPhase.FAILED || phase == BuildPhase.UPLOAD_COMPLETE)
                startBuildOnly()
        }
    }

    // =========================================================================
    // Validation
    // =========================================================================

    private fun validateBuildInput(): DeployInput = DeployInput(
        serviceAccountPath = saField.text.trim(),
        account            = parsedAccount ?: FirebaseServiceAccount("","","","","","",""),
        releaseNotes       = noteArea.text.trim(),
        flavor             = if (projectConfig.hasFlavors) flavorCombo.selectedItem as? String else null,
        buildType          = buildTypeCombo.selectedItem as? String ?: "debug",
        appId              = ""
    )

    private fun validateDeployInput(): DeployInput? {
        val saPath = saField.text.trim()
        if (saPath.isBlank() || !File(saPath).exists()) {
            showError("Please select a valid Firebase service account JSON file."); return null
        }
        val account = parsedAccount ?: run {
            showError("Invalid Firebase service account."); return null
        }
        val appId = appIdField.text.trim()
        if (appId.isBlank()) {
            showError("Please enter the Firebase App ID.\nFind it in Firebase Console → Project Settings → Your Apps.")
            return null
        }
        val notes = noteArea.text.trim()
        if (notes.isBlank()) { showError("Please enter release notes."); return null }

        settings.lastServiceAccountPath = saPath
        settings.lastAppId              = appId
        return DeployInput(
            serviceAccountPath = saPath, account = account, releaseNotes = notes,
            flavor  = if (projectConfig.hasFlavors) flavorCombo.selectedItem as? String else null,
            buildType = buildTypeCombo.selectedItem as? String ?: "debug",
            appId   = appId
        )
    }

    // =========================================================================
    // Build Only
    // =========================================================================

    private fun startBuildOnly() {
        if (!ensureSigningConfigBeforeProceeding()) return
        logArea.text = ""; linkPanel.isVisible = false; logScrollPane.isVisible = true
        val input = validateBuildInput()

        scope.launch {
            try {
                buildOnlyBtn.isEnabled = false; deployBtn.isEnabled = false
                buildOnlyBtn.text = "⏳ Building..."
                progressBar.isVisible = true
                setPhase(BuildPhase.BUILDING, "Building APK...", 25, true)

                val config = BuildConfiguration(
                    flavor                 = input.flavor,
                    buildType              = input.buildType,
                    serviceAccountJsonPath = "",
                    releaseNotes           = "",
                    appId                  = ""
                )

                // FIX 3: Pass hasFirebasePlugin so BuildService only adds
                // "-x appDistributionUploadXxx" when the plugin actually exists.
                val apk = withContext(Dispatchers.IO) {
                    buildSvc.assembleBuild(config, hasFirebasePlugin) { appendLog(it) }
                } ?: throw RuntimeException("APK not found after build — check Build Output tab.")

                val displayName = apk.name.replace("-unsigned", "")
                setPhase(BuildPhase.UPLOAD_COMPLETE, "Build complete", 100, false)
                buildOnlyBtn.text = "✅ Build Again"
                appendLog(""); appendLog("🎉 Build complete")
                appendLog("📦 $displayName")
                appendLog("   ${apk.absolutePath}")
                phase = BuildPhase.UPLOAD_COMPLETE

            } catch (e: Exception) {
                phase = BuildPhase.FAILED; buildOnlyBtn.text = "🔄 Retry"
                statusLabel.text = "Build failed"; appendLog("❌ ${e.message}")
            } finally {
                progressBar.isVisible = false; buildOnlyBtn.isEnabled = true; deployBtn.isEnabled = true
            }
        }
    }

    // =========================================================================
    // Build & Deploy
    // =========================================================================

    private fun startBuildAndDeploy() {
        if (!ensureSigningConfigBeforeProceeding()) return
        logArea.text = ""; linkPanel.isVisible = false; logScrollPane.isVisible = true
        val input = validateDeployInput() ?: return

        scope.launch {
            try {
                buildOnlyBtn.isEnabled = false; deployBtn.isEnabled = false
                deployBtn.text = "⏳ Building..."
                progressBar.isVisible = true

                appendLog("─────────────────────────────────────")
                appendLog("🔑 Project  : ${input.account.projectId}")
                appendLog("👤 Account  : ${input.account.clientEmail}")
                appendLog("📱 App ID   : ${input.appId}")
                appendLog("🏗 Flavor   : ${input.flavor ?: "none"}")
                appendLog("🏗 BuildType: ${input.buildType}")

                setPhase(BuildPhase.BUILDING, "Building APK...", 15, true)

                val config = BuildConfiguration(
                    flavor                 = input.flavor,
                    buildType              = input.buildType,
                    serviceAccountJsonPath = input.serviceAccountPath,
                    releaseNotes           = input.releaseNotes,
                    appId                  = input.appId
                )

                // FIX 3: Pass hasFirebasePlugin here too
                val apk = withContext(Dispatchers.IO) {
                    buildSvc.assembleBuildForDeploy(config, hasFirebasePlugin) { appendLog(it) }
                } ?: throw RuntimeException("APK not found after build — check Build Output tab.")

                val displayName = apk.name.replace("-unsigned", "")
                setPhase(BuildPhase.BUILD_COMPLETE, "Build complete — $displayName", 50, false)
                appendLog("✅ APK: $displayName  (${apk.length() / 1024} KB)")

                deployBtn.text = "☁ Uploading..."
                setPhase(BuildPhase.UPLOADING, "Uploading to Firebase App Distribution…", 60, true)
                appendLog("\n☁  Uploading…")

                val result = withContext(Dispatchers.IO) {
                    val testerGroups = flavorDetector.parseTesterGroups()
                    if (testerGroups.isNotEmpty()) appendLog("👥 Tester groups: ${testerGroups.joinToString()}")
                    firebaseSvc.uploadBuild(
                        appId              = input.appId,
                        apkFile            = apk,
                        releaseNotes       = input.releaseNotes,
                        serviceAccountPath = input.serviceAccountPath,
                        testerGroups       = testerGroups
                    ) { appendLog(it) }
                }

                when (result) {
                    is UploadResult.Success -> {
                        deployBtn.text = "✅ Deploy Again"
                        setPhase(BuildPhase.UPLOAD_COMPLETE, "✅ Distributed successfully!", 100, false)
                        appendLog("\n🎉 Done!")
                        appendLog("   Release : ${result.releaseId}")
                        appendLog("   Time    : ${SimpleDateFormat("HH:mm:ss").format(Date())}")
                        if (result.downloadUrl.isNotBlank() && result.downloadUrl != result.consoleUrl)
                            appendLog("   APK URL : ${result.downloadUrl}")
                        showConsoleLink(result.consoleUrl, result.downloadUrl)
                        settings.recordDeployment(RecentDeployment(
                            timestamp          = System.currentTimeMillis(),
                            flavor             = input.flavor,
                            buildType          = input.buildType,
                            releaseNotes       = input.releaseNotes,
                            firebaseConsoleUrl = result.consoleUrl
                        ))
                    }
                    is UploadResult.Failure -> throw RuntimeException(result.reason)
                }

            } catch (e: Exception) {
                phase = BuildPhase.FAILED; deployBtn.text = "🔄 Retry"
                statusLabel.text = "Deployment failed"; appendLog("❌ ${e.message}")
            } finally {
                progressBar.isVisible = false; buildOnlyBtn.isEnabled = true; deployBtn.isEnabled = true
            }
        }
    }

    // =========================================================================
    // SA parsing
    // =========================================================================

    private fun onSaPathChanged() {
        val path = saField.text.trim()
        if (path.isNotBlank() && File(path).exists()) tryParseServiceAccount(path)
        else { saInfoLabel.text = " "; saInfoLabel.foreground = JBColor.GRAY; parsedAccount = null }
    }

    private fun tryParseServiceAccount(path: String) {
        val result = firebaseSvc.parseServiceAccount(path)
        if (result.isSuccess) {
            val acct = result.getOrNull()!!
            parsedAccount = acct
            saInfoLabel.icon       = AllIcons.RunConfigurations.TestPassed
            saInfoLabel.text       = " ${acct.projectId}  ·  ${acct.clientEmail}"
            saInfoLabel.foreground = JBColor(Color(0x2E7D32), Color(0x81C784))
        } else {
            parsedAccount = null
            saInfoLabel.icon       = AllIcons.RunConfigurations.TestFailed
            saInfoLabel.text       = " Invalid JSON — ${result.exceptionOrNull()?.message?.take(60)}"
            saInfoLabel.foreground = JBColor(Color(0xC62828), Color(0xEF5350))
        }
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private fun setPhase(p: BuildPhase, msg: String, pct: Int, indeterminate: Boolean) {
        phase = p
        progressBar.isIndeterminate = indeterminate; progressBar.value = pct; statusLabel.text = msg
        val busy = p == BuildPhase.VALIDATING || p == BuildPhase.BUILDING || p == BuildPhase.UPLOADING
        deployBtn.isEnabled = !busy; buildOnlyBtn.isEnabled = !busy
        progressBar.isVisible = true
        progressBar.foreground = when (p) {
            BuildPhase.UPLOAD_COMPLETE -> JBColor(Color(0x2E7D32), Color(0x66BB6A))
            BuildPhase.FAILED          -> JBColor(Color(0xC62828), Color(0xEF5350))
            else                       -> JBColor(Color(0x1A73E8), Color(0x4D90FE))
        }
    }

    private fun appendLog(msg: String) = SwingUtilities.invokeLater {
        logArea.append("$msg\n"); logArea.caretPosition = logArea.document.length
    }

    private fun showConsoleLink(consoleUrl: String, downloadUrl: String = "") {
        val linkStyle = Font("SansSerif", Font.BOLD, 12)
        val linkColor = JBColor(Color(0x1A73E8), Color(0x82B1FF))

        consoleLinkLabel.text       = "<html><u>🔗  Open Firebase Console →</u></html>"
        consoleLinkLabel.font       = linkStyle; consoleLinkLabel.foreground = linkColor
        consoleLinkLabel.cursor     = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        consoleLinkLabel.isVisible  = true
        consoleLinkLabel.mouseListeners.forEach { consoleLinkLabel.removeMouseListener(it) }
        consoleLinkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { runCatching { Desktop.getDesktop().browse(URI(consoleUrl)) } }
        })

        val hasDownload = downloadUrl.isNotBlank() && downloadUrl != consoleUrl
        downloadLinkLabel.isVisible = hasDownload
        if (hasDownload) {
            downloadLinkLabel.text       = "<html><u>⬇️  Download APK →</u></html>"
            downloadLinkLabel.font       = linkStyle; downloadLinkLabel.foreground = linkColor
            downloadLinkLabel.cursor     = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            downloadLinkLabel.mouseListeners.forEach { downloadLinkLabel.removeMouseListener(it) }
            downloadLinkLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { runCatching { Desktop.getDesktop().browse(URI(downloadUrl)) } }
            })
        }

        linkPanel.isVisible = true; linkPanel.revalidate(); linkPanel.repaint()
    }

    private fun showError(msg: String) =
        JOptionPane.showMessageDialog(this, msg, "Firebase Co-Pilot: Build Uploader", JOptionPane.WARNING_MESSAGE)

    fun dispose() {
        scope.cancel()
        ExternalSystemProgressNotificationManager.getInstance().removeNotificationListener(syncListener)
    }

    // =========================================================================
    // Layout utilities
    // =========================================================================

    private fun makeSep(h: Int) = JPanel().also {
        it.isOpaque = false
        it.preferredSize = Dimension(0, h); it.maximumSize = Dimension(Int.MAX_VALUE, h); it.minimumSize = Dimension(0, h)
    }

    private fun gbc(
        row: Int = 0, fill: Int = GridBagConstraints.NONE,
        weightx: Double = 0.0, weighty: Double = 0.0, insets: Insets = Insets(0,0,0,0)
    ) = GridBagConstraints().apply {
        gridx = 0; gridy = row; gridwidth = 1
        this.weightx = weightx; this.weighty = weighty
        this.fill = fill; this.insets = insets
        anchor = GridBagConstraints.NORTHWEST
    }
}