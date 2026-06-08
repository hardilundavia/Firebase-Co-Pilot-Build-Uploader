# 🚀 Firebase Co-Pilot: App Uploader — Android Studio Plugin

> Build & distribute your Android app to Firebase App Distribution directly from Android Studio — with full flavor support, release notes, and one-click deployment.

---

## Features

| Feature | Details |
|---|---|
| **Flavor Detection** | Auto-scans `build.gradle` / `build.gradle.kts` for `productFlavors` |
| **Build Type Selection** | Debug / Release (or any custom build type) |
| **Flavor + Build Type Combos** | e.g. `uatDebug`, `prodRelease` |
| **Firebase Auth** | Service Account JSON — no manual token setup |
| **Release Notes** | Save and reuse notes, or type custom ones |
| **One-click Deploy** | Build → upload → done |
| **Build Log** | Live output in the tool window |
| **Persistent Settings** | Remembers your last App ID, service account path, and release notes |

---

## Installation

### From Source (Development)

**Prerequisites:** JDK 17, IntelliJ IDEA or Android Studio

```bash
git clone https://github.com/your-org/FirebaseCoPilot.git
cd FirebaseCoPilot
./gradlew buildPlugin
```

The plugin ZIP will be at:
```
build/distributions/FirebaseCoPilot-1.0.0.zip
```

**Install in Android Studio:**
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

The JSON looks like:
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
- `Firebase App Distribution Admin`

---

## Usage

1. Open **Build → Firebase Co-Pilot: App Uploader** (or press `Ctrl+Shift+F`)
2. The **Firebase Co-Pilot: App Uploader** panel opens on the right sidebar
3. **If your project has flavors**, select flavor + build type
4. **If simple project**, just select build type (Debug / Release)
5. Paste your **Firebase App ID**
6. Browse to your **Service Account JSON** file
7. Select or type **Release Notes**
8. Click **▶ Build & Deploy to Firebase**

The plugin will:
- Run `./gradlew :app:assemble[Flavor][BuildType]`
- Locate the generated APK
- Authenticate with Firebase using your service account
- Upload the APK to Firebase App Distribution
- Attach your release notes to the release

---

## Project Structure

```
FirebaseCoPilot/
├── build.gradle.kts                          # Plugin build config
├── settings.gradle.kts
└── src/main/
    ├── kotlin/com/firebasebuilduploader/
    │   ├── actions/
    │   │   └── OpenFirebaseCoPilotAction.kt        # Build menu + toolbar action
    │   ├── model/
    │   │   └── Models.kt                      # Data classes
    │   ├── services/
    │   │   ├── GradleFlavorDetectorService.kt # Parses build.gradle for flavors
    │   │   ├── BuildService.kt                # Triggers Gradle builds
    │   │   ├── FirebaseDistributionService.kt # Firebase upload + auth
    │   │   └── FirebaseCoPilotSettingsService.kt   # Persistent settings
    │   └── ui/
    │       ├── FirebaseCoPilotPanel.kt             # Main tool window panel
    │       └── FirebaseCoPilotToolWindowFactory.kt # Tool window registration
    └── resources/
        ├── META-INF/plugin.xml                # Plugin descriptor
        └── icons/buildpilot_13.svg            # Plugin icon
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
        release { ... }
    }
}
```

FirebaseCoPilot will show:
```
Flavor:     [ uat ▾ ]
Build Type: [ debug ▾ ]
→ Runs: ./gradlew :app:assembleUatDebug
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "APK not found after build" | Check **Build Output** tab for Gradle errors |
| "Upload failed (401)" | Verify the service account has `Firebase App Distribution Admin` role |
| "Upload failed (403)" | App ID may be wrong — double-check in Firebase Console |
| Flavors not detected | Click the **🔄 refresh** button in the panel header |
| Plugin not visible | Go to **Settings → Plugins** and ensure FirebaseCoPilot is enabled |

---

## Contributing

PRs welcome! Please follow:
- Kotlin coding conventions
- Add tests for any new `services/` logic
- Update `plugin.xml` version on changes

---

## License

MIT License — see [LICENSE](LICENSE)
