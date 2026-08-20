# Testing

Run:

```text
./gradlew clean test compatibilityCompile jar verifyJar
```

The unit suite covers contract ownership/privacy/complete-mode invariants, fixed decimal parsing, malicious numeric forms, durations/amounts, proportional rounding/residue, payload checksum/size/version, malformed item bundles, schema migration checksum/reentry, operation idempotency, optimistic concurrent delivery, cancellation return claims, repeat-safe claim reservation, persistent assassination antifarming, bounded maintenance cleanup, rate limiting and overflow-safe pagination.

SQLite tests run locally. MySQL 8.4.2 and MariaDB 11.5.2 migration tests use Testcontainers and skip explicitly when Docker is unavailable. `compatibilityCompile` compiles every common source file against the API matrix in [COMPATIBILITY.md](COMPATIBILITY.md). `verifyJar` checks the exact filename, Java 17 bytecode, all Paper library declarations, and the absence of bundled Bukkit/Paper, optional API, and runtime-library classes.

`runtimeDriverSmoke` loads HikariCP plus the SQLite, MySQL, and MariaDB drivers using their original packages, verifies that JDBC can discover the drivers, and executes `SELECT 1` through SQLite. The final JAR was additionally started on the official stable Paper 26.2 build 112 with Java 26: Paper resolved all declared libraries, discovered and enabled the plugin, migrated fresh SQLite to schema 3, returned a healthy `admin doctor`, and disabled the plugin/data source cleanly.

`verifyDependencies` resolves every runtime, test, smoke, Paper, and Folia configuration and compares 482 configuration-scoped SHA-256 records with `gradle/dependency-checksums.txt`. Regenerate it only for an intentional dependency/API update. This custom verifier is used because Gradle 9.6.1's native metadata writer throws a duplicate-component-key error when multiple historic Paper snapshot configurations coexist.

Manual release gates still required: real Paper/Folia startup matrix, two-region delivery, abusive inventory click suite, Vault provider failure/crash windows, reconnect/restart between journal states, full-inventory recovery, serialized shulkers/books/potions/PDC/components on each data version, and the stated scale/load targets. Do not infer performance numbers from unit tests.
