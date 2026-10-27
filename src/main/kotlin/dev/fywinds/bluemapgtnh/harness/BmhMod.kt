package dev.fywinds.bluemapgtnh.harness

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.Loader
import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.event.FMLServerStartingEvent
import cpw.mods.fml.common.event.FMLServerStoppingEvent
import net.minecraftforge.common.MinecraftForge
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

@Mod(
    modid = BmhMod.MOD_ID,
    name = "BlueMap GTNH Test Harness",
    useMetadata = true,
    acceptableRemoteVersions = "*",
    dependencies = "required-after:forgelin;after:gregtech;after:structurelib",
    modLanguageAdapter = "net.shadowfacts.forgelin.KotlinAdapter",
)
object BmhMod {
    const val MOD_ID = "bmh"

    val log: Logger = LogManager.getLogger(MOD_ID)

    val version: String
        get() = Loader.instance().indexedModList[MOD_ID]?.version ?: "unknown"

    @Mod.EventHandler
    fun init(event: FMLInitializationEvent) {
        FMLCommonHandler.instance().bus().register(ServerThread)
        MinecraftForge.EVENT_BUS.register(FrozenTiles)
    }

    @Mod.EventHandler
    fun serverStarting(event: FMLServerStartingEvent) {
        ServerThread.bind()
        event.registerServerCommand(BmhCommand)
    }

    @Mod.EventHandler
    fun serverStopping(event: FMLServerStoppingEvent) {
        ServerThread.unbind()
    }
}
