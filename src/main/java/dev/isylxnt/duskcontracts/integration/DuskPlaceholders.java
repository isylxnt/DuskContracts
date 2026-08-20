package dev.isylxnt.duskcontracts.integration;

import dev.isylxnt.duskcontracts.persistence.PlayerStats;
import dev.isylxnt.duskcontracts.persistence.Storage;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class DuskPlaceholders extends PlaceholderExpansion {
    private static final long REFRESH_NANOS = 15_000_000_000L;
    private static final long EVICT_NANOS = 600_000_000_000L;
    private static final int MAX_CACHE_ENTRIES = 2_048;
    private final JavaPlugin plugin; private final Storage storage;
    private final Map<UUID,Snapshot> cache=new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<PlayerStats>> loading = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger requests = new AtomicInteger();
    public DuskPlaceholders(JavaPlugin plugin,Storage storage){this.plugin=plugin;this.storage=storage;}
    @Override public @NotNull String getIdentifier(){return "duskcontracts";}
    @Override public @NotNull String getAuthor(){return "isylxnt";}
    @Override public @NotNull String getVersion(){return plugin.getPluginMeta().getVersion();}
    @Override public boolean persist(){return true;}
    @Override public @Nullable String onRequest(OfflinePlayer player,@NotNull String params){
        if(player==null)return "0";
        UUID id=player.getUniqueId();
        long now=System.nanoTime();
        Snapshot value=cache.getOrDefault(id,new Snapshot(new PlayerStats(0,0,0,0,0),0));
        if(!closed.get() && now-value.loadedAt>REFRESH_NANOS) refresh(id);
        if((requests.incrementAndGet() & 127)==0)evict(now);
        return switch(params.toLowerCase(java.util.Locale.ROOT)){case "active"->Long.toString(value.stats.active());case "created"->Long.toString(value.stats.created());case "completed"->Long.toString(value.stats.completed());case "claims"->Long.toString(value.stats.claims());case "contributed"->Long.toString(value.stats.contributed());default->null;};
    }

    public void shutdown(){
        if(!closed.compareAndSet(false,true))return;
        unregister();
        loading.clear();
        cache.clear();
    }

    int cachedPlayers(){return cache.size();}
    int inFlightRequests(){return loading.size();}

    private void refresh(UUID playerId){
        CompletableFuture<PlayerStats> future=loading.computeIfAbsent(playerId,storage::playerStats);
        future.whenComplete((stats,error)->{
                loading.remove(playerId,future);
                if(error==null&&!closed.get()){
                    cache.put(playerId,new Snapshot(stats,System.nanoTime()));
                    trimToLimit();
                }
        });
    }

    private void evict(long now){
        cache.entrySet().removeIf(entry->now-entry.getValue().loadedAt>EVICT_NANOS);
        trimToLimit();
    }

    private void trimToLimit(){
        while(cache.size()>MAX_CACHE_ENTRIES){
            UUID oldest=null;Snapshot oldestSnapshot=null;long loaded=Long.MAX_VALUE;
            for(Map.Entry<UUID,Snapshot> entry:cache.entrySet()){
                if(entry.getValue().loadedAt<loaded){oldest=entry.getKey();oldestSnapshot=entry.getValue();loaded=entry.getValue().loadedAt;}
            }
            if(oldest==null||oldestSnapshot==null||!cache.remove(oldest,oldestSnapshot))break;
        }
    }
    private record Snapshot(PlayerStats stats,long loadedAt){}
}
