# Storage

SQLite is the zero-configuration default. It uses one pooled connection, WAL, full synchronous commits, foreign keys, a 5-second busy timeout, and conservative serialization. MySQL and MariaDB use configurable bounded Hikari pools and transactions.

Schema 1 creates the transactional contract, escrow, contribution, claim, operation, audit and preference tables. Schema 2 adds assassination participation. Schema 3 adds persistent killer/target completion guards and time indexes for bounded maintenance. Listing queries project metadata and do not load item blobs. All player values use prepared statements.

Dates are UTC epoch milliseconds. UUIDs are canonical strings. Item payloads are binary, not YAML. Migrations are forward-only, reentrant after partially committed DDL, transactional where the database permits it, checksum-verified on every start, and never drop tables.

Database work runs on a dedicated executor. Normal server/entity/region work never calls JDBC or waits on a future. Shutdown drains pending storage work for at most five seconds before interruption, preventing an unlimited reload freeze. Audit and old assassination-guard rows are deleted in bounded daily batches according to `audit-retention-days`. MySQL/MariaDB container migration tests are automatically skipped when Docker is absent.
