# QuickLock implementation plan

Status: planning draft. Implementation, commits, pushes, deployment, and publication have not started.

## 1. Product contract

Build an original MIT-licensed Fabric server mod for Minecraft Java 26.3. Deliver source, compiled server `.jar`, and documentation. Vanilla clients must work without a client mod or resource pack. QuickLock requires no additional port. Preserve the repository license and applicable third-party notices; do not copy another lock mod's implementation.

### Containers and placement

Explicit allowlist:

- Chests and trapped chests, including double chests.
- Barrels and every shulker box variant.
- Furnaces, blast furnaces, smokers, and brewing stands.
- Hoppers, droppers, dispensers, and crafters.

Ender chests, other inventory blocks, and modded containers are outside scope. Hopper minecarts are not lockable but must respect protection.

Record the placing player's UUID for eligible blocks. Placement reserves locking rights without protecting the inventory. Unlocked containers remain public: anyone may open, use, break, or automate them. Only the recorded placer or an allowlisted admin may lock a tracked container. Containers without a placement record cannot be locked; no first-user claiming or legacy ownership assignment is planned. The intended world starts after QuickLock installation.

When a non-shulker container is legitimately broken, its lock, local trust, and placement record disappear. Its dropped item carries no ownership. Replacement starts public with the new placer's UUID. Breaking one half of a protected double chest removes that half; the surviving half retains protection.

### Access and commands

Access is granted by ownership, container-specific trust, owner-wide trust, or explicit admin override. Trusted players may use inventories and add a chest half. They may not break, unlock, change ownership, or manage trust.

Implement `/lock`, `/unlock`, `/trust <player>`, `/untrust <player>`, `/trust-all <player>`, `/untrust-all <player>`, `/lockinfo`, `/lockhelp`, and `/lockview`.

- Target commands use a server raycast, line of sight, and validated player block-interaction reach.
- Trust and ownership use UUIDs. Names are presentation and lookup data only.
- Global trust applies dynamically to existing and future locks; never copy it into individual locks.
- Removing either grant leaves the other intact. Feedback explains remaining access.
- `/lockinfo` shows the owner only, following the revised requirement.
- All feedback is private. Successful authorized opening produces no routine message.
- Locking an ineligible block returns exactly `This is not lockable`.
- Unauthorized opening returns exactly `This container is locked by PlayerName.` with the owner name substituted. Rate-limit repeated denials without weakening enforcement.
- Revalidate open menus when access changes; close any menu that is no longer authorized.

### Double chests

Both halves form one logical lock, with one owner, one local trust list, and one viewer indicator. An owner or trusted player may attach a half to a locked chest; the whole chest inherits the original lock. The attaching player gains no ownership. Other players cannot cause that merge through any placement path, including sneak-placement or placing the potentially connecting half in a different order.

Do not silently combine incompatible records. Public mixed-placer double chests require the explicit policy decision below.

### Shulkers

Only the owner may break and pick up a protected shulker, subject to admin recovery override. Local trust and owner identity survive pickup and replacement; global trust continues through owner UUID. Trusted inventory access does not grant pickup rights. Automatic collection must not bypass owner-only pickup.

### Labels

`/lockview` is per-player, persisted, and off by default. While aiming within reach at a protected container, show green `Unlocked` for a viewer with access or red `Locked` otherwise. Public containers show nothing. A green label reports viewer access; it does not remove protection. Labels must be private and disappear on look-away or range exit. Toggling labels never disables denial messages or protection.

## 2. Automation policy and decisions

Agreed intent: machine output is the user's responsibility. Locked hoppers, droppers, dispensers, and crafters may operate and send items into public storage or the world. Public destinations and emitted items are not protected by the source lock. Internal cooking, brewing, and crafting continue.

Locking a hopper must not authorize stealing from another owner's protected inventory. External pulls and copper golem access still need protection checks.

The following details remain proposals, not silently accepted requirements. Resolve them before their implementation milestone:

| Decision | Proposed rule | Reason |
| --- | --- | --- |
| External extraction from a locked inventory | Allow only through a locked hopper owned by the same owner; reject public or differently owned extractors and hopper minecarts. | Supports private automation without treating hopper placement as permission to steal. |
| Source-initiated output | Allow vanilla hopper pushes, dropper/dispenser actions, and crafter output into public destinations. Transfers into protected destinations must also satisfy destination policy. | Honors responsibility for output routing. |
| Incoming items | Allow incoming automated items, even from public sources. | Enables farm collection; means outsiders may insert junk. Confirm this consequence before implementation. |
| Trust and automation | Player trust does not automatically authorize a differently owned machine. | Keeps persistent machine permissions separate from player access. |
| Public mixed-placer double chest | Reject `/lock` unless both halves have the same recorded placer. Explain that rebuilding under one placer fixes it. | Avoids letting one player claim another player's half. |
| Public half attached to an already locked chest | Owner or trusted placement explicitly incorporates the new half under the existing owner. | This inheritance behavior is agreed. |
| Locked shulker replacement | Owner/admin only; retain original lock. | Prevents stolen or copied items from changing ownership. |
| Shulker item destruction and transformations | Do not add item invulnerability by default. Recoloring must preserve identity or be rejected; never silently strip a lock. | Pickup protection and item survival are different behaviors. |
| Offline names | Complete online and previously known names; reject unresolved names. | Avoids guessed UUIDs and unnecessary network dependency. |

Document any owner-name fallback for unavailable cached profiles. Choose denial cooldown and label sampling interval through testing, not assumptions. Admin UUID allowlist is agreed; do not automatically grant operators override. Specify console recovery syntax during design.

## 3. Technical foundation and architecture

Minecraft 26.3 support is confirmed by Fabric's release guidance. That guidance recommends Loom 1.17, Gradle 9.6.0, and Loader 0.19.5 at publication. Use Java 25 and an unobfuscated 26.x build setup; pin exact stable artifacts after verification. Tutorials currently accessible identify 26.2, so their signatures are not proof of exact 26.3 compatibility.

Separate responsibilities:

- **Container identity:** explicit allowlist, placement provenance, dimension/position lookup, chest membership, and portable shulker identity.
- **Permission policy:** shared decisions for use, management, breaking, merging, pickup, and automation.
- **Persistence:** block-local placement/lock identity plus authoritative world data for lock records, global trust, and viewer preferences. Select the exact attachment/saved-data split after inspecting 26.3 lifecycle APIs. Avoid independent duplicate sources of truth.
- **Commands:** targeting, profile resolution, suggestions, private feedback, and admin recovery.
- **Enforcement:** Fabric events where sufficient, narrowly scoped mixins where vanilla or optimized paths need interception.
- **Viewer labels:** per-player vanilla entity packets and lifecycle cleanup, independent of protection.

Use schema versions, dirty-state tracking, and bounded lookups. Keep mutations on the server thread. Validate data on load. Unknown or damaged protected records must not become public automatically; preserve evidence and provide an explicit recovery path. Test save ordering and crash recovery across block data, items, and global records.

No custom client registry entries, custom required payloads, or custom item component types that force client installation. Portable metadata must use a verified vanilla-compatible representation.

## 4. Milestones and Git checkpoints

Each checkpoint should contain a coherent change and its relevant tests/docs. Commit after the milestone gate passes, then push the working branch as a backup. A pushed development commit is not a release or compatibility claim. Do not commit local worlds, logs, player UUID lists, server credentials, or generated build caches.

Suggested implementation branch: `codex/quicklock`. Branch creation and all commits/pushes happen only once implementation is authorized. GitHub publishing, Modrinth publishing, and deployment remain separate actions.

### Phase 0 — Freeze policy and inspect exact APIs

- Resolve the decision table and record the resulting transfer matrix.
- Inspect exact 26.3 hooks for placement, use, menus, breaking, chest placement/splitting, block/item serialization, item pickup, explosions, pistons, hopper transfers, golems, and vanilla entity packets.
- Check Lithium's optimized inventory paths against the same enforcement model.
- Verify exact 26.3 releases and dependencies for Fabric API, Lithium, Simple Voice Chat, BlueMap, Fast Leaf Decay, and Universal Graves. Record unavailable releases as blockers to full-stack testing rather than guessing compatibility.
- Record authenticated server identity assumptions. Changing online/offline identity mode requires an explicit migration plan.

Gate: policy matrix and hook inventory complete; unresolved technical constraints clearly recorded.

**Commit and push:** `docs: define QuickLock policies and API research`

### Phase 1 — Establish a reproducible server build

- Add Gradle wrapper, pinned dependencies, original server-only mod scaffold, metadata, ignore rules, and CI build/test tasks.
- Preserve MIT license; record relevant third-party notices.
- Verify clean checkout builds and a dedicated Fabric server starts with the artifact.
- Verify an unmodified vanilla 26.3 client joins.

Gate: repeatable build, server startup, and real vanilla-client connection. Build success alone is insufficient.

**Commit and push:** `build: establish Fabric server mod foundation`

### Phase 2 — Validate the two highest-risk features

Perform focused development prototypes, with results recorded before expanding implementation.

- **Private text display:** send spawn, metadata, movement, and removal packets to one viewer only. Verify simultaneous viewers can see different colors, no shared world entity leaks, and cleanup after reconnect, death, respawn, and dimension change. Allocate IDs safely; do not guess protocol identifiers.
- **Portable shulker:** prove metadata survives owner break, dropped item persistence, pickup, restart, and placement without a client mod. Preserve contents and color. Demonstrate duplicate/unknown identity rejection and inspect save-order failure cases.
- If either fails, return with the specific limitation and a revised proposal. Do not replace labels with an action bar or drop portable protection silently.

Gate: both feasibility reports backed by live evidence; prototypes isolated from unfinished protection claims.

**Separate commits and pushes:** `feat: validate private vanilla text displays` and `feat: validate portable shulker identity`

### Phase 3 — Build ownership, policy, and persistence

- Track eligible placements; reject untracked locking; clear records on ordinary destruction/replacement.
- Implement UUID ownership, local trust, global trust, admin allowlist, player preferences, and name cache.
- Add schema validation, restart persistence, and stale-record reconciliation that checks actual loaded state without forcing chunk loads.
- Test permission combinations, independent grant removal, dimension isolation, save/load, and corrupted/unknown record handling.

Gate: policy tests and restart tests pass; public inventories remain unrestricted.

**Commit and push:** `feat: persist placement ownership and access policy`

### Phase 4 — Commands and player protection

- Implement all commands, targeting, autocomplete, owner-only `/lockinfo`, help, and private feedback.
- Enforce inventory access and breaking before mutation, including creative/spectator handling and alternate interaction paths.
- Revoke open-menu access when protection or permissions change.
- Add explicit admin recovery tools with logged actions; avoid silent operator bypass.

Gate: owner/trusted/untrusted/admin matrix passes; exact messages and denial cooldown verified.

**Commit and push:** `feat: add lock commands and player enforcement`

### Phase 5 — Double-chest lifecycle

- Resolve either half to one logical container and shared lock.
- Permit owner/trusted expansion; deny unauthorized merges before state changes.
- Enforce the approved public mixed-placer policy and reject incompatible records.
- Preserve remaining-half protection after owner break; remove only broken-half provenance. Unlocking makes the whole pair public without inventing placement ownership.
- Cover placement order, both orientations, trapped chests, chunk boundaries/unloads, open menus, split/rejoin, and restart.

Gate: no permission loss, stale membership, access through a second half, or inventory duplication.

**Commit and push:** `feat: preserve protection across chest merges and splits`

### Phase 6 — Automation and destruction protection

- Implement approved transfer matrix across hopper push/pull, hopper minecarts, double-chest wrappers, and relevant Fabric transfer paths.
- Cover copper golem selection and actual inventory mutation; player opening checks are insufficient.
- Protect hopper/dropper/dispenser/crafter inventories while preserving agreed redstone operation and output behavior.
- Prevent explosions, applicable piston operations, fire, and other destructive entity/block interactions from removing locked containers. Inspect displacement paths as well as direct destruction.
- Define supported boundary: no promise against privileged world-edit commands or arbitrary third-party code mutating inventories directly.
- Run identical integration scenarios with Lithium absent and present. Fix demonstrated conflicts with targeted adapters and tests.

Gate: theft attempts fail; intended public outputs and internal machine processing still work; no duplication or transfer loops introduced.

**Separate commits and pushes:** `feat: enforce protected inventory transfers` and `feat: prevent destructive lock bypasses`

### Phase 7 — Complete shulker lifecycle

- Integrate portable identity with authoritative owner/local trust and dynamic global trust.
- Enforce owner-only breaking and dropped-item pickup; prevent hopper/minecart collection bypasses.
- Validate metadata against server authority. Use a tracked placed/portable lifecycle and reject conflicting active identities; do not rely on an unsigned owner field from the item.
- Preserve container contents through the normal loot path exactly once; never manually spawn a second copy of vanilla drops.
- Test repeated moves, restarts while dropped/carried/placed, filled and empty boxes, every color, crafting transformations, unknown/copied tokens, owner death, and Universal Graves restoration.
- Implement and test explicit recovery for interrupted transitions. Document limits of external duplication exploits and inconsistent backups.

Gate: owner and trust survive normal movement; unauthorized pickup and duplicate placement fail; contents are conserved.

**Commit and push:** `feat: preserve shulker locks through pickup and placement`

### Phase 8 — Complete access labels

- Connect the validated display mechanism to live permission and targeting services.
- Persist toggle, default off. Show one indicator for a double chest.
- Remove/update on target change, range exit, toggle, unlock, destruction, trust/admin changes, death, teleport, dimension change, disconnect, and shutdown.
- Sample only opted-in players; raycast once per sample, use indexed lock lookup, never scan all locks or load chunks. Send packets only on necessary display changes.
- Measure label cost at representative player counts; record server and network results and any visible delay.

Gate: two viewers see private, correct states; no ghost labels; protection works with view disabled.

**Commit and push:** `feat: add persistent private access labels`

### Phase 9 — Full acceptance and delivery preparation

- Run the verification matrix below against the built artifact in a fresh dedicated server, then across restarts.
- Test the exact approved mod stack and pinned versions; document conflicts rather than claiming broad compatibility.
- Write DiscoPanel install/upgrade/backup/recovery instructions, build guide, command guide, admin configuration, automation examples, and limitations.
- Prepare Modrinth metadata: server required/client unsupported installation intent, exact game/loader dependencies, MIT license, source/issues links, description, and changelog. Verify actual metadata field values before packaging.
- Produce a release-candidate `.jar` and record its checksum and test evidence. No automatic deployment, GitHub release publication, or Modrinth upload.

Gate: required acceptance cases pass; remaining limitations explicit and accepted before labeling a release stable.

**Commit and push:** `docs: prepare installation guide and release candidate evidence`

## 5. Verification matrix

| Area | Automated checks | Dedicated-server / live multiplayer checks |
| --- | --- | --- |
| Permissions | Owner/local/global/admin combinations; grants removed independently; management restrictions. | Four roles, private messages, names, completion, menu revocation. |
| Placement | Exact allowlist, untracked rejection, destruction cleanup, replacement ownership. | New world, generated containers, alternate placement paths, all variants. |
| Persistence | Schema round trips, malformed records, dimension keys, lifecycle invariants. | Save/restart with locks, preferences, chests, and portable shulkers. |
| Chests | Grouping, merge authorization, split membership, conflict rejection. | Both halves/orientations, trapped chests, simultaneous interaction, chunk boundaries. |
| Shulkers | Token validation, transitions, local/global trust preservation, duplicate rejection. | Pickup/placement, death/graves, automatic collection, crafting, contents conservation. |
| Automation | World integration tests for approved directional and ownership matrix. | Hopper minecarts, golems, crafters, droppers/dispensers, public outputs; repeat with Lithium. |
| Destruction | Supported explosion, piston, fire, and entity scenarios. | Client block correction and practical bypass attempts. |
| Labels | Target/access state transitions, toggle serialization, cooldown logic. | Vanilla rendering, privacy between viewers, cleanup, latency, packet/CPU measurements. |
| Delivery | Clean build and CI checks. | Install packaged jar through a DiscoPanel-equivalent startup configuration; actual DiscoPanel validation before claiming it tested. |

Use unit tests for policy/data logic and Fabric/GameTest-style world tests where supported by the pinned version. Some behavior requires real clients and cannot be established by compilation or simulated players. Record each acceptance result with tested versions and distinguish automated passes from manual observations.

## 6. Research references

- [Fabric for Minecraft 26.3](https://fabricmc.net/2026/09/15/263.html): release-specific build baseline.
- [Fabric for Minecraft 26.1](https://fabricmc.net/2026/03/14/261.html): Java 25 and unobfuscated toolchain transition.
- [Fabric commands](https://docs.fabricmc.net/develop/commands/basics): command registration model; inspect pinned 26.3 signatures.
- [Fabric data attachments](https://docs.fabricmc.net/develop/serialization/data-attachments): candidate persistent block/entity data mechanism; inspect pinned 26.3 lifecycle semantics.
- [Fabric automated testing](https://docs.fabricmc.net/develop/automatic-testing): candidate unit and game testing workflow; verify pinned-version support.
- [Mojang display entities](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-19-4): vanilla text rendering capability, not proof of private 26.3 packet lifecycle.
