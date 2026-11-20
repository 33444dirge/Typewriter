package com.typewritermc.engine.paper.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.google.gson.JsonParser
import com.typewritermc.core.entries.Query
import com.typewritermc.engine.paper.command.dsl.DslCommand
import com.typewritermc.engine.paper.entry.entries.CustomCommandEntry
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.engine.paper.utils.msg
import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference


@Suppress("UnstableApiUsage")
class TypewriterCommandManager : Listener {
    private val snapshot = AtomicReference<CommandSnapshot<CommandSourceStack>?>()
    private var startupLabels: Set<String> = emptySet()
    @Volatile private var registeredLabels: Set<String> = emptySet()

    val labels: List<String>
        get() = snapshot.get()?.labels ?: emptyList()

    fun initialize() {
        val directory = File(plugin.dataFolder, "pages")
        val pages = directory.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            try {
                file.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            } catch (error: Exception) {
                plugin.logger.warning("Cannot discover startup commands in ${file.name}: ${error.message}")
                null
            }
        } ?: emptyList()
        val configuration = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "config.yml"))
        startupLabels = startupCommandLabels(pages, configuration.getStringList(STARTUP_LABELS))
        server.pluginManager.registerEvents(this, plugin)
    }

    fun registerDispatcher(registrar: Commands) {
        val registered = mutableSetOf<String>()
        startupLabels.filter { it != "tw" }.forEach { label ->
            registered += registrar.register(LiteralArgumentBuilder.literal<CommandSourceStack>(label)
                .executes { execute(label, it.source) }
                .then(RequiredArgumentBuilder.argument<CommandSourceStack, String>(ARGUMENT, StringArgumentType.greedyString())
                    .executes { execute("$label ${StringArgumentType.getString(it, ARGUMENT)}", it.source) }
                    .suggests { context, builder -> suggest(context.source, builder, "$label ") })
                .build(), "Typewriter", if (label == "typewriter") listOf("tw") else emptyList())
        }
        registeredLabels = registered.toSet()
    }

    fun registerCommands() {
        val commands = listOf(
            typewriterCommand(),
        ) + Query.find<CustomCommandEntry>().map { it.command() }
        val current = CommandSnapshot(commands, plugin.name.lowercase())
        snapshot.set(current)
        plugin.configurationFile.registerDefault(STARTUP_LABELS, emptyList<String>(),
            "额外预留的顶层命令名列表，供自定义扩展使用；内置命令从已发布页面自动读取。\n" +
                "不带斜杠、空格或命名空间，每项最多128字符；修改后重启生效。\n" +
                "热重载新增的顶层命令在重启前使用 /tw run <完整自定义命令> 执行。")
        val pending = current.labels.filter { it !in registeredLabels }
        if (pending.isNotEmpty()) plugin.logger.warning(
            "New command roots require a restart on Folia: ${pending.joinToString()}. Use /tw run <command> until then.")
        refreshClients()
    }

    fun unregisterCommands() {
        snapshot.set(null)
    }

    fun shutdown() {
        snapshot.set(null)
        HandlerList.unregisterAll(this)
    }

    private fun execute(input: String, source: CommandSourceStack): Int {
        val current = snapshot.get() ?: throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create()
        val entity = source.executor ?: source.sender as? Player
        if (entity == null || foliaScheduler.owns(entity)) return current.dispatcher.execute(input, source)
        foliaScheduler.atEntity(entity, {
            if (snapshot.get() === current) {
                try {
                    current.dispatcher.execute(input, source)
                } catch (error: CommandSyntaxException) {
                    source.sender.msg("<red>${error.rawMessage.string}")
                }
            }
        }, null, 1L)
        return 1
    }

    /** Executes a custom command through the fixed root while a new label is awaiting restart. */
    fun executeCustom(input: String, source: CommandSourceStack): Int {
        val root = input.substringBefore(' ')
        if (root.substringAfter(':') in setOf("tw", "typewriter")) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create()
        }
        return execute(input, source)
    }

    fun suggestCustom(source: CommandSourceStack, builder: SuggestionsBuilder): CompletableFuture<Suggestions> =
        suggest(source, builder)

    private fun refreshClients() {
        server.onlinePlayers.toList().forEach { player ->
            foliaScheduler.atEntity(player, { player.updateCommands() }, null, 1L)
        }
    }

    private fun suggest(
        source: CommandSourceStack,
        builder: SuggestionsBuilder,
        prefix: String = "",
    ): CompletableFuture<Suggestions> {
        val result = CompletableFuture<Suggestions>()
        val current = snapshot.get() ?: return Suggestions.empty()
        val input = prefix + builder.remaining
        val offset = builder.start - prefix.length
        val action = Runnable {
            if (snapshot.get() !== current) {
                result.complete(emptySuggestions())
            } else {
                try {
                    val completionDispatcher = current.completionDispatcher(source)
                    completionDispatcher.getCompletionSuggestions(completionDispatcher.parse(input, source))
                        .whenComplete { suggestions, error ->
                            if (error != null) result.completeExceptionally(error)
                            else if (snapshot.get() !== current) result.complete(emptySuggestions())
                            else result.complete(suggestions.shifted(offset))
                        }
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }
        }
        val entity = source.executor ?: source.sender as? Player
        if (entity != null) {
            if (foliaScheduler.owns(entity)) action.run()
            else foliaScheduler.atEntity(entity, action, { result.complete(emptySuggestions()) }, 1L)
        } else {
            foliaScheduler.global(action, { result.complete(emptySuggestions()) }, 0L)
        }
        return result
    }

    @EventHandler
    fun onClientCommands(event: AsyncPlayerSendCommandsEvent<CommandSourceStack>) {
        if (!event.hasFiredAsync() || !foliaScheduler.owns(event.player)) return
        val current = snapshot.get()
        val nodes = current?.clientNodes(PlayerCommandSource(event.player, event.player.location)) ?: emptyList()
        registeredLabels.forEach { removeCommandMethod.invoke(event.commandNode, it) }
        nodes.forEach { node ->
            if (node.name !in registeredLabels) return@forEach
            event.commandNode.addChild(node)
        }
    }

    private companion object {
        const val ARGUMENT = "typewriter_arguments"
        const val STARTUP_LABELS = "commands.startup-labels"

        fun emptySuggestions() = Suggestions(StringRange.at(0), emptyList())
    }
}

@Suppress("UnstableApiUsage")
private class PlayerCommandSource(
    private val player: Player,
    private val position: Location,
    private val executor: Entity = player,
) : CommandSourceStack {
    override fun getSender(): CommandSender = player
    override fun getExecutor(): Entity = executor
    override fun getLocation(): Location = position.clone()
    override fun withLocation(location: Location): CommandSourceStack = PlayerCommandSource(player, location.clone(), executor)
    override fun withExecutor(executor: Entity): CommandSourceStack = PlayerCommandSource(player, position, executor)
}

private val removeCommandMethod by lazy {
    MethodHandles.lookup().findVirtual(
        CommandNode::class.java,
        "removeCommand",
        MethodType.methodType(Void.TYPE, String::class.java)
    )
}

fun <S> CommandDispatcher<S>.unregister(literal: String) {
    try {
        removeCommandMethod.invoke(this.root, literal)
    } catch (e: Throwable) {
        e.printStackTrace()
    }
}

fun <S> CommandDispatcher<S>.register(command: DslCommand<S>, identifier: String): List<String> {
    val commandsLabels = mutableListOf<String>()
    val sourceCommand = LiteralArgumentBuilder.literal<S>("$identifier:${command.literal}")
        .executes(command.node.command)
        .requires(command.node.requirement)
        .build()
        .apply {
            command.node.children.forEach(this::addChild)
        }

    register(sourceCommand)
    commandsLabels.add(sourceCommand.literal)

    registerRedirect(command.literal, sourceCommand)
    commandsLabels.add(command.literal)

    command.aliases.forEach {
        registerRedirect(it, sourceCommand)
        commandsLabels.add(it)

        val alias = "$identifier:$it"
        registerRedirect(alias, sourceCommand)
        commandsLabels.add(alias)
    }

    return commandsLabels
}

fun <S> CommandDispatcher<S>.registerRedirect(aliasLiteral: String, target: LiteralCommandNode<S>) {
    val redirect = LiteralArgumentBuilder.literal<S>(aliasLiteral)
        .executes(target.command)
        .redirect(target)
        .requires(target.requirement)
        .build()

    register(redirect)
}

private fun <S> CommandDispatcher<S>.register(node: LiteralCommandNode<S>) {
    root.getChild(node.literal)?.let {
        unregister(node.literal)
    }
    root.addChild(node)
}
