# Changelog

All notable changes to DankVotes are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.1.1] - 2026-10-02

### Added
- **bStats metrics are switched on** (plugin id 34452): anonymous counts of servers and
  players, Minecraft, Java and server-software versions and country, plus which vote sources
  are in use - nothing about individual players, and no vote data. Public stats:
  https://bstats.org/plugin/bukkit/DankVotes/34452. Turn them off with `metrics: false` in
  `config.yml`, or for every plugin in `plugins/bStats/config.yml`. (1.0.0 and 1.1.0 shipped
  the bStats code with no plugin id, so they never sent anything.)

### Changed
- bStats 3.1.0 -> 3.2.1 (still Java 8, still Folia-aware).
- Build and CI updates: maven-compiler-plugin 3.16.0, maven-shade-plugin 3.6.2, SnakeYAML 2.7
  and PlaceholderAPI 2.12.3 (both compile-only; servers use their own copies),
  actions/checkout v7, actions/setup-java v6, actions/upload-artifact v7.

## [1.1.0] - 2026-09-29

Runs on every kind of Minecraft server, from one jar.

### Added
- **Vote forwarding for networks**: a Velocity or BungeeCord proxy passes every vote it receives
  on to the servers behind it (Paper, Folia, Purpur, Sponge... running DankVotes or NuVotifier)
  over the Votifier v2 protocol, so they give the in-world rewards. `mode: all` sends to every
  listed server, `mode: current` only to the server the player is on (held until they join one).
  Undeliverable votes wait in a retry queue that survives restarts (7 days), the site's VPN
  verdict travels with the vote, forwarded votes are never forwarded again, and
  `/dankvotes status` shows each server.
- **Sponge** support: SpongeVanilla, SpongeForge and SpongeNeo on SpongeAPI 8 and newer
  (Minecraft 1.16.5 to the latest release). Same config, commands and permissions; the config
  lives in `config/dankvotes/`.
- **BungeeCord** support, including Waterfall, FlameCord and other forks, alongside Velocity.
- Minecraft **1.7.10** support on Bukkit-based servers (the old `getOnlinePlayers()` array API is
  bridged at runtime).

### Fixed
- The built-in Votifier listener now reads Votifier v2 votes in the standard NuVotifier framing
  (0x733A header + length). 1.0.0 expected bare JSON, so v2 votes from vote sites, including
  DankMinecraftServers.com's own Votifier sender, got no answer and were lost. Replies now end
  in CRLF and errors carry NuVotifier's `cause`/`error` fields. Polling and v1 (RSA) votes were
  not affected.
- v1 (RSA) votes whose encrypted block happened to start with `{` (about 1 in 200) were taken for
  JSON and lost; they are now recognised.
- A vote arriving while DankVotes reloads or shuts down is no longer lost: the Votifier listener
  asks the sender to retry, and the poller leaves it for the next poll.
- The Votifier listener bounds slow or stalled connections (a 10-second deadline, at most 32 at
  once), so a misbehaving client can't tie up threads.

### Changed
- `plugin.yml` declares `api-version: 1.13`, so 1.13+ servers stop treating DankVotes as a legacy
  plugin (no "Legacy Material Support" start-up and no legacy warning). Older servers ignore it.
- Folia: the scheduler now calls Folia's scheduler interfaces directly, also recognises regionised
  forks that lack Folia's marker class, doesn't schedule while the plugin is disabling, and
  cancels its tasks on disable.
- bStats 3.1.0 (stays off the Bukkit scheduler on Folia); metrics now shut down on disable.
- One shared `config.yml` and one shared config reader for every platform. The bundled default
  is stored under a DankVotes-only path in the jar, so another mod's `config.yml` can never be
  copied by mistake (Sponge).
- `/dankvotes reload` can't run twice at once (Folia runs commands on several threads).
- Velocity: added the `/dankvote` alias; commands also register on Velocity 3.0.

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
