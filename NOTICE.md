# Third-party notices

This mod is MIT ([LICENSE](LICENSE)). It is unofficial and not affiliated with BlueMap, GT New Horizons, Mojang or
Microsoft, or the authors of any mod named here. The complete list for the whole project is the umbrella repository's
[NOTICE.md](https://github.com/Bluemap-GTNH-Unofficial/BlueMap-GTNH/blob/main/NOTICE.md).

| Source | Copyright / author | Licence | What we used |
|---|---|---|---|
| [GTNewHorizons/ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) | Copyright (c) 2021 Johann Bernhardt | MIT | Template: build layout (`gradle.properties` keys, `settings.gradle.kts`, `.gitignore`). Its notice is in [LICENSE](LICENSE). |
| [Gradle wrapper](https://github.com/gradle/gradle) | Copyright the original authors (Gradle) | Apache-2.0 | Unchanged `gradlew`, `gradlew.bat`, `gradle/wrapper/`. |
| [Hodgepodge](https://github.com/GTNewHorizons/Hodgepodge) | mitchej123 and the GTNH team | LGPL-3.0 | Behaviour reference: RCON threading (`ServerThread.kt`); its chunk generation scheduler is looked up by reflection (`WorldOps.kt`). |

## Compiled against (not redistributed)

The jar shades nothing; the GT New Horizons pack provides these at run time.

| Library | Licence | Used for |
|---|---|---|
| Minecraft 1.7.10 and Minecraft Forge | Mojang EULA / Forge licence | the mod itself |
| [Forgelin](https://github.com/GTNewHorizons/Forgelin) (Kotlin runtime) | LGPL-3.0 | Kotlin runtime |
| [GT5-Unofficial](https://github.com/GTNewHorizons/GT5-Unofficial) | LGPL-3.0 | GregTech machine placement (`GtCompat.kt`) |
| [StructureLib](https://github.com/GTNewHorizons/StructureLib) | LGPL-3.0 | multiblock auto-construction in test scenes (`GtCompat.kt`) |
| [GTNHLib](https://github.com/GTNewHorizons/GTNHLib) | LGPL-3.0 | development runs only (GT5-Unofficial needs it); no harness code calls it |
| [EndlessIDs](https://github.com/FalsePattern/EndlessIDs), [ChunkAPI](https://github.com/FalsePattern/ChunkAPI) | see their repositories | development runs only |
