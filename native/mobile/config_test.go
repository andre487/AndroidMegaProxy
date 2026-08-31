package mobile

import "testing"

func TestParseJA3(t *testing.T) {
	s, err := parseJA3("771,4865-4866,0-10-11-13-16-43-51,29-23,0")
	if err != nil {
		t.Fatal(err)
	}
	if s.Version != 771 || len(s.Ciphers) != 2 || len(s.Extensions) != 7 {
		t.Fatalf("unexpected spec: %#v", s)
	}
}

func TestConfigRejectsIPOrSchemeLikeHost(t *testing.T) {
	_, err := parseConfig(`{"host":"https://proxy.example","dialHost":"192.0.2.1","port":443,"username":"u","password":"p","profile":"CHROME_ANDROID","dohUrl":"https://dns.google/dns-query"}`)
	if err == nil {
		t.Fatal("expected invalid hostname")
	}
}

func TestDoHRequiresHTTPS(t *testing.T) {
	if validateDoHURL("http://dns.example/query") == nil {
		t.Fatal("expected HTTPS requirement")
	}
	if validateDoHURL("https://dns.example/dns-query") != nil {
		t.Fatal("expected valid DoH URL")
	}
}
