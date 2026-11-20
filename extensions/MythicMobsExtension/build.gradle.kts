import com.typewritermc.loader.ExtensionFlag

repositories {
    maven("https://mvn.lumine.io/repository/maven-public/") {
        content { includeGroupAndSubgroups("io.lumine") }
    }
}

dependencies {
    compileOnly(files("../../compile-libs/MythicMobs-5.13.1-20260807.jar"))
    testImplementation(files("../../compile-libs/MythicMobs-5.13.1-20260807.jar"))
}

typewriter {
    namespace = "typewritermc"

    extension {
        name = "MythicMobs"
        shortDescription = "Integrate MythicMobs with Typewriter."
        description = """
            |The MythicMobs Extension allows you to create MyticMobs, and trigger Skills from Typewriter.
            |Create cool particles during cinematics or have dialgues triggered when interacting with a MythicMob.
        """.trimMargin()
        flag(ExtensionFlag.Deprecated)
        engineVersion = rootProject.extra["typewriterEngineVersion"] as String
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE


        paper {
            dependency("MythicMobs")
        }
    }
}
