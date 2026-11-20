package com.typewritermc.basic.entries.cinematic

import com.typewritermc.core.serialization.createDataSerializerGson
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.loader.serializers.*
import com.typewritermc.engine.paper.utils.SelfSoundSource
import com.typewritermc.engine.paper.utils.Sound
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.World
import java.util.UUID

class StationaryCameraSerializationSpec : FunSpec({
    val server = mockk<Server>()
    val world = mockk<World>()
    val worldId = UUID.fromString("8f239311-e067-4c48-b2a5-950a8d5d973b")
    val gson = createDataSerializerGson(listOf(
        VarSerializer(), PositionSerializer(), WorldSerializer(), SoundIdSerializer(), SoundSourceSerializer(),
    ))
    fun resource(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/stationary-camera/$name"),
    ).bufferedReader().use { it.readText() }

    beforeTest {
        mockkStatic(Bukkit::class)
        every { Bukkit.getServer() } returns server
        every { server.getWorld("worlds_lobby") } returns world
        every { world.uid } returns worldId
    }
    afterTest { unmockkStatic(Bukkit::class) }

    test("delivered shot deserializes with the engine serializers and resolves the named world") {
        val entry = gson.fromJson(resource("baige-entry.json"), StationaryCameraCinematicEntry::class.java)
        entry.worldName shouldBe "worlds_lobby"
        entry.maxDistance shouldBe 96.0
        entry.segments.size shouldBe 1
        val segment = entry.segments.single()
        val start = (segment.start as ConstVar).value
        val end = (segment.end as ConstVar).value
        val target = (segment.lookAt as ConstVar).value
        start.world.identifier shouldBe worldId.toString()
        listOf(start.x, start.y, start.z) shouldBe listOf(35.0, 83.0, 28.0)
        listOf(end.x, end.y, end.z) shouldBe listOf(39.0, 83.0, 34.0)
        listOf(target.x, target.y, target.z) shouldBe listOf(40.0, 82.0, 29.0)
        segment.eyeOffset shouldBe 1.6
        segment.targetOffset shouldBe 1.6
        segment.usePointRotation shouldBe false
        segment.moveFrames shouldBe 40
        segment.endFrame - segment.startFrame shouldBe 140
    }

    test("explicit point rotation survives the engine serializers") {
        val payload = resource("baige-entry.json").replace("\"moveFrames\": 40", "\"usePointRotation\": true, \"moveFrames\": 40")
        val entry = gson.fromJson(payload, StationaryCameraCinematicEntry::class.java)
        entry.segments.single().usePointRotation shouldBe true
    }

    test("speaker sound deserializes as a quiet self sound with constant volume and pitch") {
        val sound = gson.fromJson(resource("speaker-sound.json"), Sound::class.java)
        sound.soundId.namespacedKey.toString() shouldBe "minecraft:block.note_block.chime"
        sound.soundSource shouldBe SelfSoundSource
        (sound.volume as ConstVar).value shouldBe 0.35f
        (sound.pitch as ConstVar).value shouldBe 1.35f
    }
})
