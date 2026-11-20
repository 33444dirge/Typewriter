package com.typewritermc.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.typewritermc.engine.paper.command.CommandSnapshot
import com.typewritermc.engine.paper.command.dsl.DslCommand
import com.typewritermc.engine.paper.command.shifted
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

private fun command(name: String, result: Int) = DslCommand(name, listOf("alias"),
    LiteralArgumentBuilder.literal<Int>(name).requires { it > 0 }
        .then(LiteralArgumentBuilder.literal<Int>("confirm")
            .then(RequiredArgumentBuilder.argument<Int, Int>("count", IntegerArgumentType.integer(1, 10))
                .executes { result + IntegerArgumentType.getInteger(it, "count") }))
        .build())

class CommandSnapshotSpec : FunSpec({
    test("namespaces and aliases retain nested argument parsing and permission filtering") {
        val snapshot = CommandSnapshot(listOf(command("story", 10)), "typewriter")
        snapshot.dispatcher.execute("story confirm 2", 1) shouldBe 12
        snapshot.dispatcher.execute("alias confirm 3", 1) shouldBe 13
        snapshot.dispatcher.execute("typewriter:story confirm 4", 1) shouldBe 14
        snapshot.dispatcher.execute("typewriter:alias confirm 5", 1) shouldBe 15
        snapshot.clientNodes(0).size shouldBe 0
        snapshot.clientNodes(1).map { it.literal }.toSet() shouldBe snapshot.labels.toSet()
    }

    test("modifying a client copy cannot change the executable server snapshot") {
        val snapshot = CommandSnapshot(listOf(command("story", 10)), "typewriter")
        val client = CommandDispatcher<Int>()
        snapshot.clientNodes(1).forEach(client.root::addChild)
        client.root.getChild("story").addChild(LiteralArgumentBuilder.literal<Int>("client_only").build())
        snapshot.dispatcher.root.getChild("typewriter:story").getChild("client_only") shouldBe null
        client.execute("alias confirm 3", 1) shouldBe 0
        snapshot.dispatcher.execute("alias confirm 3", 1) shouldBe 13
    }

    test("server completions filter inaccessible children even when the parent root is public") {
        val command = DslCommand("public", emptyList(), LiteralArgumentBuilder.literal<Int>("public")
            .then(LiteralArgumentBuilder.literal<Int>("secret").requires { it > 0 }.executes { 1 })
            .then(LiteralArgumentBuilder.literal<Int>("visible").executes { 1 }).build())
        val snapshot = CommandSnapshot(listOf(command), "typewriter")
        val public = snapshot.completionDispatcher(0)
        public.getCompletionSuggestions(public.parse("public ", 0)).get().list.map { it.text } shouldBe listOf("visible")
        val allowed = snapshot.completionDispatcher(1)
        allowed.getCompletionSuggestions(allowed.parse("public ", 1)).get().list.map { it.text } shouldBe
            listOf("secret", "visible")
    }

    test("concurrent parsing and replacement never mutates a previously published tree") {
        val original = CommandSnapshot(listOf(command("story", 10)), "typewriter")
        val published = AtomicReference(original)
        coroutineScope {
            val readers = List(8) {
                async(Dispatchers.Default) {
                    repeat(250) {
                        val current = published.get()
                        val result = current.dispatcher.execute("alias confirm 1", 1)
                        (result == 11 || result == 21) shouldBe true
                        current.clientNodes(1).size shouldBe 4
                    }
                }
            }
            repeat(100) {
                published.set(CommandSnapshot(listOf(command("story", if (it % 2 == 0) 10 else 20)), "typewriter"))
            }
            readers.awaitAll()
        }
        original.dispatcher.execute("alias confirm 1", 1) shouldBe 11
    }

    test("nested completion ranges shift back into the caller input without changing the suggestion text") {
        val suggestions = Suggestions(StringRange.between(6, 8), listOf(Suggestion(StringRange.between(6, 8), "confirm")))
        val shifted = suggestions.shifted(23)
        shifted.range shouldBe StringRange.between(29, 31)
        shifted.list.single().text shouldBe "confirm"
    }
})
