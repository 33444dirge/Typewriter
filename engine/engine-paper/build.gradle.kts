import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.papermc.hangarpublishplugin.model.Platforms
import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.1"
    id("io.papermc.hangar-publish-plugin") version "0.1.4"
}

configurations.named("testImplementation") {
    extendsFrom(configurations.compileOnly.get())
}

repositories {
    mavenCentral()
    // Floodgate & Geyser
    maven("https://repo.opencollab.dev/main/") {
        content {
            includeGroupAndSubgroups("org.geysermc")
            // Geyser's own transitives live here too.
            includeGroupAndSubgroups("org.cloudburstmc")
            includeGroupAndSubgroups("com.nukkitx")
        }
    }
    // PacketEvents
    maven("https://repo.codemc.io/repository/maven-releases/") {
        content { includeGroupAndSubgroups("com.github.retrooper") }
    }
    maven("https://repo.codemc.io/repository/maven-snapshots/") {
        content { includeGroupAndSubgroups("com.github.retrooper") }
    }
    // PlaceholderAPI
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
        content { includeGroup("me.clip") }
    }
    // PaperMC
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
        content {
            includeGroupAndSubgroups("io.papermc")
            includeGroup("com.mojang")
            includeGroup("net.md-5")
        }
    }
    // EntityLib
    maven {
        name = "TypewriterMC"
        url = uri("https://maven.typewritermc.com/external")
        content { includeGroupAndSubgroups("me.tofaa2") }
    }
}

dependencies {
    compileOnlyApi(files("../../compile-libs/shiroha-api-26.2-SNAPSHOT.jar"))
    compileOnlyApi("com.google.guava:guava:33.6.0-jre")
    compileOnlyApi("org.joml:joml:1.10.8")
    compileOnlyApi("org.yaml:snakeyaml:2.6")
    compileOnlyApi(files("../../compile-libs/bungeecord-chat.jar"))
    compileOnlyApi(files("../../compile-libs/brigadier-1.3.10.jar"))
    compileOnlyApi("it.unimi.dsi:fastutil:8.5.18")
    compileOnly("org.apache.maven.resolver:maven-resolver-api:1.9.18")

    api(project(":engine-core"))
    api(project(":engine-loader"))

    compileOnlyApi("com.corundumstudio.socketio:netty-socketio:1.7.19") // Keep this on a lower version as the newer version breaks the ping

    api("me.tofaa2:spigot:3.3.6-SNAPSHOT")
    compileOnlyApi("com.github.shynixn.mccoroutine:mccoroutine-folia-api:2.22.0")
    compileOnlyApi("com.github.shynixn.mccoroutine:mccoroutine-folia-core:2.22.0")

    // Doesn't want to load properly using the spigot api.
    compileOnlyApi("io.ktor:ktor-server-core-jvm:2.3.13")
    compileOnlyApi("io.ktor:ktor-server-netty-jvm:2.3.13")
    compileOnlyApi("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    compileOnlyApi("org.bstats:bstats-bukkit:3.2.1")

    val adventureVersion = "5.2.0"
    compileOnlyApi("net.kyori:adventure-api:$adventureVersion")
    compileOnlyApi("net.kyori:adventure-text-minimessage:$adventureVersion")
    compileOnlyApi("net.kyori:adventure-text-serializer-plain:$adventureVersion")
    compileOnlyApi("net.kyori:adventure-text-serializer-legacy:$adventureVersion")
    compileOnlyApi("net.kyori:adventure-text-serializer-gson:$adventureVersion")
    compileOnlyApi("net.kyori:adventure-text-logger-slf4j:$adventureVersion")
    compileOnlyApi("org.apache.logging.log4j:log4j-api:2.26.0")

    compileOnlyApi("com.github.retrooper:packetevents-api:2.13.0")
    compileOnlyApi("com.github.retrooper:packetevents-spigot:2.13.0")

    compileOnly(files("../../compile-libs/placeholderapi-2.12.2.jar"))
    compileOnlyApi("org.geysermc.geyser:api:2.9.5-SNAPSHOT")
    compileOnlyApi("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")

    testImplementation(files("../../compile-libs/shiroha-api-26.2-SNAPSHOT.jar"))
}

tasks.withType<ShadowJar> {
    manifest.attributes["Typewriter-Connection-Revision"] = "null-player-guard-20261108-1"
    manifest.attributes["Typewriter-Dialogue-Revision"] = "external-overlay-20261108-1"
    manifest.attributes["Typewriter-Dialogue-Input-Revision"] = "dialogue-input-sounds-20261108-1"
    manifest.attributes["Typewriter-Interaction-Revision"] = "sneak-exit-20261108-1"
    minimize {
        exclude("kotlin/**")
        exclude("org/intellij/**")
        exclude("org/jetbrains/**")
        exclude(dependency("web::"))
    }
}

tasks.register<Copy>("buildAndMove") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    group = "build"
    description = "Builds the jar and moves it to the server folder"
    outputs.upToDateWhen { false }

    into(file("../../server/plugins"))
    rename { "Typewriter.jar" }
}

tasks.register<Jar>("buildRelease") {
    dependsOn(tasks.shadowJar)
    from(zipTree(tasks.shadowJar.flatMap { it.archiveFile }))
    val compiledPanel = file("../../app/build/web")
    val panelResources = if (compiledPanel.resolve("index.html").isFile) compiledPanel else file("../../web-assets")
    from(panelResources) {
        into("web")
    }
    doFirst {
        check(panelResources.resolve("index.html").isFile) { "Missing compiled web panel resources" }
    }
    group = "build"
    description = "Builds the jar including the flutter web panel"

    outputs.upToDateWhen { false }

    archiveFileName = "Typewriter-${project.version}.jar"
    destinationDirectory.set(file("../../jars/engine"))
    manifest.from(tasks.shadowJar.get().manifest)
    manifest.attributes("Typewriter-Localization-Revision" to "zh-CN-20261108-1")
    manifest.attributes("Typewriter-Performance-Revision" to "bounded-resources-20261108-1")
}

fun executeGitCommand(vararg command: String): String {
    return providers.exec {
        commandLine("git", *command)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "Typewriter ${project.version} source build" }
}

fun latestCommitMessage(): String {
    return executeGitCommand("log", "-1", "--pretty=%B")
}

hangarPublish {
    publications.register("plugin") {
        version.set(project.version.toString())
        if (project.version.toString().contains("beta")) {
            channel.set("Beta")
        } else {
            channel.set("Release")
        }

        id.set("Typewriter")
        changelog.set(latestCommitMessage())
        apiKey.set(System.getenv("HANGAR_API_TOKEN"))

        platforms {
            register(Platforms.PAPER) {
                url.set("https://modrinth.com/plugin/typewriter/version/${project.version}")

                val versions: List<String> = (property("paperVersion") as String)
                    .split(",")
                    .map { it.trim() }
                platformVersions.set(versions)

                dependencies {
                    url("PacketEvents", "https://modrinth.com/plugin/packetevents/versions?l=paper") {
                        required.set(true)
                    }
                    hangar("PlaceholderAPI") {
                        required.set(false)
                    }
                    hangar("Floodgate") {
                        required.set(false)
                    }
                }
            }
        }
    }
}

paperPluginYaml {
    name = "Typewriter"
    description = "Next Generation Story Telling Plugin"
    authors = listOf("gabber235")
    website = "https://docs.typewritermc.com"
    version = project.version.toString()

    main = "com.typewritermc.engine.paper.TypewriterPaperPlugin"
    apiVersion = "1.21.3"

    foliaSupported = true

    dependencies {
        server("packetevents", load = PaperPluginYaml.Load.BEFORE, required = true, joinClasspath = true)
        server("PlaceholderAPI", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
        server("floodgate", load = PaperPluginYaml.Load.BEFORE, required = false, joinClasspath = true)
    }

    loader = "com.typewritermc.engine.paper.TypewriterPaperLoader"
}
