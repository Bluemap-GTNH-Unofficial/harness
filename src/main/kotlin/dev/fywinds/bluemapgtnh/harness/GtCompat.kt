package dev.fywinds.bluemapgtnh.harness

import com.google.gson.JsonObject
import com.gtnewhorizon.structurelib.StructureLibAPI
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider
import cpw.mods.fml.common.Loader
import gregtech.api.GregTechAPI
import gregtech.api.interfaces.tileentity.IGregTechTileEntity
import net.minecraft.init.Items
import net.minecraft.item.ItemStack
import net.minecraftforge.common.util.ForgeDirection

/**
 * Everything that touches GregTech or StructureLib classes. Only loaded when `bmh mte` / `bmh multiblock` run, so the
 * rest of the harness keeps working if these APIs move.
 */
object GtCompat {
    /** Chunks around a multiblock controller loaded before construct(), which does not load chunks itself. */
    private const val MULTIBLOCK_CHUNK_RADIUS = 2

    private fun requireMod(modid: String) {
        if (!Loader.isModLoaded(modid)) throw BmhException("$modid is not loaded")
    }

    fun mte(dim: Int, x: Int, y: Int, z: Int, mId: Int, facing: Int): JsonObject {
        requireMod("gregtech")
        val world = WorldOps.world(dim)
        WorldOps.checkY(world, y)
        val prototype = GregTechAPI.METATILEENTITIES.getOrNull(mId)
            ?: throw BmhException("no MetaTileEntity with id $mId")
        if (facing !in 0..5) throw BmhException("facing $facing is not a ForgeDirection ordinal 0..5")
        val direction = ForgeDirection.getOrientation(facing)

        // Mirrors gregtech.common.blocks.ItemMachines#placeBlockAt without a player. Not frozen: machines must tick
        // (e.g. to form multiblocks).
        WorldOps.place(world, x, y, z, GregTechAPI.sBlockMachines, prototype.tileEntityBaseType.toInt(), freeze = false)
        val te = world.getTileEntity(x, y, z) as? IGregTechTileEntity
            ?: throw BmhException("no GregTech tile entity at $x,$y,$z after placing")
        te.setInitialValuesAsNBT(null, mId.toShort())
        val mte = te.metaTileEntity ?: throw BmhException("MetaTileEntity $mId was not created")
        mte.initDefaultModes(null)
        if (!te.isValidFacing(direction)) throw BmhException("facing $direction is not valid for ${mte.metaName}")
        te.frontFacing = direction
        world.markBlockForUpdate(x, y, z)
        return JsonObject().apply { addProperty("name", mte.metaName) }
    }

    fun multiblock(dim: Int, x: Int, y: Int, z: Int, tier: Int): JsonObject {
        requireMod("structurelib")
        if (tier < 1) throw BmhException("tier must be >= 1")
        val world = WorldOps.world(dim)
        WorldOps.checkY(world, y)
        for (dx in -MULTIBLOCK_CHUNK_RADIUS..MULTIBLOCK_CHUNK_RADIUS) {
            for (dz in -MULTIBLOCK_CHUNK_RADIUS..MULTIBLOCK_CHUNK_RADIUS) {
                WorldOps.ensureChunkAt(world, (x shr 4) + dx, (z shr 4) + dz)
            }
        }
        val te = world.getTileEntity(x, y, z) ?: throw BmhException("no tile entity at $x,$y,$z")
        val constructable = when (te) {
            is IConstructable -> te
            is IConstructableProvider -> te.constructable
            else -> null
        } ?: throw BmhException("tile entity ${te.javaClass.name} at $x,$y,$z is not constructable")
        StructureLibAPI.startHinting(world)
        try {
            constructable.construct(ItemStack(Items.stick, tier), false)
        } finally {
            StructureLibAPI.endHinting(world)
        }
        return JsonObject()
    }
}
