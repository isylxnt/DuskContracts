# Recovery model

Minecraft inventories, Vault, and SQL cannot share one ACID transaction. DuskContracts therefore journals an idempotency key and correlation ID before taking custody, then marks the operation ambiguous during the external-asset window, and finally commits the contract/contribution/claim in one SQL transaction.

The journal also retains the opaque item bundle or exact minor-unit amount and its owner. A known rejection before an asset is removed closes the prepared operation as `FAILED`. Once an asset is in plugin custody, any uncertain failure stays `AMBIGUOUS`: the GUI does not also return it, so an administrative refund cannot duplicate an automatic one. On startup, stale `PREPARED` operations and stale `CLAIMING` claims become `AMBIGUOUS`; they are not paid again automatically. A failed money deposit is returned to pending, while provider success followed by SQL failure is ambiguous. The same rule applies to an item grant followed by SQL failure. An explicit, evidenced `REFUND` creates a unique return claim from the journaled asset.

Closing a deposit returns items to the inventory. If it no longer fits, a return claim is created. If the database also fails, the opaque versioned bundle is written under `plugins/DuskContracts/recovery/`; it is never dropped on the ground.

Administrative resolution is explicit, token-confirmed, idempotent at the operation row, and audited. “No evidence” means quarantine, not a second grant.
