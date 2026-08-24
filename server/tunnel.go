// The WebSocket<->TCP tunnel, ported from lodeway-tunnel: exposes browser-hosted Minecraft
// servers on *.<domain>.
//
// A browser session registers over WebSocket (/tunnel?name=<subdomain>&token=<jwt>). Minecraft
// clients connect over plain TCP; the first packet (handshake) carries the hostname the player
// typed, which selects the session — the same trick proxies like Velocity use, and the only
// option since raw TCP has no SNI. Each client connection is multiplexed over the session's
// WebSocket as binary frames:
//
//	[type u8][conn id u32 BE][payload]   type 1 = OPEN (server→browser, payload = remote address)
//	                                     type 2 = DATA (both directions)
//	                                     type 3 = CLOSE (both directions)
//
// Ownership (the one change from the reference): names come exclusively from /api/identity, and
// registering requires a JWT whose sub equals the name. The token is the entire proof — the
// tunnel needs no database. Because only the owner can present a valid token, a second
// connection with the same name IS the same user (another tab), so it evicts the old session.
package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	// closeReplaced tells an evicted browser not to reconnect (another tab now owns the session).
	closeReplaced = 4001
	// closeBadToken tells the browser its token is no good; the page re-mints an identity
	// instead of retrying.
	closeBadToken = 4003
)

const (
	frameOpen  = 1
	frameData  = 2
	frameClose = 3
)

// offlineVersion is the version name shown in status replies when no session is connected.
const offlineVersion = "26.2"

// encodeFrame builds a tunnel frame: [type u8][conn id u32 BE][payload].
func encodeFrame(t byte, id uint32, payload []byte) []byte {
	frame := make([]byte, 5+len(payload))
	frame[0] = t
	binary.BigEndian.PutUint32(frame[1:5], id)
	copy(frame[5:], payload)
	return frame
}

// decodeFrame is the inverse; ok is false for anything shorter than a header.
func decodeFrame(msg []byte) (t byte, id uint32, payload []byte, ok bool) {
	if len(msg) < 5 {
		return 0, 0, nil, false
	}
	return msg[0], binary.BigEndian.Uint32(msg[1:5]), msg[5:], true
}

// session is one connected browser (one hosted server).
type session struct {
	name   string
	ws     *websocket.Conn
	writeM sync.Mutex
	conns  sync.Map // uint32 -> net.Conn
	nextID uint32
	closed chan struct{}
}

func (s *session) send(t byte, id uint32, payload []byte) error {
	frame := encodeFrame(t, id, payload)
	s.writeM.Lock()
	defer s.writeM.Unlock()
	s.ws.SetWriteDeadline(time.Now().Add(30 * time.Second))
	return s.ws.WriteMessage(websocket.BinaryMessage, frame)
}

type registry struct {
	mu       sync.RWMutex
	sessions map[string]*session
}

func (r *registry) get(name string) *session {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.sessions[name]
}

func (r *registry) put(s *session) (replaced *session) {
	r.mu.Lock()
	defer r.mu.Unlock()
	replaced = r.sessions[s.name]
	r.sessions[s.name] = s
	return replaced
}

func (r *registry) remove(s *session) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.sessions[s.name] == s {
		delete(r.sessions, s.name)
	}
}

var reg = &registry{sessions: map[string]*session{}}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  64 * 1024,
	WriteBufferSize: 64 * 1024,
	CheckOrigin:     func(r *http.Request) bool { return true }, // ownership is proven by the token, not the origin
}

func validName(n string) bool {
	if len(n) == 0 || len(n) > 40 {
		return false
	}
	for _, c := range n {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
			return false
		}
	}
	return true
}

func handleTunnel(w http.ResponseWriter, r *http.Request) {
	name := strings.ToLower(r.URL.Query().Get("name"))
	if !validName(name) {
		http.Error(w, "invalid or missing ?name=", http.StatusBadRequest)
		return
	}
	sub, tokenErr := verifyToken(r.URL.Query().Get("token"))
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	// Token problems are reported as a WS close code (4003) rather than an HTTP status so the
	// page can tell "re-mint identity" apart from ordinary connection failures worth retrying.
	if tokenErr != nil || sub != name {
		ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(closeBadToken, "invalid token"), time.Now().Add(2*time.Second))
		ws.Close()
		log.Printf("session %q rejected from %s: invalid token", name, r.RemoteAddr)
		return
	}
	s := &session{name: name, ws: ws, closed: make(chan struct{})}
	if old := reg.put(s); old != nil {
		log.Printf("session %q replaced by a new browser connection", name)
		old.writeM.Lock()
		old.ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(closeReplaced, "replaced by another browser session"), time.Now().Add(2*time.Second))
		old.writeM.Unlock()
		old.ws.Close()
	}
	log.Printf("session %q connected from %s", name, r.RemoteAddr)
	ws.SetReadLimit(4 << 20)
	ws.SetPongHandler(func(string) error { ws.SetReadDeadline(time.Now().Add(90 * time.Second)); return nil })
	ws.SetReadDeadline(time.Now().Add(90 * time.Second))
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-s.closed:
				return
			case <-t.C:
				s.writeM.Lock()
				ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
				err := ws.WriteMessage(websocket.PingMessage, nil)
				s.writeM.Unlock()
				if err != nil {
					ws.Close()
					return
				}
			}
		}
	}()
	defer func() {
		close(s.closed)
		reg.remove(s)
		s.conns.Range(func(k, v any) bool { v.(net.Conn).Close(); return true })
		ws.Close()
		log.Printf("session %q disconnected", name)
	}()
	for {
		mt, msg, err := ws.ReadMessage()
		if err != nil {
			return
		}
		ft, id, payload, ok := decodeFrame(msg)
		if mt != websocket.BinaryMessage || !ok {
			continue
		}
		ws.SetReadDeadline(time.Now().Add(90 * time.Second))
		switch ft {
		case frameData:
			if c, ok := s.conns.Load(id); ok {
				c.(net.Conn).SetWriteDeadline(time.Now().Add(30 * time.Second))
				if _, err := c.(net.Conn).Write(payload); err != nil {
					c.(net.Conn).Close()
				}
			}
		case frameClose:
			if c, ok := s.conns.LoadAndDelete(id); ok {
				c.(net.Conn).Close()
			}
		}
	}
}

// --- Minecraft protocol helpers (just enough to read a handshake and answer when no session exists) ---

func readVarInt(r io.ByteReader) (int32, error) {
	var result int32
	for i := 0; i < 5; i++ {
		b, err := r.ReadByte()
		if err != nil {
			return 0, err
		}
		result |= int32(b&0x7F) << (7 * uint(i))
		if b&0x80 == 0 {
			return result, nil
		}
	}
	return 0, errors.New("varint too long")
}

func writeVarInt(w *bytes.Buffer, v int32) {
	u := uint32(v)
	for {
		b := byte(u & 0x7F)
		u >>= 7
		if u != 0 {
			b |= 0x80
		}
		w.WriteByte(b)
		if u == 0 {
			return
		}
	}
}

func writePacket(conn net.Conn, id int32, payload []byte) error {
	var body bytes.Buffer
	writeVarInt(&body, id)
	body.Write(payload)
	var out bytes.Buffer
	writeVarInt(&out, int32(body.Len()))
	out.Write(body.Bytes())
	_, err := conn.Write(out.Bytes())
	return err
}

func writeString(w *bytes.Buffer, s string) {
	writeVarInt(w, int32(len(s)))
	w.WriteString(s)
}

type handshake struct {
	protocol int32
	host     string
	port     uint16
	next     int32
	raw      []byte // the full packet bytes, replayed to the browser-side server
}

func readHandshake(conn net.Conn) (*handshake, error) {
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	br := &byteReader{r: conn}
	length, err := readVarInt(br)
	if err != nil {
		return nil, err
	}
	if length <= 0 || length > 1024 {
		return nil, fmt.Errorf("bad handshake length %d", length)
	}
	body := make([]byte, length)
	if _, err := io.ReadFull(conn, body); err != nil {
		return nil, err
	}
	raw := append(append([]byte{}, br.consumed...), body...)
	r := bytes.NewReader(body)
	id, err := readVarInt(r)
	if err != nil || id != 0 {
		return nil, fmt.Errorf("not a handshake (id %d)", id)
	}
	h := &handshake{raw: raw}
	if h.protocol, err = readVarInt(r); err != nil {
		return nil, err
	}
	hl, err := readVarInt(r)
	if err != nil || hl < 0 || hl > 255 {
		return nil, errors.New("bad host length")
	}
	hb := make([]byte, hl)
	if _, err := io.ReadFull(r, hb); err != nil {
		return nil, err
	}
	h.host = string(hb)
	var port [2]byte
	if _, err := io.ReadFull(r, port[:]); err != nil {
		return nil, err
	}
	h.port = binary.BigEndian.Uint16(port[:])
	if h.next, err = readVarInt(r); err != nil {
		return nil, err
	}
	return h, nil
}

type byteReader struct {
	r        io.Reader
	consumed []byte
}

func (b *byteReader) ReadByte() (byte, error) {
	var buf [1]byte
	if _, err := io.ReadFull(b.r, buf[:]); err != nil {
		return 0, err
	}
	b.consumed = append(b.consumed, buf[0])
	return buf[0], nil
}

// sessionNameFor maps the handshake hostname to a session name ("demo.tun.lodeway.app" -> "demo").
func sessionNameFor(host string) string {
	host = strings.ToLower(host)
	if i := strings.IndexByte(host, 0); i >= 0 { // Forge/FML markers: "host\0FML\0"
		host = host[:i]
	}
	host = strings.TrimSuffix(host, ".")
	suffix := "." + strings.ToLower(*domain)
	if !strings.HasSuffix(host, suffix) {
		return ""
	}
	return strings.TrimSuffix(host, suffix)
}

func offlineReply(conn net.Conn, h *handshake) {
	msg := "No browser-hosted server is connected for this address right now."
	switch h.next {
	case 1: // status
		status := map[string]any{
			"version":     map[string]any{"name": offlineVersion, "protocol": h.protocol},
			"players":     map[string]any{"max": 0, "online": 0},
			"description": map[string]any{"text": "§cOffline: " + msg},
		}
		js, _ := json.Marshal(status)
		var p bytes.Buffer
		writeString(&p, string(js))
		// wait for the status request packet before answering, then echo any ping
		conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		br := &byteReader{r: conn}
		if _, err := readVarInt(br); err != nil { // length
			return
		}
		if _, err := readVarInt(br); err != nil { // id (0 = request)
			return
		}
		writePacket(conn, 0, p.Bytes())
		buf := make([]byte, 64)
		n, err := conn.Read(buf)
		if err == nil && n >= 10 { // ping: len(9) id(1) + 8 bytes payload
			conn.Write(buf[:n])
		}
	case 2, 3: // login / transfer
		js, _ := json.Marshal(map[string]any{"text": msg})
		var p bytes.Buffer
		writeString(&p, string(js))
		writePacket(conn, 0, p.Bytes()) // login disconnect
	}
}

func handleClient(conn net.Conn) {
	defer conn.Close()
	h, err := readHandshake(conn)
	if err != nil {
		log.Printf("%s: handshake failed: %v", conn.RemoteAddr(), err)
		return
	}
	name := sessionNameFor(h.host)
	s := reg.get(name)
	if s == nil {
		log.Printf("%s: no session for %q (host %q, state %d)", conn.RemoteAddr(), name, h.host, h.next)
		offlineReply(conn, h)
		return
	}
	conn.SetReadDeadline(time.Time{})
	id := atomic.AddUint32(&s.nextID, 1)
	s.conns.Store(id, conn)
	defer func() {
		if _, ok := s.conns.LoadAndDelete(id); ok {
			s.send(frameClose, id, nil)
		}
	}()
	if err := s.send(frameOpen, id, []byte(conn.RemoteAddr().String())); err != nil {
		return
	}
	// Replay the raw handshake bytes as the first DATA frame so the browser-side server sees
	// exactly what the client sent (we only peeked to route).
	if err := s.send(frameData, id, h.raw); err != nil {
		return
	}
	log.Printf("%s -> session %q conn %d (state %d)", conn.RemoteAddr(), name, id, h.next)
	buf := make([]byte, 32*1024)
	for {
		n, err := conn.Read(buf)
		if n > 0 {
			if err := s.send(frameData, id, buf[:n]); err != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}
