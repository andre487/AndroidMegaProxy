package mobile

import (
	"strings"
	"testing"
)

func TestParseJA3(t *testing.T) {
	s, err := parseJA3("771,4865-4866,0-10-11-13-16-43-51,29-23,0")
	if err != nil {
		t.Fatal(err)
	}
	if s.Version != 771 || len(s.Ciphers) != 2 || len(s.Extensions) != 7 {
		t.Fatalf("unexpected spec: %#v", s)
	}
}

func TestParseJA3RejectsUnsafeOrUnboundedInput(t *testing.T) {
	for _, raw := range []string{
		"769,4865,0,29,0",
		"771,,0,29,0",
		strings.Repeat("1", 8193),
		"771," + strings.TrimSuffix(strings.Repeat("4865-", 257), "-") + ",0,29,0",
	} {
		if _, err := parseJA3(raw); err == nil {
			t.Fatalf("parseJA3(%q) unexpectedly succeeded", raw)
		}
	}
}

func TestConfigRejectsIPOrSchemeLikeHost(t *testing.T) {
	_, err := parseConfig(`{"host":"https://proxy.example","dialHost":"192.0.2.1","port":443,"username":"u","password":"p","profile":"CHROME_ANDROID","dohUrl":"https://dns.google/dns-query"}`)
	if err == nil {
		t.Fatal("expected invalid hostname")
	}
}

func TestDoHURLValidation(t *testing.T) {
	for _, raw := range []string{
		"https://dns.example", "https://dns.example/", "https://dns.example?key=synthetic",
		"HTTPS://dns.example/query", "https://dns.example:8443/query?mode=1",
		"https://192.0.2.1", "https://192.0.2.1:8443/dns-query",
		"https://[2001:db8::1]", "https://[2001:db8::1]:8443/dns-query",
	} {
		t.Run(raw, func(t *testing.T) {
			if err := validateDoHURL(raw); err != nil {
				t.Fatalf("valid DoH URL rejected: %v", err)
			}
		})
	}
	for _, raw := range []string{
		"", "http://dns.example/query", "https:///query", "https://user:secret@dns.example/query",
		"https://dns.example/query#fragment", "https://dns.example:0/query",
		"https://dns.example:65536/query", "https://dns.example/bad path",
		"https://2001:db8::1/dns-query", "https://dns.example/#",
	} {
		t.Run("invalid:"+raw, func(t *testing.T) {
			if err := validateDoHURL(raw); err == nil {
				t.Fatal("invalid DoH URL accepted")
			}
		})
	}
}
