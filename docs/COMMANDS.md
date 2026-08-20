# Commands

Player commands: `/contracts`, `help`, `browse`, `search <material|online-player|id>`, `create`, `mine`, `active`, `claim`, `cancel <id>`, `info <id>`, and `toggle-notifications`. The old `contributions` spelling remains a hidden compatibility alias for `active`.

Administrator commands: `/contracts admin reload`, `validate`, `inspect <id>`, `cancel <id> <reason>`, `operations [uuid]`, `recover <operation-id> [COMPLETE|REFUND|QUARANTINE]`, `quarantine <operation-id> <reason>`, `stats`, `doctor`, and `confirm <token>`.

Aliases are `/contract` and `/dcontracts`. Tab completion exposes only command literals and never private contract IDs.
