package dev.fywinds.bluemapgtnh.harness

import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.relauncher.ReflectionHelper
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.tileentity.TileEntity
import net.minecraft.world.ChunkPosition
import net.minecraft.world.World
import net.minecraft.world.WorldSavedData
import net.minecraftforge.event.world.ChunkEvent
import java.util.Collections
import java.util.IdentityHashMap

/** Per-world set of frozen tile entity positions, saved as `data/bmh_frozen_tiles.dat` of each dimension. */
class FrozenTileData(name: String) : WorldSavedData(name) {
    /** Chunk key -> packed block positions. */
    val positions = HashMap<Long, HashSet<Long>>()

    override fun readFromNBT(tag: NBTTagCompound) {
        positions.clear()
        val xyz = tag.getIntArray("xyz")
        for (i in 0 until xyz.size / 3) add(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2])
    }

    override fun writeToNBT(tag: NBTTagCompound) {
        val xyz = IntArray(positions.values.sumOf { it.size } * 3)
        var i = 0
        for (set in positions.values) for (p in set) {
            xyz[i++] = unpackX(p); xyz[i++] = unpackY(p); xyz[i++] = unpackZ(p)
        }
        tag.setIntArray("xyz", xyz)
    }

    fun add(x: Int, y: Int, z: Int): Boolean = positions.getOrPut(chunkKey(x shr 4, z shr 4)) { HashSet() }.add(pack(x, y, z))

    fun remove(x: Int, y: Int, z: Int): Boolean {
        val key = chunkKey(x shr 4, z shr 4)
        val set = positions[key] ?: return false
        val removed = set.remove(pack(x, y, z))
        if (set.isEmpty()) positions.remove(key)
        return removed
    }

    companion object {
        fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
        fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3ffffff) shl 38) or ((z.toLong() and 0x3ffffff) shl 12) or (y.toLong() and 0xfff)
        fun unpackX(p: Long): Int = (p shr 38).toInt()
        fun unpackZ(p: Long): Int = ((p shl 26) shr 38).toInt()
        fun unpackY(p: Long): Int = (p and 0xfff).toInt()
    }
}

/**
 * Tile entities placed by `set`/`fill`/`zoo` are "frozen": they stay in their chunk (so they are saved, dumped and
 * rendered) but are removed from the world's ticking lists, so arbitrary modded tile entities placed without a player
 * or a valid multiblock cannot crash the server or change the scene. Frozen positions are persisted per world and
 * re-applied on every chunk load (Chunk.onChunkLoad re-adds tile entities before firing ChunkEvent.Load).
 */
object FrozenTiles {
    private const val DATA_NAME = "bmh_frozen_tiles"

    private val addedTileEntityList by lazy {
        ReflectionHelper.findField(World::class.java, "addedTileEntityList", "field_147484_a")
    }

    /** Tile entities frozen by the running command; unhooked from the ticking lists when it ends. */
    private val pending = IdentityHashMap<World, MutableSet<TileEntity>>()

    private fun identitySet(): MutableSet<TileEntity> = Collections.newSetFromMap(IdentityHashMap())

    private fun data(world: World): FrozenTileData {
        val storage = world.perWorldStorage
        return storage.loadData(FrozenTileData::class.java, DATA_NAME) as FrozenTileData?
            ?: FrozenTileData(DATA_NAME).also { storage.setData(DATA_NAME, it) }
    }

    fun freeze(world: World, x: Int, y: Int, z: Int, tile: TileEntity) {
        val data = data(world)
        if (data.add(x, y, z)) data.markDirty()
        pending.getOrPut(world) { identitySet() } += tile
    }

    fun unfreeze(world: World, x: Int, y: Int, z: Int) {
        val data = data(world)
        if (data.remove(x, y, z)) data.markDirty()
    }

    /** Removes the tile entities frozen by this command from the ticking lists. Called after every command. */
    fun commitPending() {
        for ((world, tiles) in pending) unhook(world, tiles)
        pending.clear()
    }

    private fun unhook(world: World, tiles: Set<TileEntity>) {
        if (tiles.isEmpty()) return
        world.loadedTileEntityList.removeAll(tiles)
        @Suppress("UNCHECKED_CAST")
        (addedTileEntityList.get(world) as MutableList<TileEntity>).removeAll(tiles)
    }

    @SubscribeEvent
    fun onChunkLoad(event: ChunkEvent.Load) {
        val world = event.world
        if (world.isRemote) return
        val chunk = event.chunk
        val frozen = data(world).positions[FrozenTileData.chunkKey(chunk.xPosition, chunk.zPosition)] ?: return
        val tiles = identitySet()
        for (p in frozen) {
            val pos = ChunkPosition(FrozenTileData.unpackX(p) and 15, FrozenTileData.unpackY(p), FrozenTileData.unpackZ(p) and 15)
            chunk.chunkTileEntityMap[pos]?.let { tiles += it }
        }
        unhook(world, tiles)
    }
}
