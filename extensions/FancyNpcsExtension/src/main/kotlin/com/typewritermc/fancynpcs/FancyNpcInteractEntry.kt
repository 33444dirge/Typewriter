package com.typewritermc.fancynpcs

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.interaction.context
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.startDialogueWithOrNextDialogue
import com.typewritermc.engine.paper.utils.entityDispatcher
import de.oliver.fancynpcs.api.actions.ActionTrigger
import de.oliver.fancynpcs.api.events.NpcInteractEvent

@Entry("on_fancy_npc_interact", "点击 FancyNpcs NPC 时触发剧情", Colors.YELLOW, "fa6-solid:people-robbery")
class FancyNpcInteractEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("需要交互的 FancyNpcs NPC 引用。")
    val identifier: Ref<ReferenceFancyNpcEntry> = emptyRef(),
    @Help("留空时接受所有交互；ANY_CLICK 接受左键或右键。")
    val interactions: List<ActionTrigger> = emptyList(),
) : EventEntry

@EntryListener(FancyNpcInteractEntry::class)
fun onFancyNpcInteract(event: NpcInteractEvent, query: Query<FancyNpcInteractEntry>) {
    if (event.isCancelled) return
    val player = event.player
    val npcId = event.npc.data.id
    val npcName = event.npc.data.name
    val click = event.interactionType
    player.entityDispatcher.launch {
        query.findWhere { entry ->
            val target = entry.identifier.get()?.npc
            val acceptsClick = entry.interactions.isEmpty() || click in entry.interactions ||
                (ActionTrigger.ANY_CLICK in entry.interactions &&
                    click in listOf(ActionTrigger.LEFT_CLICK, ActionTrigger.RIGHT_CLICK, ActionTrigger.ANY_CLICK))
            (target == npcId || target == npcName) && acceptsClick
        }.startDialogueWithOrNextDialogue(player, context())
    }
}
