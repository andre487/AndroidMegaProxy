# Foreground service declaration

This document contains the text and supporting video for the Google Play
foreground service declaration of MegaProxy.

## Foreground service type

`specialUse`

Manifest subtype:

> User-initiated and always-on VPN tunnel that continuously routes selected
> apps or all device traffic through user-configured proxy profiles.

## Play Console description

MegaProxy provides a user-configured VPN tunnel, which is the app's core
functionality. The foreground service starts only after the user taps Connect,
when Android starts the configured Always-on VPN, or during a user-initiated
connection test.

The service continuously processes network traffic through Android's
`VpnService` tunnel. This work must start immediately and remain active for the
entire VPN session: delaying, suspending, or terminating it would immediately
interrupt the network connection that the user explicitly enabled.

While the tunnel is active, MegaProxy displays a persistent, low-priority
notification titled "MegaProxy is active". The Android VPN indicator and the
main screen also show that the VPN is connected. The user can stop a manually
started session by tapping Disconnect. An Always-on VPN session is controlled
through MegaProxy or Android's VPN settings. MegaProxy does not keep the
foreground service running after the VPN session has stopped.

## Video

[Foreground service demonstration](assets/foreground-service-demo.mp4)

The video uses an Android emulator and a non-sensitive profile label. It does
not display proxy endpoints, credentials, or private configuration values.

- `00:00` — MegaProxy is disconnected; the user starts the VPN with Connect.
- `00:20` — the main screen reports Connected and Android shows the VPN icon.
- `00:31` — the notification shade shows the ongoing notification
  "MegaProxy is active — Connected".
- `00:38` — the app remains connected after the notification shade is closed.

## Reviewer notes

- The foreground service is visible and directly associated with the user's
  active VPN session.
- It performs continuous, user-expected networking rather than deferrable
  background work.
- The service uses `android.permission.BIND_VPN_SERVICE` and is not exported to
  other apps.
- `POST_NOTIFICATIONS` is requested on supported Android versions so the
  persistent status notification is visible to the user.
