package dev.isylxnt.duskcontracts.persistence;

import dev.isylxnt.duskcontracts.domain.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface Storage extends AutoCloseable {
    CompletableFuture<Void> initialize();
    default CompletableFuture<OperationRecord> prepareOperation(UUID operationId, String idempotencyKey, OperationType type,
            UUID actorId, UUID contractId, String correlationId, String evidence) {
        return prepareAssetOperation(operationId,idempotencyKey,type,actorId,contractId,correlationId,evidence,0,null,actorId);
    }
    CompletableFuture<OperationRecord> prepareAssetOperation(UUID operationId, String idempotencyKey, OperationType type,
            UUID actorId, UUID contractId, String correlationId, String evidence, long assetMinor, byte[] assetPayload, UUID assetOwnerId);
    CompletableFuture<Void> failPreparedOperation(UUID operationId, String evidence);
    CompletableFuture<Void> markOperationAmbiguous(UUID operationId, String evidence);
    CompletableFuture<Void> commitContract(Contract contract, UUID operationId);
    CompletableFuture<Optional<Contract>> contract(String shortId);
    CompletableFuture<Optional<Contract>> contract(UUID id);
    CompletableFuture<List<ContractSummary>> browse(ContractFilter filter);
    CompletableFuture<ContributionResult> commitContribution(UUID operationId, UUID contributorId, String contributorName,
            long amount, byte[] deliveredItems, long expectedVersion, Instant now, boolean allowOwn);
    CompletableFuture<List<Contract>> completeAssassinations(UUID killerId, String killerName, UUID victimId,
            Instant now, boolean allowOwn, Duration repeatKillCooldown);
    CompletableFuture<Boolean> joinAssassination(UUID contractId, UUID playerId, Instant now, boolean allowOwn);
    CompletableFuture<Boolean> isParticipating(UUID contractId, UUID playerId);
    CompletableFuture<List<ContractSummary>> participating(UUID playerId, int limit, Instant now);
    CompletableFuture<Void> cancel(UUID contractId, UUID actorId, String reason, boolean administrative, UUID operationId, Instant now);
    CompletableFuture<List<Contract>> expireBatch(Instant now, int batchSize);
    CompletableFuture<List<ClaimRecord>> claims(UUID playerId, int limit);
    CompletableFuture<Optional<ClaimRecord>> reserveClaim(UUID claimId, UUID playerId);
    CompletableFuture<Void> releaseClaim(UUID claimId, String reason);
    CompletableFuture<Void> completeClaim(UUID claimId);
    CompletableFuture<Void> ambiguousClaim(UUID claimId, String reason);
    CompletableFuture<Integer> pendingClaimCount(UUID playerId);
    CompletableFuture<Void> storeItemReturn(UUID playerId, UUID contractId, byte[] itemPayload, String reason);
    CompletableFuture<Boolean> toggleNotifications(UUID playerId);
    CompletableFuture<PlayerStats> playerStats(UUID playerId);
    CompletableFuture<List<ContributionSummary>> contributions(UUID playerId, int limit);
    CompletableFuture<StorageStats> stats();
    CompletableFuture<List<OperationRecord>> operations(String query, int limit);
    CompletableFuture<Void> resolveOperation(UUID operationId, UUID adminId, String resolution, String note);
    CompletableFuture<Void> recoverStale(Duration timeout);
    CompletableFuture<Integer> purgeMaintenance(Instant cutoff, int batchSize);
    CompletableFuture<Void> audit(UUID actorId, UUID contractId, UUID operationId, String action, String details);
    @Override void close();
}
