# MemoryVoid 🌌
<img width="1536" height="1024" alt="image" src="https://github.com/user-attachments/assets/405bae61-419b-45f8-a11d-da7c608daff8" />


> **Instant Voice & Screen Memory Journal for Android.**  
> Effortlessly capture what's on your screen and record your voice with a single hardware gesture.

[![Android SDK](https://img.shields.io/badge/Android-API%2030%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Material 3](https://img.shields.io/badge/Material%203-Expressive-757575?style=flat-square&logo=materialdesign&logoColor=white)](https://m3.material.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)

---

## 🌟 Overview

**MemoryVoid** is a ultra-fast memory journal application designed for Android 11+ (API 30+). It bridges hardware volume button shortcuts with screen capture and background voice recording, allowing you to instantly log moments, ideas, and memories without interrupting your workflow.

---

## ✨ Key Features

### 🎙️ Instant Hold-and-Release Memory Capture
- **Press & Hold Volume Down**: Starts background voice note recording immediately.
- **Release Volume Down**: Captures a screenshot at the exact moment of release and pairs it with the voice note into a unified memory entry.
- **Floating Interactive Preview**: Displays a smooth, springy floating window overlay on memory creation for instant review or opening.

### 🎨 4 Adaptive Theme Modes
1. **Light Theme**: Crisp, high-contrast Material 3 light theme with `#F2F4F7` background and dark primary text `#121316`.
2. **Dark Theme**: Deep `#101216` background with light `#F0F2F5` text.
3. **Pitch Black Theme**: Pure `#000000` AMOLED black background with pure white text `#FFFFFF`.
4. **Glass Theme**: Translucent frosted glass over device wallpaper (`FLAG_SHOW_WALLPAPER`) with hardware window background blur (`setBackgroundBlurRadius(60)`) on Android 12+.

### 🏷️ High-Contrast Adaptive Category Badges
Category badges on memory cards and the full-screen viewer dynamically adapt to the active theme:
- **Light Theme**: Solid pitch black (`#000000`) pill background with pure white (`#FFFFFF`) text.
- **Dark / Pitch Black Theme**: Solid pure white (`#FFFFFF`) pill background with pitch black (`#000000`) text.
- **Glass Theme**: Translucent frosted white (`#26FFFFFF` fill, `1dp` `#4DFFFFFF` stroke) with pure white (`#FFFFFF`) text.

### ⚡ 120Hz Frame Pacing & Performance
- **Zero Jitter**: Offloads all audio `MediaRecorder` setup/teardown and bitmap decoding to background threads (`Dispatchers.IO`).
- **LruCache Thumbnail System**: 30-entry in-memory `LruCache` caches decoded image thumbnails to eliminate disk I/O on scroll.
- **Crash-Proof Accessibility Service**: Top-level exception guards in `KeyMappingService` ensure the volume down shortcut never unbinds or crashes.
- **Tactile Micro-Haptics**: Responsive `KEYBOARD_TAP` haptic feedback and tap-location originating bouncy press animations across all interactive items.

### 📱 Full-Screen Interactive Memory Viewer
- **iOS-Style Motion**: Smooth iOS modal slide-up transitions (`IosModalAnimation`) and center spring expand animations (`IosZoomAnimation`).
- **Floating Screenshot Card**: Screenshot view floats as a dedicated window above the bottom controls dock.
- **In-App Editing**: Rename notes, switch category tags via flyout picker, listen to voice note audio, or open interactive pinch-to-zoom mode.

### ✏️ Direct In-App Note Creation
- Tap the `+` button in the top bar to write and save custom text notes directly inside the app.

---

## 🛠️ Architecture & Tech Stack

- **Language**: Kotlin 1.9
- **Min SDK**: API 30 (Android 11)
- **Target SDK**: API 37
- **UI Architecture**: Single-Activity Architecture (`MainActivity.kt`) with Material 3 Expressive components, custom `ThemeManager`, and custom Dialog windows.
- **Background Service**: `KeyMappingService` extending `AccessibilityService` for hardware key filtering and screen capture (`takeScreenshot`).
- **Threading**: Kotlin Coroutines (`Dispatchers.IO`, `Dispatchers.Main`, `lifecycleScope`).
- **Image Caching**: `androidx.collection.LruCache`.
- **Photo Zoom**: PhotoView (`com.github.chrisbanes:PhotoView`).

---

## 🚀 Setup & Installation

### Prerequisites
- Android device running **Android 11 (API 30)** or higher.
- Android Studio Iguana / Ladybug or newer.
- JDK 17.

### Building from Source

```bash
# Clone the repository
git clone https://github.com/your-username/MemoryVoid.git
cd MemoryVoid

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

The compiled APKs will be located at:
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 🔑 Required Permissions

MemoryVoid uses the following Android permissions for its functionality:

| Permission | Purpose |
| :--- | :--- |
| `BIND_ACCESSIBILITY_SERVICE` | Intercepts Volume Down button hold/release gestures. |
| `RECORD_AUDIO` | Records voice notes during the Volume Down hold gesture. |
| `POST_NOTIFICATIONS` | Displays recording status and memory saved alerts. |
| `SYSTEM_ALERT_WINDOW` | Displays floating interactive memory preview overlays. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ensures background memory capture works reliably when the app is minimized. |

---

## 🤝 Contributing

Contributions, bug reports, and feature requests are welcome! Feel free to check the [Issues](https://github.com/your-username/MemoryVoid/issues) page.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
