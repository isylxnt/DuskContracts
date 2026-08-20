package dev.isylxnt.duskcontracts.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import dev.isylxnt.duskcontracts.api.ContractView;
import dev.isylxnt.duskcontracts.api.DuskContractsApi;
import dev.isylxnt.duskcontracts.api.DuskContractsApiImpl;
import dev.isylxnt.duskcontracts.api.event.ContractExpiredEvent;
import dev.isylxnt.duskcontracts.application.AssassinationService;
import dev.isylxnt.duskcontracts.application.ClaimApplicationService;
import dev.isylxnt.duskcontracts.application.ContractApplicationService;
import dev.isylxnt.duskcontracts.application.ContractViewMapper;
import dev.isylxnt.duskcontracts.commands.ContractsCommand;
import dev.isylxnt.duskcontracts.commands.DeferredCommand;
import dev.isylxnt.duskcontracts.config.ConfigManager;
import dev.isylxnt.duskcontracts.config.ConfigurationException;
import dev.isylxnt.duskcontracts.config.ItemRules;
import dev.isylxnt.duskcontracts.economy.EconomyBridge;
import dev.isylxnt.duskcontracts.economy.ReflectiveVaultEconomy;
import dev.isylxnt.duskcontracts.economy.VaultServiceListener;
import dev.isylxnt.duskcontracts.integration.DuskPlaceholders;
import dev.isylxnt.duskcontracts.inventory.ItemSerializer;
import dev.isylxnt.duskcontracts.inventory.MenuManager;
import dev.isylxnt.duskcontracts.inventory.WizardService;
import dev.isylxnt.duskcontracts.localization.Messages;
import dev.isylxnt.duskcontracts.persistence.DatabaseFactory;
import dev.isylxnt.duskcontracts.persistence.JdbcStorage;
import dev.isylxnt.duskcontracts.persistence.Migrations;
import dev.isylxnt.duskcontracts.persistence.Storage;
import dev.isylxnt.duskcontracts.platform.PlatformScheduler;
import dev.isylxnt.duskcontracts.platform.ReflectivePlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class DuskContractsPlugin extends JavaPlugin {
    private static final Duration PLAYER_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private volatile boolean ready;
    private volatile boolean stopping;
    private PlatformScheduler scheduler;
    private Storage storage;
    private ConfigManager config;
    private Messages messages;
    private WizardService wizard;
    private MenuManager menus;
    private DeferredCommand deferred;
    private DuskPlaceholders placeholders;

    @Override
    public void onEnable() {
        stopping = false;
        try {
            config = new ConfigManager(this);
            config.initialize();
            messages = new Messages(this);
            messages.reload(config.get().locale());
        } catch (ConfigurationException | RuntimeException ex) {
            getLogger().log(Level.SEVERE, "DuskContracts could not load configuration; the plugin will be disabled.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        scheduler = new ReflectivePlatformScheduler(this);
        deferred = new DeferredCommand(messages);
        PluginCommand command = getCommand("contracts");
        if (command == null) {
            getLogger().severe("plugin.yml did not register /contracts");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(deferred);
        command.setTabCompleter(deferred);

        EconomyBridge economy = new ReflectiveVaultEconomy(getLogger());
        getServer().getPluginManager().registerEvents(new VaultServiceListener(economy), this);
        scheduler.runAsync(() -> startStorage(economy));
    }

    private void startStorage(EconomyBridge economy) {
        try {
            HikariDataSource dataSource = DatabaseFactory.create(this, config.storage());
            JdbcStorage next = new JdbcStorage(dataSource, config.storage().type(), config.storage().poolMaximum());
            storage = next;
            next.initialize()
                    .thenCompose(ignored -> next.recoverStale(config.get().operationTimeout()))
                    .thenCompose(ignored -> next.purgeMaintenance(
                            Instant.now().minus(Duration.ofDays(config.get().auditRetentionDays())), 10_000))
                    .whenComplete((ignored, error) -> {
                        if (stopping) return;
                        if (error != null) {
                            failStartup("Storage initialization failed", unwrap(error));
                            return;
                        }
                        scheduler.runGlobal(() -> installRuntime(next, economy));
                    });
        } catch (RuntimeException ex) {
            failStartup("Could not create the storage pool. No password was logged", ex);
        }
    }

    private void failStartup(String message, Throwable error) {
        if (stopping) return;
        getLogger().log(Level.SEVERE, message + "; DuskContracts will be disabled instead of remaining half-loaded.", error);
        try {
            scheduler.runGlobal(() -> {
                if (!stopping) getServer().getPluginManager().disablePlugin(this);
            });
        } catch (RuntimeException scheduleFailure) {
            error.addSuppressed(scheduleFailure);
            getLogger().log(Level.SEVERE, "The server rejected the startup-failure shutdown task.", scheduleFailure);
        }
    }

    private void installRuntime(Storage active, EconomyBridge economy) {
        if (stopping) return;
        ItemSerializer serializer = new ItemSerializer(config.get().maxSerializedItemBytes());
        ItemRules rules = new ItemRules(new File(getDataFolder(), "item-rules.yml"));
        ContractApplicationService contractService = new ContractApplicationService(
                active, config, scheduler, economy, serializer, rules, getLogger());
        ClaimApplicationService claimService = new ClaimApplicationService(
                active, economy, config, scheduler, serializer, getLogger());
        wizard = new WizardService(this, config, messages, scheduler, contractService, rules);
        menus = new MenuManager(this, active, contractService, claimService, wizard, messages, config, scheduler, serializer);
        AssassinationService assassinations = new AssassinationService(active, config, scheduler, messages, getLogger());

        getServer().getPluginManager().registerEvents(wizard, this);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(assassinations, this);

        ContractsCommand executor = new ContractsCommand(
                this, () -> ready, config, messages, menus, wizard, contractService, active, scheduler);
        deferred.delegate(executor);

        DuskContractsApi api = new DuskContractsApiImpl(active, getPluginMeta().getVersion());
        Bukkit.getServicesManager().register(DuskContractsApi.class, api, this, ServicePriority.Normal);
        registerPlaceholders(active);
        scheduleMaintenance(active);

        ready = true;
        if (config.get().verboseStartupReport()) {
            getLogger().info("DuskContracts " + getPluginMeta().getVersion() + " ready: platform=" + scheduler.mode()
                    + ", storage=" + config.storage().type() + ", economy=" + economy.providerName()
                    + ", schema=" + Migrations.CURRENT);
        }
    }

    private void registerPlaceholders(Storage active) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        try {
            DuskPlaceholders expansion = new DuskPlaceholders(this, active);
            if (expansion.register()) {
                placeholders = expansion;
                getLogger().info("PlaceholderAPI expansion registered.");
            } else {
                getLogger().warning("PlaceholderAPI rejected the duskcontracts expansion registration.");
            }
        } catch (LinkageError error) {
            getLogger().warning("PlaceholderAPI was present but its API was incompatible: " + error.getMessage());
        }
    }

    private void scheduleMaintenance(Storage active) {
        scheduler.repeatAsync(config.get().expirationSweep(), config.get().expirationSweep(), () ->
                active.expireBatch(Instant.now(), config.get().expirationBatchSize())
                        .thenAccept(expired -> {
                            if (!expired.isEmpty() && !stopping) scheduler.runGlobal(() -> expired.forEach(contract ->
                                    Bukkit.getPluginManager().callEvent(new ContractExpiredEvent(view(contract), false))));
                        })
                        .exceptionally(error -> {
                            if (!stopping) getLogger().log(Level.WARNING, "Expiration sweep failed", unwrap(error));
                            return null;
                        }));
        scheduler.repeatAsync(Duration.ofHours(24), Duration.ofHours(24), () ->
                active.purgeMaintenance(Instant.now().minus(Duration.ofDays(config.get().auditRetentionDays())), 10_000)
                        .exceptionally(error -> {
                            if (!stopping) getLogger().log(Level.WARNING, "Maintenance cleanup failed", unwrap(error));
                            return null;
                        }));
    }

    @Override
    public void onDisable() {
        stopping = true;
        ready = false;

        DuskPlaceholders expansion = placeholders;
        placeholders = null;
        if (expansion != null) {
            try {
                expansion.shutdown();
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Could not fully unregister PlaceholderAPI expansion", ex);
            }
        }

        closePlayerMenus();
        if (scheduler != null) scheduler.cancelAll();
        Bukkit.getServicesManager().unregisterAll(this);

        Storage current = storage;
        storage = null;
        if (current != null) current.close();
        wizard = null;
        menus = null;
    }

    private void closePlayerMenus() {
        if (wizard == null && menus == null) return;
        Map<Player, CompletableFuture<Void>> pending = new LinkedHashMap<>();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            Runnable cleanup = () -> {
                if (menus != null) menus.shutdownPlayer(player);
                if (wizard != null) wizard.shutdownPlayer(player);
            };
            if (scheduler == null || scheduler.isOwnedContext(player)) {
                runCleanup(player, cleanup);
                continue;
            }
            CompletableFuture<Void> done = new CompletableFuture<>();
            pending.put(player, done);
            try {
                scheduler.runEntity(player, () -> {
                    try {
                        cleanup.run();
                        done.complete(null);
                    } catch (RuntimeException ex) {
                        done.completeExceptionally(ex);
                    }
                });
            } catch (RuntimeException ex) {
                done.completeExceptionally(ex);
            }
        }

        if (!pending.isEmpty()) awaitPlayerCleanup(pending);
        if (wizard != null) wizard.finishShutdown();
        if (menus != null) menus.finishShutdown();
    }

    private void awaitPlayerCleanup(Map<Player, CompletableFuture<Void>> pending) {
        try {
            CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new))
                    .get(PLAYER_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            getLogger().warning("Timed out waiting for regional menu cleanup; unfinished sessions will use guarded fallback cleanup.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "A regional menu cleanup failed", unwrap(ex));
        }

        List<Player> unfinished = new ArrayList<>();
        pending.forEach((player, future) -> {
            if (!future.isDone() || future.isCompletedExceptionally()) unfinished.add(player);
        });
        if (!unfinished.isEmpty() && scheduler != null) scheduler.cancelAll();
        for (Player player : unfinished) {
            runCleanup(player, () -> {
                if (menus != null) menus.shutdownPlayer(player);
                if (wizard != null) wizard.shutdownPlayer(player);
            });
        }
    }

    private void runCleanup(Player player, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Could not close and return menu assets for " + player.getUniqueId(), ex);
        }
    }

    public boolean isReady() {
        return ready;
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static ContractView view(dev.isylxnt.duskcontracts.domain.Contract contract) {
        return ContractViewMapper.from(contract);
    }
}
