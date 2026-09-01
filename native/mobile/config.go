package mobile

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"

	tls "github.com/refraction-networking/utls"
)

type config struct {
	Type                         string `json:"type"`
	Host                         string `json:"host"`
	DialHost                     string `json:"dialHost"`
	Port                         int    `json:"port"`
	Username                     string `json:"username"`
	Password                     string `json:"password"`
	AllowInvalidProxyCertificate bool   `json:"allowInvalidProxyCertificate"`
	Profile                      string `json:"profile"`
	CustomJA3                    string `json:"customJa3"`
	DoHURL                       string `json:"dohUrl"`
	AllowIPv6                    bool   `json:"allowIpv6"`
	BypassLocalNetworks          bool   `json:"bypassLocalNetworks"`
	PrivateKey                   string `json:"privateKey"`
	SSHProfile                   string `json:"sshProfile"`
	TrustedHostKey               string `json:"trustedHostKey"`
	AcceptAnyHostKey             bool   `json:"acceptAnyHostKey"`
	JumpHost                     string `json:"jumpHost"`
	JumpDialHost                 string `json:"jumpDialHost"`
	JumpPort                     int    `json:"jumpPort"`
	JumpUsername                 string `json:"jumpUsername"`
	JumpPassword                 string `json:"jumpPassword"`
	JumpPrivateKey               string `json:"jumpPrivateKey"`
	JumpTrustedHostKey           string `json:"jumpTrustedHostKey"`
	JumpAcceptAnyHostKey         bool   `json:"jumpAcceptAnyHostKey"`
	SameJumpAuthentication       bool   `json:"sameJumpAuthentication"`
}

func parseConfig(raw string) (config, error) {
	var c config
	if err := json.Unmarshal([]byte(raw), &c); err != nil {
		return c, fmt.Errorf("decode config: %w", err)
	}
	c.Host = strings.TrimSpace(c.Host)
	c.DialHost = strings.TrimSpace(c.DialHost)
	if c.Type == "" {
		c.Type = "HTTPS"
	}
	if c.Host == "" || strings.ContainsAny(c.Host, "/: \t\r\n") {
		return c, errors.New("invalid proxy hostname")
	}
	if net.ParseIP(c.DialHost) == nil {
		return c, errors.New("proxy bootstrap IP is missing or invalid")
	}
	if c.Port < 1 || c.Port > 65535 {
		return c, errors.New("invalid proxy port")
	}
	if c.Type == "HTTPS" && (c.Username == "" || c.Password == "") {
		return c, errors.New("basic auth credentials are required")
	}
	if c.Type != "HTTPS" && c.Username == "" {
		return c, errors.New("SSH username is required")
	}
	if c.Type == "SSH_JUMP" {
		if c.JumpHost == "" || strings.ContainsAny(c.JumpHost, "/: \t\r\n") {
			return c, errors.New("invalid jump hostname")
		}
		if net.ParseIP(c.JumpDialHost) == nil {
			return c, errors.New("jump bootstrap IP is missing or invalid")
		}
		if c.JumpPort < 1 || c.JumpPort > 65535 {
			return c, errors.New("invalid jump port")
		}
		if c.SameJumpAuthentication {
			c.JumpUsername, c.JumpPassword, c.JumpPrivateKey = c.Username, c.Password, c.PrivateKey
		}
		if c.JumpUsername == "" {
			return c, errors.New("jump SSH username is required")
		}
	}
	if err := validateDoHURL(c.DoHURL); err != nil {
		return c, err
	}
	if c.Type == "HTTPS" {
		if _, err := c.helloID(); err != nil {
			return c, err
		}
	}
	if c.Type != "HTTPS" && c.Type != "SSH" && c.Type != "SSH_JUMP" {
		return c, fmt.Errorf("unsupported proxy type %q", c.Type)
	}
	return c, nil
}

func (c config) address() string { return net.JoinHostPort(c.DialHost, strconv.Itoa(c.Port)) }

func (c config) displayAddress() string { return net.JoinHostPort(c.Host, strconv.Itoa(c.Port)) }
func (c config) jumpAddress() string {
	return net.JoinHostPort(c.JumpDialHost, strconv.Itoa(c.JumpPort))
}

func (c config) helloID() (tls.ClientHelloID, error) {
	switch c.Profile {
	case "CHROME_ANDROID":
		return tls.HelloChrome_133, nil
	case "FIREFOX_ANDROID":
		return tls.HelloFirefox_120, nil
	case "EDGE_ANDROID":
		return tls.HelloEdge_85, nil
	case "RANDOMIZED":
		return tls.HelloRandomizedALPN, nil
	case "CUSTOM":
		if _, err := parseJA3(c.CustomJA3); err != nil {
			return tls.ClientHelloID{}, err
		}
		return tls.HelloCustom, nil
	default:
		return tls.ClientHelloID{}, fmt.Errorf("unsupported TLS profile %q", c.Profile)
	}
}

type ja3Spec struct {
	Version    uint16
	Ciphers    []uint16
	Extensions []uint16
	Groups     []uint16
	Points     []uint8
}

func parseJA3(raw string) (ja3Spec, error) {
	var out ja3Spec
	fields := strings.Split(strings.TrimSpace(raw), ",")
	if len(fields) != 5 {
		return out, errors.New("JA3 must contain five comma-separated fields")
	}
	values := make([][]uint16, 5)
	for i, field := range fields {
		if field == "" && i > 0 {
			continue
		}
		for _, token := range strings.Split(field, "-") {
			n, err := strconv.ParseUint(token, 10, 16)
			if err != nil {
				return out, fmt.Errorf("invalid JA3 number %q", token)
			}
			values[i] = append(values[i], uint16(n))
		}
	}
	if len(values[0]) != 1 {
		return out, errors.New("JA3 TLS version is required")
	}
	out.Version, out.Ciphers, out.Extensions, out.Groups = values[0][0], values[1], values[2], values[3]
	for _, point := range values[4] {
		if point > 255 {
			return out, errors.New("EC point format exceeds 255")
		}
		out.Points = append(out.Points, uint8(point))
	}
	return out, nil
}
