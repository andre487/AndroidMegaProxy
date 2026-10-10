package mobile

import (
	"fmt"

	tls "github.com/refraction-networking/utls"
)

// applyJA3 builds the extension payloads needed for a usable handshake. JA3 identifies
// extension IDs but not their payloads, so unknown IDs are rejected instead of silently
// producing a fingerprint different from the requested one.
func applyJA3(conn *tls.UConn, raw, serverName string) error {
	spec, err := ja3ClientHelloSpec(raw, serverName, nil)
	if err != nil {
		return err
	}
	return conn.ApplyPreset(spec)
}

func ja3ClientHelloSpec(raw, serverName string, quicParameters *tls.QUICTransportParametersExtension) (*tls.ClientHelloSpec, error) {
	spec, err := parseJA3(raw)
	if err != nil {
		return nil, err
	}
	if quicParameters != nil {
		for _, required := range []uint16{16, 43, 51, 57} {
			found := false
			for _, id := range spec.Extensions {
				if id == required {
					found = true
				}
			}
			if !found {
				return nil, fmt.Errorf("QUIC JA3 requires extension %d", required)
			}
		}
		for _, cipher := range spec.Ciphers {
			if cipher < 0x1301 || cipher > 0x1303 {
				return nil, fmt.Errorf("QUIC JA3 requires TLS 1.3 cipher suites")
			}
		}
	}
	extensions := make([]tls.TLSExtension, 0, len(spec.Extensions))
	for _, id := range spec.Extensions {
		var extension tls.TLSExtension
		switch id {
		case 0:
			extension = &tls.SNIExtension{ServerName: serverName}
		case 5:
			extension = &tls.StatusRequestExtension{}
		case 10:
			curves := make([]tls.CurveID, len(spec.Groups))
			for i, group := range spec.Groups {
				curves[i] = tls.CurveID(group)
			}
			extension = &tls.SupportedCurvesExtension{Curves: curves}
		case 11:
			extension = &tls.SupportedPointsExtension{SupportedPoints: spec.Points}
		case 13:
			extension = &tls.SignatureAlgorithmsExtension{SupportedSignatureAlgorithms: []tls.SignatureScheme{tls.ECDSAWithP256AndSHA256, tls.PSSWithSHA256, tls.PKCS1WithSHA256, tls.ECDSAWithP384AndSHA384, tls.PSSWithSHA384}}
		case 16:
			extension = &tls.ALPNExtension{AlpnProtocols: []string{"http/1.1"}}
			if quicParameters != nil {
				extension = &tls.ALPNExtension{AlpnProtocols: []string{"h3"}}
			}
		case 18:
			extension = &tls.SCTExtension{}
		case 23:
			extension = &tls.UtlsExtendedMasterSecretExtension{}
		case 43:
			extension = &tls.SupportedVersionsExtension{Versions: []uint16{tls.VersionTLS13, tls.VersionTLS12}}
			if quicParameters != nil {
				extension = &tls.SupportedVersionsExtension{Versions: []uint16{tls.VersionTLS13}}
			}
		case 45:
			extension = &tls.PSKKeyExchangeModesExtension{Modes: []uint8{tls.PskModeDHE}}
		case 51:
			extension = &tls.KeyShareExtension{KeyShares: []tls.KeyShare{{Group: tls.X25519}}}
		case 65281:
			extension = &tls.RenegotiationInfoExtension{}
		case 57:
			if quicParameters == nil {
				return nil, fmt.Errorf("QUIC transport parameters require MASQUE")
			}
			extension = quicParameters
		default:
			return nil, fmt.Errorf("manual JA3 extension %d needs an explicit payload and is not supported", id)
		}
		extensions = append(extensions, extension)
	}
	minimum, maximum := uint16(tls.VersionTLS12), spec.Version
	// JA3 records ClientHello.legacy_version, not the supported_versions maximum.
	for _, id := range spec.Extensions {
		if id == 43 {
			maximum = tls.VersionTLS13
			break
		}
	}
	if quicParameters != nil {
		minimum, maximum = tls.VersionTLS13, tls.VersionTLS13
	}
	return &tls.ClientHelloSpec{
		TLSVersMin:         minimum,
		TLSVersMax:         maximum,
		CipherSuites:       spec.Ciphers,
		CompressionMethods: []uint8{0},
		Extensions:         extensions,
	}, nil
}
