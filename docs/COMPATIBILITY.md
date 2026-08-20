# Compatibility evidence

## Official findings (checked 2026-08-12)

Paper’s [project setup documentation](https://docs.papermc.io/paper/dev/project-setup/) documents the pre-26.1 `{VERSION}-R0.1-SNAPSHOT` coordinate format and the new `26.2.build.N-channel` format. Its example identifies `26.2.build.84-stable`; official Maven metadata queried during this build reported Paper `26.2.build.112-stable` and Folia `26.2.build.4-beta` as current releases.

Paper’s [runtime requirements](https://docs.papermc.io/paper/getting-started/) specify Java 21 for 1.20 through 1.21.11 and Java 25 for 26.1+. The plugin itself is emitted as Java 17 bytecode so newer runtimes can load the same artifact. Server runtime requirements still take precedence.

Folia’s [official README](https://github.com/PaperMC/Folia/blob/ver/26.2.x/README.md) requires `folia-supported: true` and region/entity schedulers. The plugin declares that field in `plugin.yml` and uses capability-based scheduler dispatch.

## Verification matrix

| Target | Evidence in this checkout |
|---|---|
| Paper 1.20.1 | Main source and tests compile against official API |
| Paper 1.20.6 | `compileAgainstPaper1206` |
| Paper 1.21.11 | `compileAgainstPaper12111` |
| Paper 26.2 build 112 stable | `compileAgainstPaper262`; real Java 26 startup, Paper library resolution, SQLite schema 3, `admin doctor`, and graceful disable passed on 2026-08-14 |
| Folia 1.20.1 | `compileAgainstFolia1201` |
| Folia 1.20.6 | `compileAgainstFolia1206` |
| Folia 1.21.11 | `compileAgainstFolia12111` |
| Folia 26.2 build 4 beta | `compileAgainstFolia262` |

The single JAR is the chosen design because it links only to 1.20.1-era Bukkit/Paper methods in common code and discovers Folia schedulers reflectively. No NMS or CraftBukkit classes are used.

Important limitation: only the Paper 26.2 row has been runtime-verified in this environment. Paper 1.20.6, representative 1.21.x, and multi-region Folia scenarios remain release-gate tests. Compilation is evidence of API compatibility, not proof of third-party Vault provider behavior or region ownership under live load.
