package dev.fywinds.bluemapgtnh.harness

import com.google.gson.JsonObject
import cpw.mods.fml.common.registry.GameData
import cpw.mods.fml.relauncher.ReflectionHelper
import net.minecraft.block.Block
import java.lang.reflect.Field

/**
 * `bmh random-ticks on|off`: 1.7.10 has no `randomTickSpeed` gamerule. `WorldServer.func_147456_g` only random-ticks
 * a block whose `getTickRandomly()` is true, so turning random ticks off clears `Block.needsRandomTick` on every
 * registered block that has it; `on` sets it again on exactly those blocks. Runtime only: a server restart always
 * starts with random ticks on.
 *
 * `ExtendedBlockStorage` ref-counts random-ticking blocks when they are set; blocks placed or removed while ticks are
 * off are counted against the cleared flag, so a chunk section can be skipped (or scanned without effect) until it is
 * reloaded after turning them back on.
 */
object RandomTicks {
    private val needsRandomTick: Field by lazy {
        ReflectionHelper.findField(Block::class.java, "needsRandomTick", "field_149789_z")
    }

    /** Blocks whose flag was cleared by `off`; empty while random ticks are on. */
    private val cleared = mutableListOf<Block>()

    /** Reply `{"randomTicks","blocks"}`: the new state and the number of blocks whose flag is cleared. */
    fun set(enabled: Boolean): JsonObject {
        if (enabled) {
            for (block in cleared) needsRandomTick.setBoolean(block, true)
            cleared.clear()
        } else {
            for (block in GameData.getBlockRegistry().typeSafeIterable()) {
                if (needsRandomTick.getBoolean(block)) {
                    needsRandomTick.setBoolean(block, false)
                    cleared += block
                }
            }
        }
        return WorldOps.obj("randomTicks" to enabled, "blocks" to cleared.size)
    }
}
