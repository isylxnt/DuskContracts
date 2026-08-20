package dev.isylxnt.duskcontracts.application;

import dev.isylxnt.duskcontracts.config.ConfigManager;
import dev.isylxnt.duskcontracts.config.ItemRules;
import dev.isylxnt.duskcontracts.api.ContractView;
import dev.isylxnt.duskcontracts.api.event.ContractCompletedEvent;
import dev.isylxnt.duskcontracts.api.event.ContractContributionEvent;
import dev.isylxnt.duskcontracts.api.event.ContractCreatedEvent;
import dev.isylxnt.duskcontracts.domain.*;
import dev.isylxnt.duskcontracts.economy.EconomyBridge;
import dev.isylxnt.duskcontracts.inventory.ItemBundleCodec;
import dev.isylxnt.duskcontracts.inventory.ItemMatcher;
import dev.isylxnt.duskcontracts.inventory.ItemSerializer;
import dev.isylxnt.duskcontracts.persistence.*;
import dev.isylxnt.duskcontracts.platform.PlatformScheduler;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ContractApplicationService {
    private final Storage storage; private final ConfigManager config; private final PlatformScheduler scheduler;
    private final EconomyBridge economy; private final ItemSerializer serializer; private final ItemMatcher matcher = new ItemMatcher();
    private final ItemRules rules;
    private final StripedLocks playerLocks = new StripedLocks(256); private final Logger logger;
    private final int maximumBundleBytes;

    public ContractApplicationService(Storage storage, ConfigManager config, PlatformScheduler scheduler,
            EconomyBridge economy, ItemSerializer serializer, ItemRules rules, Logger logger) {
        this.storage = storage; this.config = config; this.scheduler = scheduler; this.economy = economy;
        this.serializer = serializer; this.rules=rules; this.logger = logger; this.maximumBundleBytes = config.get().maxSerializedItemBytes() * 8;
    }

    public void create(Player player, ContractDraft draft, ResultCallback<Contract> callback) {
        String correlation = correlation(); UUID operation = UUID.randomUUID(); UUID contractId = UUID.randomUUID();
        Instant created = Instant.now();
        byte[] request;
        byte[] reward = null;
        try {
            if (draft.contractType() == ContractType.ASSASSINATION) {
                if (draft.targetId() == null) throw new DomainException(DomainException.Kind.VALIDATION, "Select a player target");
                request = null;
            } else {
                ItemStack template = Objects.requireNonNull(draft.requestedItem()); template.setAmount(1); request = serializer.serialize(template);
            }
            if (draft.rewardType() == RewardType.ITEM) {
                if (draft.rewardItems().isEmpty()) throw new DomainException(DomainException.Kind.VALIDATION, "Item reward cannot be empty");
                List<byte[]> serializedRewards = new ArrayList<>();
                for (ItemStack item : draft.rewardItems()) { rules.validate(item, ItemRules.Context.REWARD); serializedRewards.add(serializer.serialize(item)); }
                reward = ItemBundleCodec.encode(serializedRewards, maximumBundleBytes);
            }
        } catch (RuntimeException ex) { callback.failure(ex, correlation); return; }
        String material = draft.contractType() == ContractType.ASSASSINATION ? Material.PLAYER_HEAD.name() : draft.requestedItem().getType().name();
        Contract contract = new Contract(contractId, shortId(contractId), player.getUniqueId(), player.getName(), created,
                created.plus(draft.duration()), ContractStatus.OPEN, material, draft.matchMode(), draft.amount(), 0,
                draft.rewardType(), draft.rewardMinor(), request, reward, draft.targetId(), draft.fulfillmentMode(), 0);
        String evidence = "contract=" + contract.shortId() + ", reward=" + draft.rewardType() + ", minor=" + draft.rewardMinor()
                + ", itemChecksumStored=" + (reward != null);
        storage.prepareAssetOperation(operation, "create:" + player.getUniqueId() + ":" + draft.sessionId(), OperationType.CREATE,
                player.getUniqueId(), contractId, correlation, evidence,draft.rewardType()==RewardType.MONEY?draft.rewardMinor():0,reward,player.getUniqueId()).whenComplete((prepared, prepareError) -> {
            if (prepareError != null) { entityFailure(player, callback, prepareError, correlation); return; }
            Runnable fund = () -> {
                ReentrantLock lock = playerLocks.forId(player.getUniqueId()); lock.lock();
                try {
                    if (draft.rewardType() == RewardType.MONEY) {
                        if (!economy.available()) { abortBeforeFunding(player, operation, "Economy was unavailable before withdrawal", callback, new DomainException(DomainException.Kind.VALIDATION, "Economy unavailable"), correlation); return; }
                        EconomyBridge.Result result = economy.withdraw(player, draft.rewardMinor(), config.get().decimalPlaces());
                        if (!result.success()) { abortBeforeFunding(player, operation, "Economy rejected withdrawal: " + result.reason(), callback, new DomainException(DomainException.Kind.PERMANENT, result.reason()), correlation); return; }
                    }
                    commitFunded(player, contract, operation, evidence, correlation, callback);
                } finally { lock.unlock(); }
            };
            if (draft.rewardType() == RewardType.MONEY) scheduler.runGlobal(fund);
            else commitFunded(player, contract, operation, evidence, correlation, callback);
        });
    }

    private void commitFunded(Player player, Contract contract, UUID operation, String evidence, String correlation, ResultCallback<Contract> callback) {
        storage.markOperationAmbiguous(operation, evidence + ", externalAssetRemoved=true")
                .thenCompose(ignored -> storage.commitContract(contract, operation))
                .whenComplete((ignored, error) -> {
                    if (error != null) entityFailure(player, callback, new DomainException(DomainException.Kind.AMBIGUOUS,
                            "The funded reward is safely quarantined for recovery", unwrap(error)), correlation);
                    else scheduler.runEntity(player, () -> { Bukkit.getPluginManager().callEvent(new ContractCreatedEvent(view(contract), false)); callback.success(contract); });
                });
    }

    public void contribute(Player player, Contract contractSnapshot, List<ItemStack> items, ResultCallback<ContributionResult> callback) {
        String correlation = correlation(); UUID operation = UUID.randomUUID();
        boolean allowOwn = config.get().allowOwnFulfillment() || player.hasPermission("duskcontracts.fulfill.own");
        long amount = 0; List<byte[]> serialized = new ArrayList<>(); byte[] bundle;
        try {
            ItemStack expected = serializer.deserialize(Objects.requireNonNull(contractSnapshot.requestItem()));
            for (ItemStack item : items) {
                rules.validate(item,ItemRules.Context.DELIVERED);
                if (!matcher.matches(expected, item, contractSnapshot.matchMode())) throw new DomainException(DomainException.Kind.VALIDATION, "Invalid item in deposit");
                amount = Math.addExact(amount, item.getAmount()); serialized.add(serializer.serialize(item));
            }
            contractSnapshot.validateContribution(player.getUniqueId(), amount, Instant.now(), allowOwn);
            if (amount < config.get().minimumContribution() && amount != contractSnapshot.remaining())
                throw new DomainException(DomainException.Kind.VALIDATION, "Contribution is below the configured minimum");
            bundle = ItemBundleCodec.encode(serialized, maximumBundleBytes);
        } catch (RuntimeException ex) { callback.failure(ex, correlation); return; }
        long accepted = amount;
        String key = "deliver:" + player.getUniqueId() + ":" + contractSnapshot.id() + ":" + operation;
        storage.prepareAssetOperation(operation, key, OperationType.CONTRIBUTE, player.getUniqueId(), contractSnapshot.id(), correlation,
                "amount=" + amount + ", contractVersion=" + contractSnapshot.version(),0,bundle,player.getUniqueId()).whenComplete((prepared,prepareError)->{
            if(prepareError!=null){log(prepareError,correlation);scheduler.runEntity(player,()->callback.failure(unwrap(prepareError),correlation));return;}
            storage.markOperationAmbiguous(operation, "Items removed into plugin custody; amount=" + accepted)
                    .thenCompose(ignored -> storage.commitContribution(operation, player.getUniqueId(), player.getName(), accepted, bundle,
                            contractSnapshot.version(), Instant.now(), allowOwn))
                    .whenComplete((result, error) -> {
                    if (error != null) { log(error, correlation);DomainException quarantined=new DomainException(DomainException.Kind.AMBIGUOUS,"Delivery was quarantined for review",unwrap(error));scheduler.runEntity(player, () -> callback.failure(quarantined, correlation)); }
                    else scheduler.runEntity(player, () -> {
                        ContractView updated = ContractViewMapper.state(contractSnapshot,result.completed()?ContractStatus.COMPLETED:ContractStatus.OPEN,contractSnapshot.deliveredAmount()+result.acceptedAmount(),result.newVersion());
                        Bukkit.getPluginManager().callEvent(new ContractContributionEvent(updated,player.getUniqueId(),result.acceptedAmount(),result.payoutMinor(),false));
                        if(result.completed())Bukkit.getPluginManager().callEvent(new ContractCompletedEvent(updated,false));
                        callback.success(result);
                    });
                    });
        });
    }

    public CompletableFuture<Void> cancel(Player player, Contract contract, String reason) {
        UUID operation = UUID.randomUUID(); String correlation = correlation();
        return storage.prepareOperation(operation, "cancel:" + contract.id() + ":" + contract.version(), OperationType.CANCEL,
                        player.getUniqueId(), contract.id(), correlation, reason)
                .thenCompose(ignored -> storage.cancel(contract.id(), player.getUniqueId(), reason, false, operation, Instant.now()));
    }
    public Storage storage() { return storage; }
    public EconomyBridge economy() { return economy; }
    private void abortBeforeFunding(Player player,UUID operation,String evidence,ResultCallback<?> callback,Throwable original,String correlation){storage.failPreparedOperation(operation,evidence).whenComplete((ignored,error)->{if(error!=null)entityFailure(player,callback,new DomainException(DomainException.Kind.AMBIGUOUS,"Funding preparation could not be closed",unwrap(error)),correlation);else entityFailure(player,callback,original,correlation);});}
    private void entityFailure(Player player, ResultCallback<?> callback, Throwable error, String correlation) {
        log(error, correlation); scheduler.runEntity(player, () -> callback.failure(unwrap(error), correlation));
    }
    private void log(Throwable error, String correlation) { logger.log(Level.WARNING, "Operation failed [correlation=" + correlation + "]", unwrap(error)); }
    private static Throwable unwrap(Throwable error) { while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) error = error.getCause(); return error; }
    private static String correlation() { return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
    private static String shortId(UUID id) { return id.toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT); }
    private static ContractView view(Contract c){return ContractViewMapper.from(c);}
}
