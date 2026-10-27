package dev.fywinds.bluemapgtnh.harness

import com.google.gson.JsonObject
import net.minecraft.command.CommandBase
import net.minecraft.command.ICommandSender
import net.minecraft.util.ChatComponentText
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * `/bmh <sub> ...`: replies with exactly one line, `OK <compact-json>` or `ERR <message>`.
 *
 * RCON commands run on the RCON thread in 1.7.10, so every subcommand is marshalled onto the server thread and the
 * caller blocks until it finishes.
 */
object BmhCommand : CommandBase() {
    private const val USAGE = "/bmh <ping|registry|set|fill|pregen|mte|multiblock|zoo|dump|flush> ..."
    private const val TIMEOUT_MINUTES = 30L

    override fun getCommandName(): String = "bmh"

    override fun getCommandUsage(sender: ICommandSender): String = USAGE

    override fun getRequiredPermissionLevel(): Int = 4

    override fun processCommand(sender: ICommandSender, args: Array<String>) {
        val reply = try {
            ServerThread.call(TIMEOUT_MINUTES) { execute(args) }
        } catch (e: TimeoutException) {
            "ERR timed out after $TIMEOUT_MINUTES min waiting for the server thread"
        } catch (e: ExecutionException) {
            error(e.cause ?: e)
        }
        sender.addChatMessage(ChatComponentText(reply))
    }

    private fun error(t: Throwable): String = "ERR " + oneLine(t.message ?: t.javaClass.name)

    private fun oneLine(s: String) = s.replace(Regex("[\\r\\n]+"), " ")

    private fun execute(args: Array<String>): String = try {
        "OK " + dispatch(Args(args))
    } catch (e: BmhException) {
        error(e)
    } catch (t: Throwable) {
        BmhMod.log.error("bmh {} failed", args.joinToString(" "), t)
        "ERR " + oneLine("${t.javaClass.simpleName}: ${t.message}")
    } finally {
        FrozenTiles.commitPending()
        WorldOps.releaseChunks()
    }

    private fun dispatch(a: Args): JsonObject = when (val sub = a.word("subcommand")) {
        "ping" -> a.done { WorldOps.ping() }
        "registry" -> {
            val file = a.word("file")
            a.done { WorldOps.registry(file) }
        }
        "set" -> {
            val dim = a.int("dim")
            val x = a.int("x"); val y = a.int("y"); val z = a.int("z")
            val name = a.word("name"); val meta = a.int("meta")
            a.done { WorldOps.set(dim, x, y, z, name, meta) }
        }
        "fill" -> {
            val dim = a.int("dim")
            val x1 = a.int("x1"); val y1 = a.int("y1"); val z1 = a.int("z1")
            val x2 = a.int("x2"); val y2 = a.int("y2"); val z2 = a.int("z2")
            val name = a.word("name"); val meta = a.int("meta")
            a.done { WorldOps.fill(dim, x1, y1, z1, x2, y2, z2, name, meta) }
        }
        "pregen" -> {
            val dim = a.int("dim")
            val cx1 = a.int("cx1"); val cz1 = a.int("cz1"); val cx2 = a.int("cx2"); val cz2 = a.int("cz2")
            a.done { WorldOps.pregen(dim, cx1, cz1, cx2, cz2) }
        }
        "mte" -> {
            val dim = a.int("dim")
            val x = a.int("x"); val y = a.int("y"); val z = a.int("z")
            val mId = a.int("mID"); val facing = a.int("facing")
            a.done { GtCompat.mte(dim, x, y, z, mId, facing) }
        }
        "multiblock" -> {
            val dim = a.int("dim")
            val x = a.int("x"); val y = a.int("y"); val z = a.int("z")
            val tier = a.optionalInt("tier") ?: 1
            a.done { GtCompat.multiblock(dim, x, y, z, tier) }
        }
        "zoo" -> {
            val dim = a.int("dim")
            val x = a.int("x"); val y = a.int("y"); val z = a.int("z")
            val modid = a.word("modid|all"); val file = a.word("file")
            a.done { WorldOps.zoo(dim, x, y, z, modid, file) }
        }
        "dump" -> {
            val dim = a.int("dim")
            val x1 = a.int("x1"); val y1 = a.int("y1"); val z1 = a.int("z1")
            val x2 = a.int("x2"); val y2 = a.int("y2"); val z2 = a.int("z2")
            val file = a.word("file")
            val air = when (val flag = a.optionalWord()) {
                null -> false
                "air" -> true
                else -> throw BmhException("unexpected argument $flag (expected 'air')")
            }
            a.done { WorldOps.dump(dim, x1, y1, z1, x2, y2, z2, file, air) }
        }
        "flush" -> a.done { WorldOps.flush() }
        else -> throw BmhException("unknown subcommand $sub; usage: $USAGE")
    }

    private class Args(private val args: Array<String>) {
        private var pos = 0

        fun word(name: String): String = args.getOrNull(pos++) ?: throw BmhException("missing <$name>")

        fun optionalWord(): String? = args.getOrNull(pos)?.also { pos++ }

        fun int(name: String): Int {
            val raw = word(name)
            return raw.toIntOrNull() ?: throw BmhException("<$name> must be an integer, got $raw")
        }

        fun optionalInt(name: String): Int? = optionalWord()?.let {
            it.toIntOrNull() ?: throw BmhException("<$name> must be an integer, got $it")
        }

        fun <T> done(block: () -> T): T {
            if (pos < args.size) throw BmhException("unexpected argument ${args[pos]}")
            return block()
        }
    }
}
