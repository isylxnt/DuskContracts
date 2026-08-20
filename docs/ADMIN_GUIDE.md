# Administrator guide

Start with `/contracts admin doctor`, then `/contracts admin validate`. Expected startup messages identify SQLite/MySQL/MariaDB, scheduler mode, and Vault availability without printing passwords.

Common issues:

- “Money rewards unavailable”: install Vault and a provider, or keep item rewards only.
- “Configuration remains active”: fix the file/path/value named in the error and reload again.
- A claim remains pending: verify inventory space or the economy provider, then retry.
- `AMBIGUOUS`: do not grant assets manually before inspecting the operation, audit log, provider ledger, and related contract.

Dangerous cancel/recovery commands produce a random, action-bound token valid for 60 seconds. Confirm with `/contracts admin confirm TOKEN`. Tokens are per sender and one-use.

Recovery procedure:

1. `/contracts admin operations` or `/contracts admin operations UUID`.
2. Cross-check the correlation ID, contract, audit rows, Vault ledger, and any `recovery/*.recovery` file.
3. Keep uncertain evidence quarantined with `/contracts admin quarantine OPERATION reason`.
4. Only with conclusive evidence use `/contracts admin recover OPERATION COMPLETE` or `REFUND`, then confirm the token.
5. Record the reason; every decision is audited.

`REFUND` creates an idempotent money or item-bundle return claim from the opaque asset stored in the journal. Use it only after proving that the plugin took custody; an administrator can otherwise create value by refunding an operation whose external withdrawal never happened.
