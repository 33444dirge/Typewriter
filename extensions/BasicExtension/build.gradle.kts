repositories { }
tasks.withType<Jar>().configureEach {
    manifest.attributes["Typewriter-Dialogue-Revision"] = "external-overlay-persistent-20261108-3"
    manifest.attributes["Typewriter-Camera-Revision"] = "stationary-camera-20261108-2"
}

dependencies {
    implementation("com.mthaler:aparser:0.4.0")
    testImplementation("com.github.shynixn.mccoroutine:mccoroutine-folia-api:2.22.0")
    testImplementation("org.geysermc.geyser:api:2.9.5-SNAPSHOT")
    testImplementation("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
}

typewriter {
    namespace = "typewritermc"

    extension {
        name = "Basic"
        shortDescription = "For all the most basic entries"
        description = """
            The Basic Extension contains all the essential entries for any server.
            In most cases, it should be installed with Typewriter.
            If you haven't installed Typewriter or the Basic Extension,
            please follow the [Installation Guide](https://docs.typewritermc.com/docs/getting-started/installation)
            first.
        """.trimIndent()
        engineVersion = rootProject.extra["typewriterEngineVersion"] as String
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        paper()
    }
}
