package com.typewritermc.command

import com.google.gson.JsonParser
import com.typewritermc.engine.paper.command.startupCommandLabels
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StartupCommandLabelsSpec : FunSpec({
    test("only command entries reserve roots and aliases, never executable action text") {
        val page = JsonParser.parseString("""{"entries":[
            {"blueprintId":"custom_command","command":"story","aliases":["quest","story"]},
            {"blueprintId":"on_run_command","command":"reward"},
            {"blueprintId":"player_command","command":"stop"}
        ]}""").asJsonObject
        startupCommandLabels(listOf(page), listOf("extension")) shouldBe
            setOf("typewriter", "tw", "story", "quest", "reward", "extension")
    }

    test("invalid roots and malformed fields cannot reserve unrelated or partial commands") {
        val page = JsonParser.parseString("""{"entries":[null,{},
            {"blueprintId":"custom_command","command":"/bad","aliases":[null,false,"two words","x:y","good"]},
            {"blueprintId":"custom_command","command":{},"aliases":{}}
        ]}""").asJsonObject
        val malformed = JsonParser.parseString("""{"entries":false}""").asJsonObject
        startupCommandLabels(listOf(page, malformed), listOf("", "nested command", "a".repeat(129))) shouldBe
            setOf("typewriter", "tw", "good")
    }
})
