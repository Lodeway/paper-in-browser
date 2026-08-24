// paper-in-browser server: everything server-side for the "Paper in the browser" experiment,
// where a real Paper Minecraft server runs inside a browser tab (CheerpJ) and this binary makes
// it reachable from the outside world.
//
// One binary, four jobs:
//
//  1. Static site: the page plus ~110 MB of jars, served with HTTP Range support (CheerpJ
//     fetches jar slices) and long immutable caching for /jars/.
//  2. Same-origin reverse proxies for the Mojang APIs the in-browser server needs, so the page
//     never fights CORS.
//  3. Identity minting (/api/identity): a random player-facing subdomain per browser, proven by
//     a signed token the browser keeps forever. No database — the token is the whole record.
//  4. The WebSocket<->TCP tunnel that makes each browser-hosted server reachable at
//     <name>.tun.lodeway.app (see tunnel.go).
//
// TLS is Caddy's job (on-demand certificates gated by /ask), same as the original
// lodeway-tunnel deployment. The web instance and the tunnel instance may be the same process
// or two processes sharing -secret; nothing here assumes shared memory beyond the tunnel's own
// session registry.
package main

import (
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
)

var (
	httpListen = flag.String("http", "127.0.0.1:8090", "HTTP listen address (static site + /api + /tunnel + /ask + /healthz); put Caddy/TLS in front")
	staticDir  = flag.String("static", "./web/dist", "directory to serve as the static site")
	tcpListen  = flag.String("tcp", ":25566", "Minecraft TCP listen address")
	domain     = flag.String("domain", "tun.lodeway.app", "tunnel domain suffix")
	secretFlag = flag.String("secret", "", "HMAC secret for identity tokens; empty = random per boot (dev only)")
	secretFile = flag.String("secret-file", "", "read the HMAC secret from this file instead of the command line (invisible to ps)")
)

// secret is the HMAC key for identity tokens, resolved from -secret at boot.
var secret []byte

// staticHandler serves the site out of dir. http.FileServer already handles Range requests
// (CheerpJ reads jar slices), so the wrapper only sets cache policy, maps "/" to index.html,
// and refuses directory listings.
func staticHandler(dir string) http.Handler {
	fs := http.FileServer(http.Dir(dir))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := path.Clean("/" + r.URL.Path)
		if p == "/" {
			w.Header().Set("Cache-Control", "no-cache")
			http.ServeFile(w, r, filepath.Join(dir, "index.html"))
			return
		}
		// No directory listings: anything that isn't a plain file is a 404.
		fi, err := os.Stat(filepath.Join(dir, filepath.FromSlash(p)))
		if err != nil || fi.IsDir() {
			http.NotFound(w, r)
			return
		}
		if strings.HasPrefix(p, "/jars/") {
			// The jars are content-addressed by release; a new build ships new paths.
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		r.URL.Path = p
		fs.ServeHTTP(w, r)
	})
}

// mojangProxy reverse-proxies /proxy/<x>/... to the given upstream, so the page can call
// Mojang APIs same-origin. httputil.ReverseProxy strips hop-by-hop headers for us; the
// Rewrite form also drops any inbound forwarding headers.
func mojangProxy(upstream string) http.Handler {
	target, err := url.Parse(upstream)
	if err != nil {
		log.Fatalf("bad upstream %q: %v", upstream, err)
	}
	rp := &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(target)
			pr.Out.Host = target.Host
		},
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet, http.MethodHead, http.MethodPost:
		default:
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		rp.ServeHTTP(w, r)
	})
}

func main() {
	flag.Parse()

	if *secretFile != "" {
		data, err := os.ReadFile(*secretFile)
		if err != nil {
			log.Fatalf("reading -secret-file: %v", err)
		}
		secret = []byte(strings.TrimSpace(string(data)))
	} else if *secretFlag != "" {
		secret = []byte(*secretFlag)
	} else {
		buf := make([]byte, 32)
		if _, err := rand.Read(buf); err != nil {
			log.Fatalf("generating secret: %v", err)
		}
		secret = []byte(hex.EncodeToString(buf))
		log.Printf("WARNING: -secret not set; using a random per-boot secret. Minted identities will NOT survive a restart (fine for local dev).")
	}

	mux := http.NewServeMux()

	// Same-origin Mojang API proxies.
	mux.Handle("/proxy/services/", http.StripPrefix("/proxy/services", mojangProxy("https://api.minecraftservices.com")))
	mux.Handle("/proxy/session/", http.StripPrefix("/proxy/session", mojangProxy("https://sessionserver.mojang.com")))
	mux.Handle("/proxy/profiles/", http.StripPrefix("/proxy/profiles", mojangProxy("https://api.mojang.com")))

	// Identity minting (identity.go).
	mux.HandleFunc("/api/identity", handleIdentity)

	// Tunnel endpoints (tunnel.go).
	mux.HandleFunc("/tunnel", handleTunnel)
	// Caddy on-demand TLS gate: only issue certificates for the base domain and well-formed
	// <name>.<domain> subdomains. A live session cannot be required — the browser's registering
	// WebSocket is itself the first TLS handshake for a fresh name.
	mux.HandleFunc("/ask", func(w http.ResponseWriter, r *http.Request) {
		d := strings.ToLower(r.URL.Query().Get("domain"))
		if d == strings.ToLower(*domain) || validName(sessionNameFor(d)) {
			w.WriteHeader(http.StatusOK)
			return
		}
		w.WriteHeader(http.StatusForbidden)
	})
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		reg.mu.RLock()
		n := len(reg.sessions)
		reg.mu.RUnlock()
		fmt.Fprintf(w, "ok sessions=%d\n", n)
	})

	// Static site last (it matches everything else).
	mux.Handle("/", staticHandler(*staticDir))

	go func() {
		log.Printf("http listening on %s (static %s, tunnel /tunnel?name=<sub>&token=<jwt>)", *httpListen, *staticDir)
		log.Fatal(http.ListenAndServe(*httpListen, mux))
	}()

	ln, err := net.Listen("tcp", *tcpListen)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("minecraft tcp listening on %s for *.%s", *tcpListen, *domain)
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		if tc, ok := c.(*net.TCPConn); ok {
			tc.SetNoDelay(true)
		}
		go handleClient(c)
	}
}
