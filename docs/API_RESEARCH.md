# Version-specific implementation notes

Inspected official 26.3 metadata and locally generated unobfuscated Minecraft sources, Fabric API 0.165.0+26.3 sources, and Lithium 0.26.2+mc26.3 bytecode. Generated third-party sources are not committed or shipped.

## Pinned baseline

- Minecraft 26.3; Mojang metadata requires Java 25.
- Loader 0.20.1, stable according to Fabric metadata at implementation.
- Loom 1.17.21; Gradle 9.6.0 with verified distribution checksum.
- Fabric API 0.165.0+26.3.
- Minecraft uses unobfuscated names. This build does not use Yarn or a remapping plugin.

## Hook inventory

| Concern | Actual hook | Policy |
| --- | --- | --- |
| Commands | `CommandRegistrationCallback` | Server command tree, ordinary vanilla suggestions and private messages. |
| Placement | `BlockItem.placeBlock(BlockPlaceContext, BlockState)` | Validate before mutation, record after successful placement and before item consumption. |
| Chest membership | `ChestBlock.TYPE`, `getConnectedDirection`, reciprocal neighbor check | One logical lock, trusted expansion, identity inheritance. |
| Player use/break | `ServerPlayerGameMode.useItemOn`, `destroyBlock` | Reject unauthorized actions; a `WrapMethod` scopes authorized breaking with `finally` cleanup. |
| Alternate/menu access | `BaseContainerBlockEntity.canOpen`, `Container.stillValidBlockEntity` | Covers direct menu validity checks, including crafter overrides. |
| Block replacement | `LevelChunk.setBlockState` | Block unexpected destructive replacement, clean ordinary placement records after mutation. |
| Destruction side effects | `Level.destroyBlock`, `ServerExplosion.interactWithBlocks` | Reject before drops; explosion list filtering prevents item duplication from denied destruction. |
| Piston | `PistonBaseBlock.isPushable` | Protected blocks cannot be moved or destroyed by piston resolution. |
| Hopper pull | `HopperBlockEntity.suckInItems`, concrete `Container.canTakeItem` overrides | Same-owner locked block hoppers only; inventory wrappers checked too. |
| Fabric transfer | `ContainerSlotWrapper.extract` and `ContainerStorageImpl.container` accessor | Deny protected sources before transactional mutation; deny locked portable item extraction. This internal Fabric adapter hook is version-sensitive. |
| Copper golem | `TransportItemsBetweenContainers.isContainerLocked`, `doReachedTargetInteraction` | Reject target selection and actual mutation, including locks added during travel. |
| Shulker drop | `ShulkerBoxBlock.getDrops`, `BlockEntity.collectComponents` | Add vanilla `minecraft:custom_data` identity to normal loot exactly once. Empty creative-mode boxes get one explicit drop because vanilla otherwise drops none. |
| Shulker pickup | `ItemEntity.playerTouch`, `hasPickUpDelay`, hopper item collection | Owner/admin only; mob sensors and automatic collection cannot acquire portable boxes. |
| Chunk witness | `BlockEntity.loadWithComponents`, `saveWithoutMetadata`, `setLevel` | Persist placement-generation and lock witnesses; stop on inconsistent protected world/data state. |
| Display | `Display.TextDisplay`, `EntityTypes.TEXT_DISPLAY`, vanilla spawn/data/remove packets | Normal entity ID allocator, no world insertion/tracking, one private display per opted-in viewer. |

26.3 stores colored shulker blocks in `Blocks.DYED_SHULKER_BOX`, a `ColorCollection`; use its explicit vanilla members. Do not widen eligibility to every block entity or inventory class. Vanilla `TransmuteRecipe.createWithOriginalComponents` preserves component patches; portable shulker recoloring must retain the identity.

The initial targeting test caught use of the previous tick's eye position. Targeting now uses `player.pick(range, 1, false)` for current server position and orientation. Tests cover both command and label paths.

Lithium's double inventory extends `CompoundContainer`, so the same accessors and extraction policy apply. Its optimized hopper path still uses the source extraction permission check. Dedicated tests exercise tick-based transfers before and after lock changes, with output into public storage.

## Persistence boundaries

Authoritative JSON uses world-local dimension/position keys, UUID locks/trust, preferences, and explicit-unlock tombstones. Writes validate the graph, force a temporary file, and atomically replace the primary. A prior file is retained for diagnosis. Block entities hold witnesses, not independent trust lists. Shulker items carry only a lock UUID reference, not authoritative owner/trust fields.

Minecraft world, player, and mod saves are separate. Witness checks fail closed on detectable interrupted/mismatched transitions. They do not provide a global atomic transaction or guarantee recovery from arbitrary external edits and duplication exploits. Restore consistent backups when detection stops the server.

The cross-process restart fixture exposed a chunk post-load deadlock: `BlockEntity.setChanged()` requests its chunk, so calling it from `setLevel()` while that chunk is completing creates a circular wait. Loading now updates witness fields directly and queues any necessary dirty notification for the server tick, after chunk registration. Unchanged witnesses do not trigger a write notification.

## Sources

- [Fabric metadata](https://meta.fabricmc.net/v2/versions/loader/26.3)
- [Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [Fabric 26.3 guidance](https://fabricmc.net/2026/09/15/263.html)
- [Fabric command documentation](https://docs.fabricmc.net/develop/commands/basics)
- [Fabric testing documentation](https://docs.fabricmc.net/develop/automatic-testing)
- [Fabric API source](https://github.com/FabricMC/fabric-api/tree/26.3)
- [Lithium](https://modrinth.com/mod/lithium)
- [Modrinth API](https://docs.modrinth.com/api/)
