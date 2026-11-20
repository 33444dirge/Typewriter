package com.typewritermc.engine.paper.command

import com.google.gson.JsonObject

internal fun startupCommandLabels(pages: List<JsonObject>, extra: List<String>): Set<String> = buildSet {
    add("typewriter")
    add("tw")
    extra.filter(::validCommandLabel).forEach(::add)
    pages.forEach { page ->
        page.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach entries@ { value ->
            if (!value.isJsonObject) return@entries
            val entry = value.asJsonObject
            if (entry.get("blueprintId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString !in
                setOf("custom_command", "on_run_command")) return@entries
            entry.get("command")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.takeIf(::validCommandLabel)?.let(::add)
            entry.get("aliases")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { alias ->
                alias.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?.takeIf(::validCommandLabel)?.let(::add)
            }
        }
    }
}

internal fun validCommandLabel(label: String): Boolean =
    label.isNotBlank() && label.none { it.isWhitespace() || it == '/' || it == ':' } && label.length <= 128
