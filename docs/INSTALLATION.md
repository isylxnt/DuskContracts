# Installation

## Requirements

- Paper or Folia in the matrix documented in [COMPATIBILITY.md](COMPATIBILITY.md).
- The Java runtime required by that server: historic 1.20.x builds commonly use Java 17/21; current Paper documentation specifies Java 21 for 1.20–1.21.11 and Java 25 for 26.1+.
- Optional: Vault and any registered economy provider. Without both, money contracts are disabled and item rewards continue working.
- Optional: PlaceholderAPI 2.11.6-compatible runtime.
- Outbound HTTPS access to Maven Central on the first plugin load, unless Paper's library cache is already populated.

Copy `DuskContracts-1.0.jar` to `plugins/` and start the server. Before enabling DuskContracts, Paper reads the `libraries` section of `plugin.yml`, downloads HikariCP and the SQLite/MySQL/MariaDB JDBC drivers from Maven Central, and caches them for later starts. If dependency resolution fails, Paper discards the plugin for that startup; allow access to Maven Central or populate the same Paper library cache before starting an offline server.

After the libraries are available, the first start creates `config.yml`, `storage.yml`, `menus.yml`, `item-rules.yml`, both language files, a SQLite database, and `recovery/`.

Run `/contracts admin doctor`. A healthy report names the scheduler mode, storage type, schema 3, database latency, and economy provider. To use MySQL or MariaDB, stop the server, edit `storage.yml`, choose an appropriate `tls-mode`, migrate existing data deliberately if necessary, and restart. Storage switches are not live reloads.

Do not use Bukkit `/reload`; restart the server when replacing the JAR or changing storage. Normal text/menu settings can be refreshed with `/contracts admin reload`. PlugManX unload/reload is guarded by explicit menu cleanup, item return, PlaceholderAPI deregistration and bounded database shutdown, but a normal restart remains the preferred production deployment procedure.
