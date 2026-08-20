# Security

Contract mutation is centralized in application/domain services. SQL commits use contract version checks, unique idempotency/operation constraints, and per-player stripes for economy calls. A completed or expired contract cannot accept a delivery.

The deposit GUI is identified by a custom `InventoryHolder` and session UUID, never by title. Shift click, double click, number keys, off-hand swap, collect-to-cursor, mixed drags, repeated confirmation, stale holders, and clicks while processing are blocked. Confirmation runs on the player scheduler one tick later and revalidates both contract and items.

Money parsing accepts only fixed decimal notation at the configured scale and stores `long` minor units. Proportional allocation uses `BigInteger` cumulative floors; the last delivery receives the exact residue.

Serialized objects use the public Bukkit byte format inside a Dusk envelope containing schema, server data version, algorithm, payload length, and SHA-256. Deserialization/checksum failures remain recoverable. Container depth, material, PDC namespace, and payload-size policies are enforced.

Player MiniMessage values are unparsed placeholders. SQL is parameterized. Logs exclude passwords and complete item payloads.

Assassination completion requires prior participation and records the killer/target pair transactionally. A configurable persistent cooldown blocks immediate repeat farming even across restarts; a dedicated operator permission is required to bypass it. Self-bounties are allowed, but self-kills never qualify.

PlaceholderAPI refreshes are deduplicated per UUID, cached for 15 seconds, evicted after inactivity and capped at 2,048 players. The expansion is explicitly unregistered and cleared during disable so PlugManX cannot retain a stale plugin classloader or query closed storage.
