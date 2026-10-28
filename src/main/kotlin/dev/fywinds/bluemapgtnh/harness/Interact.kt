package dev.fywinds.bluemapgtnh.harness

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cpw.mods.fml.common.registry.GameData
import net.minecraft.block.Block
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.world.WorldServer
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.common.util.ForgeDirection
import java.nio.charset.StandardCharsets

/** `bmh te-nbt` and `bmh use`: editing tile entity NBT and right-clicking with an item as a (fake) player would. */
object Interact {
    private val KEPT_KEYS = listOf("x", "y", "z", "id")

    fun teNbt(dim: Int, x: Int, y: Int, z: Int, file: String): JsonObject {
        val world = WorldOps.world(dim)
        WorldOps.checkY(world, y)
        val text = WorldOps.inFile(file).readText(StandardCharsets.UTF_8)
        val patch: JsonElement = if (file.endsWith(".snbt")) com.google.gson.JsonPrimitive(text) else {
            JsonParser().parse(text).also { if (!it.isJsonObject) throw BmhException("$file must contain a JSON object") }
        }
        return NbtJson.toJson(applyTeNbt(world, x, y, z, patch)) as JsonObject
    }

    /**
     * An item NBT tag from `bmh-out/<file>`: SNBT if the name ends in `.snbt`, else a JSON patch (same typing as
     * `te-nbt`, e.g. `{"x": {"@ints": [{"@itemId": "minecraft:stone"}, 0]}}`) applied to an empty compound.
     */
    fun itemTagFile(file: String): NBTTagCompound {
        val text = WorldOps.inFile(file).readText(StandardCharsets.UTF_8)
        return itemTag(if (file.endsWith(".snbt")) com.google.gson.JsonPrimitive(text) else JsonParser().parse(text))
    }

    /** An item NBT tag from a JSON patch object or an SNBT string. */
    fun itemTag(patch: JsonElement): NBTTagCompound = NBTTagCompound().also { tag ->
        when {
            patch.isJsonObject -> NbtPatch.merge(tag, patch.asJsonObject)
            patch.isJsonPrimitive && patch.asJsonPrimitive.isString -> NbtPatch.mergeSnbt(tag, patch.asString)
            else -> throw BmhException("item tag must be a JSON object or an SNBT string")
        }
    }

    /**
     * Merges [patch] (a JSON object, see [NbtPatch], or a string holding SNBT) into the `writeToNBT` output of the tile
     * entity at x/y/z, keeping its x/y/z/id, and loads the result with `readFromNBT`. Some tile entities refuse to
     * re-read NBT while live (AE2's cable bus: "Loading data after part of a grid, this is invalid"); then the tile
     * entity is replaced by a fresh one created from the merged NBT (`TileEntity.createAndLoadEntity`, as a chunk load
     * does) after invalidating the old one. The tile entity must stay saveable (else the block is removed and this
     * throws); a frozen tile entity stays frozen. Returns the tile entity's `writeToNBT` output afterwards.
     */
    fun applyTeNbt(world: WorldServer, x: Int, y: Int, z: Int, patch: JsonElement): NBTTagCompound {
        WorldOps.ensureChunk(world, x, z)
        var te = world.getTileEntity(x, y, z) ?: throw BmhException("no tile entity at $x,$y,$z")
        val tag = NBTTagCompound().also { te.writeToNBT(it) }
        val kept = KEPT_KEYS.filter { tag.hasKey(it) }.associateWith { tag.getTag(it).copy() }
        when {
            patch.isJsonObject -> NbtPatch.merge(tag, patch.asJsonObject)
            patch.isJsonPrimitive && patch.asJsonPrimitive.isString -> NbtPatch.mergeSnbt(tag, patch.asString)
            else -> throw BmhException("NBT patch must be a JSON object or an SNBT string")
        }
        for ((key, value) in kept) tag.setTag(key, value)
        try {
            te.readFromNBT(tag)
        } catch (e: Exception) {
            BmhMod.log.info("te-nbt: {} cannot re-read NBT in place, replacing it", te.javaClass.name, e)
            val fresh = net.minecraft.tileentity.TileEntity.createAndLoadEntity(tag)
                ?: throw BmhException("${te.javaClass.name} rejected the NBT and cannot be recreated from it: $e")
            world.removeTileEntity(x, y, z)
            world.setTileEntity(x, y, z, fresh)
            te = fresh
        }
        te.markDirty()
        world.markBlockForUpdate(x, y, z)
        WorldOps.checkSaveable(world, x, y, z)
        if (FrozenTiles.isFrozen(world, x, y, z)) FrozenTiles.freeze(world, x, y, z, te)
        return NBTTagCompound().also { te.writeToNBT(it) }
    }

    /**
     * Right-click on face [side] of block x/y/z with `count × item:meta`, as the server does for a player: the fake
     * player `[Minecraft]` stands two blocks out from that face (feet level with the block for horizontal faces), at
     * [yaw] (default: looking at the face) and pitch 0, holding the stack; `Item.onItemUseFirst`, then
     * `ItemStack.tryPlaceItemIntoWorld` (→ `Item.onItemUse`, with Forge's block snapshots and place event) with the
     * hit at the centre of the face. The clicked block's own `onBlockActivated` is not called. [tag] becomes the
     * stack's NBT (e.g. an AE2 facade's `x`/`modid`/`itemname`). Resulting tile entities are not frozen (they tick);
     * they must be saveable.
     */
    fun use(
        world: WorldServer, x: Int, y: Int, z: Int, side: Int, itemName: String, meta: Int, yaw: Float?, count: Int,
        tag: NBTTagCompound? = null,
    ): JsonObject {
        WorldOps.checkY(world, y)
        if (side !in 0..5) throw BmhException("side $side is not a ForgeDirection ordinal 0..5")
        if (count !in 1..64) throw BmhException("count $count outside 1..64")
        val registry = GameData.getItemRegistry()
        if (!registry.containsKey(itemName)) throw BmhException("unknown item $itemName")
        val item: Item = registry.getObject(itemName) ?: throw BmhException("unknown item $itemName")
        val dir = ForgeDirection.getOrientation(side)
        val positions = listOf(Triple(x, y, z), Triple(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ))
        for ((px, _, pz) in positions) WorldOps.ensureChunk(world, px, pz)
        val before = positions.map { (px, py, pz) -> describe(world, px, py, pz) }

        val angle = yaw ?: defaultYaw(dir)
        val feetY = when (dir) {
            ForgeDirection.UP -> y + 2.0
            ForgeDirection.DOWN -> y - 2.0 - 1.62
            else -> y.toDouble()
        }
        val player = FakePlayerFactory.getMinecraft(world)
        player.setLocationAndAngles(x + 0.5 + 2 * dir.offsetX, feetY, z + 0.5 + 2 * dir.offsetZ, angle, 0f)
        player.rotationYawHead = angle
        player.prevRotationYaw = angle
        player.prevRotationPitch = 0f
        player.isSneaking = false
        val stack = ItemStack(item, count, meta).also { if (tag != null) it.tagCompound = tag }
        player.inventory.currentItem = 0
        player.inventory.setInventorySlotContents(0, stack)
        val hitX = 0.5f + 0.5f * dir.offsetX
        val hitY = 0.5f + 0.5f * dir.offsetY
        val hitZ = 0.5f + 0.5f * dir.offsetZ
        val used = try {
            item.onItemUseFirst(stack, player, world, x, y, z, side, hitX, hitY, hitZ) ||
                stack.tryPlaceItemIntoWorld(player, world, x, y, z, side, hitX, hitY, hitZ) ||
                Ae2Facades.place(stack, player, world, x, y, z, dir)
        } finally {
            player.inventory.setInventorySlotContents(0, null)
        }

        val records = positions.mapIndexed { i, (px, py, pz) ->
            val after = describe(world, px, py, pz)
            val changed = after.toString() != before[i].toString()
            if (changed && world.getTileEntity(px, py, pz) != null) WorldOps.checkSaveable(world, px, py, pz)
            after.apply {
                addProperty("role", if (i == 0) "clicked" else "adjacent")
                addProperty("changed", changed)
            }
        }
        // the adjacent block when the item placed a new block, else the clicked block (e.g. a part added to a cable)
        val placedAt = records.lastOrNull { it.get("changed").asBoolean }
        val result = JsonArray().also { a -> records.forEach { a.add(it) } }
        return WorldOps.obj("used" to used, "yaw" to angle, "positions" to result, "placed" to placedAt)
    }

    /** Yaw of a player looking at face [dir] from outside (0 = south/+z, 90 = west, 180 = north, 270 = east). */
    private fun defaultYaw(dir: ForgeDirection): Float = when (dir) {
        ForgeDirection.EAST -> 90f
        ForgeDirection.WEST -> 270f
        ForgeDirection.SOUTH -> 180f
        else -> 0f
    }

    private fun describe(world: WorldServer, x: Int, y: Int, z: Int): JsonObject {
        val block = world.getBlock(x, y, z)
        val te = world.getTileEntity(x, y, z)?.let { tile ->
            runCatching { NBTTagCompound().also { tile.writeToNBT(it) } }
                .map { NbtJson.toJson(it) }
                .getOrElse { WorldOps.obj("error" to it.toString()) }
        }
        return WorldOps.obj(
            "x" to x, "y" to y, "z" to z, "name" to WorldOps.blockName(block), "id" to Block.getIdFromBlock(block),
            "meta" to world.getBlockMetadata(x, y, z), "te" to te,
        )
    }
}
