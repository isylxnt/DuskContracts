package dev.isylxnt.duskcontracts.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Contract(
        UUID id, String shortId, UUID creatorId, String creatorName, Instant createdAt, Instant expiresAt,
        ContractStatus status, String material, MatchMode matchMode, long totalAmount, long deliveredAmount,
        RewardType rewardType, long rewardMinor, byte[] requestItem, byte[] rewardItem, UUID targetId,
        FulfillmentMode fulfillmentMode, long version) {
    public Contract {
        Objects.requireNonNull(id); Objects.requireNonNull(shortId); Objects.requireNonNull(creatorId);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(expiresAt); Objects.requireNonNull(status);
        Objects.requireNonNull(material); Objects.requireNonNull(matchMode); Objects.requireNonNull(rewardType);
        Objects.requireNonNull(fulfillmentMode);
        if (shortId.isBlank() || totalAmount <= 0 || deliveredAmount < 0 || deliveredAmount > totalAmount)
            throw new DomainException(DomainException.Kind.VALIDATION, "Invalid contract values");
        if (!expiresAt.isAfter(createdAt)) throw new DomainException(DomainException.Kind.VALIDATION, "Expiration must be after creation");
        if (rewardType == RewardType.ITEM && fulfillmentMode != FulfillmentMode.COMPLETE)
            throw new DomainException(DomainException.Kind.VALIDATION, "Item rewards require complete fulfillment");
        if (rewardType == RewardType.MONEY && rewardMinor <= 0)
            throw new DomainException(DomainException.Kind.VALIDATION, "Money reward must be positive");
        if (requestItem == null && (targetId == null || totalAmount != 1 || deliveredAmount > 1))
            throw new DomainException(DomainException.Kind.VALIDATION, "Assassination contracts require one player target");
        requestItem = requestItem == null ? null : requestItem.clone();
        rewardItem = rewardItem == null ? null : rewardItem.clone();
    }

    @Override public byte[] requestItem() { return requestItem == null ? null : requestItem.clone(); }
    @Override public byte[] rewardItem() { return rewardItem == null ? null : rewardItem.clone(); }
    public long remaining() { return totalAmount - deliveredAmount; }
    public boolean directed() { return targetId != null; }
    public boolean assassination() { return requestItem == null; }

    public void validateContribution(UUID player, long amount, Instant now, boolean allowOwn) {
        if (assassination()) throw new DomainException(DomainException.Kind.VALIDATION, "Assassination contracts cannot accept item deliveries");
        if (status != ContractStatus.OPEN) throw new DomainException(DomainException.Kind.CONFLICT, "Contract is not open");
        if (!now.isBefore(expiresAt)) throw new DomainException(DomainException.Kind.CONFLICT, "Contract has expired");
        if (!allowOwn && creatorId.equals(player)) throw new DomainException(DomainException.Kind.VALIDATION, "Creator cannot fulfill own contract");
        if (targetId != null && !targetId.equals(player)) throw new DomainException(DomainException.Kind.VALIDATION, "Contract is directed to another player");
        if (amount <= 0 || amount > remaining()) throw new DomainException(DomainException.Kind.VALIDATION, "Invalid contribution amount");
        if (fulfillmentMode == FulfillmentMode.COMPLETE && amount != remaining())
            throw new DomainException(DomainException.Kind.VALIDATION, "Complete fulfillment requires all remaining items");
    }
}
