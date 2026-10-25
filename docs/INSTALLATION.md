# Installation, configuration, and recovery

## DiscoPanel

1. Stop the server and back up its entire directory. For the intended new world, install QuickLock before any players place storage.
2. Configure the server's Java runtime/container image to **Java 25**. Java 21 or 23 is insufficient. Exact DiscoPanel image selection depends on the installed DiscoPanel version; this guide does not claim the UI was tested.
3. Install a Fabric **Minecraft 26.3** server with Loader **0.20.1**. Use Fabric's official server installer/launcher, not the QuickLock jar as the server executable.
4. Put `quicklock-0.1.0-alpha.1.jar` and Fabric API `0.165.0+26.3` in the server's `mods/` folder. Install neither source jars nor the development test mod.
5. Use the Fabric launcher in the startup command, typically `java -Xms2G -Xmx4G -jar <fabric-server-launcher>.jar nogui`. Keep memory values appropriate for the whole server/mod stack.
6. Start once, confirm QuickLock loads, then stop to configure admins. Restart after configuration changes.
7. Complete the manual acceptance checklist with an unchanged vanilla 26.3 client before opening the server to the teams.

Use authenticated player identities (`online-mode=true`, or a correctly secured authenticated proxy). Changing identity mode changes UUIDs and requires a planned migration.

QuickLock uses only existing Minecraft connections. The separate networking plan remains Minecraft 25565/TCP, Simple Voice Chat 24454/UDP, and BlueMap 8100/TCP or an HTTPS reverse proxy. QuickLock does not install, open, or configure these other services.

## Configuration

QuickLock creates `config/quicklock.json`:

```json
{
  "adminUuids": [],
  "denialCooldownTicks": 40,
  "labelIntervalTicks": 2
}
```

Add admins as quoted canonical UUID strings to `adminUuids`. Operators receive no automatic override. Never publish the server's actual configuration with private player identifiers. Restart to reload it.

- `denialCooldownTicks`: positive; default 40 ticks (about two seconds at 20 TPS). Rate limiting affects chat only.
- `labelIntervalTicks`: 1–20; default two ticks (about 100 ms at 20 TPS). Actual visual response also includes network/client delay.
- Invalid configuration or corrupt protection data prevents startup. Check the log rather than deleting protection data.

## Admin recovery

Allowlisted admins can use normal targeted lock commands and break locks for recovery. Administrative unlock, trust changes on someone else's lock, breaking, and explicit recovery actions are logged. Console can use the dedicated recovery commands; command blocks are not treated as console administrators.

| Command | Purpose |
| --- | --- |
| `/quicklockadmin list` | List lock UUIDs, owners, recorded positions, and portable identities. |
| `/quicklockadmin unlock-id <uuid>` | Unlock a placed lock by ID, including an abandoned lock in an unloaded chunk. |
| `/quicklockadmin recover-shulker <uuid>` | Mark an interrupted shulker identity portable only after its recorded chunks are loaded and no shulker remains there. Creates no item. |
| `/quicklockadmin release-held` | Remove protection metadata from a held portable or invalid token item. Requires an actual player with admin access. Refuses identities still recorded as placed. |

Coordinate-based list entries contain dimension ID and Minecraft's packed block position. For ordinary abandoned-lock recovery, inspect the actual container and use `/unlock`; ID recovery handles cases where targeting is unavailable.

`unlock-id` on a portable identity is refused: recover the item and use `release-held`. Unknown or copied portable IDs are not silently accepted. Unlocking a placed shulker clears its token from subsequent legitimate drops. A copied obsolete token can require explicit admin release.

If startup or chunk loading reports a chunk/data mismatch, stop the server and preserve the affected files. Restore a **consistent backup** of world regions, player inventories, and QuickLock data. Do not restore only `quicklock.json.bak`: it is diagnostic protection against an interrupted JSON write, not a synchronized world snapshot. Do not delete QuickLock data to bypass the error; that forfeits ownership and protection.

Dropped shulker items still burn, despawn, and can be lost to the void. Recovering identity never recreates contents. Take backups before using third-party world editing or grave recovery tools.

## Companion mods

Release metadata checked through Modrinth's API during implementation:

| Project | Fabric 26.3 release found | Dependency/status |
| --- | --- | --- |
| Fabric API | 0.165.0+26.3 | Required by QuickLock. |
| Lithium | 0.26.2+mc26.3 | Tested in the automated world-test matrix. |
| Simple Voice Chat | fabric-2.6.26+26.3 | Release found; actual voice/network behavior not tested here. Client mod needed for voice, independent of QuickLock. |
| BlueMap | 5.29-fabric | Requires Fabric API. Release found; rendering/web service not tested here. |
| Fast Leaf Decay (`fast-leaf-decay`) | No matching Fabric 26.3 release | Do not install a Forge build. Exact intended Fabric project/replacement remains unresolved. |
| Universal Graves | 3.13.0+26.3 | Requires Polymer; compatible Polymer release found: 0.18.5+26.3, which requires Fabric API. Grave restoration with locked shulkers still needs a live test. |

These are checked releases, not blanket compatibility promises. Pin exact jars, verify checksums, and rerun acceptance after any version change. No replacement for Fast Leaf Decay has been silently selected.
