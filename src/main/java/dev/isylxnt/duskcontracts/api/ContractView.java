package dev.isylxnt.duskcontracts.api;

import dev.isylxnt.duskcontracts.domain.*;
import java.time.Instant;
import java.util.UUID;

public record ContractView(UUID id, String shortId, UUID creatorId, String creatorName, Instant createdAt,
        Instant expiresAt, ContractStatus status, String material, MatchMode matchMode, long totalAmount,
        long deliveredAmount, RewardType rewardType, long rewardMinor, UUID targetId,
        FulfillmentMode fulfillmentMode, long version) {
    public long remainingAmount() { return totalAmount - deliveredAmount; }
}
