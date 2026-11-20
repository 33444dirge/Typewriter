package com.typewritermc.basic.entries.dialogue.messengers.option

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DialogueChoiceWindowSpec : StringSpec({
    "all three answers remain visible including the correct last answer" {
        val answers = listOf("damaged", "refused", "missing zero island")
        for (selected in answers.indices) {
            val page = dialogueChoiceWindow(answers, selected)
            page.choices shouldBe answers
            page.offset shouldBe 0
            page.choices[page.selectedIndex] shouldBe answers[selected]
        }
    }

    "four choices use both rows without an extra page" {
        val choices = listOf("one", "two", "three", "four")
        val page = dialogueChoiceWindow(choices, 3)
        page.choices shouldBe choices
        page.selectedIndex shouldBe 3
        page.offset shouldBe 0
    }

    "the fifth option has its own page and preserves its real action" {
        val choices = listOf("investigate", "status", "claim", "side quest", "leave")
        val page = dialogueChoiceWindow(choices, 4)
        page.choices shouldBe listOf("leave")
        page.selectedIndex shouldBe 0
        page.offset shouldBe 4
        page.choices[page.selectedIndex] shouldBe choices[4]
    }

    "scrolling back and wrapping never confirm an adjacent hidden option" {
        val choices = (0..8).map { Any() }
        for (selected in listOf(0, 3, 4, 7, 8, 0, 8, 7, 4, 3, 0)) {
            val page = dialogueChoiceWindow(choices, selected)
            (page.choices[page.selectedIndex] === choices[selected]) shouldBe true
            page.choices.size.coerceAtMost(4) shouldBe page.choices.size
            page.offset + page.selectedIndex shouldBe selected
        }
    }

    "condition filtering changes the page without restoring a hidden reward choice" {
        val choices = listOf("investigate", "status", "claim", "side quest", "leave")
        val filtered = choices.filterNot { it == "claim" }
        val page = dialogueChoiceWindow(filtered, 2)
        page.choices shouldBe listOf("investigate", "status", "side quest", "leave")
        page.choices[page.selectedIndex] shouldBe "side quest"
    }

    "empty choices do not retain the old page or selection" {
        dialogueChoiceWindow(emptyList<String>(), 8) shouldBe DialogueChoiceWindow(emptyList(), 0, 0)
    }
})
