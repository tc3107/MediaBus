# Changelog

All notable changes to this project are documented in this file.

## v1.1.0 - 2026-02-20

### Added
- LAN/hotspot compatibility is now explicitly documented in `README.md`.
- Address dialog now shows the active bind address.
- Browser warning guidance was added to explain expected local HTTPS trust prompts and how to proceed safely.

### Changed
- Host networking now prefers bindable LAN IPv4 addresses (including hotspot-capable interfaces) instead of generic private IPv4 selection.
- Server control UX was refined with clearer start/stop transition states and stronger folder-selection attention behavior.
- Web client now syncs upload/download/delete permission flags from file-list API responses to reflect runtime permission changes.
- App version now comes from `gradle.properties` (`app.versionCode`, `app.versionName`).
- Build tooling was updated to Gradle `9.1.0`, AGP `9.0.1`, and Kotlin `2.2.10`.
- Web build task now auto-detects `npm` and can use prebuilt web assets when `npm` is unavailable.

### Fixed
- Offline QR handling was improved to reduce stale/unsafe scan behavior while offline.
- Silent background poll failures in the web client no longer force immediate connection-lost UI when content is already loaded.

### Chore
- `.gitignore` was updated.

## v1.0.0 - 2026-02-19

### Added
- Initial public release of MediaBus.
