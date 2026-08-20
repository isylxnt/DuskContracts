package dev.isylxnt.duskcontracts.application;

import dev.isylxnt.duskcontracts.domain.*;
import org.bukkit.inventory.ItemStack;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public record ContractDraft(UUID sessionId, ContractType contractType, ItemStack requestedItem, long amount, MatchMode matchMode,
        FulfillmentMode fulfillmentMode, RewardType rewardType, long rewardMinor, List<ItemStack> rewardItems,
        Duration duration, UUID targetId) {
    public ContractDraft {
        if (contractType == null) contractType = ContractType.DELIVERY;
        requestedItem = requestedItem == null ? null : requestedItem.clone();
        rewardItems = rewardItems == null ? List.of() : rewardItems.stream().map(ItemStack::clone).toList();
    }
    @Override public ItemStack requestedItem() { return requestedItem == null ? null : requestedItem.clone(); }
    @Override public List<ItemStack> rewardItems() { return rewardItems.stream().map(ItemStack::clone).toList(); }
}
