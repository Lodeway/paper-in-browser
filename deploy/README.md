# Deploying paper.labs.lodeway.app

Two roles, one binary. They can share a process or run on separate machines with the same `-secret`.

- **Site**: serves `web/dist` (with the jars), the Mojang proxies, and `/api/identity`.
- **Tunnel**: `/tunnel` WebSocket, the Minecraft TCP listener, and `/ask` for Caddy's on-demand TLS.

## DNS

- `paper.labs.lodeway.app` → the site host.
- `*.tun.lodeway.app` → the tunnel host's dedicated IP, DNS-only (grey cloud): Cloudflare's proxy
  does not carry port 25565.
- No SRV record. Minecraft clients put the SRV target into the handshake, which breaks the
  hostname-based routing: joins fail while pings keep working.

## Caddy

```caddy
paper.labs.lodeway.app {
    reverse_proxy localhost:8090
}

*.tun.lodeway.app, tun.lodeway.app {
    tls {
        on_demand
    }
    reverse_proxy localhost:8090
}
```

Gate on-demand issuance in the global options so certificates are only minted for names the server
recognizes:

```caddy
{
    on_demand_tls {
        ask http://localhost:8090/ask
    }
}
```

## systemd

```ini
[Unit]
Description=paper.labs.lodeway.app
After=network.target

[Service]
ExecStart=/opt/paper-labs/paper-labs-server -http 127.0.0.1:8090 -tcp :25566 -domain tun.lodeway.app -static /opt/paper-labs/dist -secret-file /opt/paper-labs/secret
Restart=always
DynamicUser=yes
ProtectSystem=strict
NoNewPrivileges=yes
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

If port 25565 on the tunnel IP belongs to something else, redirect it the way the previous relay
did: an iptables PREROUTING rule from `<tunnel-ip>:25565` to `:25566`.

Build for the server with `GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build` in `server/`.
