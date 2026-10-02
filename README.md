<div align="center">

# DankVotes

**Vote rewards done right.** One jar for every Minecraft server: Spigot, Paper, Purpur, Folia
and other forks, Sponge, Velocity **and** BungeeCord.

[![Build](https://github.com/DankMinecraftServers/DankVotes/actions/workflows/build.yml/badge.svg)](https://github.com/DankMinecraftServers/DankVotes/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/DankMinecraftServers/DankVotes?label=download)](https://github.com/DankMinecraftServers/DankVotes/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.7.10%20→%20latest-brightgreen)](#compatibility)
[![bStats Servers](https://img.shields.io/bstats/servers/34452?label=servers)](https://bstats.org/plugin/bukkit/DankVotes/34452)

[Download](https://github.com/DankMinecraftServers/DankVotes/releases/latest) · [Quick start](#quick-start) · [Configuration](#configuration) · [Commands](#commands--permissions) · [Placeholders](#placeholderapi) · [Developer API](#developer-api)

</div>

---

DankVotes receives votes from vote sites and turns them into rewards — with everything a
server owner expects out of the box: streaks, milestones, vote parties, offline queueing,
reminders, a leaderboard, and a `/vote` command. It's a **drop-in replacement for
NuVotifier** (it speaks Votifier v1 and v2) *and* it can work **without opening any
ports** by polling [DankMinecraftServers.com](https://dankminecraftservers.com) over HTTPS.

## Features

| | |
|---|---|
| **Three ways to receive votes** | HTTPS polling (no port forwarding), a built-in Votifier v1 + v2 listener (NuVotifier-compatible), or a hook into an existing NuVotifier install — use any combination |
| **Network forwarding** | A Velocity or BungeeCord proxy passes every vote on to the servers behind it (Paper, Folia, Purpur, Sponge...), with a retry queue that survives restarts |
| **Rewards** | Console commands per vote, with per-command **chance** and **permission** gates |
| **Milestones** | Rewards every *N* votes or at exactly *N* (`EVERY` / `AT`) |
| **Streaks** | Consecutive-day streak tracking with streak rewards and best-streak records |
| **Vote parties** | Server-wide goals with progress announcements; per-player and global rewards; progress survives restarts |
| **Offline votes** | Queued and delivered on next join — milestones still trigger correctly |
| **Reminders** | Nudge players who haven't voted today (interval + on join, permission bypass) |
| **Player commands** | `/vote`, `/votes`, `/votetop`, `/voteparty` |
| **Integrations** | PlaceholderAPI expansion, `DankVoteEvent` for other plugins, NuVotifier bridge, bStats |
| **Safety** | Duplicate-vote protection across channels, verified-IP filtering, atomic data saves, update notifications |
| **Runs everywhere** | Bukkit forks from 1.7.10 to the latest release, Folia (region schedulers, no "wrong thread" errors), Sponge, Velocity and BungeeCord — one jar, one config |
| **Zero dependencies** | Nothing to install alongside it; no library conflicts |

## Compatibility

**One jar** — `DankVotes-x.y.z.jar` — runs everywhere:

| Software | Versions | How it loads |
|---|---|---|
| CraftBukkit · Spigot · Paper · Purpur · Pufferfish · Leaf and other Bukkit forks | **1.7.10 → latest** | `plugin.yml` (Java 8 bytecode) |
| Folia and its forks | every Folia build | `plugin.yml` + Folia's region schedulers, detected at runtime |
| Hybrids: Mohist · Arclight · Magma · Ketting · Youer | any with the Bukkit API | `plugin.yml` |
| Sponge: SpongeVanilla · SpongeForge · SpongeNeo | **API 8 → latest** (Minecraft 1.16.5+) | `META-INF/sponge_plugins.json` (Java 8) |
| Velocity | 3.x (Java 17+) | `velocity-plugin.json` (Java 17) |
| BungeeCord · Waterfall · FlameCord | current and older builds | `bungee.yml` (Java 8) |

The server loads only the part it understands; the rest is never touched. Java 8 or newer is
enough everywhere except Velocity. Plain Fabric, Forge or NeoForge servers can't load plugins
on their own; add SpongeForge/SpongeNeo or use a Bukkit hybrid (Mohist, Arclight, Youer...).

Where the config lives: `plugins/DankVotes/config.yml` on Bukkit-based servers and BungeeCord,
`plugins/dankvotes/config.yml` on Velocity, `config/dankvotes/config.yml` on Sponge. It's the
same file format on all of them.

> **Networks** (Velocity or BungeeCord in front of your servers): see
> [Networks: forwarding votes to your servers](#networks-forwarding-votes-to-your-servers).

## Networks: forwarding votes to your servers

Running Velocity or BungeeCord in front of Paper, Folia, Purpur or Sponge servers? Put the jar
on the proxy **and** on every backend server. The proxy receives the votes (polling needs no
open ports) and forwards each one to the backends over the Votifier v2 protocol; the backends
give the in-world rewards — also to players who aren't online on that server yet (they get them
when they join it).

**Proxy** (`plugins/dankvotes/config.yml` on Velocity, `plugins/DankVotes/config.yml` on BungeeCord):

```yaml
polling:
  enabled: true
  api-token: "your DankMinecraftServers token"
forwarding:
  enabled: true
  mode: all              # or "current": only the server the player is on (held until they join one)
  servers:
    - { name: "survival", host: "127.0.0.1", port: 8193, token: "survival-secret" }
    - { name: "skyblock", host: "10.0.0.12", port: 8193, token: "skyblock-secret" }
rewards: []              # in-world rewards live on the backends
```

**Each backend**, e.g. a Folia survival server:

```yaml
polling:
  enabled: false         # the proxy fetches the votes
votifier:
  enabled: true
  host: "127.0.0.1"      # or its private network address - keep this port off the internet
  port: 8193
  token: "survival-secret"
rewards:
  - "give %player% diamond 3"
```

- Votes a backend can't take right now (restarting, down) wait in `forwarding-queue.json` on
  the proxy and are retried for up to 7 days, across proxy restarts. `/dankvotes status` on the
  proxy shows every server's state.
- With `mode: current`, the `name` must match the server's name in `velocity.toml` or
  BungeeCord's `config.yml`.
- Backends may also run NuVotifier, and a NuVotifier proxy using its `proxy` forwarding method
  can deliver to DankVotes backends: both speak the standard Votifier v2 protocol.
- Broadcasts and vote parties: keep them on one side (for example on the proxy, off on the
  backends) so players don't see them twice.
- Test the whole chain with `/dankvotes test <player>` on the proxy.

## Quick start

1. Download the latest jar from [Releases](https://github.com/DankMinecraftServers/DankVotes/releases/latest)
   and drop it in `plugins/`.
2. Start the server once to generate `plugins/DankVotes/config.yml`.
3. Pick how votes reach you (you can use more than one):

   **Listed on DankMinecraftServers?** (recommended, no port forwarding)
   ```yaml
   polling:
     enabled: true
     api-token: "paste the token from Dashboard → your server → Vote Rewards"
   ```

   **Listed on other vote sites?** Enable the built-in Votifier listener and point the sites at it:
   ```yaml
   votifier:
     enabled: true
     port: 8192          # forward this port
     token: "a-long-random-string"   # v2 sites; v1 sites use the key from rsa/public.key
   ```
   Or, if you already run **NuVotifier**, just leave `nuvotifier-hook: true` — DankVotes
   rewards every vote NuVotifier receives and nothing changes on your vote sites.

4. Add your links under `vote-links:` and your rewards under `rewards:`.
5. `/dankvotes reload`, then `/dankvotes test <player>` to fire a simulated vote through
   the whole pipeline. `/dankvotes status` shows what's connected.

### Migrating from NuVotifier

Stop NuVotifier, then either keep it installed with `nuvotifier-hook: true` (simplest), or
replace it: copy `plugins/Votifier/rsa/` to `plugins/DankVotes/rsa/` so your existing public
key keeps working, set `votifier.enabled: true` and your v2 `token`, and remove NuVotifier.

## Configuration

The generated `config.yml` is fully commented. Highlights:

```yaml
rewards:
  - "give %player% diamond 3"
  - { command: "crate key %player% vote 1", chance: 100 }
  - { command: "give %player% diamond_block 1", chance: 25 }       # 25% bonus
  - { command: "eco give %player% 250", permission: "group.vip" }   # VIPs only

milestones:
  - { type: EVERY, votes: 5,   commands: ["crate key %player% vote 3"] }
  - { type: AT,    votes: 100, commands: ["lp user %player% parent add vip"] }

streaks:
  enabled: true
  rewards:
    - { type: EVERY, days: 7,  commands: ["crate key %player% vote 2"] }
    - { type: AT,    days: 30, commands: ["give %player% netherite_ingot 1"] }

vote-party:
  enabled: true
  goal: 50
  rewards:
    - "crate key %player% party 1"     # runs for every online player
    - "broadcast &dParty time!"        # runs once

reminders:
  enabled: true
  interval-minutes: 30
```

**Placeholders** usable in every message and reward command:
`%player%` `%service%` `%votes%` `%streak%` `%best_streak%` `%total_votes%`
`%party_progress%` `%party_goal%` `%party_remaining%`

All player-facing text lives under `messages:` and supports `&` colour codes.

## Commands & permissions

| Command | Description | Permission | Default |
|---|---|---|---|
| `/vote` | Show vote links | `dankvotes.vote` | everyone |
| `/votes [player]` | Vote count & streak | `dankvotes.votes` (`.others` for other players) | everyone / op |
| `/votetop [page]` | Top voters | `dankvotes.votetop` | everyone |
| `/voteparty` | Progress to the next party | `dankvotes.voteparty` | everyone |
| `/dankvotes reload` | Reload config | `dankvotes.admin` | op |
| `/dankvotes status` | Connection & stats overview | `dankvotes.admin` | op |
| `/dankvotes test <player>` | Simulate a vote | `dankvotes.admin` | op |
| `/dankvotes setvotes\|addvotes <player> <n>` | Edit a vote count | `dankvotes.admin` | op |
| `/dankvotes reset <player>` | Wipe a player's data | `dankvotes.admin` | op |
| `/dankvotes party` | Force a vote party | `dankvotes.admin` | op |
| `/dankvotes key` | Show the Votifier v1 public key | `dankvotes.admin` | op |

`dankvotes.reminder.bypass` — never receive reminders. `dankvotes.*` — everything.

## PlaceholderAPI

Installed automatically when PlaceholderAPI is present:

`%dankvotes_votes%` `%dankvotes_streak%` `%dankvotes_best_streak%` `%dankvotes_rank%`
`%dankvotes_voted_today%` `%dankvotes_total_votes%` `%dankvotes_party_progress%`
`%dankvotes_party_goal%` `%dankvotes_party_remaining%` `%dankvotes_top_name_<n>%`
`%dankvotes_top_votes_<n>%`

## Developer API

On Bukkit-based servers (Spigot, Paper, Folia...), DankVotes fires a cancellable Bukkit event
for every vote **before** it is counted or rewarded. Add DankVotes as a `softdepend` and listen:

```java
@EventHandler
public void onVote(com.dankmc.dankvotes.paper.DankVoteEvent e) {
    String player = e.getUsername();
    String site   = e.getServiceName();
    boolean real  = e.isVerified();     // non-VPN IP (polling API only)
    // e.setCancelled(true) to suppress the reward
}
```

The event may be asynchronous — check `e.isAsynchronous()` before touching the world.

## Building from source

Requires **JDK 17+** and **Maven**:

```bash
mvn -B clean package
# → dist/target/DankVotes-<version>.jar
```

The Bukkit, BungeeCord and Sponge parts are compiled to Java 8 bytecode and the Velocity part
to Java 17; all of them live in the one jar. Every push is also built by
[GitHub Actions](.github/workflows/build.yml), and tagging `vX.Y.Z` publishes a release with
the jar attached.

## Support

- Questions & help: [Discord](https://discord.gg/ky3tyJJaJA)
- Bugs & feature requests: [GitHub Issues](https://github.com/DankMinecraftServers/DankVotes/issues)
- Server listing & vote traffic: [DankMinecraftServers.com](https://dankminecraftservers.com)

## Metrics

On Bukkit-based servers DankVotes reports anonymous usage statistics to
[bStats](https://bstats.org/plugin/bukkit/DankVotes/34452): server and player counts, Minecraft,
Java and server-software versions, country, and which vote sources are enabled. Nothing about
individual players (no names or IPs) and no vote data. Turn it off with `metrics: false` in
`config.yml`, or for all plugins in `plugins/bStats/config.yml`.

## License

[MIT](LICENSE) — use it on any server, fork it, bundle it. Attribution appreciated.
