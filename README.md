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
2. Requires Forgelin (in the pack). GregTech and StructureLib are only needed for the GT commands (`mte`, `mtes`,
   `gt-ores`, `pipe`, `multiblock`, MTE entries of `place-list`).
3. The server must run on Java 25 via lwjgl3ify (`@java9args.txt -jar lwjgl3ify-forgePatches.jar nogui`).
   Clients do not need the mod (`acceptableRemoteVersions = "*"`).

## Commands

Permission level 4 (RCON and console always qualify). Every subcommand replies with exactly one line:
`OK <compact-json>` or `ERR <message>`. Vanilla 1.7.10 runs RCON commands on the RCON thread; the GTNH pack's
Hodgepodge (`fixRconThreading=true`, the default) runs them on the server thread instead. bmh handles both: off the
server thread it queues the subcommand (drained at the start of each server tick) and blocks until it finishes (timeout
30 min); on the server thread it runs inline, so the server does not tick while a subcommand runs.

Files are read from and written to `<server-root>/bmh-out/<file>`; absolute paths and `..` are rejected. `<dim>` is a
numeric dimension id, loaded through `DimensionManager` if needed. `<name>` is an FML registry name `modid:name`.
`<meta>` is 0..65535 except 32767 (EndlessIDs stores 16-bit metadata). `<facing>` is a ForgeDirection ordinal 0..5 or
name (`DOWN UP NORTH SOUTH WEST EAST`, case-insensitive). Chunks that a command has to load are generated if missing
and queued for unloading (thus saving) when the command ends.

| Command | Reply | Notes |
|---|---|---|
| `bmh ping` | `{"version":"0.3.0"}` | |
| `bmh registry <file>` | `{"file","blocks","biomes","dimensions"}` | File: `{"blocks":[{"id","name"}],"biomes":[{"id","name","temperature","rainfall","color","waterColorMultiplier"}],"dimensions":[{"id","folder","providerClass","name"}]}`. Dimensions are `DimensionManager.getStaticDimensionIDs()`; unloaded ones get a fresh provider instance for class/name/folder. |
| `bmh set <dim> <x> <y> <z> <name> <meta>` | `{}` | `World.setBlock` with flag 2 (no neighbour updates); drops suppressed (`restoringBlockSnapshots`, replaced inventories emptied). A resulting tile entity is checked to be saveable and frozen (see below); if not, the block is removed and the reply is `ERR`. Also places GT ores (see "GregTech"). |
| `bmh fill <dim> <x1> <y1> <z1> <x2> <y2> <z2> <name> <meta>` | `{"count"}` | Same placement as `set` (tile entities frozen); volume ≤ 1,000,000. `count` = blocks actually changed (`setBlock` returned true). |
| `bmh pregen <dim> <cx1> <cz1> <cx2> <cz2>` | `{"chunks","ms","loadedBefore","loadedAfter","deferredTasks"}` | Inclusive chunk rectangle, ≤ 4096 chunks, in 32 × 32 batches. Each batch is loaded with a one-chunk margin so vanilla population runs and any chunk still unpopulated is populated; then work that mods defer to the next ticks and that would reload the chunks (AE2's per-world meteorite/compass queue: `deferredTasks`; ArchaicFix/Phosphor light updates) runs now, and every chunk loaded since the batch started (by anyone; `hold` regions excepted, spawn chunks stay) is saved and unloaded, repeating until nothing new loads. Hodgepodge's unload protection for fresh chunks is cleared for them, their scheduled block updates leave the world with them (they are in the saved chunk), and the command waits for the chunk IO thread. The loaded chunk count is back at its baseline when it replies (gtnh-harness-dev, rwg: 4096 chunks in ~20 s, 630 → 630 loaded, 20 TPS afterwards; before this, 630 → 4986 and 6.7 TPS). |
| `bmh stats` | `{"tickMs","tps","worlds":[{"dim","chunks","entities","tileEntities","pendingTicks","tickMs"}]}` | Mean server / per-world tick time over the last 100 ticks; per loaded world the loaded chunk, entity, tile entity and scheduled-update counts. |
| `bmh hold <dim> <cx1> <cz1> <cx2> <cz2>` | `{"chunks","held"}` | Loads (generates) the inclusive chunk rectangle (≤ 4096) and keeps it loaded until `release`, so tile entities there tick and settle and a later `dump` reads exactly what `flush` saved. Without it, a dump that has to load chunks queues them for unloading afterwards (vanilla unloads ≤ 100 per tick), and tile entities ticking meanwhile can change what gets saved. `held` = total held chunks. |
| `bmh release` | `{"chunks"}` | Queues every held chunk for unloading. |
| `bmh drop-ticks <dim> <cx1> <cz1> <cx2> <cz2>` | `{"chunks","ticks"}` | Removes the scheduled block updates of the inclusive chunk rectangle (≤ 4096), so liquids stop flowing and a held area no longer changes between `flush`, `dump` and the final save. |
| `bmh mte <dim> <x> <y> <z> <mID> <facing>` | `{"name"}` | Places `gt.blockmachines` with MetaTileEntity `mID` (e.g. 1000 = EBF controller) like a player would (see "GregTech"); `ERR` if the facing is invalid for that machine. `name` = metaName. |
| `bmh mtes <file>` | `{"file","count","types":{<type>:n}}` | Every `GregTechAPI.METATILEENTITIES` entry. File: see "GregTech". |
| `bmh gt-ores <file>` | `{"file","count","blocks"}` | Every GT ore block/meta GregTech generates. File: see "GregTech". |
| `bmh te-nbt <dim> <x> <y> <z> <file>` | the tile entity's `writeToNBT` as JSON | Merges `bmh-out/<file>` (JSON patch, or SNBT if the name ends in `.snbt`) into the tile entity. See "Tile entity NBT". |
| `bmh use <dim> <x> <y> <z> <side> <modid:item> <meta> [yaw] [count]` | `{"used","yaw","positions","placed"}` | Right-click with an item as a player. See "Use". |
| `bmh pipe <dim> <x1> <y1> <z1> <x2> <y2> <z2> <mID> [color]` | `{"name","blocks","placed","connections"}` | Pipe/cable/frame MTE on an axis-aligned line (≤ 256 blocks), consecutive blocks connected. See "GregTech". |
| `bmh multiblock <dim> <x> <y> <z> [tier] [creative\|survival\|check]` | `{"mode","formed"[,"errors"]}` + survival: `{"placed","passes","last"}` | Builds (or only checks) the multiblock of the controller at x/y/z. tier (default 1) = trigger stack size. See "GregTech". |
| `bmh zoo <dim> <x> <y> <z> <modid\|all> <file>` | `{"file","placed","failed"}` | Grid with 2-block spacing on a stone slab at y-1, ordered by block id. File: `{"placed":[{"x","y","z","name","id","meta"}],"failed":[{"name","meta","reason"}]}`. See "Zoo" below. |
| `bmh place-list <dim> <x> <y> <z> <file> <out>` | `{"file","placed","failed","box"}` | Places the blocks/MTEs listed in `<file>` on a zoo grid; manifest in `<out>`. See "Place list" below. |
| `bmh dump <dim> <x1> <y1> <z1> <x2> <y2> <z2> <file> [air]` | `{"file","lines"}` | NDJSON `{"x","y","z","id","name","meta","te"}` from the live world; `te` is the tile entity NBT as JSON (numeric types not preserved) or null. Air is skipped unless `air`. |
| `bmh flush` | `{"ms"}` | `saveAllChunks` + `saveChunkData` for every loaded world (like `save-all flush`), waits for the chunk IO thread, closes cached region files. |

### Frozen tile entities

Tile entities placed by `set`, `fill`, `zoo` and block entries of `place-list` (not GT MTEs, which must tick) stay in
their chunk, so they are saved, dumped
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

### Place list

`bmh place-list <dim> <x> <y> <z> <file> <out>` places a JSON array read from `bmh-out/<file>` on the zoo grid (same
layout, population, stone floor at y-1 and dropped scheduled updates), entry *i* at
`(x + 2 (i % side), y, z + 2 (i / side))`, `side = ceil(sqrt(n))`. Entries:

```json
[
  {"name": "gregtech:gt.blockores2", "meta": 16006},
  {"mte": 1000, "facing": "NORTH"},
  {"mte": 28001, "facing": ["NORTH", "UP", "DOWN"]}
]
```

- `{"name", "meta"}` (meta defaults to 0, any 0..65535 except 32767): placed exactly like a zoo variant (frozen tile
  entity, meta restored, probes).
- `{"mte", "facing"}`: placed like `bmh mte`; `facing` is an ordinal/name or a list of them, the first one the
  machine accepts is used (default `NORTH`; ignored for pipes, which `bmh pipe` connects).
- `{"use": u}` or `{"use": [u, ...]}`, `u = {"item": "modid:item", "meta": 0, "count": 1, "side": "UP", "yaw": <default>,
  "at": [dx, dy, dz]}`: right-clicks like `bmh use`, in order; the clicked block is the cell plus `at` (default
  `[0, -1, 0]`, the floor, so side `UP` places into the cell). Example (cable with an export bus on its north side):
  `{"use": [{"item": "appliedenergistics2:item.ItemMultiPart", "meta": 16},
  {"item": "appliedenergistics2:item.ItemMultiPart", "meta": 260, "side": "NORTH", "at": [0, 0, 0]}]}`. Resulting
  tile entities tick (not frozen). The manifest record adds `"used": [bool, ...]`.
- Any entry may add `"teNbt": <patch>` (JSON object or SNBT string, see "Tile entity NBT"), applied to the tile entity
  in the cell after placing; the manifest record then adds `"te"` (its NBT afterwards).

Manifest `<out>`:
`{"placed":[{"index","x","y","z","name","id","meta"[,"mte","metaName","facing"]}],"failed":[{"index",<entry fields>,"reason"}]}`;
`facing` is the ordinal actually set (null for pipes). `box` in the reply is `[x1,y1,z1,x2,y2,z2]` = floor plus a
one-block margin up to y+3, ready for `bmh dump`.

### Tile entity NBT

`bmh te-nbt <dim> <x> <y> <z> <file>` reads `bmh-out/<file>`, merges it into the tile entity's `writeToNBT` output
(`x`, `y`, `z`, `id` are always kept), calls `readFromNBT`, `markDirty`, marks the block for update, checks that the
tile entity is still saveable (else the block is removed and the reply is `ERR`) and keeps a frozen tile entity frozen
(D14). Reply: the resulting `writeToNBT` as JSON (lossy, as in `dump`).

- **SNBT** (file name ends in `.snbt`): 1.7.10 `JsonToNBT` syntax, e.g. `{facing:4b,Items:[{Slot:0b,id:264s,Count:5b,Damage:0s}]}`
  (`b` byte, `s` short, none int, `L` long, `f` float, `d` double; compounds merge recursively, other tags replace).
- **JSON** (any other name): an object merged key by key. Value typing, first match wins:
  1. typed wrapper: `{"@byte": 1}`, `{"@short": 2}`, `{"@int": 3}`, `{"@long": 4}` (number or decimal string),
     `{"@float": 1.5}`, `{"@double": 1.5}`, `{"@string": "x"}`, `{"@bytes": [1, 2]}`, `{"@ints": [1, 2]}`,
     `{"@list": [...]}`;
  2. the type of the tag already at that key (numbers/booleans converted to it; arrays become a byte/int array, or a
     list whose elements take the existing list's element type);
  3. defaults: integral number → int (long if out of int range), other number → double, boolean → byte, string →
     string, array → list, object → compound.
  Objects merge into an existing compound recursively; `null` removes the key; lists and arrays are replaced whole.
  Example: `{"facing": 3, "Items": [{"Slot": {"@byte": 0}, "id": {"@short": 264}, "Count": {"@byte": 5}, "Damage": {"@short": 0}}]}`.

### Use

`bmh use <dim> <x> <y> <z> <side> <modid:item> <meta> [yaw] [count]` right-clicks face `side` (ordinal or name) of
block x/y/z the way the server handles a player's click: the fake player `[Minecraft]` stands 2 blocks out from that
face (feet level with the block for horizontal faces), at `yaw` (Minecraft degrees: 0 = looking south/+z, 90 = west,
180 = north, 270 = east; default: looking at the face) and pitch 0, holding `count` (default 1) × `item:meta` in slot
0; then `Item.onItemUseFirst` and, if that did not handle it, `ItemStack.tryPlaceItemIntoWorld` (→ `Item.onItemUse`,
with Forge's block snapshots and place event), hit point at the centre of the face. The clicked block's
`onBlockActivated` is not called (as when sneaking). Placed tile entities are not frozen (they tick; mods such as AE2
and EnderIO finish their NBT on the first ticks) and must be saveable.

Reply: `{"used": bool, "yaw", "positions": [<clicked>, <adjacent>], "placed": <the adjacent record if it changed,
else the clicked one if it changed, else null>}`; each record is `{"x","y","z","name","id","meta","te","role",
"changed"}` with `te` the tile entity NBT as JSON. Verified on GTNH 2.9.0-RC-2 (block facing = towards the player):

| Item | Example | Result |
|---|---|---|
| AE2 fluix glass cable | `use 0 102 4 102 UP appliedenergistics2:item.ItemMultiPart 16` | `tile.BlockCableBus`, `def:6` = cable |
| AE2 import bus on the cable's north side | `use 0 102 5 102 NORTH appliedenergistics2:item.ItemMultiPart 240` | clicked block changes, `def:2` = bus |
| EnderIO item conduit | `use 0 102 4 106 UP EnderIO:itemItemConduit 0` (then x 103, 104) | `blockConduitBundle`; `connections` become two-sided after a tick |
| Iron chest facing east | `use 0 106 4 102 UP IronChest:BlockIronChest 0 90` | `facing: 5` |
| BiblioCraft bookcase facing south | `use 0 108 4 102 UP BiblioCraft:Bibliotheca 0 180` | `bookcaseAngle: 3` (yaw 0/90/180/270 → 1/2/3/0) |

### GregTech

- **`mte` placement** mirrors `ItemMachines.placeBlockAt` with the fake player `[Minecraft]` (UUID
  `41c82c87-7afb-4024-ba57-13d2c99cae77`) as owner: `setInitialValuesAsNBT`, owner, front facing, then
  `initDefaultModes` (which reads the owner's client preferences). As for players, a basic machine (`MTEBasicMachine`)
  then turns the requested facing into its main (front) facing `mMainFacing` and moves `mFacing` (output side) to the
  opposite side. MTEs that accept no facing at all (crop manager, modification table) keep their default facing; the
  reported `facing` is the one set. MTE tile entities are not frozen: they tick like in a real world (multiblocks
  form, pipes sync), and are checked to be saveable.
- **`mtes` file:** `{"mtes":[{"id","metaName","class","type","baseType"[,"tier"][,"thickness","material"]
  [,"insulated"|"pipeAmount"|"restrictive"]}]}`, ordered by id. `type` (most specific first): `cable`, `fluid_pipe`,
  `item_pipe`, `frame`, `pipe` (other `MetaPipeEntity`, e.g. TecTech data/laser pipes), `multiblock`
  (`MTEMultiBlockBase` or any constructable MTE), `hatch`, `machine` (`MTEBasicMachine`), `generator`, `boiler`,
  `battery_buffer`, `transformer`, `hull`, `tank`, `chest`, `other`. `baseType` is the `gt.blockmachines` block meta,
  `tier` the `MTETieredMachineBlock` tier, `thickness` the pipe thickness, `material` the pipe's GT material name.
- **Ores** (`gt.blockores2..7`, no tile entity): `meta = stoneIndex * 1000 + materialId (+ 8000 natural) (+ 16000
  small)`; `stoneIndex` indexes the block's stone types (ores2: Stone, Netherrack, Endstone, BlackGranite,
  RedGranite, Marble, Basalt, Moon). `set`/`fill`/`place-list` take the full meta. `gt-ores` file:
  `{"ores":[{"name","meta","material","materialId","stone","stoneIndex","small"}]}` — every combination that
  `GTOreAdapter.supports` (normal and small, not natural).
- **`pipe`** places the pipe MTE on every block of the line (blocks that already hold the same MTE are kept, so a line
  that starts/ends on another line of the same pipe makes a T or cross), then calls `IConnectable.connect(side)` from
  each block to the next, as player placement against a pipe / wrenching does; GT connects back. With GT6-style pipes
  (GTNH default) nothing else connects, so neighbouring lines stay separate. The connection mask is copied to
  `BaseMetaPipeEntity.mConnections` right away (GT itself syncs it after 10 ticks), so it is in the saved NBT.
  `color` -1 (default, none) or 0..15 (dye index; GT stores `mColor = color + 1`) is applied to newly placed blocks;
  GT only connects equal or uncoloured pipes. `connections` = connections made along the line. Some pipes keep their
  own rules when they tick (as in a real world): TecTech data/laser pipes recompute connections and only connect when
  painted, frames never connect, the nanochip vacuum conveyor pipe drops some connections.
- **`multiblock`** loads the chunks within 2 chunks of the controller. `creative` (default) calls
  `IConstructable.construct(stick × tier, false)` like the hologram projector for creative players: casings only, no
  hatches. `survival` calls `ISurvivalConstructable.survivalConstruct` repeatedly (budget 4096, ≤ 256 passes) with the
  `gt_hatch` channel set, the fake player as actor and an unlimited item source (any exact stack; predicate requests
  get the first matching `gt.blockmachines` MTE by id, skipping debug ones, then any block with meta 0..15) — what a
  survival player with the projector and a full inventory gets: all blocks plus the minimum set of hatches, so the
  multiblock forms. Survival placement only fills replaceable blocks (air). `last`: -1 complete, 0 nothing more
  placeable, > 0 pass cap hit. `check` builds nothing (use it after placing missing hatches with `mte`). `formed` is
  `mMachine` after `checkStructure(true)` for `MTEMultiBlockBase`, else null; when false, `errors` lists GT's
  structure errors as `"<StructureErrorId>: <text>"`.
