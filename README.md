<div align="center">

# DankVotes

**Vote rewards done right.** One jar for Spigot, Paper, Purpur, Folia **and** Velocity.

[![Build](https://github.com/DankMinecraftServers/DankVotes/actions/workflows/build.yml/badge.svg)](https://github.com/DankMinecraftServers/DankVotes/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/DankMinecraftServers/DankVotes?label=download)](https://github.com/DankMinecraftServers/DankVotes/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.8%20→%20latest-brightgreen)](#compatibility)

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
| **Three ways to receive votes** | HTTPS polling (no port forwarding), a built-in Votifier v1 + v2 listener, or a hook into an existing NuVotifier install — use any combination |
| **Rewards** | Console commands per vote, with per-command **chance** and **permission** gates |
| **Milestones** | Rewards every *N* votes or at exactly *N* (`EVERY` / `AT`) |
| **Streaks** | Consecutive-day streak tracking with streak rewards and best-streak records |
| **Vote parties** | Server-wide goals with progress announcements; per-player and global rewards; progress survives restarts |
| **Offline votes** | Queued and delivered on next join — milestones still trigger correctly |
| **Reminders** | Nudge players who haven't voted today (interval + on join, permission bypass) |
| **Player commands** | `/vote`, `/votes`, `/votetop`, `/voteparty` |
| **Integrations** | PlaceholderAPI expansion, `DankVoteEvent` for other plugins, NuVotifier bridge, bStats |
| **Safety** | Duplicate-vote protection across channels, verified-IP filtering, atomic data saves, update notifications |
| **Folia-native** | Uses the region/async schedulers correctly — no "wrong thread" errors |
| **Zero dependencies** | Nothing to install alongside it; no library conflicts |

## Compatibility

**One jar** — `DankVotes-x.y.z.jar` — runs everywhere:

| Software | Versions | How it loads |
|---|---|---|
| Spigot · Paper · Purpur · Pufferfish | **1.8 → latest** | `plugin.yml` (Java 8 bytecode) |
| Folia | 1.20+ | auto-detected at runtime |
| Velocity | 3.x | `velocity-plugin.json` (Java 17) |

The server loads only the half it understands; the other half is never touched.

> **Networks:** put the jar on the Velocity proxy *and* on each backend Paper server. In-world
> rewards (items, crates) must run where the player is — on the backend. The proxy half is
> for network-wide announcements, streaks, `/vote` and `/votetop`.

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

On Paper/Spigot, DankVotes fires a cancellable Bukkit event for every vote **before** it is
counted or rewarded. Add DankVotes as a `softdepend` and listen:

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

The Spigot half is compiled to Java 8 bytecode and the Velocity half to Java 17; both live
in the one jar. Every push is also built by [GitHub Actions](.github/workflows/build.yml),
and tagging `vX.Y.Z` publishes a release with the jar attached.

## Support

- Questions & help: [Discord](https://discord.gg/ky3tyJJaJA)
- Bugs & feature requests: [GitHub Issues](https://github.com/DankMinecraftServers/DankVotes/issues)
- Server listing & vote traffic: [DankMinecraftServers.com](https://dankminecraftservers.com)

## License

[MIT](LICENSE) — use it on any server, fork it, bundle it. Attribution appreciated.
