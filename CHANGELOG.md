# Changelog

## 1.0 production hardening — 2026-08-14

- Added explicit PlaceholderAPI teardown, bounded/deduplicated placeholder caching, Folia-aware per-player menu shutdown and safe PlugManX unload cleanup.
- Added schema 3 with persistent assassination antifarming, bounded audit maintenance and supporting time indexes.
- Added two-click GUI cancellation, the public `active` command name with a legacy `contributions` alias, 64-bit visible IDs and configurable database TLS modes.
- Expanded SQLite regression coverage and synchronized the installation, storage, security, command, permission and player documentation.
- Runtime-verified the final thin-JAR loading path on Paper 26.2 build 112 / Java 26, including Paper library resolution, schema 3, diagnostics and clean shutdown.

## 1.0 — 2026-08-12

- Added escrow-backed item-delivery contracts with money or item rewards.
- Added complete/proportional fulfillment, exact minor-unit allocation, public/directed contracts, cancellation, expiration, safe claims, and recovery quarantine.
- Added Paper/Folia platform scheduler abstraction, SQLite/MySQL/MariaDB schema, versioned item payloads, configurable GUIs/rules/languages, Vault and PlaceholderAPI integration, public API/events, administration tools, tests, compatibility compile matrix, and reproducible thin-JAR verification.
- Moved HikariCP and the JDBC drivers to Paper's runtime library resolver so the plugin JAR no longer embeds their classes or SQLite native binaries.
- Reworked `/contract` into the compact bookshelf/book/emerald hub, with a gold-ingot public Library and separate Created and Participating listing pages.
- Changed the default message prefix to `&#9863E7⏵ DuskContracts &8| ` and added support for legacy ampersand colors alongside MiniMessage.
