# Contributing to DankVotes

Thanks for helping! Bug reports, features and PRs are all welcome.

## Project layout

```
core/      Platform-agnostic logic (Java 8). No dependencies. Everything interesting lives here.
paper/     Spigot/Paper/Purpur/Folia half (Java 8, Spigot 1.8.8 API for max compatibility).
velocity/  Velocity proxy half (Java 17).
dist/      Shade-merges the three into the single distributable jar.
```

The core talks to the server only through `core/.../Platform.java`. If a feature needs
something from the server (players, permissions, scheduling), add it to `Platform` with a
sensible default, then implement it in `PaperPlatform` and `VelocityPlatform`.

## Building

```bash
mvn -B clean package        # -> dist/target/DankVotes-<version>.jar
```

Needs JDK 17+ and Maven. The Paper half is compiled *to* Java 8 automatically.

## Rules of the road

- **Java 8 in `core/` and `paper/`.** No `var`, no `List.of()`, no `String.isBlank()`,
  no switch expressions, no records. The Velocity module may use Java 17.
- **Spigot 1.8.8 API only in `paper/`.** Anything newer must go through reflection with a
  fallback (see `FoliaScheduler`). Never use the Adventure API in `paper/`.
- **Thread safety.** Votes arrive on network threads. Anything touching game state must go
  through `Platform.dispatchConsoleCommand` / `runSync`.
- **Config changes** must be made in both `paper/src/main/resources/config.yml` and
  `velocity/src/main/resources/config.yml`, and in both `PaperConfigLoader` and
  `VelocityConfigLoader`. Every key needs a default so older configs keep working.
- **No new runtime dependencies** without discussion. If unavoidable, shade + relocate it in
  `dist/pom.xml`.
- Keep player-facing text in `DankVotesConfig.Messages` so owners can translate it.

## Releasing

1. Bump the version in **all** `pom.xml` files (root, core, paper, velocity, dist — they
   reference each other by version) and in the `@Plugin(version = ...)` annotation in
   `DankVotesVelocity.java`.
2. Update `CHANGELOG.md`.
3. Commit, then tag and push:
   ```bash
   git tag v1.1.0
   git push && git push --tags
   ```
4. GitHub Actions builds the jar and publishes the release automatically (the workflow
   refuses to run if the tag doesn't match the pom version).
