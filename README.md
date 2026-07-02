# BlockLogs

A Paper **26.2** (Java 25) plugin that records every player-caused change to the world — direct **and
indirect** — and lets you inspect and roll it back. Blocks placed/broken, block-state changes (redstone
repeater delays, orientations, open/closed…), container item transactions, redstone, and entity/animal
actions are all logged and attributed to the responsible player, including through causal chains
(player → TNT → explosion → destroyed blocks → spilled items).

Queries are presented **git-graph style**: a collapsed causal tree you expand and drill into, instead of
a wall of chronological rows.

## Status

Feature-complete **v0.1**, builds clean against Paper 26.2 and runs on a live 26.2 server. Logging,
causal attribution, chat/dialog query views, inspector, and rollback/restore are all in.

## Commands

All under `/bl` (aliases `/blocklog`, `/blocklogs`). Permissions: `blocklogs.use`, `blocklogs.rollback`,
`blocklogs.admin`.

| Command | What it does |
|---|---|
| `/bl find` | Opens a **Dialog form** to pick player / time / radius / category — no command syntax needed |
| `/bl gui [filters]` | Opens the **causal tree as native Dialog windows** (click to drill in, back to loop) |
| `/bl lookup [filters]` | Causal tree in chat (collapsed, aggregated, clickable, hover detail) |
| `/bl flat [filters]` | Flat chronological list |
| `/bl inspect` | Toggle the inspector — click a block to see its history |
| `/bl rollback [filters]` / `/bl restore [filters]` | Undo / redo matching changes |
| `/bl session <id>` · `/bl log` | Reopen / list saved queries |

Filter tokens: `user:<name>`, `a:<action|category>`, `r:<radius>`, `t:<30m|3h|2d>`, `world:<name>`,
`b:<material>`.

## Architecture

```
com.blocklogs
├── BlockLogsPlugin        lifecycle + assembly (wires the service graph)
├── BlockLogsLoader        runtime library loader (pulls sqlite-jdbc, no shading)
├── BlockLogsServices      service locator passed to listeners/commands
├── config.BlockLogsConfig typed config.yml view
├── api/                   PUBLIC, stable surface for other plugins
│   ├── BlockLogsApi(+Impl) ServicesManager entry point
│   ├── actor/             Actor, ActorType
│   ├── action/            ActionType, ActionCategory
│   ├── model/             WorldPos
│   └── log/               LogEntry (sealed) + Block/Container/Entity records
├── core/
│   ├── logging/           LoggingService + LogBuilder  ← every listener calls this
│   │                      QueuedLoggingService (async writer, monotonic ids)  [DONE, skeleton-owned]
│   ├── causal/            CausalTracker + SimpleCausalTracker  [DONE, skeleton-owned]
│   ├── storage/           Database + StorageException; sqlite/SqliteDatabase [SHELL]
│   ├── query/             QueryEngine[SHELL], QueryParams, LookupResult, tree/CausalNode
│   │   └── session/       QuerySession, QuerySessionManager (+Default) [partial]
│   ├── rollback/          RollbackEngine[SHELL], RollbackResult
│   └── inspect/           InspectorService (+Default)  [DONE, skeleton-owned]
├── command.BlCommand      /bl dispatcher (inspect+help functional; rest [SHELL])
└── listener/              (empty — listener subagents add here)
```

### Key design decisions

- **Monotonic, plugin-assigned ids.** `LogBuilder.commit()` returns the entry id *synchronously* on the
  main thread (from an `AtomicLong` seeded off `Database.currentMaxId()` at startup). Children link to a
  parent by passing that id to `.cause(id)`. This is what makes causal trees buildable while the actual
  DB write happens later on the writer thread. The DB must store the supplied id verbatim.
- **Async single-writer pipeline.** Listeners only enqueue (cheap, main-thread). `QueuedLoggingService`
  batches to `Database.insert(...)` on a background thread. Never do I/O in a listener.
- **BlockData strings for state.** Capture with `block.getBlockData().getAsString()` and restore with
  `Bukkit.createBlockData(str)` — no NMS. This is how "redstone repeater setting changed" is recorded
  and rolled back precisely.
- **`Actor` attribution.** Players carry a UUID; environmental causes use `#tnt`, `#piston`, `#water`,
  `minecraft:enderman`, etc. Indirect causes resolve through `CausalTracker`.

## The contract (what every part builds against)

- **Log something:** `services.logging().record(actor, ActionType.X, pos).block(...|container|entity).commit()`.
  Returns the id; pass it to child `.cause(id)`.
- **Query:** `services.queryEngine().lookup(params)` (flat) / `.tree(params)` / `.expand(nodeId)`.
- **Rollback:** `services.rollbackEngine().rollback|restore|preview(params)`.
- **Sessions:** `services.sessionManager().create|open|save|history(...)`.
- Filters are built with `QueryParams.builder()....build()`.

## Feature parts (isolated subagent tasks)

Each depends only on the contracts above and can be built independently:

1. **Storage** — implement `SqliteDatabase` (schema, WAL, batched insert, query/roots/children,
   rollback flags, purge, session persistence).
2. **Direct block listeners** — place/break/sign → `LoggingService`.
3. **Container listeners** — insert/remove, hopper transfers.
4. **Redstone / interaction listeners** — levers/buttons/doors/repeaters/comparators, redstone power.
5. **Indirect-physics listeners + causal wiring** — pistons, explosions, liquid flow, dispensers,
   entity-changed-block; populate/read `CausalTracker`.
6. **Entity/animal listeners** — kill/spawn/shear/breed/tame/leash/dye/drops.
7. **Query engine + tree rendering** — implement `DefaultQueryEngine.tree/expand`; render collapsed,
   aggregated, clickable+hover causal trees in `BlCommand`.
8. **Inspector listener** — click handling for players in inspector mode.
9. **Rollback/restore engine** — implement `DefaultRollbackEngine` (chunk-batched world edits).
10. **Commands** — flesh out `BlCommand` arg parsing + all subcommands + session navigation.

Listener parts register themselves in `BlockLogsPlugin#registerListeners()`.

## Build

Requires JDK 25 (bundled Gradle wrapper is 9.1.0).

```
./gradlew build
```

Output jar: `build/libs/BlockLogs-*.jar` → drop into a Paper 26.2 server's `plugins/`.
