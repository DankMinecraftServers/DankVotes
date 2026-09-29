# Contributing to DankVotes

Thanks for helping! Bug reports, features and PRs are all welcome.

## Project layout

```
core/      Platform-agnostic logic (Java 8). No dependencies. Everything interesting lives here,
           including the shared config.yml, ConfigMapper and CommandHandler.
paper/     Every Bukkit-API server: Spigot, Paper, Purpur, Folia, forks, hybrids
           (Java 8, Spigot 1.8.8 API, runs on 1.7.10+).
velocity/  Velocity proxy (Java 17).
bungee/    BungeeCord / Waterfall proxy (Java 8).
sponge/    Sponge API 8+ (Java 8, compiled against SpongeAPI 8.1.0).
           Vote forwarding (proxy -> backends) lives in core/VoteForwarder.java.
dist/      Shade-merges them all into the single distributable jar.
```

The core talks to the server only through `core/.../Platform.java`. If a feature needs
something from the server (players, permissions, scheduling), add it to `Platform` with a
sensible default, then implement it in `PaperPlatform`, `VelocityPlatform`, `BungeePlatform`
and `SpongePlatform`.

## Building

```bash
mvn -B clean package        # -> dist/target/DankVotes-<version>.jar
```

Needs JDK 17+ and Maven. The Paper half is compiled *to* Java 8 automatically.

## Rules of the road

- **Java 8 in `core/`, `paper/`, `bungee/` and `sponge/`.** No `var`, no `List.of()`, no
  `String.isBlank()`, no switch expressions, no records. The Velocity module may use Java 17.
- **Spigot 1.8.8 API only in `paper/`.** Anything newer must go through reflection with a
  fallback (see `SchedulerAdapter`, `BukkitCompat`). Never use the Adventure API in `paper/`.
- **SpongeAPI 8 only in `sponge/`,** and only calls that are unchanged in later API versions
  (Sponge breaks binary compatibility between major versions; check the newest API before
  using anything new).
- **Thread safety.** Votes arrive on network threads. Anything touching game state must go
  through `Platform.dispatchConsoleCommand` / `runSync`.
- **Config changes** go in `core/src/main/resources/com/dankmc/dankvotes/core/config.yml` (the
  one file every platform writes on first start) and in both readers: `PaperConfigLoader`
  (Bukkit) and `ConfigMapper` (everything else). Every key needs a default so older configs
  keep working.
- **No new runtime dependencies** without discussion. If unavoidable, shade + relocate it in
  `dist/pom.xml`.
- Keep player-facing text in `DankVotesConfig.Messages` so owners can translate it.

## Releasing

1. Bump the version in the root `pom.xml` and in the `<parent>` block of every module's
   `pom.xml` (core, paper, velocity, bungee, sponge, dist), and in the
   `@Plugin(version = ...)` annotation in `DankVotesVelocity.java`. CI fails if they differ.
2. Update `CHANGELOG.md`.
3. Commit, then tag and push:
   ```bash
   git tag v1.2.0
   git push && git push --tags
   ```
4. GitHub Actions builds the jar and publishes the release automatically (the workflow
   refuses to run if the tag doesn't match the pom version).
