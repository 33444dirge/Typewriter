package com.typewritermc.engine.paper.ui

import com.corundumstudio.socketio.Configuration
import com.corundumstudio.socketio.AckMode
import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.HandshakeData
import com.corundumstudio.socketio.SocketIOClient
import com.corundumstudio.socketio.SocketIOServer
import com.google.gson.JsonArray
import com.typewritermc.engine.paper.entry.StagingManager
import com.typewritermc.engine.paper.events.StagingChangeEvent
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.config
import com.typewritermc.engine.paper.utils.logErrorIfNull
import com.typewritermc.engine.paper.utils.optionalConfig
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.loader.ExtensionLoader
import lirand.api.extensions.events.listen
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


class CommunicationHandler : KoinComponent {
    private val clientSynchronizer: ClientSynchronizer by inject()
    private val writers: Writers by inject()
    private val stagingManager: StagingManager by inject()
    private val panelHost: PanelHost by inject()
    private val extensionLoader: ExtensionLoader by inject()
    private val extensionJson by extensionLoader::extensionJson

    private val hostName: String by config(
        "hostname",
        "127.0.0.1",
        comment = "The hostname of the server. CHANGE THIS to your servers ip."
    )
    private val panelPort: Int by config(
        "panel.port",
        8080,
        comment = "The port of the web panel. Make sure this port is open."
    )
    private val panelAppendPort: Boolean? by optionalConfig("panel.append_port")

    private val enabled: Boolean by config(
        "enabled",
        false,
        comment = "Whether the web panel and web sockets are enabled."
    )
    private val webSocketPort: Int by config(
        "websocket.port",
        9092,
        comment = "The port of the websocket server. Make sure this port is open."
    )
    private val auth: String by config(
        "websocket.auth", "session", comment = """
        |The authentication that is used. Leave unchanged if you don't know what you are doing.
        |Possible values: none (not recommended), session
    """.trimMargin()
    )
    private val websocketHostname: String? by optionalConfig("websocket.hostname")
    private val websocketAppendPort: Boolean? by optionalConfig("websocket.append_port")
    private val websocketSecure: Boolean? by optionalConfig("websocket.secure")
    private val bindAddress: String by config("websocket.bind", "0.0.0.0", "WebSocket监听地址；0.0.0.0监听所有网卡，127.0.0.1仅允许本机访问。修改后重启生效。")


    val authenticationEnabled: Boolean
        get() = auth == "session"

    private val maxSessions: Int by config(
        "websocket.sessions.capacity", 1024,
        "编辑器链接令牌最大数量（1–16384）；达到上限时淘汰最久未使用的链接，需重新打开编辑器。修改后重启生效。",
    )
    private val sessionIdleMinutes: Int by config(
        "websocket.sessions.idle-minutes", 30,
        "编辑器链接闲置有效期，单位分钟（1–10080）；握手与编辑操作续期，过期后需重新打开编辑器。修改后重启生效。",
    )
    private val sessionTokens by lazy {
        SessionTokenRegistry(maxSessions.coerceIn(1, 16384), Duration.ofMinutes(sessionIdleMinutes.coerceIn(1, 10080).toLong()))
    }
    private val stopped = AtomicBoolean()

    @Volatile var server: SocketIOServer? = null
        private set

    fun initialize() {
        if (!enabled) return
        logger.warning("Websocket server is enabled. This is not recommended for production servers.")
        panelHost.initialize()
        val config = Configuration().apply {
            ackMode = AckMode.MANUAL
            hostname = bindAddress
            port = this@CommunicationHandler.webSocketPort
            isWebsocketCompression = false // Disable compression to avoid Netty 4.1/4.2 API conflict
            setAuthorizationListener(this@CommunicationHandler::authenticate)
        }

        val currentServer = SocketIOServer(config)
        server = currentServer

        registerEvent("fetch", clientSynchronizer::handleFetchRequest)
        registerEvent("createPage", clientSynchronizer::handleCreatePage)
        registerEvent("renamePage", clientSynchronizer::handleRenamePage)
        registerEvent("changePageValue", clientSynchronizer::handleChangePageValue)
        registerEvent("deletePage", clientSynchronizer::handleDeletePage)
        registerEvent("moveEntry", clientSynchronizer::handleMoveEntry)
        registerEvent("createEntry", clientSynchronizer::handleCreateEntry)
        registerEvent("updateEntry", clientSynchronizer::handleEntryFieldUpdate)
        registerEvent(
            "updateCompleteEntry",
            clientSynchronizer::handleEntryUpdate
        )
        registerEvent("reorderEntry", clientSynchronizer::handleReorderEntry)
        registerEvent("deleteEntry", clientSynchronizer::handleDeleteEntry)

        registerEvent("publish", clientSynchronizer::handlePublish)

        registerEvent("updateWriter", clientSynchronizer::handleUpdateWriter)
        registerEvent("contentModeRequest", clientSynchronizer::handleContentModeRequest)

        server?.addConnectListener { socket ->
            if (server !== currentServer || !authenticate(socket.handshakeData)) {
                socket.disconnect()
                return@addConnectListener
            }
            logger.info("Client connected: ${socket.remoteAddress}")
            socket.sendEvent("stagingState", stagingManager.stagingState.name.lowercase())
            socket.sendEvent("serverInfo", Bukkit.getMinecraftVersion())

            val token = getSessionToken(socket.handshakeData)
            if (token != null) {
                sessionTokens.connected(token, socket.sessionId)
            }

            val iconUrl = getIconUrl(token)

            writers.addWriter(socket.sessionId.toString(), iconUrl)
            server.broadcastWriters(writers)
        }

        server?.addDisconnectListener {
            if (server !== currentServer) return@addDisconnectListener
            logger.info("Client disconnected: ${it.remoteAddress}")
            server?.broadcastOperations?.sendEvent("disconnectWriter", it, it.sessionId.toString())

            writers.removeWriter(it.sessionId.toString())
            server.broadcastWriters(writers)
        }

        server?.start()


        plugin.listen<StagingChangeEvent> { (newState) ->
            server?.broadcastOperations?.sendEvent("stagingState", newState.name.lowercase())
        }
    }

    fun load() {
        if (!enabled) return
        server?.broadcastOperations?.sendEvent("stagingState", stagingManager.stagingState.name.lowercase())

        val array = JsonArray()
        stagingManager.pages.forEach { (_, page) ->
            array.add(page)
        }
        server?.broadcastOperations?.sendEvent("updatePages", array.toString())
        server?.broadcastOperations?.sendEvent("updateExtensions", extensionJson.toString())
    }

    private fun authenticate(data: HandshakeData): Boolean {
        if (stopped.get()) return false
        if (auth == "none") return true
        if (auth == "session") {
            val token = getSessionToken(data)
                .logErrorIfNull("${data.address} tried to connect to the socket without token!") ?: return false
            val session = sessionTokens.get(token) ?: return false
            return session.isValid
        }
        return false
    }

    private fun getSessionToken(data: HandshakeData): UUID? {
        if (auth == "none") return null
        if (auth == "session") {
            val token = data.getSingleUrlParam("token") ?: return null
            return runCatching { UUID.fromString(token) }.getOrNull()
        }
        return null
    }

    private fun registerEvent(name: String, handler: (SocketIOClient, String, AckRequest) -> Unit) {
        val current = server ?: return
        current.addEventListener(name, String::class.java) { client, data, ack ->
            if (server !== current || !authenticate(client.handshakeData)) {
                client.disconnect()
                return@addEventListener
            }
            handler(client, data, ack)
        }
    }

    private fun generateSessionToken(playerId: UUID?): UUID = sessionTokens.generate(playerId)

    fun generateUrl(playerId: UUID?): String {
        var url: String = if (hostName.startsWith("http")) hostName
        else "http://${hostName}"

        if (panelAppendPort != false) url += ":${panelPort}"
        url += "/#/connect?host=${websocketHostname ?: hostName}"
        if (websocketAppendPort != false) url += "&port=${webSocketPort}"
        if (auth == "session") url += "&token=${generateSessionToken(playerId)}"
        if (websocketSecure == true) url += "&secure=true"

        return url
    }

    fun getPlayer(client: SocketIOClient): Player? {
        val token = getSessionToken(client.handshakeData) ?: return null
        val session = sessionTokens.get(token) ?: return null
        return session.playerId?.let { plugin.server.getPlayer(it) }
    }

    fun getIconUrl(sessionId: String): String? {
        val client = server?.getClient(UUID.fromString(sessionId)) ?: return null
        val token = getSessionToken(client.handshakeData) ?: return null
        return getIconUrl(token)
    }

    private fun getIconUrl(token: UUID?): String? {
        if (token == null) return null
        val session = sessionTokens.get(token) ?: return null
        return session.playerId?.let {
            "https://crafatar.com/avatars/$it?size=64&overlay"
        }
    }

    fun shutdown() {
        stopped.set(true)
        sessionTokens.close()
        val previous = server
        server = null
        try {
            previous?.stop()
        } finally {
            try {
                panelHost.dispose()
            } finally {
                writers.dispose()
            }
        }
    }
}

data class SessionData(
    val playerId: UUID?,
    val created: Instant,
    val lastConnectionId: UUID? = null,
) {
    companion object {
        fun create(playerId: UUID?) = SessionData(playerId, Instant.now())
    }

    /**
     * Check if the session is still valid.
     *
     * A session is valid if the player is still online.
     *
     * @return true if the session is still valid, false otherwise.
     */
    val isValid: Boolean
        get() = playerId?.let { server.getPlayer(it) != null } ?: true
}
