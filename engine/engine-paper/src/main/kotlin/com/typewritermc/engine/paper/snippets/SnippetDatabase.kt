package com.typewritermc.engine.paper.snippets

import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.ConcurrentYamlFile
import java.io.File
import org.koin.core.component.KoinComponent
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

interface SnippetDatabase {
    fun get(path: String, default: Any, comment: String = ""): Any
    fun <T : Any> getSnippet(path: String, klass: KClass<T>, default: T, comment: String = ""): T
    fun registerSnippet(path: String, defaultValue: Any, comment: String = "")
}

class SnippetDatabaseImpl : SnippetDatabase, KoinComponent {
    private val file by lazy { ConcurrentYamlFile(File(plugin.dataFolder, "snippets.yml")) }

    suspend fun load() = file.load()
    suspend fun shutdown() = file.close()

    override fun get(path: String, default: Any, comment: String): Any {
        return file.get(path) ?: default
    }

    override fun <T : Any> getSnippet(path: String, klass: KClass<T>, default: T, comment: String): T {
        val value = get(path, default, comment)

        val casted = klass.safeCast(value) ?: return default

        return casted
    }

    override fun registerSnippet(path: String, defaultValue: Any, comment: String) {
        file.registerDefault(path, defaultValue, comment.takeIf { it.isNotBlank() })
    }
}
