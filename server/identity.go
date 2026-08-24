// Identity minting: each browser gets a random player-facing address (<name>.<domain>) plus a
// signed token proving it owns the name. There is no server-side store — collision resistance
// comes from randomness (64 adjectives x 64 nouns x 256 suffixes ~= one million names), and the
// token is the entire proof of ownership. Tokens are JWTs (HS256, compact serialization) written
// by hand: it's ~40 lines with crypto/hmac and keeps the module dependency-free.
//
// Tokens deliberately have no expiry: the whole point is that the browser keeps its address
// forever (in localStorage), surviving reloads and server restarts as long as -secret is stable.
package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// Friendly adjectives + Minecraft-flavored (but non-trademark) nouns: animals, plants, landforms.
// Everything here must satisfy validName ([a-z0-9-]).
var adjectives = []string{
	"amber", "bold", "brave", "breezy", "bright", "brisk", "calm", "cheery",
	"clever", "cozy", "crimson", "curious", "dapper", "deft", "dusty", "eager",
	"early", "emerald", "fabled", "fleet", "gentle", "gilded", "glad", "golden",
	"happy", "hardy", "hazel", "humble", "ivory", "jolly", "keen", "kind",
	"lively", "lucky", "lunar", "mellow", "merry", "mighty", "misty", "nimble",
	"noble", "olive", "peppy", "plucky", "proud", "quick", "quiet", "rosy",
	"rustic", "sandy", "scarlet", "shiny", "silent", "silver", "sleek", "snug",
	"spry", "stout", "sunny", "swift", "tidy", "violet", "witty", "zesty",
}

var nouns = []string{
	"badger", "basin", "birch", "bluff", "brook", "canyon", "cavern", "cliff",
	"cove", "crag", "creek", "dale", "delta", "dune", "falcon", "fern",
	"fjord", "fox", "gale", "geyser", "glacier", "glade", "gorge", "grove",
	"harbor", "heron", "hollow", "isle", "knoll", "lagoon", "lark", "lynx",
	"marsh", "meadow", "mesa", "moss", "newt", "oak", "orchard", "osprey",
	"otter", "peak", "pine", "pond", "prairie", "quarry", "raven", "reef",
	"ridge", "river", "shale", "shore", "sparrow", "spruce", "summit", "taiga",
	"tarn", "thicket", "trail", "tundra", "vale", "willow", "wolf", "wren",
}

// randomName mints "<adjective>-<noun>-<2 hex chars>". Always satisfies validName and stays
// well under the 40-char limit.
func randomName() string {
	var buf [3]byte
	if _, err := rand.Read(buf[:]); err != nil {
		panic(err) // crypto/rand failing means the host is broken
	}
	adj := adjectives[int(buf[0])%len(adjectives)]
	noun := nouns[int(buf[1])%len(nouns)]
	return fmt.Sprintf("%s-%s-%02x", adj, noun, buf[2])
}

// --- Minimal JWT (HS256, compact serialization) ---

var b64 = base64.RawURLEncoding

type claims struct {
	Sub string `json:"sub"`
	Iat int64  `json:"iat"`
}

// signToken builds a compact JWS: base64url(header).base64url(claims).base64url(HMAC-SHA256).
func signToken(name string) string {
	header := b64.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	body, _ := json.Marshal(claims{Sub: name, Iat: time.Now().Unix()})
	signing := header + "." + b64.EncodeToString(body)
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(signing))
	return signing + "." + b64.EncodeToString(mac.Sum(nil))
}

// verifyToken checks the signature (constant time via hmac.Equal) and returns the subject.
func verifyToken(token string) (name string, err error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return "", errors.New("not a compact JWT")
	}
	var header struct {
		Alg string `json:"alg"`
	}
	hb, err := b64.DecodeString(parts[0])
	if err != nil || json.Unmarshal(hb, &header) != nil || header.Alg != "HS256" {
		return "", errors.New("bad header")
	}
	sig, err := b64.DecodeString(parts[2])
	if err != nil {
		return "", errors.New("bad signature encoding")
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(parts[0] + "." + parts[1]))
	if !hmac.Equal(sig, mac.Sum(nil)) {
		return "", errors.New("signature mismatch")
	}
	cb, err := b64.DecodeString(parts[1])
	if err != nil {
		return "", errors.New("bad claims encoding")
	}
	var c claims
	if err := json.Unmarshal(cb, &c); err != nil || !validName(c.Sub) {
		return "", errors.New("bad claims")
	}
	return c.Sub, nil
}

// handleIdentity implements POST /api/identity.
//
// Without a token it mints a fresh name+token. With "Authorization: Bearer <jwt>" of a valid
// existing token it echoes back the same name/address, letting the page re-derive its address
// display from just the stored token. A presented-but-invalid token gets a 401 so the page
// knows to drop it and mint fresh (rather than silently handing back a new identity).
func handleIdentity(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var name, token string
	if auth := r.Header.Get("Authorization"); auth != "" {
		bearer, ok := strings.CutPrefix(auth, "Bearer ")
		if !ok {
			http.Error(w, "malformed Authorization header", http.StatusUnauthorized)
			return
		}
		sub, err := verifyToken(strings.TrimSpace(bearer))
		if err != nil {
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}
		name, token = sub, strings.TrimSpace(bearer)
	} else {
		name = randomName()
		token = signToken(name)
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"name":    name,
		"address": name + "." + strings.ToLower(*domain),
		"token":   token,
	})
}
