# Security

If you find a vulnerability (e.g. a way to forge votes or crash the Votifier listener),
please **do not** open a public issue. Email dankminecraftservers@gmail.com or use
GitHub's private vulnerability reporting on this repository. We aim to respond within a
week.

Hardening notes for server owners:

- The Votifier listener validates every v2 vote with HMAC-SHA256 + a per-connection
  challenge, and every v1 vote with your private RSA key. Keep `rsa/private.key` and
  `votifier.token` secret.
- Your polling `api-token` grants access to your server's vote queue only. Rotate it from
  the DankMinecraftServers dashboard if it leaks.
- `duplicate-window-seconds` and `require-verified` limit abuse from repeated or VPN votes.
