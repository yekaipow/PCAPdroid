# MCP Server

PCAPdroid can expose its capture engine to AI tools and agents through the
[Model Context Protocol](https://modelcontextprotocol.io/) (MCP), using the
Streamable HTTP transport with JSON-RPC 2.0.

> This is an opt-in feature. It is disabled by default.

## Enabling the server

Open **Settings → MCP Server** and turn on **Enable MCP server**. The following
options are available:

| Option | Default | Description |
|--------|---------|-------------|
| Server port | `8081` | TCP port the HTTP server listens on |
| Listen on all interfaces | off | When off, the server binds to `127.0.0.1` only. Enable it to accept connections from the local network |
| Bearer token | empty | When set, clients must send `Authorization: Bearer <token>` on every request |

The server runs in a foreground service and starts automatically on app launch
and on device boot while it is enabled.

### Connecting from another device

By default the server only listens on loopback. To reach it from a host machine,
use `adb forward`:

```bash
adb forward tcp:8081 tcp:8081
```

The MCP endpoint is then available at `http://127.0.0.1:8081/mcp`.

Alternatively, enable **Listen on all interfaces** and set a Bearer token, then
connect to `http://<device-ip>:8081/mcp` (set a token whenever listening on all
interfaces, since capture control is sensitive).

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Liveness probe, returns server name and version |
| `POST` | `/mcp` | JSON-RPC 2.0 endpoint |

## Tools

| Tool | Description |
|------|-------------|
| `get_capture_status` | Capture running state, version and traffic statistics |
| `start_capture` | Start a capture with the saved settings (optional `app_filter`, `root_capture`) |
| `stop_capture` | Stop the capture and return final statistics |
| `list_connections` | List captured connections, newest first (`limit`, `offset`) |
| `get_connection_details` | Full details of a connection (`id`, optional `include_payload`) |

`start_capture` uses the settings saved in PCAPdroid. The VPN permission must have
been granted beforehand by starting a capture once from the app UI.

### Example client configuration

```json
{
  "mcpServers": {
    "pcapdroid": {
      "url": "http://127.0.0.1:8081/mcp",
      "headers": { "Authorization": "Bearer your-token" }
    }
  }
}
```

## Security notes

- The server exposes capture start/stop and full traffic data. Only enable it on
  trusted networks, keep the loopback-only default whenever possible, and always
  set a Bearer token when binding to all interfaces.
- Tool results are JSON strings returned as MCP `text` content blocks.

## Disabling

Turn off **Enable MCP server** in the settings. This stops the foreground service
and closes the listening socket.
