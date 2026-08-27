# Natat Tunnel

Minimal Android-native foundation for a lightweight tunneling client.

## Config format

Configuration is stored as JSON in app-private storage. Example:

```json
{
  "version": 1,
  "name": "My server",
  "protocol": "SOCKS5",
  "host": "127.0.0.1",
  "port": 1080,
  "username": "",
  "password": "",
  "sni": "",
  "path": "/",
  "autoReconnect": true,
  "connectTimeoutMs": 10000,
  "keepAliveSeconds": 25
}
```

The service uses the embedded `hev-socks5-tunnel` native engine for the TUN-to-SOCKS5 relay. The service excludes its own package from the VPN, stops the native worker before closing the TUN descriptor, and monitors the worker for reconnects.

Implemented transports:

- SOCKS5 upstream with optional username/password.
- SSH dynamic forwarding through a bounded loopback SOCKS5 bridge, with password or private-key authentication and required SHA-256 host-key fingerprint verification.
- Optional custom HTTP payload and TLS/SNI wrapping for the SSH transport. Certificate verification remains required.

Profiles also include DNS servers, UDP preference, auto-reconnect, traffic totals, import/export JSON, and settings reserved for WebSocket. WebSocket framing is not enabled yet because it requires a complete binary stream adapter; selecting it reports that limitation instead of opening an unsafe or nonfunctional connection.

The native engine is tracked as a recursive Git submodule at `third-party/hev-socks5-tunnel`. It is built for `arm64-v8a`, `armeabi-v7a`, and `x86_64` by the GitHub Actions workflow.

## Build on GitHub Actions

This project does not require Android SDK or Gradle to be installed on the development computer. Push the project to GitHub, then run the `Android Build` workflow from the Actions tab. The generated debug APK is available under the workflow run's `natat-debug-apk` artifact.
