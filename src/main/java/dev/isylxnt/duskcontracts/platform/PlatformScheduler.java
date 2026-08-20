package dev.isylxnt.duskcontracts.platform;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import java.time.Duration;

public interface PlatformScheduler {
    PlatformTask runEntity(Entity entity, Runnable task);
    PlatformTask runEntityLater(Entity entity, Duration delay, Runnable task);
    PlatformTask runRegion(Location location, Runnable task);
    PlatformTask runGlobal(Runnable task);
    PlatformTask runAsync(Runnable task);
    PlatformTask repeatAsync(Duration initialDelay, Duration period, Runnable task);
    boolean isOwnedContext(Entity entity);
    boolean isFolia();
    String mode();
    void cancelAll();
}
