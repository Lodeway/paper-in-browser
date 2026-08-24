package main

import (
	"bytes"
	"net"
	"regexp"
	"strings"
	"testing"
)

func TestJWTRoundTrip(t *testing.T) {
	secret = []byte("test-secret")
	tok := signToken("brave-otter-a1")
	sub, err := verifyToken(tok)
	if err != nil {
		t.Fatalf("verifyToken(valid): %v", err)
	}
	if sub != "brave-otter-a1" {
		t.Fatalf("sub = %q, want brave-otter-a1", sub)
	}
}

func TestJWTTamperRejected(t *testing.T) {
	secret = []byte("test-secret")
	tok := signToken("brave-otter-a1")

	// Tamper with the claims segment (swap the subject) while keeping the old signature.
	parts := strings.Split(tok, ".")
	forged := parts[0] + "." + b64.EncodeToString([]byte(`{"sub":"evil-name-00","iat":1}`)) + "." + parts[2]
	if _, err := verifyToken(forged); err == nil {
		t.Fatal("tampered claims accepted")
	}

	// Token signed under a different secret.
	secret = []byte("other-secret")
	if _, err := verifyToken(tok); err == nil {
		t.Fatal("token from another secret accepted")
	}
	secret = []byte("test-secret")

	// Structural garbage.
	for _, bad := range []string{"", "abc", "a.b", "a.b.c.d", parts[0] + "." + parts[1] + ".!!!"} {
		if _, err := verifyToken(bad); err == nil {
			t.Fatalf("verifyToken(%q) accepted", bad)
		}
	}
}

func TestRandomNameValid(t *testing.T) {
	re := regexp.MustCompile(`^[a-z]+-[a-z]+-[0-9a-f]{2}$`)
	for i := 0; i < 1000; i++ {
		n := randomName()
		if !validName(n) {
			t.Fatalf("randomName() = %q fails validName", n)
		}
		if !re.MatchString(n) {
			t.Fatalf("randomName() = %q does not match adjective-noun-xx", n)
		}
	}
	for _, w := range append(append([]string{}, adjectives...), nouns...) {
		if !validName(w) {
			t.Fatalf("wordlist entry %q fails validName", w)
		}
	}
}

// buildHandshake assembles a Minecraft handshake packet: [len][id=0][protocol][host][port][next].
func buildHandshake(protocol int32, host string, port uint16, next int32) []byte {
	var body bytes.Buffer
	writeVarInt(&body, 0) // packet id
	writeVarInt(&body, protocol)
	writeString(&body, host)
	body.WriteByte(byte(port >> 8))
	body.WriteByte(byte(port))
	writeVarInt(&body, next)
	var out bytes.Buffer
	writeVarInt(&out, int32(body.Len()))
	out.Write(body.Bytes())
	return out.Bytes()
}

func TestHandshakeHostnameExtraction(t *testing.T) {
	old := *domain
	*domain = "tun.lodeway.app"
	defer func() { *domain = old }()

	tests := []struct {
		host     string
		wantName string
	}{
		{"brave-otter-a1.tun.lodeway.app", "brave-otter-a1"},
		{"Brave-Otter-A1.Tun.Lodeway.App", "brave-otter-a1"},            // case-insensitive
		{"brave-otter-a1.tun.lodeway.app.", "brave-otter-a1"},           // trailing dot
		{"brave-otter-a1.tun.lodeway.app\x00FML\x00", "brave-otter-a1"}, // Forge/FML marker
		{"example.com", ""},     // wrong suffix
		{"tun.lodeway.app", ""}, // bare domain, no name
		{"deep.brave-otter-a1.tun.lodeway.app", "deep.brave-otter-a1"}, // not validName; /tunnel would reject
	}
	for _, tc := range tests {
		pkt := buildHandshake(772, tc.host, 25565, 1)
		client, server := net.Pipe()
		go func() {
			client.Write(pkt)
			client.Close()
		}()
		h, err := readHandshake(server)
		server.Close()
		if err != nil {
			t.Fatalf("readHandshake(%q): %v", tc.host, err)
		}
		if h.host != tc.host || h.port != 25565 || h.protocol != 772 || h.next != 1 {
			t.Fatalf("handshake fields for %q: %+v", tc.host, h)
		}
		if !bytes.Equal(h.raw, pkt) {
			t.Fatalf("raw bytes for %q not preserved: got %x want %x", tc.host, h.raw, pkt)
		}
		if got := sessionNameFor(h.host); got != tc.wantName {
			t.Fatalf("sessionNameFor(%q) = %q, want %q", tc.host, got, tc.wantName)
		}
	}
}

func TestHandshakeRejectsGarbage(t *testing.T) {
	// Wrong packet id (1 instead of 0).
	var body bytes.Buffer
	writeVarInt(&body, 1)
	var out bytes.Buffer
	writeVarInt(&out, int32(body.Len()))
	out.Write(body.Bytes())
	client, server := net.Pipe()
	go func() {
		client.Write(out.Bytes())
		client.Close()
	}()
	if _, err := readHandshake(server); err == nil {
		t.Fatal("non-handshake packet accepted")
	}
	server.Close()
}

func TestFrameEncodeDecode(t *testing.T) {
	for _, tc := range []struct {
		t       byte
		id      uint32
		payload []byte
	}{
		{frameOpen, 1, []byte("203.0.113.9:51234")},
		{frameData, 0xDEADBEEF, []byte{0, 1, 2, 255}},
		{frameClose, 42, nil},
	} {
		frame := encodeFrame(tc.t, tc.id, tc.payload)
		if len(frame) != 5+len(tc.payload) {
			t.Fatalf("frame length %d, want %d", len(frame), 5+len(tc.payload))
		}
		ft, id, payload, ok := decodeFrame(frame)
		if !ok || ft != tc.t || id != tc.id || !bytes.Equal(payload, tc.payload) {
			t.Fatalf("decode(encode(%d,%d,%x)) = (%d,%d,%x,%v)", tc.t, tc.id, tc.payload, ft, id, payload, ok)
		}
	}
	if _, _, _, ok := decodeFrame([]byte{1, 2, 3}); ok {
		t.Fatal("short frame accepted")
	}
}
