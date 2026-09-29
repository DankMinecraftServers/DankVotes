# Changelog

All notable changes to DankVotes are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.0] - 2026-09-29

First public release.

### Added
- Bedrock players who join through Geyser/Floodgate: vote names with a prefix
  (`.Steve`, `*Steve`, `+`, `-`, `~`, `!`) are accepted, matching DankMinecraftServers' Bedrock vote option.
- Three vote sources: HTTPS polling of DankMinecraftServers (no port forwarding), a built-in
  Votifier **v1 (RSA) + v2 (token)** listener on one port, and a NuVotifier bridge.
- Rewards with per-command chance and permission gates; `EVERY`/`AT` milestones.
- Day-streak tracking with streak rewards and best-streak records.
- Vote parties with persistent progress, progress announcements, per-player and global rewards.
- Offline vote queueing with correct milestone evaluation on replay.
- Vote reminders (interval + on-join) with a bypass permission.
- Player commands: `/vote`, `/votes`, `/votetop`, `/voteparty`.
- Admin commands: `reload`, `status`, `test`, `setvotes`, `addvotes`, `reset`, `party`, `key`, `version`, with tab completion.
- PlaceholderAPI expansion (`%dankvotes_*%`).
- `DankVoteEvent` (cancellable Bukkit event) for other plugins.
- Duplicate-vote protection across delivery channels.
- Atomic, debounced data saves; corrupt-file recovery with backup.
- Update checker (GitHub Releases) and bStats metrics (opt-out).
- Folia support via runtime scheduler detection; Velocity support in the same jar.
