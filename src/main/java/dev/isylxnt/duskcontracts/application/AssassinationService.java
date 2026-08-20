package dev.isylxnt.duskcontracts.application;

import dev.isylxnt.duskcontracts.api.ContractView;
import dev.isylxnt.duskcontracts.api.event.ContractCompletedEvent;
import dev.isylxnt.duskcontracts.api.event.ContractContributionEvent;
import dev.isylxnt.duskcontracts.config.ConfigManager;
import dev.isylxnt.duskcontracts.domain.Contract;
import dev.isylxnt.duskcontracts.domain.ContractStatus;
import dev.isylxnt.duskcontracts.domain.RewardType;
import dev.isylxnt.duskcontracts.localization.Messages;
import dev.isylxnt.duskcontracts.persistence.Storage;
import dev.isylxnt.duskcontracts.platform.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AssassinationService implements Listener {
    private final Storage storage;
    private final ConfigManager config;
    private final PlatformScheduler scheduler;
    private final Messages messages;
    private final Logger logger;

    public AssassinationService(Storage storage, ConfigManager config, PlatformScheduler scheduler, Messages messages, Logger logger) {
        this.storage = storage; this.config = config; this.scheduler = scheduler; this.messages = messages; this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        boolean allowOwn = config.get().allowOwnFulfillment() || killer.hasPermission("duskcontracts.fulfill.own");
        Duration repeatCooldown = killer.hasPermission("duskcontracts.assassination.bypass-farming")
                ? Duration.ZERO : config.get().assassinationRepeatKillCooldown();
        storage.completeAssassinations(killer.getUniqueId(), killer.getName(), victim.getUniqueId(),
                        Instant.now(), allowOwn, repeatCooldown)
                .whenComplete((completed, error) -> {
                    if (error != null) { logger.log(Level.WARNING, "Could not complete assassination contracts", error); return; }
                    if (completed.isEmpty()) return;
                    scheduler.runEntity(killer, () -> {
                        long money = completed.stream().filter(c -> c.rewardType() == RewardType.MONEY).mapToLong(Contract::rewardMinor).sum();
                        for (Contract contract : completed) {
                            ContractView view = view(contract);
                            Bukkit.getPluginManager().callEvent(new ContractContributionEvent(view, killer.getUniqueId(), 1,
                                    contract.rewardType() == RewardType.MONEY ? contract.rewardMinor() : 0, false));
                            Bukkit.getPluginManager().callEvent(new ContractCompletedEvent(view, false));
                        }
                        messages.send(killer, "assassination.completed", Map.of("target", victim.getName(),
                                "contracts", completed.size(), "money_minor", money));
                    });
                });
    }

    private static ContractView view(Contract c) {
        return ContractViewMapper.from(c);
    }
}
