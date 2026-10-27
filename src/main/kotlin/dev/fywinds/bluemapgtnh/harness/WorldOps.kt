package dev.fywinds.bluemapgtnh.harness

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import cpw.mods.fml.common.registry.GameData
import net.minecraft.block.Block
import net.minecraft.init.Blocks
import net.minecraft.inventory.IInventory
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompressedStreamTools
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.server.MinecraftServer
import net.minecraft.world.MinecraftException
import net.minecraft.world.WorldServer
import net.minecraft.world.biome.BiomeGenBase
import net.minecraft.world.chunk.Chunk
import net.minecraft.world.chunk.storage.RegionFileCache
import net.minecraft.world.storage.ThreadedFileIOBase
import net.minecraftforge.common.DimensionManager
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Thrown for user errors; becomes `ERR <message>`. */
class BmhException(message: String) : RuntimeException(message)

/** All world access. Must run on the server thread. */
object WorldOps {
    const val FILL_CAP = 1_000_000L
    const val PREGEN_CAP = 4096L
    const val OUT_DIR = "bmh-out"
    private const val DROP_PROBES = 32

    /** Block-update flags for World.setBlock: 2 = send to clients, no neighbour notification. */
    private const val SET_FLAGS = 2

    // ---- shared helpers ----

    fun world(dim: Int): WorldServer {
        DimensionManager.getWorld(dim)?.let { return it }
        if (!DimensionManager.isDimensionRegistered(dim)) throw BmhException("dimension $dim is not registered")
        DimensionManager.initDimension(dim)
        return DimensionManager.getWorld(dim) ?: throw BmhException("failed to load dimension $dim")
    }

    fun block(name: String): Block {
        val registry = GameData.getBlockRegistry()
        if (!registry.containsKey(name)) throw BmhException("unknown block $name")
        return registry.getObject(name) ?: throw BmhException("unknown block $name")
    }

    fun blockName(block: Block): String? = GameData.getBlockRegistry().getNameForObject(block)

    fun checkMeta(meta: Int) {
        if (meta !in 0..65535 || meta == 32767) throw BmhException("meta $meta out of range (0..65535 except 32767)")
    }

    fun checkY(world: WorldServer, y: Int) {
        if (y !in 0 until world.height) throw BmhException("y $y outside 0..${world.height - 1}")
    }

    /** Resolves `<server-root>/bmh-out/<relative>`, rejecting absolute paths and `..`. */
    private fun harnessFile(relative: String): File {
        val normalized = relative.replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/") || File(relative).isAbsolute ||
            normalized.split('/').any { it == ".." || it.isEmpty() }
        ) {
            throw BmhException("invalid path $relative (relative to $OUT_DIR, no '..')")
        }
        val root = MinecraftServer.getServer().getFile(OUT_DIR).canonicalFile
        val file = File(root, normalized).canonicalFile
        if (!file.toPath().startsWith(root.toPath())) throw BmhException("invalid path $relative")
        return file
    }

    /** Output file `<server-root>/bmh-out/<relative>`; parent directories are created. */
    fun outFile(relative: String): File = harnessFile(relative).also { it.parentFile.mkdirs() }

    /** Existing input file `<server-root>/bmh-out/<relative>`. */
    fun inFile(relative: String): File =
        harnessFile(relative).also { if (!it.isFile) throw BmhException("input file $OUT_DIR/$relative does not exist") }

    /** Chunks loaded by the current command that were not loaded before; unloaded again by [releaseChunks]. */
    private val loadedByCommand = LinkedHashMap<WorldServer, LinkedHashSet<Long>>()

    /**
     * Loads (generating if needed) the chunk containing block x/z. Needed because on the GTNH server World.setBlock /
     * getBlock on a chunk that is not loaded neither loads it nor stores anything (observed: blocks read back as air).
     */
    fun ensureChunk(world: WorldServer, x: Int, z: Int): Chunk = ensureChunkAt(world, x shr 4, z shr 4)

    fun ensureChunkAt(world: WorldServer, cx: Int, cz: Int): Chunk {
        val provider = world.theChunkProviderServer
        if (!provider.chunkExists(cx, cz)) {
            loadedByCommand.getOrPut(world) { LinkedHashSet() } += (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
        }
        return provider.loadChunk(cx, cz)
    }

    /** Queues the chunks loaded by this command for unloading (which saves them). Called after every command. */
    fun releaseChunks() {
        for ((world, chunks) in loadedByCommand) {
            for (key in chunks) world.theChunkProviderServer.unloadChunksIfNotNearSpawn((key shr 32).toInt(), key.toInt())
        }
        loadedByCommand.clear()
    }

    /** Chunks kept loaded by `bmh hold` until `bmh release`. */
    private val held = LinkedHashMap<WorldServer, LinkedHashSet<Long>>()

    /**
     * Loads (generating if needed) the inclusive chunk rectangle and keeps it loaded, so its tile entities tick and the
     * world settles before a flush, and a dump reads exactly what the flush saved (a dump that has to load chunks
     * releases them afterwards, at most 100 per tick, so ticking tile entities can change them before they are saved).
     */
    fun hold(dim: Int, cx1: Int, cz1: Int, cx2: Int, cz2: Int): JsonObject {
        val world = world(dim)
        val (xa, xb) = min(cx1, cx2) to max(cx1, cx2)
        val (za, zb) = min(cz1, cz2) to max(cz1, cz2)
        val count = (xb - xa + 1).toLong() * (zb - za + 1)
        if (count > PREGEN_CAP) throw BmhException("hold of $count chunks exceeds $PREGEN_CAP")
        val keys = held.getOrPut(world) { LinkedHashSet() }
        for (cx in xa..xb) for (cz in za..zb) {
            ensureChunkAt(world, cx, cz) // also takes it off the provider's unload queue
            keys += (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
        }
        loadedByCommand[world]?.removeAll(keys)
        return obj("chunks" to count, "held" to held.values.sumOf { it.size })
    }

    /** Queues every held chunk for unloading. */
    fun release(): JsonObject {
        var count = 0
        for ((world, chunks) in held) {
            for (key in chunks) world.theChunkProviderServer.unloadChunksIfNotNearSpawn((key shr 32).toInt(), key.toInt())
            count += chunks.size
        }
        held.clear()
        return obj("chunks" to count)
    }

    /** Removes the scheduled block updates (pending ticks) of the chunks in the rectangle; returns how many. */
    private fun dropTicks(world: WorldServer, xa: Int, za: Int, xb: Int, zb: Int): Int {
        var count = 0
        for (cx in xa..xb) for (cz in za..zb) {
            count += world.getPendingBlockUpdates(ensureChunkAt(world, cx, cz), true)?.size ?: 0
        }
        return count
    }

    /**
     * Drops the scheduled block updates of the inclusive chunk rectangle (≤ 4096 chunks): liquids stop flowing,
     * falling blocks stay, so a world whose dumped chunks are held does not change between `flush`, `dump` and the
     * final save (e.g. lava still flowing in generated caves).
     */
    fun dropTicksCommand(dim: Int, cx1: Int, cz1: Int, cx2: Int, cz2: Int): JsonObject {
        val world = world(dim)
        val (xa, xb) = min(cx1, cx2) to max(cx1, cx2)
        val (za, zb) = min(cz1, cz2) to max(cz1, cz2)
        val count = (xb - xa + 1).toLong() * (zb - za + 1)
        if (count > PREGEN_CAP) throw BmhException("drop-ticks of $count chunks exceeds $PREGEN_CAP")
        return obj("chunks" to count, "ticks" to dropTicks(world, xa, za, xb, zb))
    }

    /**
     * Places a block without neighbour notifications and without item drops from the replaced block.
     * With EndlessIDs installed `World.setBlock` stores the full 16-bit meta.
     *
     * With [freeze], a resulting tile entity is frozen (see [FrozenTiles]) after checking that it can be saved
     * (`writeToNBT` and binary NBT serialization, which catches e.g. null child tags); otherwise the block is removed
     * again and a [BmhException] carrying the reason is thrown. An unserializable tile entity silently prevents its
     * whole chunk from being written to disk.
     */
    fun place(world: WorldServer, x: Int, y: Int, z: Int, block: Block, meta: Int, freeze: Boolean = true): Boolean {
        ensureChunk(world, x, z)
        val old = world.getTileEntity(x, y, z)
        if (old is IInventory) {
            for (slot in 0 until old.sizeInventory) old.setInventorySlotContents(slot, null)
        }
        val changed = withoutDrops(world) { world.setBlock(x, y, z, block, meta, SET_FLAGS) }
        val tile = world.getTileEntity(x, y, z)
        if (tile == null || !freeze) {
            FrozenTiles.unfreeze(world, x, y, z)
            return changed
        }
        checkSaveable(world, x, y, z)
        FrozenTiles.freeze(world, x, y, z, tile)
        return changed
    }

    /**
     * Throws a [BmhException] (after removing the block) if the tile entity at x/y/z fails `writeToNBT` or binary NBT
     * serialization. Such a tile entity would silently keep its whole chunk from being written.
     */
    fun checkSaveable(world: WorldServer, x: Int, y: Int, z: Int) {
        val tile = world.getTileEntity(x, y, z) ?: return
        try {
            val tag = NBTTagCompound()
            tile.writeToNBT(tag)
            CompressedStreamTools.write(tag, DataOutputStream(OutputStream.nullOutputStream()))
        } catch (t: Throwable) {
            removeBroken(world, x, y, z)
            throw BmhException("tile entity ${tile.javaClass.name} cannot be saved: $t")
        }
    }

    /** Forge: Block.dropBlockAsItem / spawnAsEntity do nothing while `restoringBlockSnapshots` is set. */
    private inline fun <T> withoutDrops(world: WorldServer, action: () -> T): T {
        val restoring = world.restoringBlockSnapshots
        world.restoringBlockSnapshots = true
        try {
            return action()
        } finally {
            world.restoringBlockSnapshots = restoring
        }
    }

    /** Best-effort removal of a block whose tile entity is broken, so a later save never touches it. */
    fun removeBroken(world: WorldServer, x: Int, y: Int, z: Int) {
        runCatching { world.removeTileEntity(x, y, z) }
        runCatching { withoutDrops(world) { world.setBlock(x, y, z, Blocks.air, 0, SET_FLAGS) } }
        runCatching { world.getChunkFromBlockCoords(x, z).removeTileEntity(x and 15, y, z and 15) }
        FrozenTiles.unfreeze(world, x, y, z)
    }

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().also { o ->
        for ((k, v) in pairs) when (v) {
            null -> o.add(k, JsonNull.INSTANCE)
            is Number -> o.addProperty(k, v)
            is String -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            is com.google.gson.JsonElement -> o.add(k, v)
            else -> o.addProperty(k, v.toString())
        }
    }

    // ---- subcommands ----

    fun ping(): JsonObject = obj("version" to BmhMod.version)

    fun registry(file: String): JsonObject {
        val blocks = JsonArray()
        val blockRegistry = GameData.getBlockRegistry()
        blockRegistry.typeSafeIterable()
            .map { Block.getIdFromBlock(it) to blockName(it) }
            .sortedBy { it.first }
            .forEach { (id, name) -> blocks.add(obj("id" to id, "name" to name)) }

        val biomes = JsonArray()
        for (biome in BiomeGenBase.getBiomeGenArray()) {
            if (biome == null) continue
            biomes.add(
                obj(
                    "id" to biome.biomeID,
                    "name" to biome.biomeName,
                    "temperature" to biome.temperature,
                    "rainfall" to biome.rainfall,
                    "color" to biome.color,
                    "waterColorMultiplier" to biome.waterColorMultiplier,
                ),
            )
        }

        val dimensions = JsonArray()
        for (id in DimensionManager.getStaticDimensionIDs().sorted()) {
            val provider = DimensionManager.getWorld(id)?.provider
                ?: runCatching { DimensionManager.createProviderFor(id) }.getOrNull()
            dimensions.add(
                obj(
                    "id" to id,
                    "folder" to provider?.let { runCatching { it.saveFolder }.getOrNull() },
                    "providerClass" to (provider?.javaClass?.name ?: "unknown"),
                    "name" to (provider?.let { runCatching { it.dimensionName }.getOrNull() } ?: "unknown"),
                ),
            )
        }

        val root = obj("blocks" to blocks, "biomes" to biomes, "dimensions" to dimensions)
        outFile(file).writeText(root.toString(), StandardCharsets.UTF_8)
        return obj("file" to file, "blocks" to blocks.size(), "biomes" to biomes.size(), "dimensions" to dimensions.size())
    }

    fun set(dim: Int, x: Int, y: Int, z: Int, name: String, meta: Int): JsonObject {
        val world = world(dim)
        checkY(world, y)
        checkMeta(meta)
        place(world, x, y, z, block(name), meta)
        return JsonObject()
    }

    fun fill(dim: Int, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, name: String, meta: Int): JsonObject {
        val world = world(dim)
        val (xa, xb) = min(x1, x2) to max(x1, x2)
        val (ya, yb) = min(y1, y2) to max(y1, y2)
        val (za, zb) = min(z1, z2) to max(z1, z2)
        checkY(world, ya)
        checkY(world, yb)
        checkMeta(meta)
        val volume = (xb - xa + 1).toLong() * (yb - ya + 1) * (zb - za + 1)
        if (volume > FILL_CAP) throw BmhException("fill volume $volume exceeds $FILL_CAP")
        val block = block(name)
        var count = 0
        for (x in xa..xb) for (z in za..zb) for (y in ya..yb) {
            if (place(world, x, y, z, block, meta)) count++
        }
        return obj("count" to count)
    }

    /**
     * Generates and populates every chunk of the inclusive rectangle. A chunk is populated by vanilla once it and its
     * +x, +z and +x+z neighbours exist, so the rectangle is loaded with a one-chunk margin; anything still unpopulated
     * afterwards is populated explicitly. Chunks that were not loaded before are unloaded again after the command.
     */
    fun pregen(dim: Int, cx1: Int, cz1: Int, cx2: Int, cz2: Int): JsonObject {
        val start = System.nanoTime()
        val world = world(dim)
        val (xa, xb) = min(cx1, cx2) to max(cx1, cx2)
        val (za, zb) = min(cz1, cz2) to max(cz1, cz2)
        val count = (xb - xa + 1).toLong() * (zb - za + 1)
        if (count > PREGEN_CAP) throw BmhException("pregen of $count chunks exceeds $PREGEN_CAP")
        populate(world, xa, za, xb, zb)
        return obj("chunks" to count, "ms" to (System.nanoTime() - start) / 1_000_000)
    }

    private fun populate(world: WorldServer, xa: Int, za: Int, xb: Int, zb: Int) {
        val provider = world.theChunkProviderServer
        for (cx in xa - 1..xb + 1) for (cz in za - 1..zb + 1) ensureChunkAt(world, cx, cz)
        for (cx in xa..xb) for (cz in za..zb) {
            if (!ensureChunkAt(world, cx, cz).isTerrainPopulated) provider.populate(provider, cx, cz)
        }
    }

    /**
     * Meta variants of a block for the zoo. `Item.getSubItems` is @SideOnly(CLIENT) and absent on a dedicated server,
     * so: metas 0..15 for which the block drops itself with that meta (`damageDropped(m) == m`), and, when the ItemBlock
     * names its stacks differently per meta, only the first meta of each distinct unlocalized name (drops clamped
     * metas such as planks 6..15). Falls back to meta 0. Some blocks pick their drop at random (e.g. LootGames dungeon
     * lights), so `damageDropped` must return the meta on every one of [DROP_PROBES] calls; this keeps the zoo layout
     * identical between runs.
     */
    fun variants(block: Block): List<Int> {
        val item = Item.getItemFromBlock(block) ?: return listOf(0)
        if (!item.hasSubtypes) return listOf(0)
        val metas = (0..15).filter { meta ->
            runCatching { (1..DROP_PROBES).all { block.damageDropped(meta) == meta } }.getOrDefault(false)
        }
        val names = metas.associateWith { meta -> runCatching { item.getUnlocalizedName(ItemStack(item, 1, meta)) }.getOrNull() }
        val distinct = if (names.values.toSet().size > 1) metas.distinctBy { names[it] } else metas
        return distinct.ifEmpty { listOf(0) }
    }

    fun zoo(dim: Int, x0: Int, y: Int, z0: Int, modid: String, file: String): JsonObject {
        val world = world(dim)
        val prefix = "$modid:"
        val blocks = GameData.getBlockRegistry().typeSafeIterable()
            .filter { it !== Blocks.air }
            .mapNotNull { b -> blockName(b)?.let { b to it } }
            .filter { (_, name) -> modid == "all" || name.startsWith(prefix) }
            .sortedBy { (b, _) -> Block.getIdFromBlock(b) }
        if (blocks.isEmpty()) throw BmhException("no blocks registered for $modid")

        val entries = blocks.flatMap { (b, name) ->
            val metas = runCatching { variants(b) }.getOrElse { listOf(0) }
            metas.map { Triple(b, name, it) }
        }
        val grid = Grid.prepare(world, x0, y, z0, entries.size)
        val placed = JsonArray()
        val failed = JsonArray()
        entries.forEachIndexed { index, (b, name, meta) ->
            val x = grid.x(index)
            val z = grid.z(index)
            try {
                val actual = placeVariant(world, x, y, z, b, meta)
                placed.add(obj("x" to x, "y" to y, "z" to z, "name" to name, "id" to Block.getIdFromBlock(b), "meta" to actual))
            } catch (t: Throwable) {
                val reason = failure(t)
                failed.add(obj("name" to name, "meta" to meta, "reason" to reason))
                BmhMod.log.warn("zoo: failed to place {}:{} at {},{},{}: {}", name, meta, x, y, z, reason)
                removeBroken(world, x, y, z)
            }
        }
        grid.dropScheduledUpdates()
        outFile(file).writeText(obj("placed" to placed, "failed" to failed).toString(), StandardCharsets.UTF_8)
        return obj("file" to file, "placed" to placed.size(), "failed" to failed.size())
    }

    private fun failure(t: Throwable): String = if (t is BmhException) t.message ?: "failed" else t.toString()

    /**
     * Places a block variant as the zoo does: with a frozen tile entity, checking that it sticks, restoring the
     * requested meta if onBlockAdded changed it, and probing common Block methods. Returns the meta in the world.
     */
    private fun placeVariant(world: WorldServer, x: Int, y: Int, z: Int, block: Block, meta: Int): Int {
        place(world, x, y, z, block, meta)
        if (world.getBlock(x, y, z) !== block) throw BmhException("block did not stick")
        // Some blocks re-orient themselves at random or by surroundings in onBlockAdded; restore the variant.
        if (world.getBlockMetadata(x, y, z) != meta) world.setBlockMetadataWithNotify(x, y, z, meta, SET_FLAGS)
        probe(world, x, y, z, block, meta)
        return world.getBlockMetadata(x, y, z)
    }

    /**
     * Square grid with 2-block spacing at height [y] starting at x0/z0, entry i at (x0 + 2 (i % side), z0 + 2 (i / side)).
     * [prepare] populates the chunks first, so world decoration (trees, villages, ores) cannot later overwrite the grid
     * (population of chunk (cx, cz) decorates blocks offset by +8, so the chunks one step towards -x/-z are populated
     * too), and lays a continuous stone floor at y-1.
     */
    class Grid private constructor(private val world: WorldServer, val x0: Int, val y: Int, val z0: Int, count: Int) {
        private val side = max(1, ceil(sqrt(count.toDouble())).toInt())
        private val rows = max(1, (count + side - 1) / side)
        private val xMax = x0 + 2 * (side - 1)
        private val zMax = z0 + 2 * (rows - 1)

        fun x(index: Int) = x0 + 2 * (index % side)

        fun z(index: Int) = z0 + 2 * (index / side)

        /** Floor plus a one-block margin, up to 3 blocks above the grid: `[x1, y1, z1, x2, y2, z2]`. */
        fun box(): JsonArray = JsonArray().also { a ->
            for (v in intArrayOf(x0 - 1, y - 1, z0 - 1, xMax + 1, y + 3, zMax + 1)) a.add(JsonPrimitive(v))
        }

        /**
         * Drops scheduled block updates queued by onBlockAdded (flowing liquids, falling blocks, fire, ...), so the
         * scene stays as placed. Random ticks only run in chunks near players.
         */
        fun dropScheduledUpdates() {
            dropTicks(world, (x0 - 1) shr 4, (z0 - 1) shr 4, (xMax + 1) shr 4, (zMax + 1) shr 4)
        }

        companion object {
            fun prepare(world: WorldServer, x0: Int, y: Int, z0: Int, count: Int): Grid {
                checkY(world, y)
                checkY(world, y - 1)
                val grid = Grid(world, x0, y, z0, count)
                populate(world, (x0 shr 4) - 1, (z0 shr 4) - 1, grid.xMax shr 4, grid.zMax shr 4)
                for (x in x0..grid.xMax) for (z in z0..grid.zMax) place(world, x, y - 1, z, Blocks.stone, 0)
                return grid
            }
        }
    }

    /**
     * Places the entries of a JSON list on a [Grid], in list order. Entries are `{"name": "modid:name", "meta": n}`
     * (placed like zoo variants, any meta 0..65535 except 32767) or `{"mte": mID, "facing": f}` (placed like `bmh mte`;
     * facing is a ForgeDirection ordinal or name, or a list of them of which the first valid one is used; default
     * NORTH; ignored for pipes).
     */
    fun placeList(dim: Int, x0: Int, y: Int, z0: Int, file: String, out: String): JsonObject {
        val world = world(dim)
        val list = inFile(file).reader(StandardCharsets.UTF_8).use { JsonParser().parse(it) }
        if (!list.isJsonArray) throw BmhException("$file must contain a JSON array")
        val entries = list.asJsonArray.toList()
        val grid = Grid.prepare(world, x0, y, z0, entries.size)
        val placed = JsonArray()
        val failed = JsonArray()
        entries.forEachIndexed { index, element ->
            val x = grid.x(index)
            val z = grid.z(index)
            val entry = element as? JsonObject
            try {
                if (entry == null) throw BmhException("entry is not a JSON object")
                placed.add(placeEntry(world, x, y, z, entry).also { it.addProperty("index", index) })
            } catch (t: Throwable) {
                val reason = failure(t)
                val record = JsonObject()
                record.addProperty("index", index)
                entry?.entrySet()?.forEach { (k, v) -> record.add(k, v) }
                record.addProperty("reason", reason)
                failed.add(record)
                BmhMod.log.warn("place-list: failed to place {} at {},{},{}: {}", element, x, y, z, reason)
                removeBroken(world, x, y, z)
            }
        }
        grid.dropScheduledUpdates()
        outFile(out).writeText(obj("placed" to placed, "failed" to failed).toString(), StandardCharsets.UTF_8)
        return obj("file" to out, "placed" to placed.size(), "failed" to failed.size(), "box" to grid.box())
    }

    private fun placeEntry(world: WorldServer, x: Int, y: Int, z: Int, entry: JsonObject): JsonObject {
        val mte = entry.get("mte")
        if (mte != null) {
            val mId = mte.asInt
            val facings = when (val f = entry.get("facing")) {
                null -> listOf(GtCompat.NORTH)
                is JsonArray -> f.map { parseFacing(it.asString) }
                else -> listOf(parseFacing(f.asString))
            }
            val result = GtCompat.placeMte(world, x, y, z, mId, facings)
            val block = world.getBlock(x, y, z)
            return obj(
                "x" to x, "y" to y, "z" to z, "name" to blockName(block), "id" to Block.getIdFromBlock(block),
                "meta" to world.getBlockMetadata(x, y, z), "mte" to mId, "metaName" to result.metaName,
                "facing" to result.facing,
            )
        }
        val name = entry.get("name")?.asString ?: throw BmhException("entry needs \"name\" or \"mte\"")
        val meta = entry.get("meta")?.asInt ?: 0
        checkMeta(meta)
        val block = block(name)
        val actual = placeVariant(world, x, y, z, block, meta)
        return obj("x" to x, "y" to y, "z" to z, "name" to name, "id" to Block.getIdFromBlock(block), "meta" to actual)
    }

    /** ForgeDirection ordinal 0..5 or name (DOWN, UP, NORTH, SOUTH, WEST, EAST; case-insensitive). */
    fun parseFacing(raw: String): Int {
        raw.toIntOrNull()?.let { if (it in 0..5) return it }
        val names = listOf("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST")
        val index = names.indexOf(raw.uppercase())
        if (index < 0) throw BmhException("facing $raw is not a ForgeDirection ordinal 0..5 or name")
        return index
    }

    /**
     * Calls Block methods that lighting, collision and other mods' chunk load/unload hooks (e.g. BuildCraft's robot
     * map calling getMapColor) invoke on any loaded block, world-aware ones first because some blocks (kubatech)
     * remember the last accessed position. A meta the block does not support usually throws here; the zoo then
     * removes it instead of letting a later tick or chunk unload crash the server.
     */
    private fun probe(world: WorldServer, x: Int, y: Int, z: Int, block: Block, meta: Int) {
        try {
            block.getLightValue(world, x, y, z)
            block.getLightOpacity(world, x, y, z)
            block.getBlockHardness(world, x, y, z)
            block.isNormalCube(world, x, y, z)
            block.getCollisionBoundingBoxFromPool(world, x, y, z)
            block.isOpaqueCube()
            block.material
            block.getMapColor(meta)
        } catch (t: Throwable) {
            throw BmhException("block probe failed: $t")
        }
    }

    fun dump(dim: Int, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, file: String, includeAir: Boolean): JsonObject {
        val world = world(dim)
        val (xa, xb) = min(x1, x2) to max(x1, x2)
        val (ya, yb) = max(0, min(y1, y2)) to min(world.height - 1, max(y1, y2))
        val (za, zb) = min(z1, z2) to max(z1, z2)
        var lines = 0
        outFile(file).bufferedWriter(StandardCharsets.UTF_8).use { out ->
            for (x in xa..xb) for (z in za..zb) {
                ensureChunk(world, x, z)
                for (y in ya..yb) {
                    val block = world.getBlock(x, y, z)
                    if (!includeAir && block === Blocks.air) continue
                    val te = world.getTileEntity(x, y, z)?.let { tile ->
                        runCatching { NBTTagCompound().also { tile.writeToNBT(it) } }
                            .map { NbtJson.toJson(it) }
                            .getOrElse { obj("error" to it.toString()) }
                    }
                    out.write(
                        obj(
                            "x" to x, "y" to y, "z" to z,
                            "id" to Block.getIdFromBlock(block),
                            "name" to blockName(block),
                            "meta" to world.getBlockMetadata(x, y, z),
                            "te" to te,
                        ).toString(),
                    )
                    out.write("\n")
                    lines++
                }
            }
        }
        return obj("file" to file, "lines" to lines)
    }

    /** Like `save-all flush`, for every loaded dimension, then waits for the chunk IO thread and closes region files. */
    fun flush(): JsonObject {
        val start = System.nanoTime()
        val server = MinecraftServer.getServer()
        server.configurationManager?.saveAllPlayerData()
        for (world in DimensionManager.getWorlds()) {
            val wasSavingDisabled = world.levelSaving
            world.levelSaving = false
            try {
                world.saveAllChunks(true, null)
                world.saveChunkData()
            } catch (e: MinecraftException) {
                throw BmhException("saving dimension ${world.provider.dimensionId} failed: ${e.message}")
            } finally {
                world.levelSaving = wasSavingDisabled
            }
        }
        ThreadedFileIOBase.threadedIOInstance.waitForFinish()
        RegionFileCache.clearRegionFileReferences()
        return obj("ms" to (System.nanoTime() - start) / 1_000_000)
    }
}
