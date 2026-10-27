package dev.fywinds.bluemapgtnh.harness

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.gtnewhorizon.structurelib.StructureLibAPI
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable
import com.gtnewhorizon.structurelib.structure.IItemSource
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment
import cpw.mods.fml.common.Loader
import cpw.mods.fml.common.registry.GameData
import gregtech.api.GregTechAPI
import gregtech.api.enums.Materials
import gregtech.api.interfaces.metatileentity.IConnectable
import gregtech.api.interfaces.metatileentity.IMetaTileEntity
import gregtech.api.interfaces.tileentity.IColoredTileEntity
import gregtech.api.interfaces.tileentity.IGregTechTileEntity
import gregtech.api.metatileentity.BaseMetaPipeEntity
import gregtech.api.metatileentity.MetaPipeEntity
import gregtech.api.metatileentity.implementations.MTEBasicBatteryBuffer
import gregtech.api.metatileentity.implementations.MTEBasicGenerator
import gregtech.api.metatileentity.implementations.MTEBasicHull
import gregtech.api.metatileentity.implementations.MTEBasicMachine
import gregtech.api.metatileentity.implementations.MTECable
import gregtech.api.metatileentity.implementations.MTEFluidPipe
import gregtech.api.metatileentity.implementations.MTEFrame
import gregtech.api.metatileentity.implementations.MTEHatch
import gregtech.api.metatileentity.implementations.MTEItemPipe
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase
import gregtech.api.metatileentity.implementations.MTETieredMachineBlock
import gregtech.api.metatileentity.implementations.MTETransformer
import gregtech.api.structure.error.StructureError
import gregtech.common.blocks.GTBlockOre
import gregtech.common.misc.GTStructureChannels
import gregtech.common.ores.GTOreAdapter
import gregtech.common.ores.OreInfo
import gregtech.common.tileentities.boilers.MTEBoiler
import gregtech.common.tileentities.storage.MTEDigitalChestBase
import gregtech.common.tileentities.storage.MTEDigitalTankBase
import gtPlusPlus.xmod.gregtech.common.tileentities.storage.MTETieredChest
import gtPlusPlus.xmod.gregtech.common.tileentities.storage.MTETieredTank
import net.minecraft.block.Block
import net.minecraft.init.Items
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.world.WorldServer
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.common.util.ForgeDirection
import java.nio.charset.StandardCharsets
import java.util.function.Predicate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Everything that touches GregTech or StructureLib classes. Only loaded when a GT subcommand runs, so the rest of the
 * harness keeps working if these APIs move.
 */
object GtCompat {
    const val NORTH = 2

    /** Chunks around a multiblock controller loaded before construction, which does not load chunks itself. */
    private const val MULTIBLOCK_CHUNK_RADIUS = 2

    /** Elements per survivalConstruct call, and the cap on calls (a placed hatch ends a call early). */
    private const val SURVIVAL_BUDGET = 4096
    private const val SURVIVAL_MAX_PASSES = 256

    private const val PIPE_LINE_CAP = 256

    private fun requireMod(modid: String) {
        if (!Loader.isModLoaded(modid)) throw BmhException("$modid is not loaded")
    }

    private fun prototype(mId: Int): IMetaTileEntity {
        requireMod("gregtech")
        return GregTechAPI.METATILEENTITIES.getOrNull(mId) ?: throw BmhException("no MetaTileEntity with id $mId")
    }

    class Placed(val te: IGregTechTileEntity, val metaName: String, val facing: Int?)

    /**
     * Places `gt.blockmachines` with MetaTileEntity [mId], mirroring gregtech.common.blocks.ItemMachines#placeBlockAt
     * with the fake player `[Minecraft]` as owner: initial values, owner, front facing (the first of [facings],
     * ForgeDirection ordinals, that the machine accepts; skipped for pipes), then `initDefaultModes` (which derives e.g. a basic machine's main facing from the
     * front facing). Not frozen: machines must tick (e.g. to form multiblocks). The tile entity is checked to be
     * saveable; on any failure the block is removed again.
     */
    fun placeMte(world: WorldServer, x: Int, y: Int, z: Int, mId: Int, facings: List<Int>): Placed {
        WorldOps.checkY(world, y)
        val prototype = prototype(mId)
        if (facings.isEmpty() || facings.any { it !in 0..5 }) {
            throw BmhException("facings $facings are not ForgeDirection ordinals 0..5")
        }
        WorldOps.place(world, x, y, z, GregTechAPI.sBlockMachines, prototype.tileEntityBaseType.toInt(), freeze = false)
        try {
            val te = world.getTileEntity(x, y, z) as? IGregTechTileEntity
                ?: throw BmhException("no GregTech tile entity at $x,$y,$z after placing")
            te.setInitialValuesAsNBT(null, mId.toShort())
            val mte = te.metaTileEntity ?: throw BmhException("MetaTileEntity $mId was not created")
            val owner = FakePlayerFactory.getMinecraft(world).gameProfile
            te.setOwnerName(owner.name)
            te.setOwnerUuid(owner.id)
            var applied: Int? = null
            if (mte !is MetaPipeEntity) {
                if (ForgeDirection.VALID_DIRECTIONS.none { te.isValidFacing(it) }) {
                    // e.g. crop managers: no facing is valid, so player placement keeps the default one as well
                    applied = te.frontFacing.ordinal
                } else {
                    val directions = facings.map { ForgeDirection.getOrientation(it) }
                    val direction = directions.firstOrNull { te.isValidFacing(it) }
                        ?: throw BmhException("facing ${directions.joinToString("|")} not valid for ${mte.metaName}")
                    te.frontFacing = direction
                    applied = direction.ordinal
                }
            }
            mte.initDefaultModes(null)
            world.markBlockForUpdate(x, y, z)
            WorldOps.checkSaveable(world, x, y, z)
            return Placed(te, mte.metaName, applied)
        } catch (t: Throwable) {
            WorldOps.removeBroken(world, x, y, z)
            throw t
        }
    }

    fun mte(dim: Int, x: Int, y: Int, z: Int, mId: Int, facing: Int): JsonObject {
        val placed = placeMte(WorldOps.world(dim), x, y, z, mId, listOf(facing))
        return JsonObject().apply { addProperty("name", placed.metaName) }
    }

    // ---- bmh mtes ----

    /** Coarse kind of a MetaTileEntity, most specific first. */
    fun type(mte: IMetaTileEntity): String = when (mte) {
        is MTECable -> "cable"
        is MTEFluidPipe -> "fluid_pipe"
        is MTEItemPipe -> "item_pipe"
        is MTEFrame -> "frame"
        is MetaPipeEntity -> "pipe"
        is MTEMultiBlockBase -> "multiblock"
        is MTEHatch -> "hatch"
        is IConstructable, is ISurvivalConstructable -> "multiblock"
        is MTEBasicMachine -> "machine"
        is MTEBasicGenerator -> "generator"
        is MTEBoiler -> "boiler"
        is MTEBasicBatteryBuffer -> "battery_buffer"
        is MTETransformer -> "transformer"
        is MTEBasicHull -> "hull"
        is MTEDigitalTankBase, is MTETieredTank -> "tank"
        is MTEDigitalChestBase, is MTETieredChest -> "chest"
        else -> "other"
    }

    fun mtes(file: String): JsonObject {
        val list = JsonArray()
        val counts = sortedMapOf<String, Int>()
        requireMod("gregtech")
        GregTechAPI.METATILEENTITIES.forEachIndexed { id, mte ->
            if (mte == null) return@forEachIndexed
            val type = type(mte)
            counts.merge(type, 1, Int::plus)
            list.add(
                WorldOps.obj(
                    "id" to id,
                    "metaName" to mte.metaName,
                    "class" to mte.javaClass.name,
                    "type" to type,
                    "baseType" to mte.tileEntityBaseType.toInt(),
                ).apply { describe(mte, this) },
            )
        }
        WorldOps.outFile(file).writeText(WorldOps.obj("mtes" to list).toString(), StandardCharsets.UTF_8)
        val types = JsonObject().also { o -> counts.forEach { (k, v) -> o.addProperty(k, v) } }
        return WorldOps.obj("file" to file, "count" to list.size(), "types" to types)
    }

    /** Type-specific fields: tier, pipe material/thickness and the like. */
    private fun describe(mte: IMetaTileEntity, o: JsonObject) {
        if (mte is MTETieredMachineBlock) o.addProperty("tier", mte.mTier)
        if (mte is MetaPipeEntity) o.addProperty("thickness", mte.thickness)
        val material: Materials? = when (mte) {
            is MTECable -> mte.mMaterial.also { o.addProperty("insulated", mte.mInsulated) }
            is MTEFluidPipe -> mte.mMaterial.also { o.addProperty("pipeAmount", mte.mPipeAmount) }
            is MTEItemPipe -> mte.mMaterial.also { o.addProperty("restrictive", mte.mIsRestrictive) }
            is MTEFrame -> mte.mMaterial
            else -> null
        }
        material?.let { o.addProperty("material", it.mName) }
    }

    // ---- bmh gt-ores ----

    /**
     * Every (ore block, meta) that GTOreAdapter generates for a GregTech material: per `gt.blockoresN` stone type,
     * normal and small ore (not the "natural" +8000 variants, which only change drops).
     */
    fun gtOres(file: String): JsonObject {
        requireMod("gregtech")
        val ores = JsonArray()
        val oreBlocks = allBlocks().filterIsInstance<GTBlockOre>()
        for (block in oreBlocks) {
            val name = WorldOps.blockName(block)
            block.stoneTypes.forEachIndexed { stoneIndex, stone ->
                if (stone == null) return@forEachIndexed
                for (material in GregTechAPI.sGeneratedMaterials) {
                    if (material == null) continue
                    for (small in listOf(false, true)) {
                        val info = OreInfo.getNewInfo<Materials>()
                        try {
                            info.material = material
                            info.stoneType = stone
                            info.isSmall = small
                            if (!GTOreAdapter.INSTANCE.supports(info)) continue
                            val target = GTOreAdapter.INSTANCE.getBlock(info) ?: continue
                            if (target.block !== block) continue
                            ores.add(
                                WorldOps.obj(
                                    "name" to name,
                                    "meta" to target.blockMeta,
                                    "material" to material.mName,
                                    "materialId" to material.mMetaItemSubID,
                                    "stone" to stone.name,
                                    "stoneIndex" to stoneIndex,
                                    "small" to small,
                                ),
                            )
                        } finally {
                            info.close()
                        }
                    }
                }
            }
        }
        WorldOps.outFile(file).writeText(WorldOps.obj("ores" to ores).toString(), StandardCharsets.UTF_8)
        return WorldOps.obj("file" to file, "count" to ores.size(), "blocks" to oreBlocks.size)
    }

    private fun allBlocks(): List<Block> = GameData.getBlockRegistry().typeSafeIterable().toList()

    // ---- bmh pipe ----

    /**
     * Places pipe MetaTileEntity [mId] on every block of the axis-aligned line from (x1,y1,z1) to (x2,y2,z2) and
     * connects consecutive blocks with `IConnectable.connect`, as a player's placement and wrench do (GT6-style pipes
     * only keep explicit connections). Blocks that already hold the same pipe are kept with their connections, so a
     * line starting or ending on an existing line makes a T or cross junction. [color] is -1 (none) or 0..15 and is
     * applied to newly placed blocks; GT only connects equal or uncoloured pipes.
     */
    fun pipe(dim: Int, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, mId: Int, color: Int): JsonObject {
        val world = WorldOps.world(dim)
        val prototype = prototype(mId)
        if (prototype !is MetaPipeEntity) throw BmhException("${prototype.metaName} ($mId) is not a pipe")
        if (color !in -1..15) throw BmhException("color $color outside -1..15")
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        if (listOf(dx, dy, dz).count { it != 0 } > 1) throw BmhException("pipe line must be axis-aligned")
        val length = max(abs(dx), max(abs(dy), abs(dz))) + 1
        if (length > PIPE_LINE_CAP) throw BmhException("pipe line of $length blocks exceeds $PIPE_LINE_CAP")
        WorldOps.checkY(world, min(y1, y2))
        WorldOps.checkY(world, max(y1, y2))
        val step = Triple(Integer.signum(dx), Integer.signum(dy), Integer.signum(dz))
        val side = ForgeDirection.VALID_DIRECTIONS.first {
            length == 1 || (it.offsetX == step.first && it.offsetY == step.second && it.offsetZ == step.third)
        }

        val tiles = ArrayList<IGregTechTileEntity>(length)
        var placed = 0
        for (i in 0 until length) {
            val x = x1 + i * step.first
            val y = y1 + i * step.second
            val z = z1 + i * step.third
            WorldOps.ensureChunk(world, x, z)
            val existing = world.getTileEntity(x, y, z) as? IGregTechTileEntity
            if (existing != null && existing.metaTileID == mId) {
                tiles += existing
                continue
            }
            val te = placeMte(world, x, y, z, mId, listOf(NORTH)).te
            if (color >= 0) (te as IColoredTileEntity).setColorization(color.toByte())
            tiles += te
            placed++
        }
        var connections = 0
        for (i in 0 until tiles.size - 1) {
            val pipe = tiles[i].metaTileEntity as IConnectable
            if (pipe.connect(side) > 0 && pipe.isConnectedAtSide(side)) connections++
        }
        // BaseMetaPipeEntity saves its own copy of the connection mask, which it only syncs from the MetaTileEntity
        // after ticking for 10 ticks; copy it now so the world saved right away already has the connections.
        for (te in tiles) {
            val base = te as? BaseMetaPipeEntity ?: continue
            val mte = base.metaTileEntity as? MetaPipeEntity ?: continue
            base.mConnections = mte.mConnections
        }
        return WorldOps.obj(
            "name" to prototype.metaName,
            "blocks" to length,
            "placed" to placed,
            "connections" to connections,
        )
    }

    // ---- bmh multiblock ----

    /**
     * Builds the multiblock of the controller at x/y/z. `creative`: `IConstructable.construct` (what the hologram
     * projector does for creative players), places casings only. `survival`: `ISurvivalConstructable.survivalConstruct`
     * with the `gt_hatch` channel set, drawing items from an unlimited source and a fake player, as a survival player
     * with the projector and a full inventory would; GT then also places the minimum set of hatches. `check`: builds
     * nothing, only reports the structure check (e.g. after placing missing hatches with `bmh mte`). [tier] is the
     * trigger stack size (tier / size channel).
     */
    fun multiblock(dim: Int, x: Int, y: Int, z: Int, tier: Int, mode: String): JsonObject {
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
        val trigger = ItemStack(Items.stick, tier)
        val result = JsonObject()
        result.addProperty("mode", mode)
        StructureLibAPI.startHinting(world)
        try {
            when (mode) {
                "creative" -> constructable.construct(trigger, false)
                "check" -> {}
                "survival" -> {
                    val survival = constructable as? ISurvivalConstructable
                        ?: throw BmhException("${constructable.javaClass.name} does not support survival construction")
                    ChannelDataAccessor.setChannelData(trigger, GTStructureChannels.HATCH.get(), 1)
                    val env = ISurvivalBuildEnvironment.create(UnlimitedSource, FakePlayerFactory.getMinecraft(world))
                    var placed = 0
                    var passes = 0
                    var last = 0
                    while (passes < SURVIVAL_MAX_PASSES) {
                        last = survival.survivalConstruct(trigger, SURVIVAL_BUDGET, env)
                        passes++
                        if (last <= 0) break
                        placed += last
                    }
                    result.addProperty("placed", placed)
                    result.addProperty("passes", passes)
                    // -1: complete; 0: nothing left that this source/channel could place; >0: pass cap reached
                    result.addProperty("last", last)
                }
                else -> throw BmhException("mode must be creative, survival or check, got $mode")
            }
        } finally {
            StructureLibAPI.endHinting(world)
        }
        val gtTe = te as? IGregTechTileEntity
        val multi = gtTe?.metaTileEntity as? MTEMultiBlockBase
        if (multi != null) {
            multi.checkStructure(true, gtTe)
            result.addProperty("formed", multi.mMachine)
            if (!multi.mMachine) result.add("errors", structureErrors(multi))
        } else {
            result.add("formed", JsonNull.INSTANCE)
        }
        return result
    }

    /**
     * The structure errors of the last check (GT keeps them in a private list for its GUI) as `<id>: <text>`; the text
     * is GT's display string, translated with the server's language table where it has one.
     */
    private fun structureErrors(multi: MTEMultiBlockBase): JsonArray = JsonArray().also { out ->
        runCatching {
            val field = MTEMultiBlockBase::class.java.getDeclaredField("structureErrors").apply { isAccessible = true }
            for (error in field.get(multi) as List<*>) {
                error as StructureError
                val text = runCatching { error.displayString }.getOrElse { it.toString() }
                out.add(JsonPrimitive("${error.id.name}: $text"))
            }
        }
    }

    /**
     * Item source with everything: any exact stack is available; predicate requests (hatches, tiered elements) get
     * the first matching candidate, trying `gt.blockmachines` MetaTileEntities in id order (skipping debug ones)
     * before every registered block with metas 0..15.
     */
    private object UnlimitedSource : IItemSource {
        private val candidates: List<ItemStack> by lazy {
            val machines = Item.getItemFromBlock(GregTechAPI.sBlockMachines)
            val mtes = GregTechAPI.METATILEENTITIES.withIndex()
                .filter { (_, mte) -> mte != null && !mte.javaClass.simpleName.contains("Debug") }
                .map { (id, _) -> ItemStack(machines, 1, id) }
            val blocks = allBlocks().mapNotNull { Item.getItemFromBlock(it) }
                .flatMap { item -> (0..15).map { ItemStack(item, 1, it) } }
            mtes + blocks
        }

        override fun take(predicate: Predicate<ItemStack>, simulate: Boolean, count: Int): MutableMap<ItemStack, Int> {
            val match = candidates.firstOrNull { runCatching { predicate.test(it) }.getOrDefault(false) }
                ?: return HashMap()
            return hashMapOf(match.copy().also { it.stackSize = count } to count)
        }

        override fun takeOne(stack: ItemStack, simulate: Boolean): Boolean = true

        override fun takeAll(stack: ItemStack, simulate: Boolean): Boolean = true
    }
}
