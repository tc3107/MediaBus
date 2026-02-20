# Privacy Policy

## Scope
This app (MediaBus) is designed for local-network file sharing. It does not require an online account and does not include analytics or ad SDKs.

## Data Processed
MediaBus may process the following data locally on your device:
- Selected shared folder URI (persisted for server access)
- Pairing/session metadata for approved devices
- Transfer status and activity metadata
- Local network information needed to bind and advertise the host

## Data Sharing
- Data is served only to clients that connect to your local MediaBus server.
- MediaBus does not send personal data to a cloud backend run by the app developer.

## Permissions Used
- `INTERNET`: serve the local web client and API
- `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`: network binding and mDNS behavior
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`: keep host service running while active
- `CAMERA`: optional QR scanning for pairing
- `VIBRATE`: optional haptics
- `POST_NOTIFICATIONS`: foreground/status notification handling on newer Android versions

## Security
- MediaBus uses HTTPS for local connections.
- Pairing/session checks are used to control access.

## Your Control
- You choose whether to start/stop hosting.
- You choose the shared folder.
- You can revoke paired devices.

## Contact
If you distribute this app publicly, replace this section with your support contact details.
