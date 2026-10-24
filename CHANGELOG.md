# Changelog

All notable changes to DankVotes are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.3.0] - 2026-10-23

Votes you can see: particles, sounds, fireworks and titles.

### Added
- **Vote effects**, set under `effects:` in `config.yml` and on by default:
  - every vote: sparks burst out around the player, the level-up sound and a **THANK YOU!**
    title. Votes saved for an offline player play their effect a few seconds after they join;
  - the day's first vote that keeps a streak going: a whirlwind of flames and
    **3-DAY STREAK!** *Vote again tomorrow to keep it going*;
  - milestones: a totem helix, a firework and the totem sound. Streak rewards use the streak
    effect;
  - vote parties: rainbow shockwaves and a firework for everyone online;
  - reminders: a soft chime.

  Players within 32 blocks who can see the voter see a vote's effect too, and hear it if
  they're close; a vanished player's effect is theirs alone. One effect plays per vote, the
  biggest moment winning (a milestone's own effect, then a milestone, a streak day, a plain
  vote), and votes on several sites at once don't stack them.
- For each effect: eight particle animations (`burst`, `sparkle`, `helix`, `tornado`,
  `fountain`, `ring`, `halo`, `hearts`), any particle, colours for dust and fireworks, any sound
  key with volume and pitch, a firework and its type, a title and subtitle (placeholders work),
  and whether players nearby see it.
- **Each milestone and streak reward can have its own effect** (`effect:` on the entry): the
  keys it sets replace the default effect's, and it plays even while the default is switched
  off. `effect: none` gives it none of its own.
- **Switches**, all on by default: `effects.enabled: false` (or `effects: false`) turns every
  effect off; `particles`, `sounds`, `fireworks` or `titles: false` leaves just that kind out
  everywhere, milestones' own effects included; `enabled: false` under one effect switches off
  only that one. A `config.yml` from 1.2 (no `effects:` section) gets them on, and the console
  says how to switch them off.
- `/dankvotes effect <vote|streak|milestone|party|reminder> [player] [votes|days]` previews an
  effect, also one that is switched off. A number picks a milestone or streak reward.
- `/dankvotes status` shows which effects are on and which kinds are left out. Mistakes in the
  effects section (an unknown animation or colour, a misspelt key) are explained in the console
  at startup.

### Compatibility
- Bukkit-based servers from 1.7.10 to the latest, Folia included. Particles use each version's
  own API (Spigot's particle API on 1.7 and 1.8, the Particle API from 1.9), with dust colours in
  that version's format and both the particle names from before and after 1.20.5. Sounds are
  played by key under the name each version gives them (Sound became an interface in 1.21.3).
  Titles need Spigot's title API (every 1.9+ server). A particle or sound a version doesn't
  have is swapped for a similar one, and the console says so once. On Folia every effect runs
  on the player's own region. On Paper and Folia the server sends each particle to the players
  who can see the voter, so a crowd costs little.
- Fireworks burst in front of the player, where they can see them (1.12+; older ones fly their
  short course), never hurt anyone, and go up at most once per 8×8 blocks a second, so a vote
  party in a crowded spawn stays smooth. Fireworks still in the air when DankVotes is disabled
  are removed.
- Sponge (API 8+): the same effects, with the firework drawn in particles.
- Velocity and BungeeCord have no worlds: the servers behind them play the effects, forwarded
  votes included.

### Changed
- A `config.yml` from an earlier version has no `effects:` section, so the effects are on with
  their defaults, whatever `features.vote-messages` says; the console says so at startup. Add
  `effects: false` to switch them off (DankVotes as a vote receiver next to another plugin).
- Reminders come with a soft chime (`effects.reminder`; `enabled: false` under it silences it).

## [1.2.0] - 2026-10-02

Use only the parts of DankVotes you want, next to any other vote plugin.

### Added
- **`features:` in `config.yml`** switches off vote **statistics** (totals, streaks, the
  leaderboard and their placeholders), **rewards** (reward commands, milestones, streak
  rewards and offline saving) and **vote-messages** (the broadcast, thank-you and streak
  messages). With `reminders.enabled` and `vote-party.enabled`, every part can now be off. A
  part that is off does nothing at all; votes are still received, duplicate-checked, fired as
  `DankVoteEvent` (Bukkit) and forwarded, so DankVotes can also just receive votes for another
  plugin.
- **`commands:` in `config.yml`** switches off `/vote`, `/votes`, `/votetop`, `/voteparty` and
  the `/dankvotes setvotes|addvotes`, `reset` and `party` subcommands.
- On Bukkit servers, a startup notice when another plugin also has one of these command names,
  naming the plugin and the setting to change, and when one of their aliases (such as `/vp`)
  belongs to another plugin. Velocity and BungeeCord say when DankVotes takes a name over from
  another proxy plugin.
- **`behaviour.offline-votes: false`** ignores votes from players who aren't online when the
  vote arrives (on a proxy: anywhere on the network). They aren't counted, saved for the next
  join, rewarded, announced, passed to other plugins or forwarded, and don't add to the vote
  party. Votes a DankVotes proxy forwards count as the proxy decided.
- `/dankvotes test` says when the test vote wasn't counted (`messages.test-vote-refused`).
- `/dankvotes status` shows which parts and commands are off and how offline votes are handled;
  `/dankvotes reload` says when a command change needs a restart.
- `messages.command-disabled`, and any vote, vote-party or reminder message set to `""` is no
  longer sent.

### Changed
- **Commands that are off are not registered at all**, so another plugin can have the name.
  That includes `/voteparty` (and `/vp`) while the vote party is off, which is the default,
  and `/votes` and `/votetop` while statistics are off. On Bukkit servers the player commands
  are now registered when DankVotes starts instead of being listed in `plugin.yml`; `/dankvotes`
  is unchanged.
- A PlaceholderAPI placeholder of a part that is off stays unparsed (`%dankvotes_party_*%` need
  the vote party, the rest need statistics).
- With statistics off, the bundled thank-you message and `/vote` footer leave out vote totals,
  `%votes%`-style placeholders show 0, and DankVotes logs any message or reward that still uses
  them. The same goes for `%party_*%` placeholders while the vote party is off.
- `/dankvotes help`, tab completion and the `/dankvotes` usage list only subcommands that are on.
- Paper and Velocity commands now run through the same shared command handler as BungeeCord
  and Sponge.

### Fixed
- **Streak rewards ran once for every vote** on the day a streak reached its number, so a player
  who voted on three sites got a 7-day streak reward three times (also when the votes were saved
  for an offline player and replayed on join). They now run once a day, with the player's first
  vote that day; the streak broadcast too. Saved offline votes now remember which streak day
  they reached.
- A vote that arrives late from an earlier day (a delayed delivery) no longer resets the
  player's streak to 1.
- With `streaks.enabled: false`, reminders kept reminding players who had already voted that day.
- A vote dated in the future (a site clock running ahead, or a timestamp in the wrong unit) no
  longer resets the player's streak or their "voted today" for reminders.
- After a `/dankvotes reload` that fails, an admin can fix `config.yml` and run
  `/dankvotes reload` again (BungeeCord and Sponge needed a restart).

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
