@file:Suppress("UnstableApiUsage")

package com.typewritermc.engine.paper.command

import com.typewritermc.core.books.pages.PageType
import com.mojang.brigadier.arguments.StringArgumentType
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.formattedName
import com.typewritermc.core.interaction.context
import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.command.dsl.*
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.audienceState
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.entry.inAudience
import com.typewritermc.engine.paper.entry.temporal.temporalCommand
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.facts.FactData
import com.typewritermc.engine.paper.interaction.chatHistory
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.ui.CommunicationHandler
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.isFloodgate
import com.typewritermc.engine.paper.utils.msg
import com.typewritermc.engine.paper.utils.sendMini
import com.typewritermc.loader.Extension
import com.typewritermc.loader.ExtensionFlag
import com.typewritermc.loader.ExtensionInfo
import com.typewritermc.loader.ExtensionLoader
import kotlinx.coroutines.Dispatchers
import net.kyori.adventure.inventory.Book
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get
import java.time.format.DateTimeFormatter

fun typewriterCommand() = command("typewriter", "tw") {
    CommandMessage.registerDefaults()
    versionCommand()
    reloadCommand()
    extensionsCommand()
    factsCommand()
    clearChatCommand()
    connectCommand()
    triggerCommand()
    manifestCommand()
    temporalCommand()
    literal("run") {
        argument("command", StringArgumentType.greedyString(), String::class) {
            suggests { context, builder ->
                get<TypewriterCommandManager>(TypewriterCommandManager::class.java).suggestCustom(context.source, builder)
            }
            executes {
                get<TypewriterCommandManager>(TypewriterCommandManager::class.java)
                    .executeCustom(StringArgumentType.getString(context, "command"), source)
            }
        }
    }

    registerDynamicCommands()
}


fun CommandTree.registerDynamicCommands() {
    val extensionLoader = get<ExtensionLoader>(ExtensionLoader::class.java)
    extensionLoader.loadedExtensions.flatMap { it.typewriterCommands }
        .map {
            val clazz = extensionLoader.loadClass(it.className)
            clazz.getMethod(it.methodName, CommandTree::class.java)
        }
        .forEach {
            try {
                it.invoke(null, this)
            } catch (t: Throwable) {
                logger.severe("Exception thrown while registering command '${it.declaringClass.simpleName}'.")
                t.printStackTrace()
            }
        }
}

private fun CommandTree.versionCommand() = literal("version") {
    withPermission("typewriter.version")
    executes {
        sender.msg(CommandMessage.VERSION.text("version" to plugin.pluginMeta.version))
    }
}

private fun CommandTree.reloadCommand() = literal("reload") {
    withPermission("typewriter.reload")
    executes {
        sender.msg(CommandMessage.RELOADING.text())
        Dispatchers.UntickedAsync.launch {
            plugin.reload()
            sender.msg(CommandMessage.RELOADED.text())
        }
    }
}

private fun CommandTree.extensionsCommand() = literal("extensions") {
    withPermission("typewriter.extensions")

    literal("info") {
        withPermission("typewriter.extensions.info")
        extension<Extension>("extension") { extension ->
            executes {
                when (val extension = extension()) {
                    is Extension.LoadedExtension -> sender.displayLoadedExtension(extension)
                    is Extension.FailedExtension -> sender.displayFailedExtension(extension)
                }
            }
        }
    }

    executes {
        val extensionLoader = get<ExtensionLoader>(ExtensionLoader::class.java)
        val loaded = extensionLoader.loadedExtensions
        val failed = extensionLoader.failedExtensions

        if (loaded.isEmpty() && failed.isEmpty()) {
            sender.msg(CommandMessage.NO_EXTENSIONS.text())
            return@executes
        }

        sender.displayExtensionsList(loaded, failed)
    }
}

private fun CommandSender.displayExtensionsList(
    loaded: List<Extension.LoadedExtension>,
    failed: List<Extension.FailedExtension>
) {
    val message = buildString {
        append(createSection(CommandMessage.EXTENSIONS_TITLE.text()))

        if (loaded.isNotEmpty()) {
            append("<#7ed957><b>${CommandMessage.EXTENSIONS_LOADED.text()}</b> (${loaded.size}):</#7ed957>\n")

            loaded
                .sortedBy { it.info.name }
                .forEach { ext ->
                    val info = ext.info
                    val flags = info.flags.joinToString(" ") { it.warning }
                        .takeIf { it.isNotBlank() }?.let { " $it" } ?: ""

                    append(
                        "  <hover:show_text:'<gray>${CommandMessage.CLICK_DETAILS.text()}</gray>'>" +
                                "<click:run_command:'/tw extensions info ${info.namespace}:${info.name}'>" +
                                "<#7ed957>•</#7ed957> <white>${info.name}Extension</white> <#a0a0a0>(${info.version})</#a0a0a0>$flags" +
                                "</click></hover>\n"
                    )
                }

            val unsupportedCount = loaded.count { it.info.flags.contains(ExtensionFlag.Unsupported) }
            if (unsupportedCount > 0) {
                append("\n  <yellow>${CommandMessage.UNSUPPORTED_EXTENSIONS.text("count" to unsupportedCount)}</yellow>\n")
            }
        }

        if (failed.isNotEmpty()) {
            if (loaded.isNotEmpty()) append("\n")
            append("<#ff5555><b>${CommandMessage.EXTENSIONS_FAILED.text()}</b> (${failed.size}):</#ff5555>\n")

            failed.sortedBy { it.info?.name ?: it.jarName }.forEach { failure ->
                val displayName = failure.info?.name?.let { "${it}Extension" }
                    ?: failure.jarName.removeSuffix(".jar")
                val reason = failure.reason.message

                val content = "<#ff5555>•</#ff5555> <red>$displayName</red> <italic><#ff8888>($reason)</#ff8888>"

                failure.info?.let { info ->
                    append(
                        "  <hover:show_text:'<gray>${CommandMessage.CLICK_DETAILS.text()}</gray>'>" +
                                "<click:run_command:'/tw extensions info ${info.namespace}:${info.name}'>$content</click></hover>\n"
                    )
                } ?: append("  $content\n")
            }
        }

        append(createFooter())
    }

    sendMini(message)
}

private fun CommandSender.displayLoadedExtension(extension: Extension.LoadedExtension) {
    val info = extension.info

    val message = buildString {
        append(createExtensionHeader(info, isLoaded = true))
        append(createExtensionBasicInfo(info))

        append("\n<gradient:#ff69b4:#ff1493><bold>${CommandMessage.COMPONENTS.text()}:</bold></gradient>\n")
        listOf(
            CommandMessage.ENTRIES.text() to extension.entries.size,
            CommandMessage.LISTENERS.text() to extension.entryListeners.size,
            CommandMessage.INJECTIONS.text() to extension.dependencyInjections.size,
            CommandMessage.COMMANDS.text() to extension.typewriterCommands.size
        ).forEach { (label, count) ->
            append("  <#ff69b4>▸</#ff69b4> <#b8b8b8>$label:</#b8b8b8> <white>$count</white>\n")
        }

        if (info.dependencies.isNotEmpty()) {
            append("\n<#00d4ff>${CommandMessage.EXTENSION_DEPENDENCIES.text()}:</#00d4ff>\n")
            info.dependencies.forEach { dep ->
                append("  <#00d4ff>▸</#00d4ff> <white>${dep.name}</white> <dark_gray>·</dark_gray> <#a0a0a0>${dep.namespace}</#a0a0a0>\n")
            }
        }

        info.paper?.dependencies?.takeIf { it.isNotEmpty() }?.let { deps ->
            append("\n<#00d4ff>${CommandMessage.PLUGIN_DEPENDENCIES.text()}:</#00d4ff>\n")
            deps.forEach { append("  <#00d4ff>▸</#00d4ff> <white>$it</white>\n") }
        }

        if (info.flags.isNotEmpty()) {
            append("\n<gradient:#a855f7:#9333ea><bold>${CommandMessage.FLAGS.text()}:</bold></gradient>\n")
            info.flags.forEach { append("  ${it.warning}\n") }
        }

        append(createFooter())
    }

    sendMini(message)
}

private fun CommandSender.displayFailedExtension(failure: Extension.FailedExtension) {
    val message = buildString {
        failure.info?.let { info ->
            append(createExtensionHeader(info, isLoaded = false))
            append(createExtensionBasicInfo(info))
        } ?: run {
            append(createSection(CommandMessage.EXTENSION_DETAILS.text()))
            append("<red><b>${failure.jarName}</b> (${CommandMessage.NOT_LOADED.text()})</red>\n")
            append("<gray>${CommandMessage.JAR_FILE.text()}:</gray> ${failure.jarName}\n")
        }

        append("\n<red><b>${CommandMessage.FAILURE_REASON.text()}:</b></red>\n")
        append("  ${failure.reason.message}\n")
        append(createFooter())
    }

    sendMini(message)
}

private fun createSection(title: String): String {
    val (leftPadding, rightPadding) = calculatePadding(title)
    return "\n<gradient:#00d4ff:#0099ff><b><st>$leftPadding</st> $title <st>$rightPadding</st></b></gradient>\n\n"
}

private fun createFooter(): String =
    "\n<gradient:#00d4ff:#0099ff><b><st>${" ".repeat(53)}</st></b></gradient>\n"

private fun calculatePadding(title: String): Pair<String, String> {
    val remainingSpace = (50 - title.length - 2).coerceAtLeast(2)
    val leftPadding = remainingSpace / 2
    val rightPadding = remainingSpace - leftPadding  // Takes the extra space if odd
    return " ".repeat(leftPadding) to " ".repeat(rightPadding)
}

private fun createExtensionHeader(info: ExtensionInfo, isLoaded: Boolean): String {
    val (color, status) = if (isLoaded) "gradient:#7ed957:#00d4ff" to "" else "red" to " (${CommandMessage.NOT_LOADED.text()})"
    return buildString {
        append(createSection(CommandMessage.EXTENSION_DETAILS.text()))
        append("<$color><b>${info.name}Extension</b>$status</$color>\n")
    }
}

private fun createExtensionBasicInfo(info: ExtensionInfo): String = buildString {
    append("<#5ba3d0>${CommandMessage.EXTENSION_VERSION.text()}:</#5ba3d0> ${info.version}\n")
    append("<#5ba3d0>${CommandMessage.NAMESPACE.text()}:</#5ba3d0> ${info.namespace}\n")
    append("<#5ba3d0>${CommandMessage.ENGINE_VERSION.text()}:</#5ba3d0> ${info.engineVersion}\n")

    if (info.shortDescription.isNotBlank()) {
        append("\n<gradient:#ffd700:#ffaa00><bold>${CommandMessage.DESCRIPTION.text()}:</bold></gradient>\n")
        append("<#e0e0e0>${info.shortDescription}</#e0e0e0>\n")
    }
}

private fun CommandTree.factsCommand() = literal("facts") {
    withPermission("typewriter.facts")


    literal("set") {
        withPermission("typewriter.facts.set")
        entry<WritableFactEntry>("fact") { fact ->
            int("value") { value ->
                executePlayerOrTarget { target ->
                    fact().write(target, value())
                    sender.msg(CommandMessage.FACT_SET.text("player" to target.name, "fact" to fact().formattedName, "value" to value()))
                }
            }
        }
    }

    literal("add") {
        withPermission("typewriter.facts.add")
        entry<WritableFactEntry>("fact") { fact ->
            int("value") { value ->
                executePlayerOrTarget { target ->
                    val fact = fact()
                    if (fact !is ReadableFactEntry) {
                        sender.msg(CommandMessage.FACT_NOT_READABLE.text())
                        return@executePlayerOrTarget
                    }

                    val newValue = if (fact is CachableFactEntry) {
                        fact.add(target, value())?.after?.value ?: return@executePlayerOrTarget
                    } else {
                        val updated = fact.readForPlayersGroup(target).value + value()
                        fact().write(target, updated)
                        updated
                    }
                    sender.msg(CommandMessage.FACT_SET.text("player" to target.name, "fact" to fact().formattedName, "value" to newValue))
                }
            }
        }
    }

    literal("reset") {
        withPermission("typewriter.facts.reset")
        executePlayerOrTarget { target ->
            val entries = Query.find<WritableFactEntry>().toList()
            if (entries.isEmpty()) {
                sender.msg(CommandMessage.NO_FACTS.text())
                return@executePlayerOrTarget
            }

            for (entry in entries) {
                entry.write(target, 0)
            }
            sender.msg(CommandMessage.FACTS_RESET.text("player" to target.name))
        }
    }

    literal("query") {
        entry<ReadableFactEntry>("fact") { fact ->
            executePlayerOrTarget { target ->
                sender.sendMini(CommandMessage.FACT_QUERY.text("player" to target.name))
                sender.sendMini(fact().format(target))
            }
        }
    }

    literal("inspect") {
        page("page", PageType.STATIC) { page ->
            executePlayerOrTarget { target ->
                val facts = page().entries.filterIsInstance<ReadableFactEntry>()
                    .map { it to it.readForPlayersGroup(target) }
                    .sortedByDescending { (_, data) -> data.value }
                sender.sendMini(CommandMessage.FACTS_PAGE.text("player" to target.name, "page" to page().name))

                if (facts.isEmpty()) {
                    sender.msg(CommandMessage.NO_PAGE_FACTS.text())
                    return@executePlayerOrTarget
                }

                for ((fact, data) in facts) {
                    sender.sendMini(fact.format(target, data))
                }
            }
        }
    }

    executePlayerOrTarget { target ->
        val factEntries = Query.find<ReadableFactEntry>().toList()
            .map { it to it.readForPlayersGroup(target) }
            .sortedByDescending { (_, data) -> data.value }
        if (factEntries.isEmpty()) {
            sender.msg(CommandMessage.NO_FACTS.text())
            return@executePlayerOrTarget
        }

        sender.sendMini("\n\n")
        sender.msg(CommandMessage.FACTS_LIST.text("player" to target.name))

        for ((entry, data) in factEntries.take(10)) {
            sender.sendMini(entry.format(target, data))
        }

        val remaining = factEntries.size - 10
        if (remaining > 0) {
            sender.sendMini(
                CommandMessage.FACTS_MORE.text("count" to remaining)
            )
        }
    }
}

private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy")
private fun ReadableFactEntry.format(player: Player, data: FactData = readForPlayersGroup(player)): String {
    return "<hover:show_text:'${
        comment.replace(
            Regex(" +"),
            " "
        ).replace("'", "\\'")
    }\\n\\n<gray><i>${CommandMessage.CLICK_MODIFY.text().replace("'", "\\'")}'><click:suggest_command:'/tw facts set $name ${data.value} ${player.name}'><gray> - </gray><blue>${formattedName}:</blue> ${data.value} <gray><i>(${
        formatter.format(
            data.lastUpdate
        )
    })</i></gray>"
}

private fun CommandTree.clearChatCommand() = literal("clearChat") {
    withPermission("typewriter.clearChat")
    executePlayerOrTarget { player ->
        player.chatHistory.let {
            it.clear()
            it.allowedMessageThrough()
            it.resendMessages(player)
        }
    }
}


private fun CommandTree.connectCommand() = literal("connect") {
    val communicationHandler: CommunicationHandler = get(CommunicationHandler::class.java)
    withPermission("typewriter.connect")
    executes {
        if (communicationHandler.server == null) {
            sender.msg(CommandMessage.CONNECT_DISABLED.text())
            return@executes
        }

        val player = (source.executor as? Player) ?: (sender as? Player)
        val url = communicationHandler.generateUrl(player?.uniqueId)

        if (player == null) {
            sender.msg(CommandMessage.CONNECT_LINK.text("url" to url))
            return@executes
        }


        val bookTitle = CommandMessage.CONNECT_BOOK_TITLE.text().asMini()
        val bookAuthor = CommandMessage.CONNECT_BOOK_AUTHOR.text().asMini()

        val bookPage = CommandMessage.CONNECT_BOOK_PAGE.text("url" to url).asMini()

        if (player.isFloodgate) {
            sender.sendMessage(bookPage)
            return@executes
        }

        val book = Book.book(bookTitle, bookAuthor, listOf(bookPage))
        player.openBook(book)
    }

    playerResolver("target") { player ->
        executes {
            if (source.sender !is ConsoleCommandSender) {
                sender.msg(CommandMessage.CONNECT_CONSOLE_ONLY.text())
                return@executes
            }
            val targets = player().resolve(source)
            if (targets.isEmpty()) {
                sender.msg(CommandMessage.NO_PLAYERS.text())
                return@executes
            }

            if (targets.size > 1) {
                sender.msg(CommandMessage.CONNECT_SINGLE_PLAYER.text())
                return@executes
            }

            val target = targets.first()

            val url = communicationHandler.generateUrl(target.uniqueId)
            sender.msg(CommandMessage.CONNECT_LINK.text("url" to url))
        }
    }
}

private fun CommandTree.triggerCommand() = literal("trigger") {
    withPermission("typewriter.trigger")
    entry<TriggerableEntry>("entry") { entry ->
        executePlayerOrTarget { target ->
            EntryTrigger(entry()).triggerFor(target, context())
        }
    }
}

private fun CommandTree.manifestCommand() = literal("manifest") {
    withPermission("typewriter.manifest")
    literal("inspect") {
        withPermission("typewriter.manifest.inspect")
        executePlayerOrTarget { target ->
            val inEntries = Query.findWhere<AudienceEntry> { target.inAudience(it) }
                .sortedBy { it.name }
                .toList()

            if (inEntries.none()) {
                sender.msg(CommandMessage.NO_AUDIENCE.text())
                return@executePlayerOrTarget
            }

            sender.sendMini("\n\n")
            sender.msg(CommandMessage.AUDIENCE_LIST.text())
            for (entry in inEntries) {
                sender.sendMini(
                    "<hover:show_text:'<gray>${entry.id}'><click:copy_to_clipboard:${entry.id}><gray> - </gray><blue>${entry.formattedName}</blue></click></hover>"
                )
            }
        }
    }

    literal("page") {
        withPermission("typewriter.manifest.page")
        page("page", PageType.MANIFEST) { page ->
            executePlayerOrTarget { target ->
                val audienceEntries = page().entries
                    .filterIsInstance<AudienceEntry>()
                    .sortedBy { it.name }
                    .toList()

                if (audienceEntries.isEmpty()) {
                    sender.msg(CommandMessage.NO_PAGE_AUDIENCE.text("page" to page().name))
                    return@executePlayerOrTarget
                }

                val entryStates = audienceEntries.groupBy { target.audienceState(it) }

                sender.sendMini("\n\n")
                sender.msg(CommandMessage.AUDIENCE_PAGE.text("page" to page().name))
                for (state in AudienceDisplayState.entries) {
                    val entries = entryStates[state] ?: continue
                    val color = state.color
                    val label = when (state) {
                        AudienceDisplayState.IN_AUDIENCE -> CommandMessage.AUDIENCE_IN
                        AudienceDisplayState.BLOCKED -> CommandMessage.AUDIENCE_BLOCKED
                        AudienceDisplayState.NOT_CONSIDERED -> CommandMessage.AUDIENCE_NOT_CONSIDERED
                    }
                    sender.sendMini("\n<b><$color>${label.text()}</$color></b>")

                    for (entry in entries) {
                        sender.sendMini(
                            "<hover:show_text:'<gray>${entry.id}'><click:copy_to_clipboard:${entry.id}><gray> - </gray><$color>${entry.formattedName}</$color></click></hover>"
                        )
                    }
                }
            }
        }
    }
}
