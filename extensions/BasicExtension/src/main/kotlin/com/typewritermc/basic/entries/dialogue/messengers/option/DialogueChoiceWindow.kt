package com.typewritermc.basic.entries.dialogue.messengers.option

internal data class DialogueChoiceWindow<T>(
    val choices: List<T>,
    val selectedIndex: Int,
    val offset: Int,
)

internal fun <T> dialogueChoiceWindow(choices: List<T>, selectedIndex: Int): DialogueChoiceWindow<T> {
    if (choices.isEmpty()) return DialogueChoiceWindow(emptyList(), 0, 0)
    val selected = selectedIndex.coerceIn(choices.indices)
    val offset = selected / 4 * 4
    return DialogueChoiceWindow(choices.drop(offset).take(4), selected - offset, offset)
}
