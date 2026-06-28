# System UI Diagnostics (Dr. Clicker Admin Pro)

A localized workflow diagnostics, screen-reading calibration, and touch-latency testing utility for the Rapido Captain app.

## Features

- **100% Local Operation** - No Firebase, no analytics, no ads
- **Dual-Mode Screen Analysis** - Text-based reading + OpenCV template matching
- **Humanized Touch Dispatch** - Safe elliptical targeting with randomized reflex delays
- **Android 15 Gesture Fix** - InputDispatcher stuck pointer workaround
- **Dark Theme Dashboard** - Beautiful Material 3 UI

## Requirements

- Android 8.0+ (API 26)
- Android 14 Target (API 34)
- Accessibility Service enabled
- Overlay permission granted
- OpenCV Android SDK

## Setup

1. Clone this repository
2. Open in Android Studio Hedgehog (2023.1.1) or later
3. Sync Gradle and build

## Permissions Required

- `SYSTEM_ALERT_WINDOW` - For floating overlay
- `BIND_ACCESSIBILITY_SERVICE` - For screen reading and gesture dispatch
- `READ_MEDIA_IMAGES` - For template selection

## Architecture

- `MainActivity.kt` - Dashboard with unified engine activation switch
- `AutoAcceptEngineService.kt` - Background accessibility service with OpenCV pipeline
- `FloatingOverlayService.kt` - Compact floating widget with touch pass-through

## License

Private - For authorized diagnostic use only.
