# 🚀 Firebase Co-Pilot: Build Uploader — Android Studio Plugin

> Build, sign, and distribute your Android app to Firebase App Distribution directly from Android Studio —
> with full flavor support, release signing, release notes, tester group distribution, and one-click deployment.

---

## What's New in v2.0.1 🔧

| # | Change |
|---|--------|
| ✅ | **JetBrains Marketplace compatibility** — Removed deprecated IntelliJ Platform APIs (file browse dialogs, Gradle sync listener, project refresh) flagged on Android Studio 2025.x / 2026.x |
| 🛡️ | **Security dependency updates** — Upgraded Google Auth Library (BOM 1.37.1); pinned Guava (CVE-2023-2976) and Commons Codec (WS-2019-0379) to safe versions |
| 🏗️ | **Forward-compatible build** — Compile SDK updated to Android Studio 2025.1 Platform APIs |
| ℹ️ | **No user-facing behaviour changes** — All v2.0.0 features work exactly as before |

---

## What's New in v2.0 🎉

| # | Feature |
|---|---------|
| 🔐 | **Release Signing Wizard** — Detects missing signing config for release builds and guides you through setup without leaving the IDE |
| 🗝️ | **Create New Keystore** — Generate a `.jks` / `.p12` keystore via `keytool` with a step-by-step form: alias, passwords, certificate DN, validity |
| ⚙️ | **Gradle Signing Config Injection** — Writes `keystore.properties` and injects a `signingConfigs` block into your `build.gradle` or `build.gradle.kts` |
| 🔒 | **Auto `.gitignore` Protection** — Keystore files and `keystore.properties` are added to `.gitignore` automatically |
| 👥 | **Tester Group Auto-Distribution** — Reads `groups` from your `firebaseAppDistribution { }` block and distributes builds to those groups after every upload |
| 🔗 | **Accurate Firebase Console Link** — Console URL now comes directly from the Firebase API response |
| 🔄 | **Auto Refresh on Gradle Sync** — Flavor and build type dropdowns update automatically after every Gradle sync |
| 🐛 | **Fixed: Wrong APK selected** — APK discovery is now scoped strictly to the selected flavor + build type |

---

## Features

### Build & Distribution

| Feature | Details |
|---------|---------|
| **Flavor Detection** | Auto-scans `build.gradle` / `build.gradle.kts` for `productFlavors` (Groovy & KTS DSL). Falls back to `firebasecopilot-flavors.json` when Gradle parsing is unavailable |
| **Build Type Selection** | Debug, Release, or any custom build type defined in your project |
| **Flavor + Build Type Combos** | e.g. `uatDebug`, `prodRelease` — all combinations are available |
| **Build Only** | Assemble an APK locally without uploading |
| **Build & Deploy** | Run the full pipeline: build → locate APK → upload → attach release notes → distribute |
| **Firebase Auth** | Service Account JSON authentication — no CLI, no manual token management |
| **Release Notes** | Select from saved templates or write custom notes; up to 20 templates saved persistently |
| **Tester Group Distribution** | Auto-reads `groups` from `firebaseAppDistribution { }` in your build.gradle and distributes after upload |
| **Live Build Log** | Gradle output streamed in real time in the tool window |
| **Persistent Settings** | Remembers App ID, service account path, release note templates, and last 10 deployments across IDE restarts |
| **Auto Sync** | Flavor/build type combos refresh automatically after every Gradle project sync |

### Signing Configuration (New in v2.0)

| Feature | Details |
|---------|---------|
| **Signing Detection** | Warns when "release" is selected but no `signingConfigs` block is found in `build.gradle` |
| **Create New Keystore** | Guided form to generate a `.jks` / `.p12` via `keytool` — no terminal needed |
| **Use Existing Keystore** | Point to an existing keystore; plugin writes `keystore.properties` and injects Gradle config |
| **Gradle Config Injection** | Injects or replaces a `signingConfigs` block and adds `signingConfig` reference in `buildTypes.release` |
| **Groovy & KTS Support** | Signing injection works for both `build.gradle` (Groovy) and `build.gradle.kts` (Kotlin Script) |
| **Secure Storage** | Credentials stored in `keystore.properties`, never hardcoded in Gradle files |
| **`.gitignore` Auto-Update** | Adds `keystore.properties`, `*.jks`, `*.keystore`, `*.p12` to `.gitignore` automatically |

---

## Installation

### From JetBrains Marketplace

1. Open **Settings → Plugins → Marketplace**
2. Search for **Firebase Co-Pilot: Build Uploader**
3. Click **Install** and restart Android Studio

### From Source (Development)

**Prerequisites:** JDK 17, IntelliJ IDEA or Android Studio

```bash
git clone https://github.com/hardilundavia/Firebase-Co-Pilot-Build-Uploader.git
cd FirebaseCoPilot
./gradlew buildPlugin
```

The plugin ZIP will be at:
```
build/distributions/FirebaseCoPilot-2.0.1.zip
```

**Install from ZIP:**
1. Open **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Select the generated ZIP
3. Restart Android Studio

---

## Setup

### 1. Get your Firebase App ID

1. Open [Firebase Console](https://console.firebase.google.com)
2. Go to **Project Settings → General**
3. Scroll to **Your apps** → find your Android app
4. Copy the **App ID** (format: `1:123456789012:android:abc123def456`)

### 2. Create a Service Account JSON

1. In Firebase Console → **Project Settings → Service accounts**
2. Click **Generate new private key**
3. Save the JSON file somewhere safe (e.g. `~/credentials/firebase-sa.json`)

> ⚠️ **Never commit this file to version control!** Add it to `.gitignore`.

```json
{
  "type": "service_account",
  "project_id": "your-project",
  "private_key_id": "abc123",
  "private_key": "-----BEGIN RSA PRIVATE KEY-----\n...",
  "client_email": "firebase-adminsdk-xyz@your-project.iam.gserviceaccount.com",
  "client_id": "123456789",
  "token_uri": "https://oauth2.googleapis.com/token"
}
```

### 3. Grant Firebase App Distribution roles

In [Google Cloud IAM](https://console.cloud.google.com/iam-admin/iam), grant the service account:
- **Firebase App Distribution Admin**

### 4. (Optional) Configure Release Signing

If you plan to build and distribute **release** APKs, you need a signing configuration.
Firebase Co-Pilot will warn you automatically when it detects this is missing:

- Click the **⚠ warning link** in the panel
- Choose **Create New Keystore** to generate a `.jks` from scratch, or **Use Existing** to point to your keystore
- The plugin writes `keystore.properties` and injects the `signingConfigs` block into your `build.gradle`

> ⚠️ `keystore.properties` is added to `.gitignore` automatically. Never commit your keystore or passwords.

---

## Usage

1. Open **Build → Firebase Co-Pilot: Build Uploader** or press `Alt+Shift+B`
2. The panel opens on the right sidebar
3. **Select Flavor** (if your project uses product flavors)
4. **Select Build Type** (Debug / Release / custom)
    - For **Release**: if no signing config exists, click the warning link to set one up
5. Paste your **Firebase App ID**
6. Browse to your **Service Account JSON** file
7. Select or type **Release Notes**
8. Click:
    - **🔨 Build Only** — assemble the APK locally
    - **▶ Build & Deploy to Firebase** — full pipeline

The plugin will:
1. Run `./gradlew :app:assemble[Flavor][BuildType]`
2. Locate the generated APK
3. Authenticate with Firebase using your service account
4. Upload the APK to Firebase App Distribution
5. Attach your release notes
6. Distribute to tester groups (if configured in `firebaseAppDistribution { groups = "..." }`)
7. Show a direct link to the Firebase Console release

---

## Project Structure

```
FirebaseCoPilot/
├── build.gradle.kts
├── settings.gradle.kts
└── src/main/
    ├── kotlin/com/firebasebuilduploader/
    │   ├── actions/
    │   │   └── OpenFirebaseCoPilotAction.kt         # Build menu + toolbar action
    │   ├── model/
    │   │   └── Models.kt                            # Data classes
    │   ├── services/
    │   │   ├── GradleFlavorDetectorService.kt       # Parses build.gradle for flavors & tester groups
    │   │   ├── BuildService.kt                      # Triggers Gradle builds, locates APK
    │   │   ├── FirebaseDistributionService.kt       # Firebase upload, auth, release notes, distribution
    │   │   ├── SigningConfigService.kt              # Keystore generation, Gradle signing injection
    │   │   └── FirebaseCoPilotSettingsService.kt    # Persistent settings & deployment history
    │   └── ui/
    │       ├── FirebaseCoPilotPanel.kt              # Main tool window panel
    │       ├── FirebaseCoPilotToolWindowFactory.kt  # Tool window registration
    │       ├── SigningConfigDialog.kt               # Signing config dialog (use existing keystore)
    │       └── CreateKeystoreDialog.kt              # Create new keystore sub-dialog
    └── resources/
        ├── META-INF/plugin.xml                      # Plugin descriptor
        ├── icons/40.png                             # Tool window icon (40×40)
        └── icons/14.png                             # Build tab icon (14×14)
```

---

## Example — Flavored Project

Given a `build.gradle` like:

```groovy
android {
    flavorDimensions "env"
    productFlavors {
        uat  { dimension "env" }
        prod { dimension "env" }
    }
    buildTypes {
        debug   { ... }
        release { signingConfig signingConfigs.release }
    }
    // Optional: auto-distribute to tester groups after upload
    firebaseAppDistribution {
        groups = "qa-team,internal-testers"
    }
}
```

Firebase Co-Pilot will show:
```
Flavor:     [ uat ▾ ]
Build Type: [ release ▾ ]
→ Runs: ./gradlew :app:assembleUatRelease
→ Distributes to: qa-team, internal-testers
```

---

## Optional: JSON Flavor Fallback

If Firebase Co-Pilot can't parse your `build.gradle` (e.g. complex multi-module setup), place a
`firebasecopilot-flavors.json` file in your project root:

```json
{
  "flavors": ["uat", "staging", "prod"]
}
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "APK not found after build" | Check the **Build Output** tab for Gradle errors; verify flavor/buildType names match `build.gradle` exactly |
| "Upload failed (401)" | Verify the service account has the `Firebase App Distribution Admin` IAM role |
| "Upload failed (403)" | App ID may be wrong — double-check in Firebase Console → Project Settings |
| Flavors not detected | Click the **🔄 refresh** button in the panel header; or add a `firebasecopilot-flavors.json` fallback |
| Signing warning on release | Click the warning link and follow the signing config wizard |
| Keystore generation fails | Ensure JDK `bin/keytool` is accessible; check the error message for DN formatting issues |
| Plugin not visible | Go to **Settings → Plugins** and ensure **Firebase Co-Pilot: Build Uploader** is enabled |
| Gradle sync not triggering | The plugin triggers sync after signing config injection; check the IDE's Gradle tool window |

---

## Contributing

PRs welcome! Please follow:
- Kotlin coding conventions
- Add tests for any new `services/` logic
- Update `plugin.xml` version and `change-notes` on changes

---