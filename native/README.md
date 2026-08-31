# Go dataplane

This module is bound into `megaproxy.aar` with gomobile. It embeds tun2socks/gVisor,
registers an HTTPS CONNECT transport, protects every upstream socket through Android
`VpnService.protect`, verifies the proxy certificate and uses uTLS for ClientHello control.
`Start` always takes ownership of the passed duplicate TUN descriptor, including error paths.

Build prerequisites: Go 1.26+, Android SDK/NDK and `gomobile`.

```shell
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
gomobile bind -target=android -androidapi 26 -o ../app/libs/megaproxy.aar ./mobile
```

Run `go test ./...` before producing the AAR. The dependency versions are pinned in `go.mod`;
commit the generated `go.sum` after the first successful dependency download.
