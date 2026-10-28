package dev.fywinds.bluemapgtnh.harness

import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraft.tileentity.TileEntity
import net.minecraft.world.World
import net.minecraftforge.common.util.ForgeDirection

/**
 * AE2 does not place facades through `Item.onItemUse`: its client handles the right-click and sends a
 * `PacketPartPlacement`, which the server answers with `PartPlacement.facadeLogic(held, player, face, host, false)` on
 * the part host at the clicked block. [place] runs that server-side step (reflection; no-op without AE2).
 */
object Ae2Facades {
    private class Api(
        val facadeItem: Class<*>,
        val existingHost: java.lang.reflect.Method,
        val facadeLogic: java.lang.reflect.Method,
    )

    private val api by lazy {
        runCatching {
            val placement = Class.forName("appeng.parts.PartPlacement")
            Api(
                Class.forName("appeng.facade.IFacadeItem"),
                placement.getMethod("getExistingHost", TileEntity::class.java),
                placement.getMethod(
                    "facadeLogic", ItemStack::class.java, EntityPlayer::class.java, ForgeDirection::class.java,
                    Class.forName("appeng.api.parts.IPartHost"), Boolean::class.javaPrimitiveType,
                ),
            )
        }.getOrNull()
    }

    /** Puts the facade [stack] on face [side] of the AE2 part host at x/y/z; false if not a facade or not placed. */
    fun place(stack: ItemStack, player: EntityPlayer, world: World, x: Int, y: Int, z: Int, side: ForgeDirection): Boolean {
        val api = api ?: return false
        if (!api.facadeItem.isInstance(stack.item)) return false
        val host = api.existingHost.invoke(null, world.getTileEntity(x, y, z)) ?: return false
        return api.facadeLogic.invoke(null, stack, player, side, host, false) as Boolean
    }
}
