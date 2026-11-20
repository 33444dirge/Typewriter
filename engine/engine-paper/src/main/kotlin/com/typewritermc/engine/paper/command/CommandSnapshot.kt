package com.typewritermc.engine.paper.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.tree.ArgumentCommandNode
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.typewritermc.engine.paper.command.dsl.DslCommand
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import java.util.Collections
import java.util.IdentityHashMap
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

internal class CommandSnapshot<S>(commands: List<DslCommand<S>>, identifier: String) {
    val dispatcher = CommandDispatcher<S>()
    val labels: List<String>

    init {
        val nodes = linkedMapOf<String, LiteralCommandNode<S>>()
        commands.forEach { command ->
            val target = LiteralArgumentBuilder.literal<S>("$identifier:${command.literal}")
                .executes(command.node.command)
                .requires(command.node.requirement)
                .build()
            command.node.children.forEach(target::addChild)
            nodes[target.literal] = target
            (listOf(command.literal) + command.aliases.flatMap { listOf(it, "$identifier:$it") }).forEach { alias ->
                nodes[alias] = LiteralArgumentBuilder.literal<S>(alias)
                    .executes(target.command).requires(target.requirement).redirect(target).build()
            }
        }
        nodes.values.forEach(dispatcher.root::addChild)
        labels = nodes.keys.toList()
    }

    fun clientNodes(source: S): List<LiteralCommandNode<S>> = dispatcher.root.children.mapNotNull {
        copyForClient(it, source, Collections.newSetFromMap(IdentityHashMap())) as? LiteralCommandNode<S>
    }

    fun completionDispatcher(source: S): CommandDispatcher<S> = CommandDispatcher<S>().also { filtered ->
        dispatcher.root.children.forEach { node ->
            copyForClient(node, source, Collections.newSetFromMap(IdentityHashMap()), false)
                ?.let(filtered.root::addChild)
        }
    }
}

private fun <S> copyForClient(
    node: CommandNode<S>, source: S, path: MutableSet<CommandNode<S>>, client: Boolean = true,
): CommandNode<S>? {
    if (!node.canUse(source) || !path.add(node)) return null
    try {
        val target = node.redirect ?: node
        if (target !== node && !target.canUse(source)) return null
        val builder = when (node) {
            is LiteralCommandNode -> LiteralArgumentBuilder.literal<S>(node.literal)
            is ArgumentCommandNode<*, *> -> RequiredArgumentBuilder.argument<S, Any>(
                node.name,
                if (client) nativeArgument(node.type) else node.type as ArgumentType<Any>,
            ).also { argument ->
                if (client) argument.suggests { _, _ -> Suggestions.empty() }
                else argument.suggests((node as ArgumentCommandNode<S, *>).customSuggestions)
            }
            else -> return null
        }
        if (node.command != null || target.command != null) builder.executes { 0 }
        target.children.forEach { child -> copyForClient(child, source, path, client)?.let { builder.then(it) } }
        return builder.build()
    } finally {
        path.remove(node)
    }
}

@Suppress("UNCHECKED_CAST", "UnstableApiUsage")
private fun nativeArgument(argument: ArgumentType<*>): ArgumentType<Any> {
    var current = argument
    val visited = Collections.newSetFromMap(IdentityHashMap<ArgumentType<*>, Boolean>())
    while (current is CustomArgumentType<*, *>) {
        check(visited.add(current)) { "Cyclic native command argument" }
        current = current.nativeType
    }
    if (current.javaClass.name == NATIVE_WRAPPER) {
        current = nativeNmsArgument.invoke(current) as ArgumentType<*>
    }
    if (current.javaClass !in PRIMITIVE_ARGUMENTS) {
        check(nativeArgumentRecognized.invoke(current.javaClass) as Boolean) {
            "Unsupported client command argument: ${current.javaClass.name}"
        }
    }
    return current as ArgumentType<Any>
}

private val PRIMITIVE_ARGUMENTS = setOf(
    com.mojang.brigadier.arguments.BoolArgumentType::class.java,
    com.mojang.brigadier.arguments.DoubleArgumentType::class.java,
    com.mojang.brigadier.arguments.FloatArgumentType::class.java,
    com.mojang.brigadier.arguments.IntegerArgumentType::class.java,
    com.mojang.brigadier.arguments.LongArgumentType::class.java,
    com.mojang.brigadier.arguments.StringArgumentType::class.java,
)

private const val NATIVE_WRAPPER =
    "io.papermc.paper.command.brigadier.argument.VanillaArgumentProviderImpl\$NativeWrapperArgumentType"

private val nativeNmsArgument by lazy {
    MethodHandles.publicLookup().findVirtual(
        Class.forName(NATIVE_WRAPPER), "nativeNmsArgumentType", MethodType.methodType(ArgumentType::class.java),
    )
}

private val nativeArgumentRecognized by lazy {
    MethodHandles.publicLookup().findStatic(
        Class.forName("net.minecraft.commands.synchronization.ArgumentTypeInfos"),
        "isClassRecognized", MethodType.methodType(Boolean::class.javaPrimitiveType, Class::class.java),
    )
}

internal fun Suggestions.shifted(offset: Int): Suggestions {
    fun StringRange.shift() = StringRange.between(start + offset, end + offset)
    return Suggestions(range.shift(), list.map { Suggestion(it.range.shift(), it.text, it.tooltip) })
}
