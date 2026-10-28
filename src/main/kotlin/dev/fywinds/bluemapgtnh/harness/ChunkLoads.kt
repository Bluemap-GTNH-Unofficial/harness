package dev.fywinds.bluemapgtnh.harness

import cpw.mods.fml.common.eventhandler.SubscribeEvent
import net.minecraft.world.World
import net.minecraftforge.event.world.ChunkEvent
import java.util.Queue

/**
 * Records every chunk loaded while tracking is on (by the harness, by world generation, or by mods' deferred
 * generation), so `pregen` can unload all of them again.
 */
object ChunkLoads {
    private var world: World? = null
    private val loaded = LinkedHashSet<Long>()

    fun start(world: World) {
        this.world = world
        loaded.clear()
    }

    /** The chunks loaded since [start] or the previous call. */
    fun drain(): List<Long> = loaded.toList().also { loaded.clear() }

    fun stop() {
        world = null
        loaded.clear()
    }

    @SubscribeEvent
    fun onChunkLoad(event: ChunkEvent.Load) {
        if (event.world !== world) return
        loaded += (event.chunk.xPosition.toLong() shl 32) or (event.chunk.zPosition.toLong() and 0xffffffffL)
    }

    /**
     * Runs world generation that mods defer to the next world ticks, so it happens while its chunks are still loaded
     * instead of reloading them after `pregen` unloaded them: AE2 queues meteorite/compass work per world
     * (`appeng.hooks.TickHandler`), which loads every chunk around each generated one. Returns how many tasks ran.
     */
    fun runDeferredGeneration(world: World): Int {
        val queue = ae2Queue(world) ?: return 0
        var ran = 0
        while (true) {
            val task = queue.poll() ?: break
            runCatching { ae2Call?.invoke(task, world) }.onFailure { BmhMod.log.debug("AE2 world task failed", it) }
            ran++
        }
        return ran
    }

    private val ae2 by lazy {
        runCatching {
            val handler = Class.forName("appeng.hooks.TickHandler")
            val instance = handler.getField("INSTANCE").get(null)
            val field = handler.getDeclaredField("callQueue").apply { isAccessible = true }
            instance to field
        }.getOrNull()
    }

    private val ae2Call by lazy {
        runCatching { Class.forName("appeng.util.IWorldCallable").getMethod("call", World::class.java) }.getOrNull()
    }

    private fun ae2Queue(world: World): Queue<*>? {
        val (instance, field) = ae2 ?: return null
        return (field.get(instance) as Map<*, *>)[world] as Queue<*>?
    }
}
