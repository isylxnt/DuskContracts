package dev.isylxnt.duskcontracts.platform;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ReflectivePlatformScheduler implements PlatformScheduler {
    private final Plugin plugin;
    private final boolean folia;
    private final Set<PlatformTask> tasks = ConcurrentHashMap.newKeySet();

    public ReflectivePlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.folia = classPresent("io.papermc.paper.threadedregions.RegionizedServer")
                && methodPresent(Bukkit.getServer().getClass(), "getGlobalRegionScheduler");
    }

    @Override public PlatformTask runEntity(Entity entity, Runnable task) {
        if (!folia) return trackOnce(task, wrapped -> Bukkit.getScheduler().runTask(plugin, wrapped));
        Object scheduler = invoke(entity, "getScheduler");
        return trackReflectiveOnce(task, wrapped -> invokeCompatible(scheduler, "run", plugin, wrapped, (Runnable) () -> { }));
    }

    @Override public PlatformTask runEntityLater(Entity entity, Duration delay, Runnable task) {
        long ticks = Math.max(1, (delay.toMillis() + 49) / 50);
        if (!folia) return trackOnce(task, wrapped -> Bukkit.getScheduler().runTaskLater(plugin, wrapped, ticks));
        Object scheduler = invoke(entity, "getScheduler");
        return trackReflectiveOnce(task, wrapped -> invokeCompatible(scheduler, "runDelayed", plugin, wrapped, (Runnable) () -> { }, ticks));
    }

    @Override public PlatformTask runRegion(Location location, Runnable task) {
        if (!folia) return trackOnce(task, wrapped -> Bukkit.getScheduler().runTask(plugin, wrapped));
        Object scheduler = invoke(Bukkit.getServer(), "getRegionScheduler");
        return trackReflectiveOnce(task, wrapped -> invokeCompatible(scheduler, "run", plugin, location, wrapped));
    }

    @Override public PlatformTask runGlobal(Runnable task) {
        if (!folia) return trackOnce(task, wrapped -> Bukkit.getScheduler().runTask(plugin, wrapped));
        Object scheduler = invoke(Bukkit.getServer(), "getGlobalRegionScheduler");
        return trackReflectiveOnce(task, wrapped -> invokeCompatible(scheduler, "run", plugin, wrapped));
    }

    @Override public PlatformTask runAsync(Runnable task) {
        if (!folia) return trackOnce(task, wrapped -> Bukkit.getScheduler().runTaskAsynchronously(plugin, wrapped));
        Object scheduler = invoke(Bukkit.getServer(), "getAsyncScheduler");
        return trackReflectiveOnce(task, wrapped -> invokeCompatible(scheduler, "runNow", plugin, wrapped));
    }

    @Override public PlatformTask repeatAsync(Duration initialDelay, Duration period, Runnable task) {
        if (!folia) {
            long start = Math.max(1, initialDelay.toMillis() / 50);
            long every = Math.max(1, period.toMillis() / 50);
            return track(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, start, every));
        }
        Object scheduler = invoke(Bukkit.getServer(), "getAsyncScheduler");
        Object handle = invokeCompatible(scheduler, "runAtFixedRate", plugin, consumer(task),
                Math.max(1, initialDelay.toMillis()), Math.max(1, period.toMillis()), TimeUnit.MILLISECONDS);
        return trackReflective(handle);
    }

    @Override public boolean isOwnedContext(Entity entity) {
        if (!folia) return Bukkit.isPrimaryThread();
        try {
            Method m = Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class);
            return (boolean) m.invoke(null, entity);
        } catch (ReflectiveOperationException ex) { return false; }
    }
    @Override public boolean isFolia() { return folia; }
    @Override public String mode() { return folia ? "Folia capability schedulers" : "Paper Bukkit scheduler fallback"; }
    @Override public void cancelAll() { tasks.forEach(PlatformTask::cancel); tasks.clear(); }

    private PlatformTask track(BukkitTask task) {
        PlatformTask wrapped = task::cancel; tasks.add(wrapped); return wrapped;
    }
    private PlatformTask trackReflective(Object handle) {
        PlatformTask wrapped = () -> { if (handle != null) invoke(handle, "cancel"); };
        tasks.add(wrapped); return wrapped;
    }
    private PlatformTask trackOnce(Runnable task, Function<Runnable, BukkitTask> schedule) {
        TrackedTask tracked = new TrackedTask(); tasks.add(tracked);
        try {
            tracked.bukkit = schedule.apply(() -> { try { task.run(); } finally { tasks.remove(tracked); } });
            return tracked;
        } catch (RuntimeException ex) { tasks.remove(tracked); throw ex; }
    }
    private PlatformTask trackReflectiveOnce(Runnable task, Function<Consumer<Object>, Object> schedule) {
        TrackedTask tracked = new TrackedTask(); tasks.add(tracked);
        try {
            tracked.reflective = schedule.apply(ignored -> { try { task.run(); } finally { tasks.remove(tracked); } });
            return tracked;
        } catch (RuntimeException ex) { tasks.remove(tracked); throw ex; }
    }
    private final class TrackedTask implements PlatformTask {
        private volatile BukkitTask bukkit; private volatile Object reflective;
        @Override public void cancel() {
            tasks.remove(this);
            BukkitTask localBukkit = bukkit; if (localBukkit != null) localBukkit.cancel();
            Object localReflective = reflective; if (localReflective != null) invoke(localReflective, "cancel");
        }
    }
    private static Consumer<Object> consumer(Runnable task) { return ignored -> task.run(); }
    private static boolean classPresent(String name) { try { Class.forName(name); return true; } catch (ClassNotFoundException ex) { return false; } }
    private static boolean methodPresent(Class<?> type, String name) {
        for (Method method : type.getMethods()) if (method.getName().equals(name)) return true;
        return false;
    }
    private static Object invoke(Object target, String name) { return invokeCompatible(target, name); }
    private static Object invokeCompatible(Object target, String name, Object... args) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try { return method.invoke(target, args); }
            catch (IllegalArgumentException ignored) { /* try overload */ }
            catch (IllegalAccessException | InvocationTargetException ex) { throw new IllegalStateException("Scheduler invocation failed: " + name, ex); }
        }
        throw new IllegalStateException("Required scheduler capability is missing: " + name + "/" + args.length);
    }
}
