package dev.isylxnt.duskcontracts.inventory;

import dev.isylxnt.duskcontracts.application.ContractApplicationService;
import dev.isylxnt.duskcontracts.application.ContractDraft;
import dev.isylxnt.duskcontracts.application.ResultCallback;
import dev.isylxnt.duskcontracts.config.ConfigManager;
import dev.isylxnt.duskcontracts.config.ItemRules;
import dev.isylxnt.duskcontracts.domain.Contract;
import dev.isylxnt.duskcontracts.domain.ContractType;
import dev.isylxnt.duskcontracts.domain.DomainException;
import dev.isylxnt.duskcontracts.domain.FulfillmentMode;
import dev.isylxnt.duskcontracts.domain.MatchMode;
import dev.isylxnt.duskcontracts.domain.Money;
import dev.isylxnt.duskcontracts.domain.Parsers;
import dev.isylxnt.duskcontracts.domain.RewardType;
import dev.isylxnt.duskcontracts.localization.Messages;
import dev.isylxnt.duskcontracts.platform.PlatformScheduler;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"deprecation", "removal"})
public final class WizardService implements Listener {
    private enum InputMode { NONE, MATERIAL, AMOUNT, REWARD, TARGET }

    private static final List<Integer> DEFAULT_DURATION_SLOTS = List.of(10, 11, 12, 14, 15, 16);
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> starting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastCreation = new ConcurrentHashMap<>();
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final Messages messages;
    private final PlatformScheduler scheduler;
    private final ContractApplicationService contracts;
    private final ItemRules rules;
    private volatile YamlConfiguration menus;

    public WizardService(JavaPlugin plugin, ConfigManager config, Messages messages, PlatformScheduler scheduler,
            ContractApplicationService contracts, ItemRules rules) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.scheduler = scheduler;
        this.contracts = contracts;
        this.rules = rules;
        reload();
    }

    public synchronized void reload() {
        YamlConfiguration candidate = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "menus.yml"));
        if (candidate.getInt("schema-version", -1) != 1)
            throw new IllegalArgumentException("menus.yml: schema-version must be the integer 1");
        for (String path : List.of("creation-type", "creation", "creation-assassination", "creation-time", "creation-reward", "creation-reward-items", "creation-confirm", "creation-assassination-confirm")) {
            int size = candidate.getInt(path + ".size", 27);
            if (size < 18 || size > 54 || size % 9 != 0)
                throw new IllegalArgumentException("menus.yml: " + path + ".size must be a multiple of 9 from 18 through 54; received " + size);
        }
        menus = candidate;
    }

    public void shutdownPlayer(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        Inventory top = player.getOpenInventory().getTopInventory();
        boolean openRewardDeposit = top.getHolder() instanceof WizardMenuHolder holder
                && holder.type() == WizardMenuHolder.Type.REWARD_ITEMS;
        if (openRewardDeposit) {
            returnDeposited(player, top, top.getSize() - 9);
            if (session != null) session.rewardItems = List.of();
        }
        if (session != null) returnHeldReward(player, session);
        if (top.getHolder() instanceof WizardMenuHolder) player.closeInventory();
    }

    public void finishShutdown() {
        sessions.clear();
        starting.clear();
    }

    public void start(Player player) {
        if (!player.hasPermission("duskcontracts.create")) {
            messages.send(player, "error.no-permission");
            return;
        }
        Session current = sessions.get(player.getUniqueId());
        if (current != null && !current.processing) {
            current.input = InputMode.NONE;
            openTypeEditor(player, current);
            return;
        }
        long now = System.nanoTime();
        Long previous = lastCreation.get(player.getUniqueId());
        if (previous != null && !player.hasPermission("duskcontracts.bypass.cooldown")
                && now - previous < config.get().creationCooldown().toNanos()) {
            messages.send(player, "wizard.cooldown");
            return;
        }
        if (!starting.add(player.getUniqueId())) return;
        contracts.storage().playerStats(player.getUniqueId()).whenComplete((stats, error) -> scheduler.runEntity(player, () -> {
            starting.remove(player.getUniqueId());
            if (error != null) {
                messages.send(player, "error.generic", Map.of("correlation_id", shortCorrelation()));
                return;
            }
            if (stats.active() >= config.get().maxActivePerPlayer() && !player.hasPermission("duskcontracts.bypass.limits")) {
                messages.send(player, "wizard.limit", Map.of("limit", config.get().maxActivePerPlayer()));
                return;
            }
            Session session = new Session(UUID.randomUUID(), config.get().defaultMatchMode(),
                    defaultFulfillment(), defaultDuration());
            sessions.put(player.getUniqueId(), session);
            openTypeSelector(player, session);
            scheduler.runEntityLater(player, config.get().sessionTimeout(), () -> expire(player, session));
        }));
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.input == InputMode.NONE || session.processing) return;
        event.setCancelled(true);
        String input = event.getMessage().trim();
        scheduler.runEntity(event.getPlayer(), () -> acceptChat(event.getPlayer(), session, input));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory().getHolder() instanceof WizardMenuHolder holder)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.id.equals(holder.sessionId()) || session.processing) { event.setCancelled(true); return; }
        if (holder.type() == WizardMenuHolder.Type.REWARD_ITEMS) {
            rewardItemsClick(player, session, event);
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) playSound(player, "sounds.select");
        int slot = event.getRawSlot();
        switch (holder.type()) {
            case TYPE -> typeClick(player, session, slot);
            case EDITOR -> editorClick(player, session, slot, event.isRightClick());
            case ASSASSINATION -> assassinationClick(player, session, slot);
            case TIME -> timeClick(player, session, slot);
            case REWARD -> rewardClick(player, session, slot);
            case CONFIRM -> confirmClick(player, session, slot);
            case ASSASSINATION_CONFIRM -> assassinationConfirmClick(player, session, slot);
            case REWARD_ITEMS -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof WizardMenuHolder holder)) return;
        if (holder.type() == WizardMenuHolder.Type.REWARD_ITEMS) {
            int depositSlots = event.getView().getTopInventory().getSize() - 9;
            event.setCancelled(event.getRawSlots().stream().anyMatch(slot -> slot >= depositSlots));
        } else event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !(event.getInventory().getHolder() instanceof WizardMenuHolder holder)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.id.equals(holder.sessionId())) {
            if (holder.type() == WizardMenuHolder.Type.REWARD_ITEMS) returnDeposited(player, event.getInventory(), event.getInventory().getSize() - 9);
            return;
        }
        if (holder.type() == WizardMenuHolder.Type.REWARD_ITEMS && !session.switching) {
            returnDeposited(player, event.getInventory(), event.getInventory().getSize() - 9);
            session.rewardItems = List.of();
        }
        if (session.switching
                || session.input != InputMode.NONE || session.processing) return;
        if (sessions.remove(player.getUniqueId(), session)) { returnHeldReward(player, session); messages.send(player, "wizard.cancelled"); }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Inventory top = event.getPlayer().getOpenInventory().getTopInventory();
        boolean returnedOpenDeposit = top.getHolder() instanceof WizardMenuHolder holder && holder.type() == WizardMenuHolder.Type.REWARD_ITEMS;
        if (returnedOpenDeposit)
            returnDeposited(event.getPlayer(), top, top.getSize() - 9);
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null && !returnedOpenDeposit) returnHeldReward(event.getPlayer(), session);
        starting.remove(event.getPlayer().getUniqueId());
    }

    private void typeClick(Player player, Session session, int slot) {
        if (slot == menuInt("creation-type.assassination.slot", 12)) {
            if (!player.hasPermission("duskcontracts.assassination.create")) { messages.send(player, "error.no-permission"); return; }
            session.contractType = ContractType.ASSASSINATION;
            session.request = new ItemStack(Material.PLAYER_HEAD);
            session.amount = 1;
            session.match = MatchMode.EXACT;
            session.fulfillment = FulfillmentMode.COMPLETE;
            session.target = null;
            session.targetName = null;
            openAssassinationEditor(player, session);
        } else if (slot == menuInt("creation-type.delivery.slot", 14)) {
            session.contractType = ContractType.DELIVERY;
            session.request = new ItemStack(Material.GOLD_INGOT);
            session.amount = 1;
            session.match = config.get().defaultMatchMode();
            session.fulfillment = defaultFulfillment();
            session.target = null;
            session.targetName = null;
            openEditor(player, session);
        } else if (slot == menuInt("creation-type.cancel.slot", 18)) cancel(player, session);
    }

    private void assassinationClick(Player player, Session session, int slot) {
        if (slot == menuInt("creation-assassination.target.slot", 11)) {
            beginInput(player, session, InputMode.TARGET, "wizard.input.assassination-target");
        } else if (slot == menuInt("creation-assassination.duration.slot", 13)) {
            session.durationBeforeMenu = session.duration;
            openTime(player, session);
        } else if (slot == menuInt("creation-assassination.reward.slot", 15)) {
            openReward(player, session);
        } else if (slot == menuInt("creation-assassination.cancel.slot", 18)) {
            cancel(player, session);
        } else if (slot == menuInt("creation-assassination.continue.slot", 26)) {
            try { validate(player, session); openAssassinationConfirmation(player, session); }
            catch (RuntimeException error) { messages.send(player, "wizard.invalid", Map.of("reason", reason(error))); }
        }
    }

    private void editorClick(Player player, Session session, int slot, boolean rightClick) {
        if (slot == menuInt("creation.material.slot", 10)) {
            beginInput(player, session, InputMode.MATERIAL, "wizard.input.material");
        } else if (slot == menuInt("creation.amount.slot", 11)) {
            beginInput(player, session, InputMode.AMOUNT, "wizard.input.amount");
        } else if (slot == menuInt("creation.matching.slot", 13)) {
            cycleMatching(session, rightClick ? -1 : 1);
            openEditor(player, session);
        } else if (slot == menuInt("creation.duration.slot", 14)) {
            session.durationBeforeMenu = session.duration;
            openTime(player, session);
        } else if (slot == menuInt("creation.reward.slot", 15)) {
            openReward(player, session);
        } else if (slot == menuInt("creation.visibility.slot", 16)) {
            if (rightClick) {
                if (!config.get().allowPrivateContracts() || !player.hasPermission("duskcontracts.private.create"))
                    messages.send(player, "wizard.invalid", Map.of("reason", "Directed contracts are disabled"));
                else beginInput(player, session, InputMode.TARGET, "wizard.input.target");
            } else {
                session.target = null;
                session.targetName = null;
                openEditor(player, session);
            }
        } else if (slot == menuInt("creation.cancel.slot", 18)) {
            cancel(player, session);
        } else if (slot == menuInt("creation.continue.slot", 26)) {
            try {
                validate(player, session);
                openConfirmation(player, session);
            } catch (RuntimeException error) {
                messages.send(player, "wizard.invalid", Map.of("reason", reason(error)));
            }
        }
    }

    private void rewardClick(Player player, Session session, int slot) {
        if (slot == menuInt("creation-reward.money.slot", 11)) {
            if (!config.get().moneyEnabled() || !contracts.economy().available()) {
                playSound(player, "sounds.error");
                messages.send(player, "error.money-unavailable");
            } else beginInput(player, session, InputMode.REWARD, "wizard.input.reward");
        } else if (slot == menuInt("creation-reward.items.slot", 15)) {
            openRewardItems(player, session);
        } else if (slot == menuInt("creation-reward.back.slot", 18)
                || slot == menuInt("creation-reward.accept.slot", 26)) {
            openTypeEditor(player, session);
        }
    }

    private void rewardItemsClick(Player player, Session session, InventoryClickEvent event) {
        event.setCancelled(true);
        Inventory top = event.getView().getTopInventory();
        int depositSlots = top.getSize() - 9;
        int raw = event.getRawSlot();
        if (event.getClickedInventory() == top && raw >= 0 && raw < depositSlots) {
            if (event.isShiftClick()) moveDepositToPlayer(player, event);
            else if (safeDepositAction(event)) event.setCancelled(false);
            return;
        }
        if (event.getClickedInventory() == event.getView().getBottomInventory() && event.isShiftClick()) {
            movePlayerToDeposit(event, top, depositSlots);
            return;
        }
        if (event.getClickedInventory() == event.getView().getBottomInventory() && safeDepositAction(event)) {
            event.setCancelled(false);
            return;
        }
        if (event.getClickedInventory() != top) return;
        if (raw == menuInt("creation-reward-items.back.slot", 45)) {
            returnDeposited(player, top, depositSlots);
            session.rewardItems = List.of();
            playSound(player, "sounds.back");
            openReward(player, session);
        } else if (raw == menuInt("creation-reward-items.accept.slot", 53)) {
            selectDepositedReward(player, session, top, depositSlots);
        }
    }

    private static boolean safeDepositAction(InventoryClickEvent event) {
        return event.getClick() != ClickType.DOUBLE_CLICK && event.getClick() != ClickType.NUMBER_KEY
                && event.getClick() != ClickType.SWAP_OFFHAND && event.getAction() != InventoryAction.COLLECT_TO_CURSOR;
    }

    private static void movePlayerToDeposit(InventoryClickEvent event, Inventory top, int limit) {
        ItemStack source = event.getCurrentItem();
        if (source == null || source.getType().isAir()) return;
        int remaining = source.getAmount();
        for (int slot = 0; slot < limit && remaining > 0; slot++) {
            ItemStack present = top.getItem(slot);
            if (present == null || !present.isSimilar(source) || present.getAmount() >= present.getMaxStackSize()) continue;
            int moved = Math.min(remaining, present.getMaxStackSize() - present.getAmount());
            present.setAmount(present.getAmount() + moved); remaining -= moved;
        }
        for (int slot = 0; slot < limit && remaining > 0; slot++) {
            ItemStack present = top.getItem(slot); if (present != null && !present.getType().isAir()) continue;
            int moved = Math.min(remaining, source.getMaxStackSize()); ItemStack placed = source.clone(); placed.setAmount(moved);
            top.setItem(slot, placed); remaining -= moved;
        }
        if (remaining == source.getAmount()) return;
        if (remaining == 0) event.setCurrentItem(null); else { ItemStack leftover = source.clone(); leftover.setAmount(remaining); event.setCurrentItem(leftover); }
    }

    private static void moveDepositToPlayer(Player player, InventoryClickEvent event) {
        ItemStack source = event.getCurrentItem(); if (source == null || source.getType().isAir()) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(source.clone());
        event.setCurrentItem(leftovers.isEmpty() ? null : leftovers.values().iterator().next());
    }

    private void selectDepositedReward(Player player, Session session, Inventory inventory, int limit) {
        List<ItemStack> selected = deposited(inventory, limit);
        if (selected.isEmpty()) { playSound(player, "sounds.error"); messages.send(player, "wizard.reward-items-empty"); return; }
        try { for (ItemStack item : selected) rules.validate(item, ItemRules.Context.REWARD); }
        catch (RuntimeException error) { playSound(player, "sounds.error"); messages.send(player, "wizard.invalid", Map.of("reason", reason(error))); return; }
        clearDeposited(inventory, limit);
        session.rewardType = RewardType.ITEM; session.rewardItems = selected; session.rewardMinor = 0;
        session.fulfillment = FulfillmentMode.COMPLETE;
        playSound(player, "sounds.success"); messages.send(player, "wizard.reward-items-selected",
                Map.of("summary", itemRewardSummary(selected), "stacks", selected.size()));
        openTypeEditor(player, session);
    }

    private void timeClick(Player player, Session session, int slot) {
        Duration choice = session.durationSlots.get(slot);
        if (choice != null) {
            session.duration = choice;
            openTime(player, session);
        } else if (slot == menuInt("creation-time.cancel.slot", 18)) {
            session.duration = session.durationBeforeMenu;
            openTypeEditor(player, session);
        } else if (slot == menuInt("creation-time.accept.slot", 26)) {
            openTypeEditor(player, session);
        }
    }

    private void confirmClick(Player player, Session session, int slot) {
        if (slot == menuInt("creation-confirm.back.slot", 16)) {
            openEditor(player, session);
        } else if (slot == menuInt("creation-confirm.accept.slot", 15)) {
            createContract(player, session);
        }
    }

    private void assassinationConfirmClick(Player player, Session session, int slot) {
        if (slot == menuInt("creation-assassination-confirm.back.slot", 15)) openAssassinationEditor(player, session);
        else if (slot == menuInt("creation-assassination-confirm.accept.slot", 16)) createContract(player, session);
    }

    private void acceptChat(Player player, Session session, String input) {
        if (sessions.get(player.getUniqueId()) != session || session.processing) return;
        if (input.equalsIgnoreCase("cancel")) {
            session.input = InputMode.NONE;
            openTypeEditor(player, session);
            return;
        }
        try {
            switch (session.input) {
                case MATERIAL -> selectMaterial(session, input);
                case AMOUNT -> session.amount = Parsers.positiveAmount(input, config.get().maxRequestAmount());
                case REWARD -> selectMoneyReward(player, session, input);
                case TARGET -> selectTarget(player, session, input);
                case NONE -> { return; }
            }
            session.input = InputMode.NONE;
            openTypeEditor(player, session);
            playSound(player, "sounds.success");
            messages.send(player, "wizard.updated");
        } catch (RuntimeException error) {
            playSound(player, "sounds.error");
            messages.send(player, "wizard.invalid", Map.of("reason", reason(error)));
        }
    }

    private void selectMaterial(Session session, String input) {
        String normalized = input.trim().replace(' ', '_');
        Material material = Material.matchMaterial(normalized);
        if (material == null) material = Material.matchMaterial(normalized.toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem() || material.isAir()) throw invalid("Unknown or unusable material");
        ItemStack selected = new ItemStack(material);
        rules.validate(selected, ItemRules.Context.REQUESTED);
        session.request = selected;
    }

    private void selectMoneyReward(Player player, Session session, String input) {
        if (!config.get().moneyEnabled() || !contracts.economy().available()) throw invalid("Money rewards are currently unavailable");
        Money money = Money.parse(input.replace(',', '.'), config.get().decimalPlaces());
        if (money.minorUnits() < config.get().minimumRewardMinor() || money.minorUnits() > config.get().maximumRewardMinor())
            throw invalid("Reward is outside the configured limits");
        session.rewardType = RewardType.MONEY;
        session.rewardMinor = money.minorUnits();
        returnHeldReward(player, session);
        session.fulfillment = session.contractType == ContractType.ASSASSINATION ? FulfillmentMode.COMPLETE : defaultFulfillment();
    }

    private void selectTarget(Player player, Session session, String input) {
        if (session.contractType == ContractType.ASSASSINATION) {
            Player online = Bukkit.getPlayerExact(input);
            OfflinePlayer target = online == null ? Bukkit.getOfflinePlayer(input) : online;
            if (!target.isOnline() && !target.hasPlayedBefore()) throw invalid("That player has never joined this server");
            session.target = target.getUniqueId();
            session.targetName = target.getName() == null ? input : target.getName();
            return;
        }
        if (!config.get().allowPrivateContracts() || !player.hasPermission("duskcontracts.private.create"))
            throw invalid("Directed contracts are disabled");
        Player target = Bukkit.getPlayerExact(input);
        if (target == null) throw invalid("That exact player name is not online");
        session.target = target.getUniqueId();
        session.targetName = target.getName();
    }

    private void cycleMatching(Session session, int direction) {
        List<MatchMode> modes = Arrays.stream(MatchMode.values())
                .filter(config.get().enabledMatchModes()::contains).toList();
        if (modes.isEmpty()) return;
        int current = Math.max(0, modes.indexOf(session.match));
        session.match = modes.get(Math.floorMod(current + direction, modes.size()));
    }

    private void createContract(Player player, Session session) {
        try {
            validate(player, session);
        } catch (RuntimeException error) {
            playSound(player, "sounds.error");
            messages.send(player, "wizard.invalid", Map.of("reason", reason(error)));
            openTypeEditor(player, session);
            return;
        }
        session.processing = true;
        Inventory current = player.getOpenInventory().getTopInventory();
        if (current.getHolder() instanceof WizardMenuHolder holder && holder.sessionId().equals(session.id)) {
            String path = session.contractType == ContractType.ASSASSINATION
                    ? "creation-assassination-confirm.processing" : "creation-confirm.processing";
            current.setItem(menuSlot(path, session.contractType == ContractType.ASSASSINATION ? 16 : 15, current), item(path, Map.of()));
        }
        ContractDraft draft = new ContractDraft(session.id, session.contractType,
                session.contractType == ContractType.ASSASSINATION ? null : session.request, session.amount, session.match,
                session.fulfillment, session.rewardType, session.rewardMinor, session.rewardItems, session.duration, session.target);
        contracts.create(player, draft, new ResultCallback<>() {
            @Override public void success(Contract value) {
                sessions.remove(player.getUniqueId(), session);
                session.rewardItems = List.of();
                lastCreation.put(player.getUniqueId(), System.nanoTime());
                player.closeInventory();
                playSound(player, "sounds.success");
                messages.send(player, "contract.created", Map.of("contract_id", value.shortId()));
            }
            @Override public void failure(Throwable error, String correlation) {
                sessions.remove(player.getUniqueId(), session);
                if (!(error instanceof DomainException domain && domain.kind() == DomainException.Kind.AMBIGUOUS)) returnHeldReward(player, session);
                else session.rewardItems = List.of();
                player.closeInventory();
                playSound(player, "sounds.error");
                messages.send(player, "wizard.create-failed", Map.of("reason", reason(error), "correlation_id", correlation));
            }
        });
    }

    private void validate(Player player, Session session) {
        if (session.contractType == ContractType.ASSASSINATION) {
            if (session.target == null || session.targetName == null) throw invalid("Select the player to assassinate");
            session.amount = 1; session.match = MatchMode.EXACT; session.fulfillment = FulfillmentMode.COMPLETE;
        } else rules.validate(session.request, ItemRules.Context.REQUESTED);
        if (session.amount < 1 || session.amount > config.get().maxRequestAmount()) throw invalid("Invalid requested amount");
        if (session.contractType == ContractType.DELIVERY && !config.get().enabledMatchModes().contains(session.match)) throw invalid("That matching mode is disabled");
        if (!config.get().allowedDurations().contains(session.duration)) throw invalid("That duration is not allowed");
        if (session.rewardType == null) throw invalid("Select a reward first");
        if (session.rewardType == RewardType.MONEY) {
            if (!config.get().moneyEnabled() || !contracts.economy().available()) throw invalid("Money rewards are currently unavailable");
            if (session.rewardMinor < config.get().minimumRewardMinor() || session.rewardMinor > config.get().maximumRewardMinor())
                throw invalid("Reward is outside the configured limits");
        } else {
            if (session.rewardItems.isEmpty()) throw invalid("Select at least one reward item");
            for (ItemStack reward : session.rewardItems) rules.validate(reward, ItemRules.Context.REWARD);
        }
        if (session.contractType == ContractType.DELIVERY && session.target != null && (!config.get().allowPrivateContracts()
                || !player.hasPermission("duskcontracts.private.create"))) throw invalid("Directed contracts are disabled");
    }

    private void openTypeSelector(Player player, Session session) {
        WizardMenuHolder holder = new WizardMenuHolder(session.id, WizardMenuHolder.Type.TYPE);
        Inventory inventory = create(holder, menuSize("creation-type", 27),
                menuString("creation-type.title", "<dark_gray>Contract type"), Map.of());
        place(inventory, "creation-type.assassination", 12, Map.of());
        place(inventory, "creation-type.delivery", 14, Map.of());
        place(inventory, "creation-type.cancel", 18, Map.of());
        show(player, session, holder, inventory);
    }

    private void openTypeEditor(Player player, Session session) {
        if (session.contractType == ContractType.ASSASSINATION) openAssassinationEditor(player, session);
        else openEditor(player, session);
    }

    private void openEditor(Player player, Session session) {
        Map<String, Object> values = values(session);
        WizardMenuHolder holder = new WizardMenuHolder(session.id, WizardMenuHolder.Type.EDITOR);
        Inventory inventory = create(holder, menuSize("creation", 27), menuString("creation.title", "<dark_gray>Creation"), Map.of());
        ItemStack material = dynamic("creation.material", session.request.getType(), values);
        inventory.setItem(menuSlot("creation.material", 10, inventory), material);
        ItemStack amount = dynamic("creation.amount", session.request.getType(), values);
        amount.setAmount(displayAmount(session.amount, amount.getMaxStackSize()));
        inventory.setItem(menuSlot("creation.amount", 11, inventory), amount);
        place(inventory, "creation.matching", 13, values);
        place(inventory, "creation.duration", 14, values);
        place(inventory, "creation.reward", 15, values);
        place(inventory, "creation.visibility", 16, values);
        place(inventory, "creation.cancel", 18, values);
        place(inventory, "creation.continue", 26, values);
        show(player, session, holder, inventory);
    }

    private void openAssassinationEditor(Player player, Session session) {
        Map<String, Object> values = values(session);
        WizardMenuHolder holder = new WizardMenuHolder(session.id, WizardMenuHolder.Type.ASSASSINATION);
        Inventory inventory = create(holder, menuSize("creation-assassination", 27),
                menuString("creation-assassination.title", "<dark_gray>Assassination contract"), Map.of());
        inventory.setItem(menuSlot("creation-assassination.target", 11, inventory),
                playerHead("creation-assassination.target", session.target, values));
        place(inventory, "creation-assassination.duration", 13, values);
        place(inventory, "creation-assassination.reward", 15, values);
        place(inventory, "creation-assassination.cancel", 18, values);
        place(inventory, "creation-assassination.continue", 26, values);
        show(player, session, holder, inventory);
    }

    private void openTime(Player player, Session session) {
        WizardMenuHolder holder = new WizardMenuHolder(session.id, WizardMenuHolder.Type.TIME);
        Inventory inventory = create(holder, menuSize("creation-time", 27),
                menuString("creation-time.title", "<dark_gray>Contract time"), Map.of());
        session.durationSlots.clear();
        List<Integer> slots = menuIntegerList("creation-time.option-slots", DEFAULT_DURATION_SLOTS);
        List<Duration> durations = config.get().allowedDurations();
        for (int index = 0; index < Math.min(slots.size(), durations.size()); index++) {
            int slot = slots.get(index);
            if (slot < 0 || slot >= inventory.getSize()) continue;
            Duration duration = durations.get(index);
            boolean selected = duration.equals(session.duration);
            Map<String, Object> values = Map.of("duration", human(duration), "type", isWholeDays(duration) ? "DAYS" : "HOURS");
            ItemStack icon = named(Material.CLOCK,
                    menuString(selected ? "creation-time.selected-name" : "creation-time.option-name",
                            selected ? "<green>{duration}" : "<yellow>{duration}"),
                    menuStringList("creation-time.option-lore"), values);
            icon.setAmount(durationAmount(duration));
            if (isWholeDays(duration)) addGlow(icon);
            inventory.setItem(slot, icon);
            session.durationSlots.put(slot, duration);
        }
        place(inventory, "creation-time.cancel", 18, Map.of());
        place(inventory, "creation-time.accept", 26, Map.of("duration", human(session.duration)));
        show(player, session, holder, inventory);
    }

    private void openReward(Player player, Session session) {
        WizardMenuHolder holder = new WizardMenuHolder(session.id, WizardMenuHolder.Type.REWARD);
        Inventory inventory = create(holder, menuSize("creation-reward", 27),
                menuString("creation-reward.title", "<dark_gray>Reward type"), Map.of("reward", reward(session)));
        place(inventory, "creation-reward.money", 11, Map.of("reward", reward(session)));
        place(inventory, "creation-reward.items", 15, Map.of("stacks", session.rewardItems.size()));
        place(inventory, "creation-reward.back", 18, Map.of());
        place(inventory, "creation-reward.accept", 26, Map.of("reward", reward(session)));
        show(player, session, holder, inventory);
    }

    private void openRewardItems(Player player, Session session) {
        WizardMenuHolder holder = new WizardMenuHolder(session.id, WizardMenuHolder.Type.REWARD_ITEMS);
        Inventory inventory = create(holder, menuSize("creation-reward-items", 54),
                menuString("creation-reward-items.title", "<dark_gray>Item reward"), Map.of());
        int depositSlots = inventory.getSize() - 9; int slot = 0;
        for (ItemStack reward : session.rewardItems) { if (slot >= depositSlots) break; inventory.setItem(slot++, reward.clone()); }
        session.rewardItems = List.of();
        place(inventory, "creation-reward-items.back", 45, Map.of());
        place(inventory, "creation-reward-items.accept", 53, Map.of());
        show(player, session, holder, inventory);
    }

    private void openConfirmation(Player player, Session session) {
        Map<String, Object> values = values(session);
        WizardMenuHolder holder = new WizardMenuHolder(session.id, WizardMenuHolder.Type.CONFIRM);
        Inventory inventory = create(holder, menuSize("creation-confirm", 27),
                menuString("creation-confirm.title", "<dark_gray>Confirmation"), Map.of());
        inventory.setItem(menuSlot("creation-confirm.material", 10, inventory),
                dynamic("creation-confirm.material", session.request.getType(), values));
        ItemStack amount = dynamic("creation-confirm.amount", session.request.getType(), values);
        amount.setAmount(displayAmount(session.amount, amount.getMaxStackSize()));
        inventory.setItem(menuSlot("creation-confirm.amount", 11, inventory), amount);
        place(inventory, "creation-confirm.settings", 13, values);
        place(inventory, "creation-confirm.accept", 15, values);
        place(inventory, "creation-confirm.back", 16, values);
        show(player, session, holder, inventory);
    }

    private void openAssassinationConfirmation(Player player, Session session) {
        Map<String, Object> values = values(session);
        WizardMenuHolder holder = new WizardMenuHolder(session.id, WizardMenuHolder.Type.ASSASSINATION_CONFIRM);
        Inventory inventory = create(holder, menuSize("creation-assassination-confirm", 27),
                menuString("creation-assassination-confirm.title", "<dark_gray>Assassination confirmation"), Map.of());
        inventory.setItem(menuSlot("creation-assassination-confirm.target", 10, inventory),
                playerHead("creation-assassination-confirm.target", session.target, values));
        place(inventory, "creation-assassination-confirm.reward", 12, values);
        place(inventory, "creation-assassination-confirm.duration", 13, values);
        place(inventory, "creation-assassination-confirm.back", 15, values);
        place(inventory, "creation-assassination-confirm.accept", 16, values);
        show(player, session, holder, inventory);
    }

    private void beginInput(Player player, Session session, InputMode mode, String message) {
        session.input = mode;
        player.closeInventory();
        messages.send(player, message);
    }

    private void show(Player player, Session session, WizardMenuHolder holder, Inventory inventory) {
        session.switching = true;
        try {
            player.openInventory(inventory);
            playSound(player, "sounds.open");
        } finally {
            session.switching = false;
        }
    }

    private void cancel(Player player, Session session) {
        sessions.remove(player.getUniqueId(), session);
        returnHeldReward(player, session);
        player.closeInventory();
        playSound(player, "sounds.back");
        messages.send(player, "wizard.cancelled");
    }

    private void expire(Player player, Session session) {
        if (!sessions.remove(player.getUniqueId(), session)) return;
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof WizardMenuHolder holder && holder.sessionId().equals(session.id)) {
            if (holder.type() == WizardMenuHolder.Type.REWARD_ITEMS) { returnDeposited(player, top, top.getSize() - 9); session.rewardItems = List.of(); }
            player.closeInventory();
        }
        returnHeldReward(player, session);
        messages.send(player, "wizard.expired");
    }

    private static List<ItemStack> deposited(Inventory inventory, int limit) {
        List<ItemStack> selected = new ArrayList<>();
        for (int slot = 0; slot < limit; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) selected.add(item.clone());
        }
        return List.copyOf(selected);
    }

    private static void returnDeposited(Player player, Inventory inventory, int limit) {
        List<ItemStack> items = deposited(inventory, limit);
        clearDeposited(inventory, limit);
        if (items.isEmpty()) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(items.toArray(ItemStack[]::new));
        for (ItemStack leftover : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
    }

    private static void clearDeposited(Inventory inventory, int limit) {
        for (int slot = 0; slot < limit; slot++) inventory.setItem(slot, null);
    }

    private static void returnHeldReward(Player player, Session session) {
        if (session.rewardItems.isEmpty()) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(session.rewardItems.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        session.rewardItems = List.of();
        for (ItemStack leftover : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
    }

    private Inventory create(WizardMenuHolder holder, int size, String title, Map<String, ?> values) {
        Inventory inventory = Bukkit.createInventory(holder, size, messages.parseTemplate(title, values));
        holder.inventory(inventory);
        return inventory;
    }

    private ItemStack item(String path, Map<String, ?> values) {
        ConfigurationSection section = menuSection(path);
        if (section == null) return named(Material.BARRIER, "<red>Missing " + path, List.of(), values);
        Material material = Material.matchMaterial(section.getString("material", "BARRIER"));
        return decorate(named(material == null ? Material.BARRIER : material, section.getString("name", " "),
                section.getStringList("lore"), values), path);
    }

    private ItemStack dynamic(String path, Material material, Map<String, ?> values) {
        ConfigurationSection section = menuSection(path);
        return decorate(section == null ? named(material, " ", List.of(), values)
                : named(material, section.getString("name", " "), section.getStringList("lore"), values), path);
    }

    private ItemStack playerHead(String path, UUID owner, Map<String, ?> values) {
        ItemStack item = dynamic(path, Material.PLAYER_HEAD, values);
        if (owner != null && item.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            item.setItemMeta(skull);
        }
        return item;
    }

    private ItemStack named(Material material, String name, List<String> lore, Map<String, ?> values) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseTemplate(name, values).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> messages.parseTemplate(line, values)
                .decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack decorate(ItemStack item, String path) {
        ItemMeta meta = item.getItemMeta();
        int customModelData = menuInt(path + ".custom-model-data", 0);
        if (customModelData > 0) meta.setCustomModelData(customModelData);
        if (menuBoolean(path + ".glow", false)) {
            Enchantment glow = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (glow != null) {
                meta.addEnchant(glow, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private static void addGlow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Enchantment glow = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
        if (glow != null) {
            meta.addEnchant(glow, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
    }

    private void place(Inventory inventory, String path, int fallback, Map<String, ?> values) {
        inventory.setItem(menuSlot(path, fallback, inventory), item(path, values));
    }

    private ConfigurationSection menuSection(String path) {
        ConfigurationSection localized = menus.getConfigurationSection("locales." + config.get().locale() + "." + path);
        return localized == null ? menus.getConfigurationSection(path) : localized;
    }

    private String menuString(String path, String fallback) {
        return menus.getString("locales." + config.get().locale() + "." + path, menus.getString(path, fallback));
    }

    private List<String> menuStringList(String path) {
        List<String> localized = menus.getStringList("locales." + config.get().locale() + "." + path);
        return localized.isEmpty() ? menus.getStringList(path) : localized;
    }

    private int menuInt(String path, int fallback) {
        return menus.getInt("locales." + config.get().locale() + "." + path, menus.getInt(path, fallback));
    }

    private boolean menuBoolean(String path, boolean fallback) {
        return menus.getBoolean("locales." + config.get().locale() + "." + path, menus.getBoolean(path, fallback));
    }

    private void playSound(Player player, String path) {
        String name = menus.getString(path + ".name", ""); if (name.isBlank()) return;
        try { player.playSound(player.getLocation(), Sound.valueOf(name.toUpperCase(Locale.ROOT)),
                (float) menus.getDouble(path + ".volume", 1.0), (float) menus.getDouble(path + ".pitch", 1.0)); }
        catch (IllegalArgumentException error) { plugin.getLogger().warning("Unknown menu sound at " + path + ": " + name); }
    }

    private List<Integer> menuIntegerList(String path, List<Integer> fallback) {
        List<Integer> localized = menus.getIntegerList("locales." + config.get().locale() + "." + path);
        if (!localized.isEmpty()) return localized;
        List<Integer> global = menus.getIntegerList(path);
        return global.isEmpty() ? fallback : global;
    }

    private int menuSize(String path, int fallback) {
        int value = menuInt(path + ".size", fallback);
        return value >= 18 && value <= 54 && value % 9 == 0 ? value : fallback;
    }

    private int menuSlot(String path, int fallback, Inventory inventory) {
        int configured = menuInt(path + ".slot", fallback);
        if (configured >= 0 && configured < inventory.getSize()) return configured;
        return Math.min(Math.max(0, fallback), inventory.getSize() - 1);
    }

    private Map<String, Object> values(Session session) {
        Map<String, Object> values = new HashMap<>();
        values.put("material", session.request.getType().name());
        values.put("amount", session.amount);
        values.put("matching", session.match.name());
        values.put("duration", human(session.duration));
        values.put("reward", reward(session));
        values.put("visibility", session.target == null ? "PUBLIC" : "EXACT: " + session.targetName);
        values.put("fulfillment", session.fulfillment.name());
        values.put("target", session.targetName == null ? "NOT SET" : session.targetName);
        values.put("type", session.contractType.name());
        return values;
    }

    private String reward(Session session) {
        if (session.rewardType == null) return "NOT SET";
        if (session.rewardType == RewardType.MONEY)
            return new Money(session.rewardMinor, config.get().decimalPlaces()).decimal().toPlainString();
        return itemRewardSummary(session.rewardItems);
    }

    private String itemRewardSummary(List<ItemStack> items) {
        int stacks = items.size();
        int total = items.stream().mapToInt(ItemStack::getAmount).sum();
        if (config.get().locale().toLowerCase(Locale.ROOT).startsWith("es"))
            return total + (total == 1 ? " objeto" : " objetos") + " en " + stacks + (stacks == 1 ? " pila" : " pilas");
        return total + (total == 1 ? " item" : " items") + " in " + stacks + (stacks == 1 ? " stack" : " stacks");
    }

    private FulfillmentMode defaultFulfillment() {
        return config.get().partialEnabled() ? FulfillmentMode.PROPORTIONAL : FulfillmentMode.COMPLETE;
    }

    private Duration defaultDuration() {
        return config.get().allowedDurations().contains(config.get().defaultDuration())
                ? config.get().defaultDuration() : config.get().allowedDurations().get(0);
    }

    private static int displayAmount(long amount, int maximum) {
        return (int) Math.max(1, Math.min(amount, maximum));
    }

    private static int durationAmount(Duration duration) {
        long seconds = duration.toSeconds();
        long units = isWholeDays(duration) ? seconds / 86_400 : seconds % 3_600 == 0 ? seconds / 3_600 : 1;
        return (int) Math.max(1, Math.min(64, units));
    }

    private static boolean isWholeDays(Duration duration) {
        return duration.toSeconds() >= 86_400 && duration.toSeconds() % 86_400 == 0;
    }

    private static String human(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 86_400 == 0) return seconds / 86_400 + "d";
        if (seconds % 3_600 == 0) return seconds / 3_600 + "h";
        if (seconds % 60 == 0) return seconds / 60 + "m";
        return seconds + "s";
    }

    private static DomainException invalid(String reason) {
        return new DomainException(DomainException.Kind.VALIDATION, reason);
    }

    private static String reason(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.isBlank() ? "Unknown error" : value;
    }

    private static String shortCorrelation() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static final class Session {
        private final UUID id;
        private final Map<Integer, Duration> durationSlots = new HashMap<>();
        private ContractType contractType = ContractType.DELIVERY;
        private ItemStack request = new ItemStack(Material.GOLD_INGOT);
        private long amount = 1;
        private MatchMode match;
        private FulfillmentMode fulfillment;
        private RewardType rewardType;
        private long rewardMinor;
        private List<ItemStack> rewardItems = List.of();
        private Duration duration;
        private Duration durationBeforeMenu;
        private UUID target;
        private String targetName;
        private InputMode input = InputMode.NONE;
        private boolean switching;
        private boolean processing;

        private Session(UUID id, MatchMode match, FulfillmentMode fulfillment, Duration duration) {
            this.id = id;
            this.match = match;
            this.fulfillment = fulfillment;
            this.duration = duration;
            this.durationBeforeMenu = duration;
        }
    }
}
