package com.typewritermc.fancynpcs

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.StaticEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.SoundEmitter
import com.typewritermc.engine.paper.entry.entries.SoundSourceEntry
import com.typewritermc.engine.paper.entry.entries.SpeakerEntry
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.Sound
import de.oliver.fancynpcs.api.FancyNpcsPlugin
import de.oliver.fancynpcs.api.Npc
import org.bukkit.entity.Player

@Tags("reference_npc")
@Entry("reference_fancy_npc", "引用 FancyNpcs 中已有的 NPC", Colors.ORANGE, "fa-solid:user-tie")
class ReferenceFancyNpcEntry(
    override val id: String = "",
    override val name: String = "",
    override val displayName: Var<String> = ConstVar(""),
    override val sound: Var<Sound> = ConstVar(Sound.EMPTY),
    @Help("FancyNpcs 的 NPC 名称或内部 ID；不使用临时实体数字编号。")
    val npc: String = "",
) : SoundSourceEntry, SpeakerEntry, StaticEntry {
    fun resolve(): Npc? {
        val manager = FancyNpcsPlugin.get().npcManager
        return manager.getNpcById(npc) ?: manager.getNpc(npc)
    }

    override fun getEmitter(player: Player): SoundEmitter =
        SoundEmitter(resolve()?.entityId ?: player.entityId)
}
