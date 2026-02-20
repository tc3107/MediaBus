<p align="center">
  <img src="docs/icon.svg" alt="MediaBus app icon" width="120" />
</p>

<h1 align="center">MediaBus</h1>

<p align="center">
  Private local file sharing from your Android device to any browser on your network.
</p>

## Overview
MediaBus turns your phone into a secure local file host. Start the server, scan the QR code, and manage transfers from a browser over your LAN.

## Features
- HTTPS local web server with QR-based access
- Pairing flow for client authorization
- Upload, download, delete, and folder operations
- Permission toggles (upload/download/delete/hidden files)
- Device presence and transfer status tracking
- mDNS/Bonjour local hostname support (`mediabus.local`)

## Tech Stack
- Android app: Kotlin, Jetpack Compose, DataStore
- Embedded server: NanoHTTPD
- Web client: React + Vite (bundled into Android assets at build time)

## Releases
Releases are published on this GitHub page.

## Security Notes
- Intended for trusted local networks.
- Pairing/session controls are enforced in-app.
- See `PRIVACY.md` for data-handling details.

## License
MIT. See `LICENSE`.
