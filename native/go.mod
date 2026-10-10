module dev.megaproxy/native

go 1.26.3

require (
	github.com/refraction-networking/uquic v0.0.7-0.20260804184259-837c7ce1b72b
	github.com/refraction-networking/utls v1.8.2
	github.com/xjasonlyu/tun2socks/v2 v2.7.0
	go.uber.org/zap v1.28.0
	golang.org/x/crypto v0.55.0
	golang.org/x/net v0.58.0
	golang.org/x/sys v0.47.0
	gvisor.dev/gvisor v0.0.0-20260701204157-69c2d17aea96
)

require (
	github.com/andybalholm/brotli v1.1.1 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/google/gopacket v1.1.19 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/klauspost/compress v1.18.0 // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/refraction-networking/clienthellod v0.5.0-alpha2 // indirect
	go.uber.org/atomic v1.11.0 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	golang.org/x/exp v0.0.0-20260611194520-c48552f49976 // indirect
	golang.org/x/mobile v0.0.0-20260821190718-4776eadac327 // indirect
	golang.org/x/mod v0.39.0 // indirect
	golang.org/x/sync v0.22.0 // indirect
	golang.org/x/text v0.41.0 // indirect
	golang.org/x/time v0.15.0 // indirect
	golang.org/x/tools v0.49.0 // indirect
)

tool golang.org/x/mobile/cmd/gobind

replace github.com/refraction-networking/uquic => ./third_party/uquic
