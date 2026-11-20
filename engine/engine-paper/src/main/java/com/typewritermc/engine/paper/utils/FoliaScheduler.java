package com.typewritermc.engine.paper.utils;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Scheduling boundaries for the Folia build. Entity retirement callbacks must only release
 * bookkeeping; they must never read or modify an entity, inventory, block, or world.
 */
public final class FoliaScheduler implements AutoCloseable {
    private final Plugin plugin;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Pending> pendingTasks = ConcurrentHashMap.newKeySet();

    public FoliaScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Runs an entity operation on a later tick, or its retirement cleanup if the entity is gone. */
    public ScheduledTask atEntity(Entity entity, Runnable action, Runnable retired, long delayTicks) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(action, "action");
        if (!active()) {
            retire(retired);
            return null;
        }
        Pending pending = register(retired);
        try {
            ScheduledTask scheduled = entity.getScheduler().runDelayed(plugin, task -> {
                if (active()) pending.execute(action);
                else pending.reject();
            }, pending::reject, Math.max(1L, delayTicks));
            return pending.attach(scheduled);
        } catch (org.bukkit.plugin.IllegalPluginAccessException error) {
            pending.reject();
            return null;
        } catch (RuntimeException error) {
            pending.reject();
            throw error;
        }
    }

    /** Runs a block or chunk operation on the region owning the copied target location. */
    public ScheduledTask atRegion(Location target, Runnable action, long delayTicks) {
        return atRegion(target, action, null, delayTicks);
    }

    public ScheduledTask atRegion(Location target, Runnable action, Runnable rejected, long delayTicks) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(action, "action");
        if (!active()) {
            retire(rejected);
            return null;
        }
        Location snapshot = target.clone();
        Objects.requireNonNull(snapshot.getWorld(), "target.world");
        Pending pending = register(rejected);
        try {
            return pending.attach(Bukkit.getRegionScheduler().runDelayed(plugin, snapshot, task -> {
                if (active()) pending.execute(action);
                else pending.reject();
            }, Math.max(1L, delayTicks)));
        } catch (org.bukkit.plugin.IllegalPluginAccessException error) {
            pending.reject();
            return null;
        } catch (RuntimeException error) {
            pending.reject();
            throw error;
        }
    }

    /** Runs global state and console operations; the callback must not access region-owned state. */
    public ScheduledTask global(Runnable action, long delayTicks) {
        return global(action, null, delayTicks);
    }

    public ScheduledTask global(Runnable action, Runnable rejected, long delayTicks) {
        Objects.requireNonNull(action, "action");
        if (!active()) {
            retire(rejected);
            return null;
        }
        Pending pending = register(rejected);
        try {
            return pending.attach(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                if (active()) pending.execute(action);
                else pending.reject();
            }, Math.max(1L, delayTicks)));
        } catch (org.bukkit.plugin.IllegalPluginAccessException error) {
            pending.reject();
            return null;
        } catch (RuntimeException error) {
            pending.reject();
            throw error;
        }
    }

    /** Runs snapshot processing or I/O without access to live Bukkit state. */
    public ScheduledTask async(Runnable action) {
        return async(action, null);
    }

    public ScheduledTask async(Runnable action, Runnable rejected) {
        Objects.requireNonNull(action, "action");
        if (!active()) {
            retire(rejected);
            return null;
        }
        Pending pending = register(rejected);
        try {
            return pending.attach(Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                if (active()) pending.execute(action);
                else pending.reject();
            }));
        } catch (org.bukkit.plugin.IllegalPluginAccessException error) {
            pending.reject();
            return null;
        } catch (RuntimeException error) {
            pending.reject();
            throw error;
        }
    }

    /** Runs asynchronous work after a nonnegative wall-clock delay. */
    public ScheduledTask asyncDelayed(Runnable action, long delayMillis) {
        return asyncDelayed(action, null, delayMillis);
    }

    public ScheduledTask asyncDelayed(Runnable action, Runnable rejected, long delayMillis) {
        Objects.requireNonNull(action, "action");
        if (!active()) {
            retire(rejected);
            return null;
        }
        Pending pending = register(rejected);
        try {
            return pending.attach(Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
                if (active()) pending.execute(action);
                else pending.reject();
            }, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS));
        } catch (org.bukkit.plugin.IllegalPluginAccessException error) {
            pending.reject();
            return null;
        } catch (RuntimeException error) {
            pending.reject();
            throw error;
        }
    }

    /** Repeats an entity callback and makes its first execution wait at least one tick. */
    public ScheduledTask repeatEntity(Entity entity, Consumer<ScheduledTask> action, Runnable retired,
                                      long initialDelayTicks, long periodTicks) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(action, "action");
        if (!active()) {
            retire(retired);
            return null;
        }
        Pending pending = register(retired);
        try {
            return pending.attach(entity.getScheduler().runAtFixedRate(plugin, task -> {
                if (active() && !pending.completed.get()) {
                    try {
                        action.accept(pending);
                    } catch (Throwable error) {
                        pending.reject();
                        throw error;
                    }
                } else pending.reject();
            }, pending::reject, Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks)));
        } catch (org.bukkit.plugin.IllegalPluginAccessException error) {
            pending.reject();
            return null;
        } catch (RuntimeException error) {
            pending.reject();
            throw error;
        }
    }

    /**
     * Starts an asynchronous teleport from the entity's owner. The returned future is never waited
     * on by a region thread. A caller accessing the destination must return through atEntity.
     */
    public CompletableFuture<Boolean> teleport(Entity entity, Location target) {
        Objects.requireNonNull(entity, "entity");
        Location snapshot = Objects.requireNonNull(target, "target").clone();
        Objects.requireNonNull(snapshot.getWorld(), "target.world");
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        atEntity(entity, () -> {
            if (result.isCancelled()) return;
            try {
                entity.teleportAsync(snapshot).whenComplete((success, error) -> {
                    if (error == null) result.complete(success);
                    else result.completeExceptionally(error);
                });
            } catch (Exception error) {
                result.completeExceptionally(error);
            }
        }, () -> result.complete(false), 1L);
        return result;
    }

    /** Checks actual entity ownership instead of the traditional primary-thread flag. */
    public boolean owns(Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    /** Checks the actual region owning a target location. */
    public boolean owns(Location target) {
        return Bukkit.isOwnedByCurrentRegion(target);
    }

    public boolean active() {
        return !closed.get() && plugin.isEnabled();
    }

    /** Prevents queued callbacks from entering plugin code after shutdown. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pendingTasks.forEach(pending -> cleanup(pending::reject));
        cleanup(() -> Bukkit.getGlobalRegionScheduler().cancelTasks(plugin));
        cleanup(() -> Bukkit.getAsyncScheduler().cancelTasks(plugin));
    }

    private Pending register(Runnable rejected) {
        Pending pending = new Pending(rejected);
        pendingTasks.add(pending);
        if (!active()) pending.reject();
        return pending;
    }

    private final class Pending implements ScheduledTask {
        private final AtomicBoolean completed = new AtomicBoolean();
        private final Runnable rejected;
        private volatile ScheduledTask task;

        private Pending(Runnable rejected) {
            this.rejected = rejected;
        }

        private ScheduledTask attach(ScheduledTask scheduled) {
            task = scheduled;
            if (scheduled == null) reject();
            else if (completed.get()) scheduled.cancel();
            return scheduled == null ? null : this;
        }

        private void execute(Runnable action) {
            if (!completed.compareAndSet(false, true)) return;
            pendingTasks.remove(this);
            action.run();
        }

        private void reject() {
            if (!completed.compareAndSet(false, true)) return;
            pendingTasks.remove(this);
            ScheduledTask scheduled = task;
            try {
                if (scheduled != null) scheduled.cancel();
            } finally {
                retire(rejected);
            }
        }

        @Override
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return task != null && task.isRepeatingTask();
        }

        @Override
        public CancelledState cancel() {
            ScheduledTask scheduled = task;
            try {
                return scheduled == null ? CancelledState.CANCELLED_ALREADY : scheduled.cancel();
            } finally {
                if (completed.compareAndSet(false, true)) {
                    pendingTasks.remove(this);
                    retire(rejected);
                }
            }
        }

        @Override
        public ExecutionState getExecutionState() {
            ScheduledTask scheduled = task;
            return scheduled == null ? ExecutionState.CANCELLED : scheduled.getExecutionState();
        }
    }

    private void retire(Runnable retired) {
        if (retired == null) return;
        cleanup(retired);
    }

    private void cleanup(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Typewriter task cleanup failed", error);
        }
    }
}
