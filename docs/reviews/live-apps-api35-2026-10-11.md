# Real-application API 35 verification — 2026-10-11

This report records a separate real-application acceptance run using the same
signed candidate as the earlier API 35 verification. The requested acceptance
sequence did **not pass in full**. This is a dated observation, not release approval.

## Privacy

Server addresses, domains, account names, credentials, profile names, video
identifiers and raw network captures are excluded. Servers and profiles are
identified only by their role and transport. Private configuration and raw
evidence remain outside version control.

## Candidate and setup

- Android API 35, a dedicated clean ARM64 emulator.
- MegaProxy 1.1.0, version code 19002.
- APK SHA-256: `3ffd2d25179047b83ccf8b636539788e2ba908213370b3f9bc43b8fb06982e0a`.
- Installation and any required store/video sign-in are completed before network scenarios.
- A user-provided configuration is imported through the application's UI.
- Split tunneling includes YouTube and Chrome; Vivaldi is excluded.
- VK Video and Chrome are included for the final controlled SOCKS5 checks.

## Preparation evidence

The candidate APK hash matches the earlier verified artifact. The excluded
browser was downloaded from its publisher and its APK signature verified.
The clean emulator has booted; store sign-in is complete. All four requested
applications are installed. Both video applications opened a public video
without an additional sign-in. Browser startup prompts are handled before
network scenarios.

Application versions: YouTube 21.40.161, Chrome 154.0.8037.126,
Vivaldi 8.2.4147.157, VK Video 1.166.

The temporary SOCKS5 server passed a host-side authentication and UDP DNS
round-trip preflight. Its access rules are restricted to the test client and
automatic cleanup was scheduled. This preflight is **not Android VPN coverage**;
the Android results and verified final cleanup are recorded below.

## Scenario ledger

| ID | Scenario | Verdict |
| --- | --- | --- |
| L01 | HTTPS profile negotiates HTTP/3; muted YouTube playback continues during Chrome browsing and speed checks | FAIL: optional HTTP/3 negotiation timed out; HTTPS fallback worked |
| L02 | Switching to MASQUE preserves YouTube playback and browser connectivity | FAIL: QUIC handshake timed out |
| L03 | Switching to SSH preserves playback and browsing through the TCP transport | PARTIAL: SSH and browsing worked; the full uninterrupted sequence was not established |
| L04 | An authenticated SOCKS5 server with UDP forwards VK Video, browsing and UDP checks | PASS after routing setup was corrected and frozen |
| L05 | Vivaldi remains outside the VPN while Chrome is included | PASS in explicitly checked intervals |
| L06 | Temporary server resources are removed; existing services are preserved | PASS |

Successful playback alone does not prove use of QUIC: buffered playback must be
distinguished from continued media downloads, and the application's actual
traffic protocol must be distinguished from the outer proxy transport.
Browser checks alone do not establish the video application's protocol.

## Routing and subscription interference

The system VPN UID ranges include Chrome and YouTube and exclude Vivaldi.
An actual request in Vivaldi reported a different exit from the same check in
Chrome while the VPN was connected. This confirms the excluded browser's route.

The imported subscription had hourly automatic updates enabled. Its interface
recorded a successful refresh during this run, and routing settings changed
before the video application was replaced. Subsequently, the effective VPN
allowlist contained only VK Video. Chrome's unresolved-name result in that
state is **not evidence of a SOCKS5 forwarding defect**.

Automatic subscription updates were disabled on the test device, Chrome was
selected again, and the app's reconnect action applied the changes. System UID
ranges then confirmed Chrome and VK Video were included and Vivaldi excluded.
The final SOCKS5 results use this controlled setup. The original imported file
was not edited. Freeze subscription updates before a transport acceptance run;
exercise subscription refresh separately when it is the subject of the test.

## HTTPS, MASQUE and SSH

The first HTTPS connection fell back to HTTPS after an optional HTTP/3
negotiation timeout. Chrome used HTTP/2 during this control run. The emulator
host initially routed proxy traffic through a separate host VPN. Direct host
routes, disabling that VPN, and restarting the emulator did not resolve the
Android handshake failure. HTTPS fallback connected without a suspected-blocking
warning in the observed final interval. MASQUE failed to establish its QUIC
session; failed handshakes produced suspected-blocking diagnostic events.

A short capture limited to this emulator showed outbound QUIC Initial packets
to the expected destination and no returning packets. A host-side control using
the production MASQUE dialer and browser fingerprint succeeded, including both
external HTTP/3 probes. This narrows the discrepancy but **does not establish
the root cause or a specific client fix**. SOCKS5 UDP success below shows that
UDP is not universally unavailable on the emulator.

The same long native YouTube video remained in picture-in-picture and advanced
beyond 33 minutes before it was stopped for the VK Video phase. SSH authentication
and host-key verification succeeded; Chrome loaded a diagnostic page and a
content site over HTTP/2. However, a failed MASQUE connection cannot count as
continuous media delivery through that proxy. YouTube media QUIC/HTTP/3 and the
complete uninterrupted HTTPS-to-MASQUE-to-SSH sequence remain **unverified**.

The requested speed-test service completed its mobile web download measurement
through the HTTPS fallback: 7.26 Mbps download and 52 ms ping. The completed page
offered another test; it did not display an upload measurement. This is a single
live-network observation over HTTP/2, not an HTTP/3 performance result or a
controlled throughput benchmark.

## SOCKS5 and native VK Video

The manually added profile authenticated against the disposable UDP-capable
server. With the corrected VPN allowlist, Chrome loaded diagnostic and content
pages successfully. The content page returned HTTP 200 with populated content.

The independent browser QUIC probe recorded successful HTTP/3 responses. The
app's native Test action separately confirmed HTTP/3 through both external probe
providers. These results establish actual UDP forwarding through SOCKS5; an
ordinary diagnostic-page request using HTTP/2 does not invalidate them.

VK Video showed decoded video frames, its native media session reported PLAYING,
and its playback position advanced. The disposable server also recorded substantial
data delivery. VK Video's own media ALPN was not established; this scenario does
not require the video service itself to select HTTP/3.

## Cleanup and remaining work

The disposable service and cleanup timer were stopped. Its listening port,
exact tagged firewall rules, generated credentials/configuration and temporary
directory were verified absent. No temporary capture units remain. Both existing
proxy services and the original SSH service remained active. Captures and raw
configuration are private local evidence and are not attached to this report.

The installed APK hash was checked again after restarting the emulator and
matches the candidate above. No production source was changed. Before accepting
the feature, diagnose the Android direct-MASQUE handshake discrepancy, establish
YouTube media QUIC independently, and repeat the uninterrupted profile-switch
sequence with frozen routing settings. Upload throughput was not measured.
