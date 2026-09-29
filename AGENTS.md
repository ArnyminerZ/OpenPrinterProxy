# Agent guidance

## Project overview

OpenPrinterProxy is an Android application that exposes USB printers through Android's print framework and shares them over the network using IPP. It is a single-module Kotlin project with Jetpack Compose and Material 3 UI.

## Repository map

- `app/src/main/java/com/arnyminerz/openprinterproxy/` contains application code:
  - `activity/`, `viewmodel/`, and `ui/`: activities, UI state, Compose theme, and icons.
  - `service/`: Android print service, USB discovery, network foreground service, boot receiver, and notifications.
  - `printing/`: shared USB print job runner, PDF rasterization, and PDF, PCL, and Brother laser output.
  - `ipp/`: HTTP/IPP server, protocol messages, printer operations, and raster conversion.
  - `settings/`: printer settings and SharedPreferences persistence.
  - `OtgUtils.kt`: USB printer interface and device identification helpers.
- `app/src/main/res/` contains Android resources, including USB device filters and print service configuration.
- `app/src/test/java/` contains JUnit 4 tests for IPP messages and Brother line encoding.
- `gradle/libs.versions.toml` contains dependency and plugin aliases. The Compose BOM is declared in `app/build.gradle.kts`.

## Build and validation

Use the checked-in Gradle wrapper from the repository root. The current configuration uses Gradle 9.7.1, Android Gradle Plugin 9.4.1, compile/target SDK 37, and minimum SDK 24. The daemon toolchain requests JDK 25; Java source and target compatibility are 11. Keep these distinct when diagnosing build failures.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Run checks relevant to the change. To target an existing test class:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.arnyminerz.openprinterproxy.ipp.IppMessageTest'
./gradlew :app:testDebugUnitTest --tests 'com.arnyminerz.openprinterproxy.printing.BrotherLineEncoderTest'
```

Builds require the Android SDK and access to the configured dependencies/toolchain, or populated local caches. Report checks that could not run and their actual errors. Unit tests do not establish physical printer compatibility; changes to USB transfers, service lifecycle, or rendered output may require device and printer validation. Documentation-only changes do not require an Android build.

## Implementation conventions

- `logo.jpeg` is the launcher artwork source. After replacing it, run `bash scripts/generate-launcher-icons.sh` with ImageMagick installed to regenerate the legacy and adaptive raster resources. The monochrome launcher uses a separate printer vector.
- Follow the existing Kotlin style: four-space indentation, descriptive names, and package organization by responsibility.
- Keep dependency versions in the existing catalog/BOM structure and repositories in `settings.gradle.kts`.
- Maintain compatibility with API 24; guard newer Android APIs where needed.
- Keep blocking USB, network, and rasterization work off the main thread. Preserve cancellation, progress reporting, resource closure, and temporary-file cleanup.
- Route printing through the shared printing components so local and network jobs retain consistent behavior.
- Preserve IPP parser limits, connection bounds, timeouts, and client access checks when changing network handling.
- For protocol and encoder changes, extend the existing byte-level regression tests, including malformed input or boundary cases as appropriate.
- Review manifest permissions, exported components, foreground service declarations, and XML configuration when changing service or discovery behavior.
- Preserve persisted settings keys and defaults, or provide deliberate migration behavior.
- Avoid editing generated build output, local caches, or machine-specific `local.properties` as part of source changes.

## Licensing

The project is GPL-2.0-or-later. Preserve `LICENSE`, `NOTICE`, and source attribution. The Brother laser writer and its encoder tests contain code ported from brlaser; retain their copyright and license notices.

## graphify

- **graphify** (`~/.Codex/skills/graphify/SKILL.md`) — any input to a knowledge graph. Trigger: `/graphify`.
- When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else. If the listed path is unavailable, locate the installed skill rather than assuming that path exists.
