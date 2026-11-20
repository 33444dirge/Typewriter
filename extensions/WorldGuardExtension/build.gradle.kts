repositories {
    maven("https://maven.enginehub.org/repo/") {
        content {
            includeGroupAndSubgroups("com.sk89q")
            includeGroupAndSubgroups("org.enginehub")
        }
    }
}

dependencies {
    compileOnly(files("../../compile-libs/worldguard-bukkit-7.0.19.jar"))
    testImplementation(files("../../compile-libs/worldguard-bukkit-7.0.19.jar"))
    compileOnly(files("../../compile-libs/WorldEdit-7.4.5-foliafix-1.jar"))
    testImplementation(files("../../compile-libs/WorldEdit-7.4.5-foliafix-1.jar"))
}

typewriter {
    namespace = "typewritermc"

    extension {
        name = "WorldGuard"
        shortDescription = "Integrate WorldGuard with Typewriter."
        description = """
            |The WorldGuard Extension allows you to create dialogue triggered by WorldGuard regions.
            |Have dialogues that only show up when a player enters or leaves a specific region.
            |Have sidebars that only show when the player is in a specific region.
        """.trimMargin()
        engineVersion = rootProject.extra["typewriterEngineVersion"] as String
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE


        paper {
            dependency("WorldGuard")
        }
    }
}
