package com.typewritermc.engine.paper.interaction

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_19_3
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter
import com.github.retrooper.packetevents.protocol.nbt.codec.NBTCodec
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import com.google.common.cache.CacheBuilder
import com.typewritermc.engine.paper.extensions.packetevents.sendPacketTo
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.plainText
import com.typewritermc.engine.paper.utils.server
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.format.TextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent.get
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private val darkenLimit by snippet(
    "chat.darken-limit",
    12,
    "The amount of messages displayed in the chat history during a dialogue"
)
private val spacing by snippet("chat.spacing", 3, "The amount of padding between the dialogue and the chat history")
private val historyLimit by snippet("chat.history-limit", 100, "每玩家聊天历史条数上限，范围20至1000；溢出的待发签名消息会先发送。")
private val historyBytes by snippet("chat.history-bytes", 4_194_304, "每玩家聊天历史估算字节上限，范围1048576至16777216；用于限制长对话缓存。")

private val pendingPacketLimit by snippet("chat.pending-packet-limit", 512, "每玩家待发签名聊天包上限（16–8192）；异常积压超限时断开该玩家连接，避免签名乱序和内存持续增长。")
private val pendingByteLimit by snippet("chat.pending-byte-limit", 4_194_304, "每玩家待发签名聊天包估算字节上限（65536–16777216）；按至少256字节计一个包，非实际堆内存精确值。")

private const val NBT_SIZE_LIMIT = 870_400 // ~850KB (out of 1MB limit)

/**
 * Token-based registry to prevent duplicate message processing during chat history resends.

 * Known edge case: If a player sends an identical message simultaneously with a history resend,
 * and chat formatting removes distinguishing information (like player names), the new message might
 * be incorrectly consumed as a resend token. This could cause a previous message to be re-added to
 * history, but is considered acceptable in practice due to identical visual result.
 */
class ResendTokenRegistry {

    @JvmInline
    private value class MessageToken private constructor(val value: Long) {
        companion object {
            fun of(recipientId: UUID, message: Component): MessageToken {
                val recipientHash = recipientId.mostSignificantBits xor recipientId.leastSignificantBits
                val contentHash = message.plainText().hashCode().toLong()
                return MessageToken(recipientHash xor contentHash)
            }
        }
    }

    private val tokenCountsCache = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(Duration.ofSeconds(2))
        .build<MessageToken, AtomicInteger>()

    fun issue(recipientId: UUID, message: Component) {
        val token = MessageToken.of(recipientId, message)
        tokenCountsCache.asMap().compute(token) { _, existing ->
            existing?.apply { incrementAndGet() } ?: AtomicInteger(1)
        }
    }

    fun consume(recipientId: UUID, message: Component): Boolean {
        val token = MessageToken.of(recipientId, message)
        var consumed = false
        tokenCountsCache.asMap().computeIfPresent(token) { _, count ->
            consumed = true
            count.takeIf { it.decrementAndGet() > 0 }
        }
        return consumed
    }
}

class ChatHistoryHandler :
    PacketListenerAbstract(PacketListenerPriority.HIGH), Listener, KoinComponent {

    private val resendTokenRegistry: ResendTokenRegistry by inject()

    fun initialize() {
        PacketEvents.getAPI().eventManager.registerListener(this)
        server.pluginManager.registerEvents(this, plugin)
        server.onlinePlayers.forEach { getHistory(it.uniqueId) }
    }

    private val histories = ConcurrentHashMap<UUID, ChatHistory>()

    // When the server sends a message to the player
    override fun onPacketSend(event: PacketSendEvent?) {
        try {
            if (event == null) return
            val playerId = event.user.uuid ?: return
            val history = histories[playerId] ?: return
            val message = findMessage(event) ?: return
            val component = message.message

            if (component is TextComponent && component.content().startsWith("no-index")) {
                history.allowedMessageThrough()
                return
            }

            if (resendTokenRegistry.consume(playerId, component)) {
                return
            }

            if (component.shouldSaveMessage()) {
                val nbtSize = max(measurePacketNBTSize(event), (event.byteBuf as ByteBuf).writerIndex())
                history.addMessage(message.withEstimatedSize(nbtSize))
                history.drainPackets(event.user, resendTokenRegistry)
            }

            if (history.shouldBlockMessage()) {
                event.isCancelled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    // NBTLimiter is marked @NonExtendable, but we need an anonymous
    // implementation to count bytes. Kotlin doesn't support anonymous
    // classes without implementing the interface directly.
    @Suppress("NonExtendableApiUsage")
    private fun measurePacketNBTSize(event: PacketSendEvent): Int {
        var totalBytes = 0
        val measuringLimiter = object : NBTLimiter {
            override fun increment(amount: Int) {
                totalBytes += amount
            }

            override fun checkReadability(length: Int) {}
            override fun enterDepth() {}
            override fun exitDepth() {}
        }

        try {
            val buffer = event.byteBuf as ByteBuf
            buffer.markReaderIndex()
            NBTCodec.readNBTFromBuffer(buffer, event.serverVersion, measuringLimiter)

            return totalBytes
        } catch (_: Exception) {
            return 0
        } finally {
            try {
                (event.byteBuf as ByteBuf).resetReaderIndex()
            } catch (_: Exception) {
            }
        }
    }

    private fun findMessage(event: PacketSendEvent): Message? {
        return when (event.packetType) {
            PacketType.Play.Server.CHAT_MESSAGE -> {
                val packet = WrapperPlayServerChatMessage(event)
                val message =
                    packet.message as? ChatMessage_v1_19_3 ?: return Message.TextMessage(packet.message.chatContent)
                val component = message.unsignedChatContent.orElseGet {
                    Component.translatable("chat.type.text", message.chatFormatting.name, message.chatContent)
                }
                Message.PlayerMessage(component, packet)
            }

            PacketType.Play.Server.DISGUISED_CHAT -> {
                val packet = WrapperPlayServerDisguisedChat(event)
                Message.TextMessage(
                    packet.chatFormatting.type.chatDecoration.decorate(
                        packet.message,
                        packet.chatFormatting
                    )
                )
            }

            PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> {
                val packet = WrapperPlayServerSystemChatMessage(event)
                if (packet.isOverlay) return null
                Message.TextMessage(packet.message)
            }

            else -> null
        }
    }

    @Suppress("RedundantIf")
    fun Component.shouldSaveMessage(): Boolean {
        if (this is TranslatableComponent && key() == "multiplayer.message_not_delivered") {
            return false
        }
        return true
    }

    fun getHistory(pid: UUID): ChatHistory {
        return histories.computeIfAbsent(pid) {
            ChatHistory(
                maxMessages = historyLimit.coerceIn(20, 1000),
                maxBytes = historyBytes.coerceIn(1_048_576, 16_777_216),
                maxPendingPackets = pendingPacketLimit.coerceIn(16, 8192),
                maxPendingBytes = pendingByteLimit.coerceIn(65_536, 16_777_216),
                onOverflow = { logger.warning("Signed chat backlog limit exceeded for ${it.uuid}; closing connection") },
            )
        }
    }

    fun getHistory(player: Player): ChatHistory = getHistory(player.uniqueId)

    fun blockMessages(player: Player) {
        getHistory(player).startBlocking()
    }

    fun unblockMessages(player: Player) {
        getHistory(player).stopBlocking()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        histories.remove(event.player.uniqueId)?.close()
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        getHistory(event.player.uniqueId)
    }

    fun shutdown() {
        PacketEvents.getAPI().eventManager.unregisterListener(this)
        org.bukkit.event.HandlerList.unregisterAll(this)
        histories.values.forEach(ChatHistory::close)
        histories.clear()
    }
}

val Player.chatHistory: ChatHistory
    get() = get<ChatHistoryHandler>(ChatHistoryHandler::class.java).getHistory(this)

fun Player.startBlockingMessages() = chatHistory.startBlocking()
fun Player.stopBlockingMessages() = chatHistory.stopBlocking()

class ChatHistory(
    private val maxMessages: Int = historyLimit.coerceIn(20, 1000),
    private val maxBytes: Int = historyBytes.coerceIn(1_048_576, 16_777_216),
) {
    private var maxPendingPackets: Int = 512
    private var maxPendingBytes: Int = 4_194_304
    private var onOverflow: (User) -> Unit = {}

    internal constructor(
        maxMessages: Int,
        maxBytes: Int,
        maxPendingPackets: Int,
        maxPendingBytes: Int,
        onOverflow: (User) -> Unit = {},
    ) : this(maxMessages, maxBytes) {
        require(maxPendingPackets > 0 && maxPendingBytes > 0)
        this.maxPendingPackets = maxPendingPackets
        this.maxPendingBytes = maxPendingBytes
        this.onOverflow = onOverflow
    }

    private val messages = ConcurrentLinkedQueue<Message>()
    private val stateLock = Any()
    @Volatile
    private var blocking = false
    private var blockingState: BlockingStatus = BlockingStatus.FullBlocking
    private var retainedBytes = 0L
    private var messageCount = 0
    private val pendingPackets = ArrayDeque<PendingChatMessage>()
    private var pendingBytes = 0L
    @Volatile private var overflowed = false
    private val overflowDisconnected = AtomicBoolean()
    private val draining = AtomicBoolean()
    @Volatile private var closed = false

    init {
        require(maxMessages > 0 && maxBytes > 0 && maxPendingPackets > 0 && maxPendingBytes > 0)
    }

    fun startBlocking() = synchronized(stateLock) {
        if (blocking) return@synchronized
        blockingState = BlockingStatus.PartialBlocking(0)
        blocking = true
    }

    fun stopBlocking() = synchronized(stateLock) {
        blocking = false
    }

    fun shouldBlockMessage(): Boolean = blocking

    // Because addMessage is internal and is called after findMessage,
    // individual messages passed here will not exceed NBT_SIZE_LIMIT.
    // However, when messages are merged into batches elsewhere, those batches might exceed the limit,
    // which is why batching logic exists outside this function.
    internal fun addMessage(message: Message): Unit = synchronized(stateLock) {
        if (closed) return@synchronized
        message.onAddedToHistory(blocking)
        if (blocking) {
            blockingState = blockingState.addMessage()
        }
        messages.add(message)
        messageCount++
        retainedBytes += message.estimatedNBTSize.coerceAtLeast(0)
        while (!closed && (messageCount > maxMessages || retainedBytes > maxBytes)) {
            val removed = messages.poll() ?: break
            messageCount--
            retainedBytes -= removed.estimatedNBTSize.coerceAtLeast(0)
            if (removed is Message.PlayerMessage) {
                queuePacket(removed)
            }
        }
    }

    private fun queuePacket(message: Message.PlayerMessage): Boolean {
        val packet = message.takePendingPacket() ?: return false
        val bytes = message.estimatedNBTSize.coerceAtLeast(256)
        if (pendingPackets.size >= maxPendingPackets || pendingBytes + bytes > maxPendingBytes) {
            overflowed = true
            closed = true
            clear()
            clearPending()
        } else {
            pendingPackets.addLast(PendingChatMessage(message.message, packet, bytes))
            pendingBytes += bytes
        }
        return true
    }

    internal fun sendSignedMessage(message: Message.PlayerMessage, user: User, registry: ResendTokenRegistry): Boolean {
        val queued = synchronized(stateLock) { !closed && queuePacket(message) }
        if (queued || overflowed) drainPackets(user, registry)
        return queued || overflowed
    }

    internal fun drainPackets(user: User, registry: ResendTokenRegistry) {
        if (!overflowed && !hasPending()) return
        val channel = user.channel as Channel
        if (overflowed) {
            if (overflowDisconnected.compareAndSet(false, true)) {
                try { onOverflow(user) } finally { channel.close() }
            }
            return
        }
        if (!hasPending() || !draining.compareAndSet(false, true)) return
        val action = Runnable {
            try {
                if (!closed && channel.isActive) {
                    repeat(256) {
                        val pending = pollPending() ?: return@Runnable
                        registry.issue(user.uuid, pending.component)
                        user.sendPacket(pending.packet)
                    }
                } else synchronized(stateLock) { clearPending() }
            } finally {
                draining.set(false)
                if (hasPending()) {
                    try {
                        channel.eventLoop().execute { drainPackets(user, registry) }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        synchronized(stateLock) { clearPending() }
                    }
                }
            }
        }
        try {
            if (channel.eventLoop().inEventLoop()) action.run() else channel.eventLoop().execute(action)
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            synchronized(stateLock) { clearPending() }
            draining.set(false)
        }
    }

    internal fun close() = synchronized(stateLock) {
        closed = true
        clear()
        clearPending()
    }

    private fun hasPending(): Boolean = synchronized(stateLock) { !closed && pendingPackets.isNotEmpty() }

    private fun pollPending(): PendingChatMessage? = synchronized(stateLock) {
        pendingPackets.pollFirst()?.also { pendingBytes -= it.estimatedBytes }
    }

    private fun clearPending() {
        pendingPackets.clear()
        pendingBytes = 0L
    }

    fun hasMessage(message: Component): Boolean {
        return messages.any { it.message == message }
    }

    fun clear() = synchronized(stateLock) {
        messages.clear()
        retainedBytes = 0L
        messageCount = 0
        blockingState = BlockingStatus.FullBlocking
    }

    fun allowedMessageThrough() = synchronized(stateLock) {
        blockingState = BlockingStatus.FullBlocking
    }

    private fun clearMessage() = "\n".repeat(100 - min(messages.size, darkenLimit))

    fun resendMessages(player: Player, clear: Boolean = true) {
        val (status, snapshot) = synchronized(stateLock) {
            val status = blockingState
            blockingState = BlockingStatus.PartialBlocking(0)
            status to messages.toList()
        }
        when (status) {
            is BlockingStatus.FullBlocking -> {
                resendMessagesWithSizeCheck(player, clear, snapshot)
            }

            is BlockingStatus.PartialBlocking -> {
                snapshot.takeLast(status.newMessages).forEach { it.send(player) }
            }
        }
    }

    private fun resendMessagesWithSizeCheck(player: Player, clear: Boolean, snapshot: List<Message>) {
        var currentBatch = if (clear) {
            Message.TextMessage(Component.text(clearMessage()))
        } else {
            Message.Empty
        }

        var currentSize = currentBatch.estimatedNBTSize

        for (message in snapshot) {
            if (message.canMerge) {
                val messageSize = message.estimatedNBTSize

                if (currentSize + messageSize > NBT_SIZE_LIMIT) {
                    currentBatch.send(player)
                    currentBatch = message
                    currentSize = messageSize
                } else {
                    currentBatch = currentBatch.merge(message)
                    currentSize += messageSize
                }
            } else {
                currentBatch.send(player)
                message.send(player)
                currentBatch = Message.Empty
                currentSize = 0
            }
        }

        if (currentBatch != Message.Empty) {
            currentBatch.send(player)
        }
    }

    fun composeDarkMessage(message: Component, clear: Boolean = true): Component {
        // Start with "no-index" to prevent the server from adding the message to the history
        var msg = Component.text("no-index")
        if (clear) msg = msg.append(Component.text(clearMessage()))
        messages.drop(max(0, messages.size - darkenLimit)).take(darkenLimit).forEach {
            msg = msg.append(it.darkenMessage)
        }
        msg = msg.append(Component.text("\n".repeat(spacing)))
        return msg.append(message)
    }

    @Deprecated(
        "If you use this method, report it on the Discord server; otherwise, it will be removed in future releases!",
        level = DeprecationLevel.ERROR
    )
    fun composeEmptyMessage(message: Component, clear: Boolean = true): Component {
        // Start with "no-index" to prevent the server from adding the message to the history
        var msg = Component.text("no-index")
        if (clear) msg = msg.append(Component.text(clearMessage()))
        return msg.append(message)
    }
}

internal data class PendingChatMessage(
    val component: Component,
    val packet: WrapperPlayServerChatMessage,
    val estimatedBytes: Int,
)

sealed interface BlockingStatus {
    fun addMessage(): BlockingStatus

    // When it only stopped messages from being sent, but not allowed messages to be sent.
    data class PartialBlocking(val newMessages: Int) : BlockingStatus {
        override fun addMessage(): BlockingStatus = copy(newMessages = newMessages + 1)
    }

    // When a message was allowed through.
    data object FullBlocking : BlockingStatus {
        override fun addMessage(): BlockingStatus = this
    }
}

/**
 * Sends [message] back to this player the way it was taken off the wire.
 *
 * A component read out of a packet is built by PacketEvents, and the values it hangs on an item
 * hover are PacketEvents' own. Bukkit hands those to Paper to convert into its own, which fails on
 * the netty thread once the packet is already on its way out, so the write is lost with nothing but
 * a console error. Writing it with the same library that read it keeps that conversion out of the
 * way.
 */
private fun Player.resend(message: Component) =
    WrapperPlayServerSystemChatMessage(false, message) sendPacketTo this

internal interface Message : KoinComponent {
    val message: Component
    val darkenMessage: Component
    val canMerge: Boolean
    val canDelete: Boolean
    val estimatedNBTSize: Int

    fun send(player: Player)

    fun withEstimatedSize(size: Int): Message

    fun merge(other: Message): Message {
        require(canMerge) { "Cannot merge messages that cannot be merged." }
        require(other.canMerge) { "Cannot merge with another message that cannot be merged." }
        return TextMessage(
            message.append(Component.text("\n")).append(other.message),
            estimatedNBTSize + other.estimatedNBTSize
        )
    }

    fun onAddedToHistory(isBlocking: Boolean) {}

    object Empty : Message {
        override val message: Component = Component.empty()
        override val darkenMessage: Component = Component.empty()
        override val canMerge: Boolean = true
        override val canDelete: Boolean = true
        override val estimatedNBTSize: Int = 0

        override fun send(player: Player) {
        }

        override fun withEstimatedSize(size: Int): Message = this
    }

    data class TextMessage(
        override val message: Component,
        override val estimatedNBTSize: Int = 0
    ) : Message {
        private val resendTokenRegistry: ResendTokenRegistry by inject()
        override val canMerge: Boolean = true
        override val canDelete: Boolean = true

        override val darkenMessage: Component by lazy(LazyThreadSafetyMode.NONE) {
            Component.text("${message.plainText()}\n").color(TextColor.color(0x7d8085))
        }

        override fun send(player: Player) {
            resendTokenRegistry.issue(player.uniqueId, message)
            player.resend(message)
        }

        override fun withEstimatedSize(size: Int): Message = copy(estimatedNBTSize = size)
    }

    data class PlayerMessage(
        override val message: Component,
        @Volatile var packet: WrapperPlayServerChatMessage?,
        override val estimatedNBTSize: Int = 0
    ) : Message {
        private val resendTokenRegistry: ResendTokenRegistry by inject()
        override val canMerge: Boolean get() = packet == null
        override val canDelete: Boolean get() = packet == null

        override val darkenMessage: Component by lazy(LazyThreadSafetyMode.NONE) {
            Component.text("${message.plainText()}\n").color(TextColor.color(0x7d8085))
        }

        override fun onAddedToHistory(isBlocking: Boolean) {
            if (!isBlocking) {
                packet = null
            }
        }

        override fun send(player: Player) {
            val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return
            if (!player.chatHistory.sendSignedMessage(this, user, resendTokenRegistry)) {
                resendTokenRegistry.issue(player.uniqueId, message)
                player.resend(message)
            }
        }

        internal fun takePendingPacket(): WrapperPlayServerChatMessage? = synchronized(this) {
            packet.also { packet = null }
        }

        override fun withEstimatedSize(size: Int): Message = copy(estimatedNBTSize = size)
    }
}
