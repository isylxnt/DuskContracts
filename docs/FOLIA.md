# Folia design

The official Paper guidance says Folia has independently ticking regions and requires Global, Region, Async, and Entity schedulers. Merely setting `folia-supported: true` is insufficient. See [Paper’s Folia support guide](https://docs.papermc.io/paper/dev/folia-support/) and the [Folia project overview](https://docs.papermc.io/folia/reference/overview/).

`PlatformScheduler` exposes entity, region, global, async, delayed, repeating, cancellation, and ownership checks. Capability detection checks the Folia regionized-server class and scheduler methods. Reflection is confined to the platform package so the common bytecode can still link on Paper 1.20.1, where it falls back to `BukkitScheduler`.

Player inventories, messages, menu opens, and sounds run through the entity scheduler. Database/file work runs asynchronously. Expiration is one grouped asynchronous sweep and does not load chunks. Vault calls are serialized per player and scheduled on the global scheduler because legacy providers do not publish a Folia thread-safety capability contract.

During plugin disable, online-player cleanup is dispatched to each entity scheduler and awaited for a bounded five seconds before scheduler cancellation. Session removal and inventory clearing are idempotent; if a regional scheduler refuses shutdown work, guarded fallback cleanup prioritizes returning escrowed menu items and logs the affected UUID.

Runtime testing must still place players in distinct regions with multiple tick threads and strict checks enabled. Compatibility compilation alone cannot prove thread ownership of third-party economy providers.
