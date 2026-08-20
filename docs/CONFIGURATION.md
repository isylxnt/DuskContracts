# Configuration

Every YAML file has `schema-version: 1`. Missing default keys are merged at startup; existing values and custom sections are retained. A parsed configuration is immutable and swapped only after complete validation. An invalid reload leaves the previous snapshot active.

`config.yml` controls limits, cooldown/duration policy, matching modes, proportional minimums, exact money scale/range, grouped expiration sweeps, serialized-item limits, operation/session timeouts, rate limiting, persistent assassination repeat-kill cooldown, and enforced audit retention.

`storage.yml` selects SQLite, MySQL, or MariaDB and configures the bounded Hikari pool. MySQL and MariaDB expose `DISABLED`, `PREFERRED`, `REQUIRED`, and `VERIFY_IDENTITY` TLS modes; use `VERIFY_IDENTITY` with a trusted certificate in production. Passwords are never logged. Storage changes require restart and are rejected by hot reload so diagnostics cannot report a backend different from the active pool.

`menus.yml` controls titles, validated sizes, slots, materials, Custom Model Data, names, lore, filler, state buttons, glow, and sounds. Text is MiniMessage. `item-rules.yml` controls global and request/delivery/reward-specific blocked or allowlisted materials, nested containers, blocked PDC namespaces, and serialized-size limits. Item-rule changes require restart in 1.0.

The bundled `money.creation-fee` and `money.tax-percent` keys are reserved migration-compatible settings and remain `0` in 1.0; non-zero charging is not enabled by this release candidate.

`lang/messages_en.yml` and `messages_es.yml` contain player-facing strings. Player values are inserted with unparsed MiniMessage placeholders, preventing tag injection.
