# Public API

Other plugins obtain `DuskContractsApi` from Bukkit’s `ServicesManager`. The small API exposes `ContractService`, `ClaimService`, immutable `ContractView` records, asynchronous lookups, browsing, and pending-count access. It never exposes JDBC rows or mutable collections.

Immutable Bukkit events are available for created, contributed, completed, cancelled, expired, and claimed contracts. Player events run on the owning entity context; grouped expiration events return to the global scheduler before publication.

PlaceholderAPI identifiers are `%duskcontracts_active%`, `_created`, `_completed`, `_claims`, and `_contributed`. They use a nonblocking 15-second cache and never query SQL synchronously from a placeholder request.
