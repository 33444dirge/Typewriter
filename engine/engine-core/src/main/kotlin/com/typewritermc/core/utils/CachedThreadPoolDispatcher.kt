package com.typewritermc.core.utils

import kotlinx.coroutines.*
import org.jetbrains.annotations.ApiStatus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@ApiStatus.NonExtendable
@ApiStatus.Internal
abstract class TypewriterDispatcher(
    private val delegate: CoroutineContext
) : CoroutineDispatcher(), KoinComponent {
    private val isEnabled by inject<Boolean>(named("isEnabled"))

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        return delegate[CoroutineDispatcher]!!.isDispatchNeeded(context)
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        return delegate[CoroutineDispatcher]!!.dispatch(context, block)
    }
}

/**
 * The maximum number of platform threads allowed in the pool.
 *
 * [MAX_PLATFORM_THREADS] = The maximum number of threads in the pool.
 *
 * All platform threads in the pool are [daemon threads][Thread.setDaemon]
 *
 * @see Thread
 */
val MAX_PLATFORM_THREADS = Runtime.getRuntime().availableProcessors() * 2

private fun createWorkerDispatcher(): ExecutorCoroutineDispatcher =
    ThreadPoolExecutor(
        MAX_PLATFORM_THREADS,
        MAX_PLATFORM_THREADS,
        60L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue((MAX_PLATFORM_THREADS * 64).coerceAtLeast(256)),
        Thread.ofPlatform().daemon(true).name("TypewriterPoolThread-", 1)
            .factory(),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }.asCoroutineDispatcher()

private object AsyncWorkers {
    @Volatile var dispatcher = createWorkerDispatcher()
    private var closed = false

    @Synchronized fun start() {
        if (!closed) return
        dispatcher = createWorkerDispatcher()
        closed = false
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        dispatcher.close()
    }
}

val Dispatchers.UntickedAsync: CoroutineDispatcher get() = AsyncWorkers.dispatcher

fun startAsyncWorkers() = AsyncWorkers.start()
fun closeAsyncWorkers() = AsyncWorkers.close()

/** Platform lifetime inherited by extension jobs so tasks cannot survive plugin shutdown. */
object TypewriterCoroutineLifetime {
    private val lifecycleLock = Any()
    @Volatile
    var parent: Job? = null
    private var platform: Job? = null
    private var generation: CompletableJob? = null
    private var stopped = false

    fun attachPlatform(job: Job?) {
        synchronized(lifecycleLock) {
            check(generation == null) { "The previous Typewriter generation must be unloaded first" }
            platform = job
            parent = job
            stopped = false
        }
    }

    fun beginGeneration() {
        synchronized(lifecycleLock) {
            check(!stopped) { "The Typewriter platform has stopped" }
            check(generation == null) { "The previous Typewriter generation must be unloaded first" }
            generation = SupervisorJob(platform).also { parent = it }
        }
    }

    suspend fun stopGeneration() {
        val previous = synchronized(lifecycleLock) {
            val current = generation ?: return
            generation = null
            parent = Job().apply { cancel() }
            current
        }
        val caller = currentCoroutineContext()[Job]
        val children = previous.children.toList()
        val retained = children.firstOrNull { it.containsJob(caller) }
        val stopping = children.filter { it !== retained }
        stopping.forEach { it.cancel() }
        stopping.joinAll()
        if (retained == null) previous.cancelAndJoin()
        else retained.invokeOnCompletion { previous.cancel() }
    }

    fun stopPlatform() {
        val previous = synchronized(lifecycleLock) {
            val jobs = generation to platform
            stopped = true
            generation = null
            platform = null
            parent = Job().apply { cancel() }
            jobs
        }
        previous.first?.cancel()
        previous.second?.cancel()
    }

    private fun Job.containsJob(target: Job?): Boolean =
        this === target || children.any { it.containsJob(target) }
}

fun CoroutineContext.launch(
    block: suspend CoroutineScope.() -> Unit
): Job = CoroutineScope(this + (this[Job] ?: TypewriterCoroutineLifetime.parent ?: SupervisorJob()))
    .launch(block = block)

suspend fun <T> CoroutineContext.switchContext(
    block: suspend CoroutineScope.() -> T
): T = withContext(this, block = block)
