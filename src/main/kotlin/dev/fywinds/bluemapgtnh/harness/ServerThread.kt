package dev.fywinds.bluemapgtnh.harness

import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.TickEvent
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Runs work on the server thread. 1.7.10's MinecraftServer has no task queue, and vanilla runs RCON commands on the
 * RCON thread, so off-thread tasks are queued here and drained at the start of every server tick. With GTNH's
 * Hodgepodge `fixRconThreading` (pack default) RCON commands already run on the server thread and run inline.
 */
object ServerThread {
    @Volatile
    private var thread: Thread? = null
    private val queue = ConcurrentLinkedQueue<FutureTask<String>>()

    /** Called from FMLServerStartingEvent, which runs on the server thread. */
    fun bind() {
        thread = Thread.currentThread()
    }

    /** Called from FMLServerStoppingEvent: fail anything still queued instead of leaving callers blocked. */
    fun unbind() {
        thread = null
        while (true) queue.poll()?.cancel(false) ?: break
    }

    fun call(timeoutMinutes: Long, task: () -> String): String {
        if (Thread.currentThread() === thread) return task()
        val future = FutureTask(task)
        queue.add(future)
        return try {
            future.get(timeoutMinutes, TimeUnit.MINUTES)
        } catch (e: CancellationException) {
            "ERR server stopping"
        }
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        while (true) queue.poll()?.run() ?: break
    }
}
