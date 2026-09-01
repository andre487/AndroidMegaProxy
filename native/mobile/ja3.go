package mobile

import (
	"fmt"

	tls "github.com/refraction-networking/utls"
)

// applyJA3 builds the extension payloads needed for a usable handshake. JA3 identifies
// extension IDs but not their payloads, so unknown IDs are rejected instead of silently
// producing a fingerprint different from the requested one.
func applyJA3(conn *tls.UConn, raw, serverName string) error {
	spec, err := parseJA3(raw)
	if err != nil {
		return err
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
		case 18:
			extension = &tls.SCTExtension{}
		case 23:
			extension = &tls.UtlsExtendedMasterSecretExtension{}
		case 43:
			extension = &tls.SupportedVersionsExtension{Versions: []uint16{tls.VersionTLS13, tls.VersionTLS12}}
		case 45:
			extension = &tls.PSKKeyExchangeModesExtension{Modes: []uint8{tls.PskModeDHE}}
		case 51:
			extension = &tls.KeyShareExtension{KeyShares: []tls.KeyShare{{Group: tls.X25519}}}
		case 65281:
			extension = &tls.RenegotiationInfoExtension{}
		default:
			return fmt.Errorf("manual JA3 extension %d needs an explicit payload and is not supported", id)
		}
		extensions = append(extensions, extension)
	}
	return conn.ApplyPreset(&tls.ClientHelloSpec{
		TLSVersMin:         tls.VersionTLS12,
		TLSVersMax:         spec.Version,
		CipherSuites:       spec.Ciphers,
		CompressionMethods: []uint8{0},
		Extensions:         extensions,
	})
}
