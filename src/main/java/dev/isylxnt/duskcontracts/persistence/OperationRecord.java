package dev.isylxnt.duskcontracts.persistence;

import dev.isylxnt.duskcontracts.domain.OperationState;
import dev.isylxnt.duskcontracts.domain.OperationType;
import java.time.Instant;
import java.util.UUID;

public record OperationRecord(UUID id, String idempotencyKey, OperationType type, OperationState state,
        UUID actorId, UUID contractId, String correlationId, String evidence, String adminNote,
        Instant createdAt, Instant updatedAt, long assetMinor, byte[] assetPayload, UUID assetOwnerId) {
    public OperationRecord { assetPayload=assetPayload==null?null:assetPayload.clone(); }
    @Override public byte[] assetPayload(){return assetPayload==null?null:assetPayload.clone();}
}
