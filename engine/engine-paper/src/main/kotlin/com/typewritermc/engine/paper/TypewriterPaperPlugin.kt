package com.typewritermc.engine.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.shynixn.mccoroutine.folia.scope
import com.google.gson.Gson
import com.typewritermc.core.TypewriterCore
import com.typewritermc.core.interaction.SessionTracker
import com.typewritermc.core.serialization.createDataSerializerGson
import com.typewritermc.core.utils.launch
import com.typewritermc.core.utils.TypewriterCoroutineLifetime
import com.typewritermc.core.utils.startAsyncWorkers
import com.typewritermc.core.utils.closeAsyncWorkers
import com.typewritermc.engine.paper.command.TypewriterCommandManager
import com.typewritermc.engine.paper.content.ContentHandler
import com.typewritermc.engine.paper.entry.*
import com.typewritermc.engine.paper.entry.action.ActionHandler
import com.typewritermc.engine.paper.entry.dialogue.DialogueHandler
import com.typewritermc.engine.paper.entry.dialogue.DialoguePlaceholders
import com.typewritermc.engine.paper.entry.entity.EntityHandler
import com.typewritermc.engine.paper.entry.entity.PlayerSkinCache
import com.typewritermc.engine.paper.entry.temporal.TemporalHandler
import com.typewritermc.engine.paper.entry.temporal.TemporalPlaceholders
import com.typewritermc.engine.paper.events.TypewriterUnloadEvent
import com.typewritermc.engine.paper.extensions.bstats.BStatsMetrics
import com.typewritermc.engine.paper.extensions.modrinth.Modrinth
import com.typewritermc.engine.paper.extensions.placeholderapi.PlaceholderExpansion
import com.typewritermc.engine.paper.extensions.placeholderapi.PlaceholderHandler
import com.typewritermc.engine.paper.facts.FactDatabase
import com.typewritermc.engine.paper.facts.FactHandler
import com.typewritermc.engine.paper.facts.FactStorage
import com.typewritermc.engine.paper.facts.FactTracker
import com.typewritermc.engine.paper.facts.storage.FileFactStorage
import com.typewritermc.engine.paper.interaction.*
import com.typewritermc.engine.paper.loader.PaperDependencyChecker
import com.typewritermc.engine.paper.loader.dataSerializerModule
import com.typewritermc.engine.paper.snippets.SnippetDatabase
import com.typewritermc.engine.paper.snippets.SnippetDatabaseImpl
import com.typewritermc.engine.paper.ui.ClientSynchronizer
import com.typewritermc.engine.paper.ui.CommunicationHandler
import com.typewritermc.engine.paper.ui.PanelHost
import com.typewritermc.engine.paper.ui.Writers
import com.typewritermc.engine.paper.utils.PlayerHides
import com.typewritermc.engine.paper.utils.WorldTimeSnapshots
import com.typewritermc.engine.paper.utils.ConcurrentYamlFile
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.createBukkitDataParser
import com.typewritermc.loader.DependencyChecker
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.typewritermc.engine.paper.utils.foliaScheduler
import kotlinx.coroutines.delay
import lirand.api.architecture.KotlinPlugin
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.logger.Level
import org.koin.core.logger.MESSAGE
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.withOptions
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent
import java.io.File
import java.util.logging.Level.*
import java.util.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@Suppress("UnstableApiUsage")
class TypewriterPaperPlugin : KotlinPlugin(), KoinComponent {
    internal val configurationFile by lazy { ConcurrentYamlFile(File(dataFolder, "config.yml")) }
    private val reloadMutex = Mutex()
    private val stopping = AtomicBoolean()
    @Volatile private var koinStarted = false
    override fun onLoad() {
        super.onLoad()
        val modules = module {
            single { this@TypewriterPaperPlugin } withOptions
                    {
                        named("plugin")
                        bind<Plugin>()
                        bind<JavaPlugin>()
                        bind<TypewriterPaperPlugin>()
                        createdAtStart()
                    }

            single<Logger> { this@TypewriterPaperPlugin.logger } withOptions {
                named("logger")
                createdAtStart()
            }

            single(named("version")) { this@TypewriterPaperPlugin.pluginMeta.version }

            singleOf(::TypewriterCore)
            factory<File>(named("baseDir")) { plugin.dataFolder }
            single { PaperDependencyChecker() } withOptions {
                named("dependencyChecker")
                bind<DependencyChecker>()
                bind<PaperDependencyChecker>()
                createdAtStart()
            }

            singleOf<StagingManager>(::StagingManagerImpl)
            singleOf(::ClientSynchronizer)
            singleOf(::PlayerSessionManager)
            singleOf(::CommunicationHandler)
            singleOf(::Writers)
            singleOf(::PanelHost)
            singleOf(::SnippetDatabaseImpl) bind SnippetDatabase::class
            singleOf(::FactDatabase)
            singleOf<FactStorage>(::FileFactStorage)
            singleOf(::EntryListeners)
            singleOf(::AudienceManager)
            singleOf<AssetStorage>(::LocalAssetStorage)
            singleOf<AssetManager>(::AssetManager)
            singleOf(::ChatHistoryHandler)
            singleOf(::ResendTokenRegistry)
            singleOf(::ActionBarBlockerHandler)
            singleOf(::PacketInterceptor)
            singleOf(::Confirmations)
            singleOf(::EntityHandler)
            singleOf(::TypewriterCommandManager)
            singleOf(::PlayerSkinCache)
            singleOf(::PlayerHides)

            factory { FactTracker(it.get()) } bind SessionTracker::class
            single { InteractionTriggerHandler() } bind TriggerHandler::class
            single { InteractionBoundHandler() } bind TriggerHandler::class
            single { ActionHandler() } bind TriggerHandler::class
            single { ContentHandler() } bind TriggerHandler::class
            single { DialogueHandler() } bind TriggerHandler::class
            single { FactHandler() } bind TriggerHandler::class
            single { TemporalHandler() } bind TriggerHandler::class

            single { DialoguePlaceholders() } bind PlaceholderHandler::class
            single { TemporalPlaceholders() } bind PlaceholderHandler::class

            factory<Gson>(named("dataSerializer")) { createDataSerializerGson(getAll()) }
            factory<Gson>(named("bukkitDataParser")) { createBukkitDataParser() }

            single<ClassLoader>(named("globalClassloader")) { globalClassloader() }

            factory<Boolean>(named("isEnabled")) { isEnabled }
        }
        startKoin {
            modules(modules, TypewriterCore.module, dataSerializerModule)
        }
        koinStarted = true
    }

    override suspend fun onEnableAsync() {
        check(koinStarted) { "Typewriter failed to load its services; see the earlier startup error" }
        startAsyncWorkers()
        TypewriterCoroutineLifetime.attachPlatform(this.scope.coroutineContext[Job])
        configurationFile.load()
        get<SnippetDatabaseImpl>().load()
        PacketEvents.getAPI().settings.downsampleColors(false)

        get<FactDatabase>().initialize()
        get<AssetManager>().initialize()
        get<PlayerSessionManager>().initialize()
        get<Confirmations>().initialize()
        get<ChatHistoryHandler>().initialize()
        get<ActionBarBlockerHandler>().initialize()
        get<PacketInterceptor>().initialize()
        get<AudienceManager>().initialize()
        get<EntityHandler>().initialize()
        get<PlayerHides>().initialize()

        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            PlaceholderExpansion.register()
        }

        BStatsMetrics.registerMetrics()

        get<TypewriterCommandManager>().initialize()
        this.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) {
            val manager = get<TypewriterCommandManager>()
            manager.registerDispatcher(it.registrar())
        }
        // We want to initialize all the extensions after all the plugins have been enabled to make sure
        // that all the plugins are loaded.
        Dispatchers.Sync.launch {
            delay(100)
            load()
            withContext(Dispatchers.IO) { get<CommunicationHandler>().initialize() }

            delay(5.seconds)
            Modrinth.initialize()
        }
    }

    suspend fun load() = reloadMutex.withLock {
        check(!stopping.get()) { "Typewriter is stopping" }
        loadState()
    }

    private suspend fun loadState() {
        configurationFile.load()
        get<SnippetDatabaseImpl>().load()
        TypewriterCoroutineLifetime.beginGeneration()
        // Needs to be first, as it will load the classLoader
        get<TypewriterCore>().load()

        withContext(Dispatchers.IO) { get<StagingManager>().loadState() }
        get<CommunicationHandler>().load()
        WorldTimeSnapshots.load()
        get<PlayerSessionManager>().load()
        get<EntryListeners>().load()
        get<AudienceManager>().load()
        get<TypewriterCommandManager>().registerCommands()

        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            PlaceholderExpansion.load()
        }
    }

    suspend fun unload() = reloadMutex.withLock { unloadState() }

    private suspend fun unloadState() {
        val failures = mutableListOf<Throwable>()
        suspend fun step(name: String, action: suspend () -> Unit) {
            cleanup(name, action)?.let(failures::add)
        }

        step("generation") { TypewriterCoroutineLifetime.stopGeneration() }
        step("world clock snapshots") { WorldTimeSnapshots.clear() }
        step("unload event") { TypewriterUnloadEvent().callEvent() }
        step("commands") { get<TypewriterCommandManager>().unregisterCommands() }
        step("packet interceptors") { get<PacketInterceptor>().unload() }
        step("audience displays") { get<AudienceManager>().unload() }
        step("entry listeners") { get<EntryListeners>().unload() }
        step("player sessions") { get<PlayerSessionManager>().unload() }
        step("confirmations") { get<Confirmations>().clear() }
        step("staging") { withContext(Dispatchers.IO) { get<StagingManager>().unload() } }
        step("placeholders") {
            if (server.pluginManager.getPlugin("PlaceholderAPI") != null) PlaceholderExpansion.unload()
        }
        step("extensions") { get<TypewriterCore>().unload() }

        if (failures.isNotEmpty()) {
            val failure = IllegalStateException("Typewriter unload failed in ${failures.size} cleanup steps")
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    suspend fun reload() = reloadMutex.withLock {
        check(!stopping.get()) { "Typewriter is stopping" }
        unloadState()
        check(!stopping.get()) { "Typewriter is stopping" }
        loadState()
    }

    val isGeyserInstalled: Boolean by lazy { server.pluginManager.isPluginEnabled("Geyser-Spigot") }
    val isFloodgateInstalled: Boolean by lazy { server.pluginManager.isPluginEnabled("Floodgate") }

    override suspend fun onDisableAsync() {
        if (!stopping.compareAndSet(false, true)) return
        if (!koinStarted) return
        cleanup("update notifications") { Modrinth.shutdown() }
        cleanup("pending scheduling") { foliaScheduler.close() }
        reloadMutex.withLock {
            try {
                cleanup("generation") { TypewriterCoroutineLifetime.stopGeneration() }
                cleanup("world clock snapshots") { WorldTimeSnapshots.clear() }
                cleanup("staging shutdown") { withContext(Dispatchers.IO) { get<StagingManager>().shutdown() } }
                cleanup("fact storage") { get<FactDatabase>().shutdown() }
                cleanup("chat history") { get<ChatHistoryHandler>().shutdown() }
                cleanup("action bars") { get<ActionBarBlockerHandler>().shutdown() }
                cleanup("packet listener") { get<PacketInterceptor>().shutdown() }
                cleanup("web panel") { withContext(Dispatchers.IO) { get<CommunicationHandler>().shutdown() } }
                cleanup("player sessions shutdown") { get<PlayerSessionManager>().shutdown() }
                cleanup("confirmations shutdown") { get<Confirmations>().shutdown() }
                cleanup("assets") { get<AssetManager>().shutdown() }
                cleanup("audience shutdown") { get<AudienceManager>().shutdown() }
                cleanup("entities") { get<EntityHandler>().shutdown() }
                cleanup("unload") { unloadState() }
                cleanup("command listeners") { get<TypewriterCommandManager>().shutdown() }
            } finally {
                cleanup("snippets configuration") { get<SnippetDatabaseImpl>().shutdown() }
                cleanup("plugin configuration") { configurationFile.close() }
                cleanup("player visibility") { get<PlayerHides>().shutdown() }
                cleanup("platform jobs") { TypewriterCoroutineLifetime.stopPlatform() }
                cleanup("schedulers") { foliaScheduler.close() }
                cleanup("worker pool") { closeAsyncWorkers() }
            }
        }
    }

    private suspend fun cleanup(name: String, action: suspend () -> Unit): Throwable? = try {
        action()
        null
    } catch (error: Throwable) {
        logger.log(SEVERE, "Failed to clean up Typewriter $name", error)
        error
    }
}

private class MinecraftLogger(private val logger: Logger) :
    org.koin.core.logger.Logger(logger.level.convertLogger()) {
    override fun display(level: Level, msg: MESSAGE) {
        when (level) {
            Level.DEBUG -> logger.fine(msg)
            Level.INFO -> logger.info(msg)
            Level.ERROR -> logger.severe(msg)
            Level.NONE -> logger.finest(msg)
            Level.WARNING -> logger.warning(msg)
        }
    }
}

fun java.util.logging.Level?.convertLogger(): Level {
    return when (this) {
        FINEST -> Level.DEBUG
        FINER -> Level.DEBUG
        FINE -> Level.DEBUG
        CONFIG -> Level.DEBUG
        INFO -> Level.INFO
        WARNING -> Level.WARNING
        SEVERE -> Level.ERROR
        else -> Level.INFO
    }
}

val logger: Logger by lazy { plugin.logger }

val plugin: TypewriterPaperPlugin by lazy { KoinJavaComponent.get(JavaPlugin::class.java) }
