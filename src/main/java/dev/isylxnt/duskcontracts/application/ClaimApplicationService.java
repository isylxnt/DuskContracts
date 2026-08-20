package dev.isylxnt.duskcontracts.application;

import dev.isylxnt.duskcontracts.config.ConfigManager;
import dev.isylxnt.duskcontracts.api.event.ContractClaimedEvent;
import dev.isylxnt.duskcontracts.domain.ClaimType;
import dev.isylxnt.duskcontracts.domain.DomainException;
import dev.isylxnt.duskcontracts.economy.EconomyBridge;
import dev.isylxnt.duskcontracts.inventory.ItemBundleCodec;
import dev.isylxnt.duskcontracts.inventory.ItemSerializer;
import dev.isylxnt.duskcontracts.persistence.ClaimRecord;
import dev.isylxnt.duskcontracts.persistence.Storage;
import dev.isylxnt.duskcontracts.platform.PlatformScheduler;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ClaimApplicationService {
    private final Storage storage; private final EconomyBridge economy; private final ConfigManager config;
    private final PlatformScheduler scheduler; private final ItemSerializer serializer; private final Logger logger;
    private final StripedLocks locks = new StripedLocks(256);
    public ClaimApplicationService(Storage storage, EconomyBridge economy, ConfigManager config,
            PlatformScheduler scheduler, ItemSerializer serializer, Logger logger) {
        this.storage = storage; this.economy = economy; this.config = config; this.scheduler = scheduler; this.serializer = serializer; this.logger = logger;
    }

    public void claim(Player player, ClaimRecord displayed, ResultCallback<ClaimRecord> callback) {
        String correlation = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        storage.reserveClaim(displayed.id(), player.getUniqueId()).whenComplete((reserved, error) -> {
            if (error != null) { fail(player, callback, error, correlation); return; }
            if (reserved.isEmpty()) { fail(player, callback, new DomainException(DomainException.Kind.CONFLICT, "Claim changed"), correlation); return; }
            ClaimRecord claim = reserved.get();
            if (claim.type() == ClaimType.MONEY_REWARD || claim.type() == ClaimType.MONEY_RETURN) claimMoney(player, claim, callback, correlation);
            else scheduler.runEntity(player, () -> claimItems(player, claim, callback, correlation));
        });
    }

    private void claimMoney(Player player, ClaimRecord claim, ResultCallback<ClaimRecord> callback, String correlation) {
        scheduler.runGlobal(() -> {
            var lock = locks.forId(player.getUniqueId()); lock.lock();
            try {
                if (!economy.available()) { release(player, claim, callback, correlation, "No economy provider is available"); return; }
                EconomyBridge.Result result = economy.deposit(player, claim.moneyMinor(), config.get().decimalPlaces());
                if (!result.success()) { release(player, claim, callback, correlation, result.reason()); return; }
                storage.completeClaim(claim.id()).whenComplete((ignored, finishError) -> {
                    if (finishError != null) {
                        storage.ambiguousClaim(claim.id(), "Economy reported success, but claim finalization failed");
                        fail(player, callback, finishError, correlation);
                    } else scheduler.runEntity(player, () -> { Bukkit.getPluginManager().callEvent(new ContractClaimedEvent(claim.id(),player.getUniqueId(),claim.type(),false)); callback.success(claim); });
                });
            } finally { lock.unlock(); }
        });
    }

    private void claimItems(Player player, ClaimRecord claim, ResultCallback<ClaimRecord> callback, String correlation) {
        List<ItemStack> items;
        try {
            if (claim.type() == ClaimType.DELIVERED_ITEMS || claim.type() == ClaimType.ITEM_BUNDLE_RETURN
                    || claim.type() == ClaimType.ITEM_REWARD || claim.type() == ClaimType.ITEM_RETURN) {
                items = decodeBundleOrLegacyItem(claim.itemPayload());
            } else items = List.of(serializer.deserialize(claim.itemPayload()));
        } catch (RuntimeException ex) {
            storage.ambiguousClaim(claim.id(), "Stored item could not be deserialized: " + ex.getMessage());
            callback.failure(ex, correlation); return;
        }
        if (!canFit(player, items)) { release(player, claim, callback, correlation, "Your inventory does not have enough room"); return; }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(items.toArray(ItemStack[]::new));
        if (!leftovers.isEmpty()) {
            storage.ambiguousClaim(claim.id(), "Inventory changed during item grant; administrative review required");
            callback.failure(new DomainException(DomainException.Kind.AMBIGUOUS, "Inventory changed while granting items"), correlation); return;
        }
        storage.completeClaim(claim.id()).whenComplete((ignored, error) -> {
            if (error != null) {
                storage.ambiguousClaim(claim.id(), "Items were granted, but claim finalization failed");
                fail(player, callback, error, correlation);
            } else scheduler.runEntity(player, () -> { Bukkit.getPluginManager().callEvent(new ContractClaimedEvent(claim.id(),player.getUniqueId(),claim.type(),false)); callback.success(claim); });
        });
    }

    private List<ItemStack> decodeBundleOrLegacyItem(byte[] payload) {
        try {
            return ItemBundleCodec.decode(payload, config.get().maxSerializedItemBytes() * 8).stream().map(serializer::deserialize).toList();
        } catch (DomainException invalidBundle) {
            return List.of(serializer.deserialize(payload));
        }
    }

    private void release(Player player, ClaimRecord claim, ResultCallback<ClaimRecord> callback, String correlation, String reason) {
        storage.releaseClaim(claim.id(), reason).whenComplete((ignored, error) -> scheduler.runEntity(player, () -> callback.failure(
                error == null ? new DomainException(DomainException.Kind.PERMANENT, reason) : unwrap(error), correlation)));
    }
    private void fail(Player player, ResultCallback<?> callback, Throwable error, String correlation) {
        Throwable actual = unwrap(error); logger.log(Level.WARNING, "Claim failed [correlation=" + correlation + "]", actual);
        scheduler.runEntity(player, () -> callback.failure(actual, correlation));
    }
    private static Throwable unwrap(Throwable error) { while (error instanceof CompletionException && error.getCause() != null) error = error.getCause(); return error; }
    private static boolean canFit(Player player, List<ItemStack> incoming) {
        ItemStack[] slots = player.getInventory().getStorageContents();
        List<ItemStack> simulated = new ArrayList<>(slots.length);
        for (ItemStack slot : slots) simulated.add(slot == null ? null : slot.clone());
        for (ItemStack source : incoming) {
            int remaining = source.getAmount();
            for (ItemStack slot : simulated) if (slot != null && slot.isSimilar(source) && slot.getAmount() < slot.getMaxStackSize()) {
                int add = Math.min(remaining, slot.getMaxStackSize() - slot.getAmount()); slot.setAmount(slot.getAmount() + add); remaining -= add;
                if (remaining == 0) break;
            }
            while (remaining > 0) {
                int empty = simulated.indexOf(null); if (empty < 0) return false;
                ItemStack part = source.clone(); int add = Math.min(remaining, part.getMaxStackSize()); part.setAmount(add); simulated.set(empty, part); remaining -= add;
            }
        }
        return true;
    }
}
