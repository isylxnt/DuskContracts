# Architecture

The code is separated into `bootstrap`, `platform`, `domain`, `application`, `persistence`, `economy`, `inventory`, `commands`, `config`, `localization`, `api`, `recovery`, and integration packages.

The domain owns exact money, proportional allocation, contract invariants, matching/fulfillment/reward types, and separate contract/operation/claim states. Application services coordinate escrow windows and are the only mutation entry points. JDBC repositories own conditional updates, constraints, transactions, claims, operations, and audits. Bukkit listeners only translate input and schedule a service call.

Contract creation follows `PREPARED → external withdrawal → AMBIGUOUS window → SQL COMMITTED`. Contribution follows the same pattern with inventory custody, then an optimistic `version` update, contribution row, recipient claims, status change, operation commit, and audit in one transaction. Claiming follows `PENDING → CLAIMING → external grant → CLAIMED`; uncertain finalization becomes `AMBIGUOUS`.

Item escrow and claims are behind storage/application interfaces so a future DuskLedger module can replace their implementation without changing commands or contract rules.

The distribution is a thin plugin JAR. Its `plugin.yml` declares HikariCP plus the SQLite, MySQL, and MariaDB JDBC drivers as Paper runtime libraries; Paper downloads them from Maven Central and adds them to the plugin classpath before enable. This avoids bundling platform-specific SQLite native binaries and removes the need for package relocation. `verifyJar` rejects accidentally embedded runtime libraries, while `runtimeDriverSmoke` loads all declared roots and executes a SQLite query on the build classpath.
