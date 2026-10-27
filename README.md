# bmh — BlueMap GTNH test harness mod

Server-side Forge 1.7.10 mod (modId `bmh`, Kotlin) for GT New Horizons 2.9.0-RC-2. It adds the `/bmh` command, which
the orchestrator in `../orchestrator` drives over RCON to build test scenes and dump world state. Not meant for players.

## Build

GTNHGradle (ExampleMod1.7.10 layout), `enableModernJavaSyntax = modern` (Java 25 bytecode, no downgrading),
Kotlin Gradle plugin forced to 2.3.21 in `settings.gradle.kts` (2.2.21 bundled with GTNHGradle cannot target JVM 25),
Kotlin `apiVersion`/`languageVersion` 2.1 because the runtime stdlib is Forgelin 2.0.3-GTNH's 2.1.10.

```bash
cd BlueMap-GTNH/testing/harness-mod
mise exec java@temurin-25.0.5+8.0.LTS -- ./gradlew \
  -Porg.gradle.java.installations.paths=$(mise where java@zulu-8.98.0.107)/Contents/Home,$(mise where java@temurin-25.0.5+8.0.LTS)/Contents/Home \
  build
```

The first build decompiles Minecraft (`setupDecompWorkspace`, ~12 min); later builds take seconds.

Output (reobfuscated, the one to install): `build/libs/bmh-<version>.jar`. `bmh-<version>-dev.jar` is the deobfuscated
dev jar.

## Install

1. Copy `build/libs/bmh-<version>.jar` into the server's `mods/`.
2. Requires Forgelin (in the pack). GregTech and StructureLib are only needed for `mte` / `multiblock`.
3. The server must run on Java 25 via lwjgl3ify (`@java9args.txt -jar lwjgl3ify-forgePatches.jar nogui`).
   Clients do not need the mod (`acceptableRemoteVersions = "*"`).

## Commands

Permission level 4 (RCON and console always qualify). Every subcommand replies with exactly one line:
`OK <compact-json>` or `ERR <message>`. RCON runs commands on the RCON thread in 1.7.10, so the mod queues each
subcommand onto the server thread (drained at the start of each server tick) and blocks until it finishes (timeout
30 min).

Files are written to `<server-root>/bmh-out/<file>`; absolute paths and `..` are rejected. `<dim>` is a numeric dimension
id, loaded through `DimensionManager` if needed. `<name>` is an FML registry name `modid:name`. `<meta>` is
0..65535 except 32767 (EndlessIDs stores 16-bit metadata). Chunks that a command has to load are generated if missing
and queued for unloading (thus saving) when the command ends.

| Command | Reply | Notes |
|---|---|---|
| `bmh ping` | `{"version":"0.1.0"}` | |
| `bmh registry <file>` | `{"file","blocks","biomes","dimensions"}` | File: `{"blocks":[{"id","name"}],"biomes":[{"id","name","temperature","rainfall","color","waterColorMultiplier"}],"dimensions":[{"id","folder","providerClass","name"}]}`. Dimensions are `DimensionManager.getStaticDimensionIDs()`; unloaded ones get a fresh provider instance for class/name/folder. |
| `bmh set <dim> <x> <y> <z> <name> <meta>` | `{}` | `World.setBlock` with flag 2 (no neighbour updates); drops suppressed (`restoringBlockSnapshots`, replaced inventories emptied). A resulting tile entity is checked to be saveable and frozen (see below); if not, the block is removed and the reply is `ERR`. |
| `bmh fill <dim> <x1> <y1> <z1> <x2> <y2> <z2> <name> <meta>` | `{"count"}` | Same placement as `set` (tile entities frozen); volume ≤ 1,000,000. `count` = blocks actually changed (`setBlock` returned true). |
| `bmh pregen <dim> <cx1> <cz1> <cx2> <cz2>` | `{"chunks","ms"}` | Inclusive chunk rectangle, ≤ 4096 chunks. Loads it with a one-chunk margin so vanilla population runs, then populates any chunk still unpopulated. |
| `bmh mte <dim> <x> <y> <z> <mID> <facing>` | `{"name"}` | Places `gt.blockmachines` with MetaTileEntity `mID` (e.g. 1000 = EBF controller), facing = ForgeDirection ordinal 0..5; `ERR` if the facing is invalid for that machine. |
| `bmh multiblock <dim> <x> <y> <z> [tier]` | `{}` | `IConstructable.construct(ItemStack(stick, tier), false)` inside `StructureLibAPI.startHinting/endHinting`; tier defaults to 1. Hatches may be missing. |
| `bmh zoo <dim> <x> <y> <z> <modid\|all> <file>` | `{"file","placed","failed"}` | Grid with 2-block spacing on a stone slab at y-1, ordered by block id. File: `{"placed":[{"x","y","z","name","id","meta"}],"failed":[{"name","meta","reason"}]}`. See "Zoo" below. |
| `bmh dump <dim> <x1> <y1> <z1> <x2> <y2> <z2> <file> [air]` | `{"file","lines"}` | NDJSON `{"x","y","z","id","name","meta","te"}` from the live world; `te` is the tile entity NBT as JSON (numeric types not preserved) or null. Air is skipped unless `air`. |
| `bmh flush` | `{"ms"}` | `saveAllChunks` + `saveChunkData` for every loaded world (like `save-all flush`), waits for the chunk IO thread, closes cached region files. |

### Frozen tile entities

Tile entities placed by `set`, `fill` and `zoo` (not `mte`/`multiblock`) stay in their chunk, so they are saved, dumped
and rendered, but are removed from `World.loadedTileEntityList`/`addedTileEntityList`, so they never tick. Their
positions are stored per dimension in `data/bmh_frozen_tiles.dat` and re-frozen on every chunk load (also after a
restart). Before freezing, `writeToNBT` plus binary NBT serialization is tried once: a tile entity that fails (e.g.
a null child tag, which otherwise silently keeps its whole chunk from ever being written) is removed with its block.
Mod hooks that run outside the tile entity tick (AE2 init on chunk load, IC2 energy net, OpenComputers addresses)
can still change tile entity NBT or block meta after a reload.

### Zoo

- **Variants:** `Item.getSubItems` is client-only and does not exist on a dedicated server. The zoo uses meta 0
  unless the ItemBlock has subtypes; otherwise metas 0..15 for which `block.damageDropped(m) == m` on each of 32 calls
  (some blocks choose drops at random), keeping one meta per distinct unlocalized stack name when names differ between
  metas. Metas above 15 are never enumerated.
- **Before placing:** the chunks under the grid (plus one chunk towards -x/-z) are populated, so world decoration
  cannot later overwrite the zoo.
- **Per block:** placed as with `set` (tile entity frozen). If the block changed its own meta in `onBlockAdded`
  (random/surroundings-based facing) the requested meta is restored. Then common `Block` methods are probed (light,
  hardness, collision, material, map colour), because other mods call them on chunk load/unload (e.g. BuildCraft's
  robot map) and unsupported metas crash there. A block that throws, does not stay in place, or has an unsaveable
  tile entity is removed and listed under `failed` with the reason.
- **After placing:** scheduled block updates in the grid's chunks are dropped (no flowing liquids, falling blocks,
  fire spread). Random ticks only happen near players.
- **Determinism:** two fresh worlds give identical manifests and identical blocks/metas in the zoo cells; tile entity
  NBT can still differ by mod-generated random ids. The default `FLAT` preset (`2;7,2x3,2;1;village`) generates
  villages whose layout differs between fresh worlds; they sit between the zoo cells unless the preset omits
  `village` (e.g. `generator-settings=2;7,2x3,2;1;`).
