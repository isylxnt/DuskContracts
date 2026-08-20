# DuskContracts 1.0

DuskContracts is a Paper/Folia marketplace for escrow-backed item-delivery and player-assassination contracts. Creators fund money or item rewards up front; delivery contracts use a protected deposit GUI and bounties require players to join before a qualifying kill. Committed rewards and returns are kept in an idempotent claims tray.

## Install in five steps

1. Use a supported Paper or Folia server and its required Java runtime.
2. Put `build/libs/DuskContracts-1.0.jar` in `plugins/`.
3. Optionally install Vault plus an economy provider for money rewards; PlaceholderAPI is also optional.
4. Start once with internet access. Paper downloads and caches the database libraries; SQLite is then ready without configuration.
5. Review `plugins/DuskContracts/config.yml`, then run `/contracts admin doctor`.

Run `/contract` for the graphical hub. Creation, contract type, requested material and amount, matching, duration, visibility, bounty target, reward deposit and confirmation are menu-driven; chat is used only for typed values. Browse public listings, join bounties or deposit requested items, then collect rewards with `/contracts claim`. Creators can cancel an open listing from its detail screen with a two-click confirmation.

The JAR uses Java 17 bytecode and is compiled against the 1.20.1 API. Compatibility compilation covers Paper 1.20.6, 1.21.11 and 26.2, and Folia 1.20.1, 1.20.6, 1.21.11 and 26.2. A clean Paper 26.2 build 112 / Java 26 process has runtime-verified library resolution, plugin discovery, SQLite schema 3 startup, `admin doctor`, and graceful shutdown. The remaining Paper/Folia matrix is documented in [Compatibility](docs/COMPATIBILITY.md).

Documentation: [installation](docs/INSTALLATION.md), [user guide](docs/USER_GUIDE.md), [administrator guide](docs/ADMIN_GUIDE.md), [configuration](docs/CONFIGURATION.md), [commands](docs/COMMANDS.md), [permissions](docs/PERMISSIONS.md), [storage](docs/STORAGE.md), [recovery](docs/RECOVERY.md), [security](docs/SECURITY.md), [Folia](docs/FOLIA.md), [compatibility](docs/COMPATIBILITY.md), [architecture](docs/ARCHITECTURE.md), [API](docs/API.md), and [testing](docs/TESTING.md).

The distribution is a thin JAR: Paper resolves HikariCP and the SQLite/MySQL/MariaDB JDBC drivers from Maven Central while loading the plugin. The server therefore needs outbound HTTPS access on the first load (or a pre-populated Paper library cache).

Build with `./gradlew clean test compatibilityCompile jar verifyJar`. The installable artifact is `build/libs/DuskContracts-1.0.jar`.

## License

DuskContracts is available under the [MIT License](LICENSE).
