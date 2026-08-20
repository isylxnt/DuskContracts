package dev.isylxnt.duskcontracts.persistence;

import dev.isylxnt.duskcontracts.domain.ClaimState;
import dev.isylxnt.duskcontracts.domain.ClaimType;
import java.time.Instant;
import java.util.UUID;

public record ClaimRecord(UUID id, UUID recipientId, UUID contractId, String contractShortId, UUID operationId,
        ClaimType type, ClaimState state, long moneyMinor, byte[] itemPayload, Instant createdAt,
        String failureReason, long version) {
    public ClaimRecord { itemPayload = itemPayload == null ? null : itemPayload.clone(); }
    @Override public byte[] itemPayload() { return itemPayload == null ? null : itemPayload.clone(); }
}
