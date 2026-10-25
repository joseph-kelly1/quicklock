# QuickLock 0.1.0-alpha.1

Built and checked October 24, 2026 (America/New_York). Alpha artifact for acceptance testing; no server deployment, GitHub release, or Modrinth publication performed.

## Artifacts

| File | SHA-256 |
| --- | --- |
| `build/libs/quicklock-0.1.0-alpha.1.jar` | `cc67c11854ae80b1963e85cefd8ca60b9e84628a42533dbed7dd933393bd931f` |
| `build/libs/quicklock-0.1.0-alpha.1-sources.jar` | `0f0a3256baae988848a80493bd6602b363552b4cef6e97a79d82e4d19afe4920` |

Only the first jar belongs in the server's `mods/` directory. Both include the MIT license and third-party notice. No Minecraft classes or runtime dependency jars are bundled.

## Local evidence

- Ten policy/persistence unit tests pass.
- Baseline dedicated server: all 22 game tests pass (21 QuickLock, one Fabric framework test).
- Lithium dedicated server: all 22 pass with Lithium 0.26.2+mc26.3.
- Saved placed lock, independent trust grants, protected items, viewer preference, and portable shulker identity verified in independent subsequent server processes.
- Packaged jar starts using the official Fabric launcher outside the development classpath; console recovery listing and clean stop verified on a localhost-only disposable server.
- A restart deadlock found by the persistent fixture was fixed before delivery by deferring chunk dirty notifications until after chunk loading.

See [TESTING.md](TESTING.md) for the remaining real-client, full companion-stack, performance, and DiscoPanel acceptance checks. Those are not marked complete.

## Git checkpoints

Work is isolated on `codex/quicklock` and pushed incrementally: planning, pinned toolchain, ownership/trust persistence, integrated protection features, client-world lifecycle fixes, dedicated-server/Lithium/restart tests, chunk-load deadlock fix, and documentation/artifact preparation. Main is unchanged. CI evidence belongs to the exact pushed commit; a green local build does not imply remote CI success.
