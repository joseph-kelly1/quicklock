# Verification record

Status: alpha. Implementation tested on macOS ARM64 with Java 25, Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, and Loom 1.17.21. Companion test configuration adds Lithium 0.26.2+mc26.3. CI repeats on Linux/Java 25; inspect actual CI results rather than assuming remote jobs passed.

Local result: all 10 unit tests and all 22 world tests pass, both baseline and with Lithium. Independent subsequent server processes verified the persisted fixture. The fixture caught and drove a fix for a chunk post-load deadlock before delivery. See [artifact checksums](RELEASE_CANDIDATE.md).

## Automated coverage

### Policy and persistence unit tests

Ten JUnit tests cover independent local/global trust, owner/admin management restrictions, future global grants, public placement and replacement ownership, mixed-placer double-chest rejection without partial mutation, expansion/split ownership, portable shulker trust and duplicate rejection, extraction ownership, corrupted graphs, incomplete files, and JSON save/load/backup handling.

### Dedicated-server game tests

Twenty-one QuickLock tests, plus Fabric's own framework test (22 total), cover:

- Exact allowlist, every shulker color, and ineligible blocks.
- Actual item placement records without automatic locking and cleanup on public destruction.
- Owner/trusted/untrusted inventory use and management rules.
- Commands executed through Minecraft's dispatcher with real server raycasts, independent grants, view toggle, help, owner info, and exact ineligible-block text.
- Private denial packets, exact message, spam limiting, and no accidental menu opening with labels off.
- Actual hopper pull and optimized tick paths before/after lock changes, same-owner machines, public output, item conservation, and hopper minecart denial.
- Rejection of direct destructive replacement and ordinary destruction.
- Real explosion execution without destroying or duplicating locked contents.
- Piston resolution distinguishes public destroyable shulkers from protected ones.
- Actual copper-golem target and mutation boundary methods, including a target locked after selection. This does not replace a live pathfinding scenario.
- Fabric container-storage extraction denial before transactional mutation.
- Trusted chest expansion, foreign expansion denial, ownership inheritance, trust-without-breaking, and survivor protection after splitting.
- A full owner shulker break, one loot drop, rejected foreign pickup, owner inventory pickup, replacement, trust preservation, contents conservation, and duplicate identity rejection.
- Automatic portable shulker collection denial and vanilla item/chunk serialization.
- Explicit admin allowlist access/breaking and rejection of non-console recovery sources; invalid-token pickup for admin repair.
- Viewer-specific vanilla spawn/data/remove packets, different simultaneous access text, no shared world entity, no redundant unchanged spawn, look-away cleanup, and respawn/level-change cache invalidation.
- A persistent fixture containing a placed lock, local/global trust, viewer preference, protected items, and a portable shulker identity. A second independent test-server process checks the saved fixture. The first run creates it; a repeat run is required to verify restart.

The label tests use server players with packet-capturing connections. They prove server recipient/state behavior; they are **not real client rendering tests**. Tests invoking lifecycle events directly prove handler behavior, not the full respawn network sequence.

## Reproduce

With Java 25 selected:

```sh
./gradlew build
./gradlew runGameTest
./gradlew build -PwithLithium
./gradlew runGameTest -PwithLithium
```

The second run in each configuration verifies a real process restart. Game-test worlds/configuration stay under `build/run/gameTest/`; they contain synthetic test identities and are never production data. Delete only that disposable world when intentionally resetting test fixtures. Do not delete a real server's protection file.

JUnit output: `build/reports/tests/test/`. Dedicated-server logs: `build/run/gameTest/logs/latest.log`. GitHub Actions stores test output and jars separately for baseline and Lithium jobs. Game tests accept the Minecraft EULA for the disposable local server.

## Packaged artifact smoke check

The compiled QuickLock jar, official Fabric server launcher, and Fabric API were started outside the development classpath on a disposable localhost-only dedicated server. QuickLock initialized, the server reached `Done`, console `quicklockadmin list` worked, and the server stopped cleanly. Player-targeted commands are intentionally unavailable from console. This smoke check is not a player connection or rendering test.

## Remaining live acceptance

Use a disposable server and at least two unchanged vanilla 26.3 clients before relying on the alpha for public survival play:

- [ ] Join without Fabric/client mods/resource packs; confirm commands autocomplete and private messages render correctly.
- [ ] Owner, locally trusted, globally trusted, untrusted, and allowlisted admin each exercise every eligible container, including crafter UI controls and brewing/furnace slots.
- [ ] Lock a container another player already has open; revoke each trust grant while open. Confirm menu closes and queued clicks cannot move items.
- [ ] Verify red `Locked` / green `Unlocked` color, size, positioning, distance, occlusion, and simultaneous viewer privacy. Confirm there is one label for a double chest.
- [ ] Rapid look changes, reach edge, toggle, death/respawn, reconnect, same/different-dimension teleports, trust changes, unlock, and break leave no ghost labels.
- [ ] Exercise chest orientations, trapped chests, sneak-placement, chunk borders, repeated split/rejoin, and server restart. Incompatible public placement ownership gives actionable feedback.
- [ ] Test owner-only shulker collection, trusted denial, every color/recoloring, filled and empty creative/survival drops, repeated replacement, and restart while dropped/carried/placed.
- [ ] Test real copper-golem pathfinding, wither/creeper/TNT destruction, fire, piston contraptions, hopper minecarts, and mixed public/private automation networks.
- [ ] Repeat practical bypass attempts with the exact pinned Lithium configuration.
- [ ] Measure server tick cost and packet traffic with representative player/container counts. Synthetic packet tests are not a performance benchmark; synchronous full-state saves may cost more as the placement registry grows.
- [ ] Test Universal Graves death/restoration of a carried locked shulker, then pickup and replacement. Verify token and inventory contents survive that actual mod's lifecycle.
- [ ] Test the complete chosen companion-mod stack. Fast Leaf Decay currently has no verified matching Fabric release under the supplied project identity.
- [ ] Validate actual DiscoPanel startup/runtime selection and stopped-server backup/restore using the installation guide.

Known limits: no global transaction across Minecraft chunk/player saves and QuickLock JSON; detectable witness mismatches stop the server. No guarantee against arbitrary privileged commands, unsupported mods mutating inventories directly, removing QuickLock, external editing, or unrelated duplication exploits. Recovery never creates replacement items.
