package dev.fywinds.bluemapgtnh.harness

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
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
    fun outFile(relative: String): File {
        val normalized = relative.replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/") || File(relative).isAbsolute ||
            normalized.split('/').any { it == ".." || it.isEmpty() }
        ) {
            throw BmhException("invalid output path $relative (relative, no '..')")
        }
        val root = MinecraftServer.getServer().getFile(OUT_DIR).canonicalFile
        val file = File(root, normalized).canonicalFile
        if (!file.toPath().startsWith(root.toPath())) throw BmhException("invalid output path $relative")
        file.parentFile.mkdirs()
        return file
    }

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
        try {
            val tag = NBTTagCompound()
            tile.writeToNBT(tag)
            CompressedStreamTools.write(tag, DataOutputStream(OutputStream.nullOutputStream()))
        } catch (t: Throwable) {
            removeBroken(world, x, y, z)
            throw BmhException("tile entity ${tile.javaClass.name} cannot be saved: $t")
        }
        FrozenTiles.freeze(world, x, y, z, tile)
        return changed
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
    private fun removeBroken(world: WorldServer, x: Int, y: Int, z: Int) {
        runCatching { world.removeTileEntity(x, y, z) }
        runCatching { withoutDrops(world) { world.setBlock(x, y, z, Blocks.air, 0, SET_FLAGS) } }
        runCatching { world.getChunkFromBlockCoords(x, z).removeTileEntity(x and 15, y, z and 15) }
        FrozenTiles.unfreeze(world, x, y, z)
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().also { o ->
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
        checkY(world, y)
        checkY(world, y - 1)
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
        val side = ceil(sqrt(entries.size.toDouble())).toInt()
        val rows = (entries.size + side - 1) / side

        val xMax = x0 + 2 * (side - 1)
        val zMax = z0 + 2 * (rows - 1)
        // Populate first, so world decoration (trees, villages, ores) cannot later overwrite the zoo. Population of
        // chunk (cx, cz) decorates blocks offset by +8, so the chunks one step towards -x/-z are populated too.
        populate(world, (x0 shr 4) - 1, (z0 shr 4) - 1, xMax shr 4, zMax shr 4)

        // Continuous stone slab under the whole grid.
        val stone = Blocks.stone
        for (x in x0..xMax) for (z in z0..zMax) place(world, x, y - 1, z, stone, 0)

        val placed = JsonArray()
        val failed = JsonArray()
        entries.forEachIndexed { index, (b, name, meta) ->
            val x = x0 + 2 * (index % side)
            val z = z0 + 2 * (index / side)
            try {
                place(world, x, y, z, b, meta)
                if (world.getBlock(x, y, z) !== b) throw BmhException("block did not stick")
                // Some blocks re-orient themselves at random or by surroundings in onBlockAdded; restore the variant.
                if (world.getBlockMetadata(x, y, z) != meta) world.setBlockMetadataWithNotify(x, y, z, meta, SET_FLAGS)
                probe(world, x, y, z, b, meta)
                val actual = world.getBlockMetadata(x, y, z)
                placed.add(obj("x" to x, "y" to y, "z" to z, "name" to name, "id" to Block.getIdFromBlock(b), "meta" to actual))
            } catch (t: Throwable) {
                val reason = if (t is BmhException) t.message ?: "failed" else t.toString()
                failed.add(obj("name" to name, "meta" to meta, "reason" to reason))
                BmhMod.log.warn("zoo: failed to place {}:{} at {},{},{}: {}", name, meta, x, y, z, reason)
                removeBroken(world, x, y, z)
            }
        }
        // Drop scheduled block updates queued by onBlockAdded (flowing liquids, falling blocks, fire, ...), so the
        // scene stays as placed. Random ticks only run in chunks near players.
        for (cx in ((x0 - 1) shr 4)..((xMax + 1) shr 4)) for (cz in ((z0 - 1) shr 4)..((zMax + 1) shr 4)) {
            world.getPendingBlockUpdates(ensureChunkAt(world, cx, cz), true)
        }
        outFile(file).writeText(obj("placed" to placed, "failed" to failed).toString(), StandardCharsets.UTF_8)
        return obj("file" to file, "placed" to placed.size(), "failed" to failed.size())
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
