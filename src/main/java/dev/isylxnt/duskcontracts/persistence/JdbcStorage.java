package dev.isylxnt.duskcontracts.persistence;

import com.zaxxer.hikari.HikariDataSource;
import dev.isylxnt.duskcontracts.config.StorageConfig;
import dev.isylxnt.duskcontracts.domain.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JdbcStorage implements Storage {
    private final HikariDataSource dataSource;
    private final StorageConfig.Type dialect;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JdbcStorage(HikariDataSource dataSource, StorageConfig.Type dialect, int workers) {
        this.dataSource = dataSource; this.dialect = dialect;
        int count = dialect == StorageConfig.Type.SQLITE ? 1 : Math.max(2, workers);
        this.executor = Executors.newFixedThreadPool(count, runnable -> {
            Thread thread = new Thread(runnable, "DuskContracts-Database"); thread.setDaemon(true); return thread;
        });
    }

    @Override public CompletableFuture<Void> initialize() { return run(() -> { try (Connection c = dataSource.getConnection()) { Migrations.migrate(c, dialect); } }); }

    @Override public CompletableFuture<OperationRecord> prepareAssetOperation(UUID id, String key, OperationType type, UUID actor,
            UUID contract, String correlation, String evidence, long assetMinor, byte[] assetPayload, UUID assetOwnerId) {
        return supply(() -> transaction(c -> {
            Optional<OperationRecord> existing = operationByKey(c, key);
            if (existing.isPresent()) return existing.get();
            long now = Instant.now().toEpochMilli();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_operations(id,idempotency_key,operation_type,state,actor_id,contract_id,correlation_id,evidence,created_at,updated_at,asset_minor,asset_payload,asset_owner_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                bind(ps, id, key, type.name(), OperationState.PREPARED.name(), string(actor), string(contract), correlation, evidence, now, now,assetMinor,assetPayload,string(assetOwnerId));
                ps.executeUpdate();
            } catch (SQLException ex) {
                if (isConstraint(ex)) return operationByKey(c, key).orElseThrow(() -> ex);
                throw ex;
            }
            auditDirect(c, actor, contract, id, "OPERATION_PREPARED", evidence);
            return operationById(c, id).orElseThrow();
        }));
    }

    @Override public CompletableFuture<Void> markOperationAmbiguous(UUID operationId, String evidence) {
        return run(() -> transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_operations SET state=?, evidence=?, updated_at=? WHERE id=? AND state=?")) {
                bind(ps, OperationState.AMBIGUOUS.name(), evidence, now(), operationId.toString(), OperationState.PREPARED.name()); ps.executeUpdate();
            }
            auditDirect(c, null, null, operationId, "OPERATION_QUARANTINED", evidence); return null;
        }));
    }

    @Override public CompletableFuture<Void> failPreparedOperation(UUID operationId, String evidence) {
        return run(() -> transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_operations SET state=?, evidence=?, updated_at=? WHERE id=? AND state=?")) {
                bind(ps, OperationState.FAILED.name(), evidence, now(), operationId.toString(), OperationState.PREPARED.name());
                if (ps.executeUpdate() != 1) throw new DomainException(DomainException.Kind.CONFLICT, "Prepared operation could not be closed");
            }
            auditDirect(c, null, null, operationId, "OPERATION_ABORTED", evidence); return null;
        }));
    }

    @Override public CompletableFuture<Void> commitContract(Contract contract, UUID operationId) {
        return run(() -> transaction(c -> {
            Optional<Contract> present = contractDirect(c, contract.id());
            if (present.isPresent()) return null;
            OperationRecord op = operationById(c, operationId).orElseThrow(() -> new DomainException(DomainException.Kind.CONFLICT, "Creation operation is missing"));
            if (op.state() == OperationState.COMMITTED) return null;
            if (op.state() != OperationState.PREPARED && op.state() != OperationState.AMBIGUOUS)
                throw new DomainException(DomainException.Kind.CONFLICT, "Creation operation is not committable");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_contracts(id,short_id,creator_id,creator_name,created_at,expires_at,status,material,match_mode,total_amount,delivered_amount,reward_type,reward_minor,target_id,fulfillment_mode,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                bind(ps, contract.id().toString(), contract.shortId(), contract.creatorId().toString(), contract.creatorName(),
                        millis(contract.createdAt()), millis(contract.expiresAt()), contract.status().name(), contract.material(), contract.matchMode().name(),
                        contract.totalAmount(), contract.deliveredAmount(), contract.rewardType().name(), contract.rewardMinor(), string(contract.targetId()),
                        contract.fulfillmentMode().name(), contract.version()); ps.executeUpdate();
            }
            if (contract.requestItem() != null) insertContractItem(c, contract.id(), "REQUEST", contract.requestItem());
            if (contract.rewardItem() != null) insertContractItem(c, contract.id(), "REWARD", contract.rewardItem());
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_escrow_assets(id,contract_id,asset_type,amount_minor,item_payload,state,operation_id,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                bind(ps, UUID.randomUUID().toString(), contract.id().toString(), contract.rewardType().name(), contract.rewardMinor(),
                        contract.rewardItem(), "COMMITTED", operationId.toString(), now()); ps.executeUpdate();
            }
            commitOperation(c, operationId);
            auditDirect(c, contract.creatorId(), contract.id(), operationId, "CONTRACT_CREATED", contract.shortId());
            return null;
        }));
    }

    @Override public CompletableFuture<Optional<Contract>> contract(String shortId) {
        return supply(() -> { try (Connection c = dataSource.getConnection()) { return contractByShortId(c, shortId.toUpperCase(Locale.ROOT)); } });
    }
    @Override public CompletableFuture<Optional<Contract>> contract(UUID id) {
        return supply(() -> { try (Connection c = dataSource.getConnection()) { return contractDirect(c, id); } });
    }

    @Override public CompletableFuture<List<ContractSummary>> browse(ContractFilter filter) {
        return supply(() -> {
            StringBuilder sql = new StringBuilder("SELECT co.id,co.short_id,co.creator_id,co.creator_name,co.created_at,co.expires_at,co.status,co.material,co.match_mode,co.total_amount,co.delivered_amount,co.reward_type,co.reward_minor,co.target_id,co.fulfillment_mode,co.version,CASE WHEN EXISTS (SELECT 1 FROM dc_contract_items ci WHERE ci.contract_id=co.id AND ci.role='REQUEST') THEN 0 ELSE 1 END AS assassination FROM dc_contracts co WHERE (NOT EXISTS (SELECT 1 FROM dc_contract_items visibility_item WHERE visibility_item.contract_id=co.id AND visibility_item.role='REQUEST') OR co.target_id IS NULL OR co.target_id=? OR co.creator_id=?)");
            List<Object> params = new ArrayList<>(); params.add(filter.viewerId().toString()); params.add(filter.viewerId().toString());
            if (filter.status() != null) { sql.append(" AND status=?"); params.add(filter.status().name()); }
            if (filter.rewardType() != null) { sql.append(" AND reward_type=?"); params.add(filter.rewardType().name()); }
            if (filter.material() != null) { sql.append(" AND material=?"); params.add(filter.material()); }
            if (filter.creatorId() != null) { sql.append(" AND creator_id=?"); params.add(filter.creatorId().toString()); }
            sql.append(switch (filter.sort()) {
                case NEWEST -> " ORDER BY created_at DESC"; case EXPIRING -> " ORDER BY expires_at ASC";
                case REWARD -> " ORDER BY reward_minor DESC";
                case PROGRESS -> " ORDER BY (delivered_amount * 1.0 / total_amount) DESC";
            });
            sql.append(" LIMIT ? OFFSET ?"); params.add(filter.pageSize()); params.add(filter.offset());
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) { List<ContractSummary> out = new ArrayList<>(); while (rs.next()) out.add(summary(rs)); return List.copyOf(out); }
            }
        });
    }

    @Override public CompletableFuture<ContributionResult> commitContribution(UUID operationId, UUID contributorId, String contributorName,
            long amount, byte[] deliveredItems, long expectedVersion, Instant instant, boolean allowOwn) {
        return supply(() -> transaction(c -> {
            Optional<ContributionResult> repeated = contributionByOperation(c, operationId);
            if (repeated.isPresent()) return repeated.get();
            OperationRecord operation = operationById(c, operationId).orElseThrow(() -> new DomainException(DomainException.Kind.CONFLICT, "Operation is missing"));
            Contract contract = contractDirect(c, Objects.requireNonNull(operation.contractId())).orElseThrow(() -> new DomainException(DomainException.Kind.CONFLICT, "Contract is missing"));
            if (contract.version() != expectedVersion) throw new DomainException(DomainException.Kind.CONFLICT, "Contract version changed");
            contract.validateContribution(contributorId, amount, instant, allowOwn);
            long payout = contract.rewardType() == RewardType.MONEY
                    ? ProportionalAllocator.allocate(contract.rewardMinor(), contract.totalAmount(), contract.deliveredAmount(), amount) : 0;
            long delivered = contract.deliveredAmount() + amount;
            boolean completed = delivered == contract.totalAmount();
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_contracts SET delivered_amount=?,status=?,version=version+1 WHERE id=? AND version=? AND status=? AND expires_at>?")) {
                bind(ps, delivered, completed ? ContractStatus.COMPLETED.name() : ContractStatus.OPEN.name(), contract.id().toString(),
                        expectedVersion, ContractStatus.OPEN.name(), millis(instant));
                if (ps.executeUpdate() != 1) throw new DomainException(DomainException.Kind.CONFLICT, "Contract changed concurrently");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_contributions(id,operation_id,contract_id,contributor_id,contributor_name,amount,payout_minor,created_at,item_payload) VALUES(?,?,?,?,?,?,?,?,?)")) {
                bind(ps, UUID.randomUUID().toString(), operationId.toString(), contract.id().toString(), contributorId.toString(),
                        contributorName, amount, payout, millis(instant), deliveredItems); ps.executeUpdate();
            }
            insertClaim(c, contract.creatorId(), contract.id(), operationId, ClaimType.DELIVERED_ITEMS, ClaimState.CLAIM_PENDING, 0, deliveredItems);
            if (payout > 0) insertClaim(c, contributorId, contract.id(), operationId, ClaimType.MONEY_REWARD, ClaimState.CLAIM_PENDING, payout, null);
            if (completed && contract.rewardType() == RewardType.ITEM) {
                byte[] reward = contractItem(c, contract.id(), "REWARD").orElseThrow(() -> new DomainException(DomainException.Kind.AMBIGUOUS, "Escrow reward item is missing"));
                insertClaim(c, contributorId, contract.id(), operationId, ClaimType.ITEM_REWARD, ClaimState.CLAIM_PENDING, 0, reward);
            }
            commitOperation(c, operationId);
            auditDirect(c, contributorId, contract.id(), operationId, "CONTRIBUTION_COMMITTED", "amount=" + amount + ", payoutMinor=" + payout);
            return new ContributionResult(operationId, amount, payout, completed, expectedVersion + 1);
        }));
    }

    @Override public CompletableFuture<List<Contract>> completeAssassinations(UUID killerId, String killerName, UUID victimId,
            Instant instant, boolean allowOwn, Duration repeatKillCooldown) {
        return supply(() -> transaction(c -> {
            if (!repeatKillCooldown.isZero() && killedRecently(c, killerId, victimId, instant.minus(repeatKillCooldown)))
                return List.of();
            List<UUID> candidates = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT co.id FROM dc_contracts co WHERE co.status=? AND co.target_id=? AND co.expires_at>? AND NOT EXISTS (SELECT 1 FROM dc_contract_items ci WHERE ci.contract_id=co.id AND ci.role='REQUEST') AND EXISTS (SELECT 1 FROM dc_participations pa WHERE pa.contract_id=co.id AND pa.player_id=?)")) {
                bind(ps, ContractStatus.OPEN.name(), victimId.toString(), millis(instant), killerId.toString());
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) candidates.add(UUID.fromString(rs.getString(1))); }
            }
            List<Contract> completed = new ArrayList<>();
            for (UUID id : candidates) {
                Contract contract = contractDirect(c, id).orElse(null);
                if (contract == null || !contract.assassination() || (!allowOwn && contract.creatorId().equals(killerId))) continue;
                try (PreparedStatement ps = c.prepareStatement("UPDATE dc_contracts SET delivered_amount=1,status=?,version=version+1 WHERE id=? AND version=? AND status=? AND expires_at>?")) {
                    bind(ps, ContractStatus.COMPLETED.name(), id.toString(), contract.version(), ContractStatus.OPEN.name(), millis(instant));
                    if (ps.executeUpdate() != 1) continue;
                }
                UUID operation = UUID.randomUUID(); long timestamp = millis(instant);
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_operations(id,idempotency_key,operation_type,state,actor_id,contract_id,correlation_id,evidence,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    bind(ps, operation.toString(), "assassination:" + id + ":" + operation, OperationType.CONTRIBUTE.name(), OperationState.COMMITTED.name(),
                            killerId.toString(), id.toString(), operation.toString().substring(0, 8), "victim=" + victimId, timestamp, timestamp); ps.executeUpdate();
                }
                long payout = contract.rewardType() == RewardType.MONEY ? contract.rewardMinor() : 0;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_contributions(id,operation_id,contract_id,contributor_id,contributor_name,amount,payout_minor,created_at,item_payload) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    bind(ps, UUID.randomUUID().toString(), operation.toString(), id.toString(), killerId.toString(), killerName, 1, payout, timestamp, new byte[0]); ps.executeUpdate();
                }
                if (contract.rewardType() == RewardType.MONEY)
                    insertClaim(c, killerId, id, operation, ClaimType.MONEY_REWARD, ClaimState.CLAIM_PENDING, payout, null);
                else {
                    byte[] reward = contractItem(c, id, "REWARD").orElseThrow(() -> new DomainException(DomainException.Kind.AMBIGUOUS, "Escrow reward item is missing"));
                    insertClaim(c, killerId, id, operation, ClaimType.ITEM_REWARD, ClaimState.CLAIM_PENDING, 0, reward);
                }
                auditDirect(c, killerId, id, operation, "ASSASSINATION_COMPLETED", "victim=" + victimId);
                completed.add(new Contract(contract.id(), contract.shortId(), contract.creatorId(), contract.creatorName(), contract.createdAt(),
                        contract.expiresAt(), ContractStatus.COMPLETED, contract.material(), contract.matchMode(), 1, 1,
                        contract.rewardType(), contract.rewardMinor(), null, contract.rewardItem(), contract.targetId(),
                        contract.fulfillmentMode(), contract.version() + 1));
            }
            if (!completed.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_assassination_kills(id,killer_id,victim_id,completed_at) VALUES(?,?,?,?)")) {
                    bind(ps, UUID.randomUUID().toString(), killerId.toString(), victimId.toString(), millis(instant));
                    ps.executeUpdate();
                }
            }
            return List.copyOf(completed);
        }));
    }

    @Override public CompletableFuture<Boolean> joinAssassination(UUID contractId, UUID playerId, Instant instant, boolean allowOwn) {
        return supply(() -> transaction(c -> {
            Contract contract = contractDirect(c, contractId).orElseThrow(() -> new DomainException(DomainException.Kind.CONFLICT, "Contract no longer exists"));
            if (!contract.assassination()) throw new DomainException(DomainException.Kind.VALIDATION, "Only assassination contracts can be started");
            if (contract.status() != ContractStatus.OPEN || !contract.expiresAt().isAfter(instant))
                throw new DomainException(DomainException.Kind.CONFLICT, "Contract is no longer open");
            if (!allowOwn && contract.creatorId().equals(playerId))
                throw new DomainException(DomainException.Kind.VALIDATION, "You cannot start your own contract");
            if (participatingDirect(c, contractId, playerId)) return false;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_participations(contract_id,player_id,joined_at) VALUES(?,?,?)")) {
                bind(ps, contractId.toString(), playerId.toString(), millis(instant));
                ps.executeUpdate();
            } catch (SQLException ex) {
                if (isConstraint(ex) && participatingDirect(c, contractId, playerId)) return false;
                throw ex;
            }
            auditDirect(c, playerId, contractId, null, "ASSASSINATION_JOINED", "target=" + contract.targetId());
            return true;
        }));
    }

    @Override public CompletableFuture<Boolean> isParticipating(UUID contractId, UUID playerId) {
        return supply(() -> { try (Connection c = dataSource.getConnection()) { return participatingDirect(c, contractId, playerId); } });
    }

    @Override public CompletableFuture<List<ContractSummary>> participating(UUID playerId, int limit, Instant instant) {
        return supply(() -> { try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT co.*,1 AS assassination FROM dc_participations pa JOIN dc_contracts co ON co.id=pa.contract_id WHERE pa.player_id=? AND co.status=? AND co.expires_at>? ORDER BY pa.joined_at DESC LIMIT ?")) {
            bind(ps, playerId.toString(), ContractStatus.OPEN.name(), millis(instant), limit);
            try (ResultSet rs = ps.executeQuery()) { List<ContractSummary> out = new ArrayList<>(); while (rs.next()) out.add(summary(rs)); return List.copyOf(out); }
        }});
    }

    @Override public CompletableFuture<Void> cancel(UUID contractId, UUID actorId, String reason, boolean administrative, UUID operationId, Instant instant) {
        return run(() -> transaction(c -> { cancelDirect(c, contractId, actorId, reason, administrative, operationId, instant, ContractStatus.CANCELLED); return null; }));
    }

    @Override public CompletableFuture<List<Contract>> expireBatch(Instant instant, int batchSize) {
        return supply(() -> {
            List<UUID> ids = new ArrayList<>();
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT id FROM dc_contracts WHERE status=? AND expires_at<=? ORDER BY expires_at ASC LIMIT ?")) {
                bind(ps, ContractStatus.OPEN.name(), millis(instant), batchSize);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(UUID.fromString(rs.getString(1))); }
            }
            List<Contract> done = new ArrayList<>();
            for (UUID id : ids) {
                UUID op = UUID.randomUUID();
                try {
                    transaction(c -> {
                        long timestamp = now();
                        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_operations(id,idempotency_key,operation_type,state,actor_id,contract_id,correlation_id,evidence,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                            bind(ps, op.toString(), "expire:" + id, OperationType.EXPIRE.name(), OperationState.PREPARED.name(), null, id.toString(), op.toString(), "UTC expiration sweep", timestamp, timestamp); ps.executeUpdate();
                        }
                        cancelDirect(c, id, null, "expired", true, op, instant, ContractStatus.EXPIRED); return null;
                    });
                    try(Connection c=dataSource.getConnection()){contractDirect(c,id).ifPresent(done::add);}
                } catch (DomainException ex) { if (ex.kind() != DomainException.Kind.CONFLICT) throw ex; }
            }
            return List.copyOf(done);
        });
    }

    @Override public CompletableFuture<List<ClaimRecord>> claims(UUID playerId, int limit) {
        return supply(() -> { try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT cl.*,co.short_id FROM dc_claims cl LEFT JOIN dc_contracts co ON co.id=cl.contract_id WHERE cl.recipient_id=? AND cl.state IN (?,?,?) ORDER BY cl.created_at ASC LIMIT ?")) {
            bind(ps, playerId.toString(), ClaimState.CLAIM_PENDING.name(), ClaimState.RETURN_PENDING.name(), ClaimState.FAILED.name(), limit);
            try (ResultSet rs = ps.executeQuery()) { List<ClaimRecord> out = new ArrayList<>(); while (rs.next()) out.add(claim(rs)); return List.copyOf(out); }
        } });
    }

    @Override public CompletableFuture<Optional<ClaimRecord>> reserveClaim(UUID claimId, UUID playerId) {
        return supply(() -> transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_claims SET state=?,version=version+1,updated_at=?,failure_reason=NULL WHERE id=? AND recipient_id=? AND state IN (?,?)")) {
                bind(ps, ClaimState.CLAIMING.name(), now(), claimId.toString(), playerId.toString(), ClaimState.CLAIM_PENDING.name(), ClaimState.RETURN_PENDING.name());
                if (ps.executeUpdate() != 1) return Optional.empty();
            }
            return claimById(c, claimId);
        }));
    }
    @Override public CompletableFuture<Void> releaseClaim(UUID claimId, String reason) { return claimState(claimId, null, reason, false); }
    @Override public CompletableFuture<Void> completeClaim(UUID claimId) { return claimState(claimId, ClaimState.CLAIMED, null, true); }
    @Override public CompletableFuture<Void> ambiguousClaim(UUID claimId, String reason) { return claimState(claimId, ClaimState.AMBIGUOUS, reason, true); }

    @Override public CompletableFuture<Integer> pendingClaimCount(UUID playerId) {
        return supply(() -> { try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM dc_claims WHERE recipient_id=? AND state IN (?,?,?)")) {
            bind(ps, playerId.toString(), ClaimState.CLAIM_PENDING.name(), ClaimState.RETURN_PENDING.name(), ClaimState.FAILED.name());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } });
    }

    @Override public CompletableFuture<Void> storeItemReturn(UUID playerId, UUID contractId, byte[] itemPayload, String reason) {
        return run(() -> transaction(c -> {
            UUID operation = UUID.randomUUID(); long timestamp = now();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_operations(id,idempotency_key,operation_type,state,actor_id,contract_id,correlation_id,evidence,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                bind(ps, operation.toString(), "safe-return:" + operation, OperationType.CANCEL.name(), OperationState.COMMITTED.name(),
                        playerId.toString(), string(contractId), operation.toString().substring(0, 8), reason, timestamp, timestamp); ps.executeUpdate();
            }
            insertClaim(c, playerId, contractId, operation, ClaimType.ITEM_BUNDLE_RETURN, ClaimState.RETURN_PENDING, 0, itemPayload);
            auditDirect(c, playerId, contractId, operation, "ITEMS_MOVED_TO_SAFE_RETURN", reason); return null;
        }));
    }

    @Override public CompletableFuture<Boolean> toggleNotifications(UUID playerId) {
        return supply(() -> transaction(c -> {
            boolean current = true;
            try (PreparedStatement ps = c.prepareStatement("SELECT notifications FROM dc_player_preferences WHERE player_id=?")) {
                ps.setString(1, playerId.toString()); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) current = rs.getInt(1) != 0; }
            }
            boolean next = !current;
            try (PreparedStatement update = c.prepareStatement("UPDATE dc_player_preferences SET notifications=?,updated_at=? WHERE player_id=?")) {
                bind(update, next ? 1 : 0, now(), playerId.toString());
                if (update.executeUpdate() == 0) try (PreparedStatement insert = c.prepareStatement("INSERT INTO dc_player_preferences(player_id,notifications,updated_at) VALUES(?,?,?)")) {
                    bind(insert, playerId.toString(), next ? 1 : 0, now()); insert.executeUpdate();
                }
            }
            return next;
        }));
    }

    @Override public CompletableFuture<PlayerStats> playerStats(UUID playerId) {
        return supply(() -> { try (Connection c = dataSource.getConnection()) {
            String id = playerId.toString();
            long active = countParam(c, "SELECT COUNT(*) FROM dc_contracts WHERE creator_id=? AND status='OPEN'", id);
            long created = countParam(c, "SELECT COUNT(*) FROM dc_contracts WHERE creator_id=?", id);
            long completed = countParam(c, "SELECT COUNT(*) FROM dc_contracts WHERE creator_id=? AND status='COMPLETED'", id);
            long claims = countParam(c, "SELECT COUNT(*) FROM dc_claims WHERE recipient_id=? AND state IN ('CLAIM_PENDING','RETURN_PENDING','FAILED')", id);
            long contributed = countParam(c, "SELECT COALESCE(SUM(amount),0) FROM dc_contributions WHERE contributor_id=?", id);
            return new PlayerStats(active, created, completed, claims, contributed);
        } });
    }

    @Override public CompletableFuture<List<ContributionSummary>> contributions(UUID playerId, int limit) {
        return supply(() -> { try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT co.id,co.short_id,co.material,co.total_amount,cn.amount,cn.payout_minor,cn.created_at,co.target_id,CASE WHEN EXISTS (SELECT 1 FROM dc_contract_items ci WHERE ci.contract_id=co.id AND ci.role='REQUEST') THEN 0 ELSE 1 END FROM dc_contributions cn JOIN dc_contracts co ON co.id=cn.contract_id WHERE cn.contributor_id=? ORDER BY cn.created_at DESC LIMIT ?")){
            bind(ps,playerId.toString(),limit);try(ResultSet rs=ps.executeQuery()){List<ContributionSummary> out=new ArrayList<>();while(rs.next())out.add(new ContributionSummary(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),instant(rs.getLong(7)),uuid(rs.getString(8)),rs.getBoolean(9)));return List.copyOf(out);}
        }});
    }

    @Override public CompletableFuture<StorageStats> stats() {
        return supply(() -> { long start = System.nanoTime(); try (Connection c = dataSource.getConnection()) {
            long open = count(c, "SELECT COUNT(*) FROM dc_contracts WHERE status='OPEN'");
            long claims = count(c, "SELECT COUNT(*) FROM dc_claims WHERE state IN ('CLAIM_PENDING','RETURN_PENDING','FAILED')");
            long ambiguous = count(c, "SELECT COUNT(*) FROM dc_operations WHERE state='AMBIGUOUS'");
            return new StorageStats(open, claims, ambiguous, Migrations.CURRENT, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } });
    }

    @Override public CompletableFuture<List<OperationRecord>> operations(String query, int limit) {
        return supply(() -> {
            String sql = query == null || query.isBlank()
                    ? "SELECT * FROM dc_operations WHERE state=? ORDER BY updated_at DESC LIMIT ?"
                    : "SELECT * FROM dc_operations WHERE id=? OR actor_id=? OR contract_id=? OR correlation_id=? ORDER BY updated_at DESC LIMIT ?";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                if (query == null || query.isBlank()) bind(ps, OperationState.AMBIGUOUS.name(), limit);
                else bind(ps, query, query, query, query, limit);
                try (ResultSet rs = ps.executeQuery()) { List<OperationRecord> out = new ArrayList<>(); while (rs.next()) out.add(operation(rs)); return List.copyOf(out); }
            }
        });
    }

    @Override public CompletableFuture<Void> resolveOperation(UUID operationId, UUID adminId, String resolution, String note) {
        return run(() -> transaction(c -> {
            OperationRecord op = operationById(c, operationId).orElseThrow(() -> new DomainException(DomainException.Kind.VALIDATION, "Operation not found"));
            OperationState target = switch (resolution.toUpperCase(Locale.ROOT)) {
                case "COMPLETE" -> OperationState.COMMITTED; case "REFUND" -> OperationState.FAILED; case "QUARANTINE" -> OperationState.AMBIGUOUS;
                default -> throw new DomainException(DomainException.Kind.VALIDATION, "Unknown resolution");
            };
            if(op.state()!=OperationState.AMBIGUOUS&&op.state()!=OperationState.PREPARED)
                throw new DomainException(DomainException.Kind.CONFLICT,"Only prepared or ambiguous operations may be resolved");
            if(target==OperationState.FAILED&&op.assetOwnerId()!=null){
                if(op.assetPayload()!=null)insertClaim(c,op.assetOwnerId(),op.contractId(),operationId,ClaimType.ITEM_BUNDLE_RETURN,ClaimState.RETURN_PENDING,0,op.assetPayload());
                else if(op.assetMinor()>0)insertClaim(c,op.assetOwnerId(),op.contractId(),operationId,ClaimType.MONEY_RETURN,ClaimState.RETURN_PENDING,op.assetMinor(),null);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_operations SET state=?,admin_note=?,updated_at=? WHERE id=?")) {
                bind(ps, target.name(), note, now(), operationId.toString()); ps.executeUpdate();
            }
            auditDirect(c, adminId, op.contractId(), operationId, "ADMIN_" + resolution.toUpperCase(Locale.ROOT), note); return null;
        }));
    }

    @Override public CompletableFuture<Void> recoverStale(Duration timeout) {
        return run(() -> transaction(c -> {
            long cutoff = Instant.now().minus(timeout).toEpochMilli();
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_operations SET state=?,evidence=?,updated_at=? WHERE state=? AND updated_at<?")) {
                bind(ps, OperationState.AMBIGUOUS.name(), "Found PREPARED after restart/timeout; manual evidence review required", now(), OperationState.PREPARED.name(), cutoff); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_claims SET state=?,failure_reason=?,updated_at=? WHERE state=? AND updated_at<?")) {
                bind(ps, ClaimState.AMBIGUOUS.name(), "Found CLAIMING after restart; do not replay automatically", now(), ClaimState.CLAIMING.name(), cutoff); ps.executeUpdate();
            }
            return null;
        }));
    }

    @Override public CompletableFuture<Integer> purgeMaintenance(Instant cutoff, int batchSize) {
        if (batchSize < 1 || batchSize > 100_000) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid maintenance batch size"));
        return supply(() -> transaction(c -> purgeBefore(c, "dc_audit_log", "created_at", cutoff, batchSize)
                + purgeBefore(c, "dc_assassination_kills", "completed_at", cutoff, batchSize)));
    }

    @Override public CompletableFuture<Void> audit(UUID actorId, UUID contractId, UUID operationId, String action, String details) {
        return run(() -> { try (Connection c = dataSource.getConnection()) { auditDirect(c, actorId, contractId, operationId, action, details); } });
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
        }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
        dataSource.close();
    }

    private void cancelDirect(Connection c, UUID contractId, UUID actorId, String reason, boolean admin, UUID operationId, Instant instant, ContractStatus target) throws SQLException {
        Contract contract = contractDirect(c, contractId).orElseThrow(() -> new DomainException(DomainException.Kind.VALIDATION, "Contract not found"));
        if (contract.status() != ContractStatus.OPEN) throw new DomainException(DomainException.Kind.CONFLICT, "Contract is not open");
        if (!admin && !contract.creatorId().equals(actorId)) throw new DomainException(DomainException.Kind.VALIDATION, "Only the creator can cancel this contract");
        try (PreparedStatement ps = c.prepareStatement("UPDATE dc_contracts SET status=?,version=version+1 WHERE id=? AND status=? AND version=?")) {
            bind(ps, target.name(), contractId.toString(), ContractStatus.OPEN.name(), contract.version());
            if (ps.executeUpdate() != 1) throw new DomainException(DomainException.Kind.CONFLICT, "Contract changed concurrently");
        }
        if (contract.rewardType() == RewardType.MONEY) {
            long allocated = contract.deliveredAmount() == 0 ? 0
                    : ProportionalAllocator.allocate(contract.rewardMinor(), contract.totalAmount(), 0, contract.deliveredAmount());
            long remainder = contract.rewardMinor() - allocated;
            if (remainder > 0) insertClaim(c, contract.creatorId(), contract.id(), operationId, ClaimType.MONEY_RETURN, ClaimState.RETURN_PENDING, remainder, null);
        } else {
            byte[] item = contractItem(c, contract.id(), "REWARD").orElseThrow(() -> new DomainException(DomainException.Kind.AMBIGUOUS, "Escrow reward is missing"));
            insertClaim(c, contract.creatorId(), contract.id(), operationId, ClaimType.ITEM_BUNDLE_RETURN, ClaimState.RETURN_PENDING, 0, item);
        }
        commitOperation(c, operationId);
        auditDirect(c, actorId, contractId, operationId, target == ContractStatus.EXPIRED ? "CONTRACT_EXPIRED" : "CONTRACT_CANCELLED", reason);
    }

    private CompletableFuture<Void> claimState(UUID claimId, ClaimState target, String reason, boolean finalState) {
        return run(() -> transaction(c -> {
            ClaimState actual = target;
            if (!finalState) {
                ClaimRecord claim = claimById(c, claimId).orElseThrow(() -> new DomainException(DomainException.Kind.VALIDATION, "Claim not found"));
                actual = claim.type() == ClaimType.MONEY_RETURN || claim.type() == ClaimType.ITEM_RETURN || claim.type() == ClaimType.ITEM_BUNDLE_RETURN
                        ? ClaimState.RETURN_PENDING : ClaimState.CLAIM_PENDING;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_claims SET state=?,failure_reason=?,updated_at=?,version=version+1 WHERE id=? AND state=?")) {
                bind(ps, actual.name(), reason, now(), claimId.toString(), ClaimState.CLAIMING.name());
                if (ps.executeUpdate() != 1) throw new DomainException(DomainException.Kind.CONFLICT, "Claim state changed");
            }
            return null;
        }));
    }

    private void insertClaim(Connection c, UUID recipient, UUID contract, UUID operation, ClaimType type, ClaimState state, long money, byte[] payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_claims(id,recipient_id,contract_id,operation_id,claim_type,state,money_minor,item_payload,created_at,updated_at,version) VALUES(?,?,?,?,?,?,?,?,?,?,0)")) {
            bind(ps, UUID.randomUUID().toString(), recipient.toString(), string(contract), operation.toString(), type.name(), state.name(), money, payload, now(), now()); ps.executeUpdate();
        }
    }
    private void insertContractItem(Connection c, UUID contract, String role, byte[] payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_contract_items(contract_id,role,payload,checksum) VALUES(?,?,?,?)")) {
            bind(ps, contract.toString(), role, payload, checksum(payload)); ps.executeUpdate();
        }
    }
    private void commitOperation(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE dc_operations SET state=?,updated_at=? WHERE id=? AND state IN (?,?)")) {
            bind(ps, OperationState.COMMITTED.name(), now(), id.toString(), OperationState.PREPARED.name(), OperationState.AMBIGUOUS.name());
            if (ps.executeUpdate() != 1) throw new DomainException(DomainException.Kind.CONFLICT, "Operation cannot be committed");
        }
    }
    private Optional<Contract> contractByShortId(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM dc_contracts WHERE short_id=?")) { ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(contract(c, rs)) : Optional.empty(); } }
    }
    private Optional<Contract> contractDirect(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM dc_contracts WHERE id=?")) { ps.setString(1, id.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(contract(c, rs)) : Optional.empty(); } }
    }
    private static boolean participatingDirect(Connection c, UUID contractId, UUID playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM dc_participations WHERE contract_id=? AND player_id=?")) {
            bind(ps, contractId.toString(), playerId.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }
    private static boolean killedRecently(Connection c, UUID killerId, UUID victimId, Instant cutoff) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM dc_assassination_kills WHERE killer_id=? AND victim_id=? AND completed_at>=? LIMIT 1")) {
            bind(ps, killerId.toString(), victimId.toString(), millis(cutoff));
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }
    private static int purgeBefore(Connection c, String table, String timeColumn, Instant cutoff, int batchSize) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + table + " WHERE " + timeColumn + "<? ORDER BY " + timeColumn + " LIMIT ?")) {
            bind(ps, millis(cutoff), batchSize);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getString(1)); }
        }
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
            return ps.executeUpdate();
        }
    }
    private Contract contract(Connection c, ResultSet rs) throws SQLException {
        UUID id = UUID.fromString(rs.getString("id"));
        return new Contract(id, rs.getString("short_id"), UUID.fromString(rs.getString("creator_id")), rs.getString("creator_name"),
                instant(rs.getLong("created_at")), instant(rs.getLong("expires_at")), ContractStatus.valueOf(rs.getString("status")),
                rs.getString("material"), MatchMode.valueOf(rs.getString("match_mode")), rs.getLong("total_amount"), rs.getLong("delivered_amount"),
                RewardType.valueOf(rs.getString("reward_type")), rs.getLong("reward_minor"),
                contractItem(c, id, "REQUEST").orElse(null), contractItem(c, id, "REWARD").orElse(null), uuid(rs.getString("target_id")),
                FulfillmentMode.valueOf(rs.getString("fulfillment_mode")), rs.getLong("version"));
    }
    private Optional<byte[]> contractItem(Connection c, UUID id, String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT payload FROM dc_contract_items WHERE contract_id=? AND role=?")) { bind(ps, id.toString(), role); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty(); } }
    }
    private static ContractSummary summary(ResultSet rs) throws SQLException {
        return new ContractSummary(UUID.fromString(rs.getString("id")), rs.getString("short_id"), UUID.fromString(rs.getString("creator_id")), rs.getString("creator_name"),
                instant(rs.getLong("created_at")), instant(rs.getLong("expires_at")), ContractStatus.valueOf(rs.getString("status")), rs.getString("material"),
                MatchMode.valueOf(rs.getString("match_mode")), rs.getLong("total_amount"), rs.getLong("delivered_amount"), RewardType.valueOf(rs.getString("reward_type")),
                rs.getLong("reward_minor"), uuid(rs.getString("target_id")), FulfillmentMode.valueOf(rs.getString("fulfillment_mode")), rs.getLong("version"), rs.getBoolean("assassination"));
    }
    private Optional<ContributionResult> contributionByOperation(Connection c, UUID operation) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT cn.amount,cn.payout_minor,co.status,co.version FROM dc_contributions cn JOIN dc_contracts co ON co.id=cn.contract_id WHERE cn.operation_id=?")) {
            ps.setString(1, operation.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(new ContributionResult(operation, rs.getLong(1), rs.getLong(2), ContractStatus.valueOf(rs.getString(3)) == ContractStatus.COMPLETED, rs.getLong(4))) : Optional.empty(); }
        }
    }
    private Optional<OperationRecord> operationByKey(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM dc_operations WHERE idempotency_key=?")) { ps.setString(1, key); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(operation(rs)) : Optional.empty(); } }
    }
    private Optional<OperationRecord> operationById(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM dc_operations WHERE id=?")) { ps.setString(1, id.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(operation(rs)) : Optional.empty(); } }
    }
    private static OperationRecord operation(ResultSet rs) throws SQLException {
        return new OperationRecord(UUID.fromString(rs.getString("id")), rs.getString("idempotency_key"), OperationType.valueOf(rs.getString("operation_type")),
                OperationState.valueOf(rs.getString("state")), uuid(rs.getString("actor_id")), uuid(rs.getString("contract_id")), rs.getString("correlation_id"),
                rs.getString("evidence"), rs.getString("admin_note"), instant(rs.getLong("created_at")), instant(rs.getLong("updated_at")),rs.getLong("asset_minor"),rs.getBytes("asset_payload"),uuid(rs.getString("asset_owner_id")));
    }
    private Optional<ClaimRecord> claimById(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT cl.*,co.short_id FROM dc_claims cl LEFT JOIN dc_contracts co ON co.id=cl.contract_id WHERE cl.id=?")) { ps.setString(1, id.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(claim(rs)) : Optional.empty(); } }
    }
    private static ClaimRecord claim(ResultSet rs) throws SQLException {
        return new ClaimRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("recipient_id")), uuid(rs.getString("contract_id")), rs.getString("short_id"),
                UUID.fromString(rs.getString("operation_id")), ClaimType.valueOf(rs.getString("claim_type")), ClaimState.valueOf(rs.getString("state")), rs.getLong("money_minor"),
                rs.getBytes("item_payload"), instant(rs.getLong("created_at")), rs.getString("failure_reason"), rs.getLong("version"));
    }
    private static long count(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { return rs.next() ? rs.getLong(1) : 0; } }
    private static long countParam(Connection c, String sql, String value) throws SQLException { try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setString(1,value); try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getLong(1):0;} } }
    private static void auditDirect(Connection c, UUID actor, UUID contract, UUID operation, String action, String details) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_audit_log(id,actor_id,contract_id,operation_id,action,details,created_at) VALUES(?,?,?,?,?,?,?)")) {
            bind(ps, UUID.randomUUID().toString(), string(actor), string(contract), string(operation), action, details, now()); ps.executeUpdate();
        }
    }
    private <T> CompletableFuture<T> supply(SqlSupplier<T> body) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Storage is closed"));
        return CompletableFuture.supplyAsync(() -> { try { return body.get(); } catch (DomainException ex) { throw new CompletionException(ex); } catch (SQLException ex) { throw new CompletionException(new DomainException(DomainException.Kind.TRANSIENT, "Database operation failed", ex)); } }, executor);
    }
    private CompletableFuture<Void> run(SqlRunnable body) { return supply(() -> { body.run(); return null; }); }
    private <T> T transaction(SqlConnectionFunction<T> body) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            boolean old = c.getAutoCommit(); c.setAutoCommit(false);
            if (dialect == StorageConfig.Type.SQLITE) try (Statement s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); }
            try { T result = body.apply(c); c.commit(); return result; }
            catch (SQLException | RuntimeException ex) { try { c.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); } throw ex; }
            finally { try { c.setAutoCommit(old); } catch (SQLException ignored) { /* connection is closing */ } }
        }
    }
    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            Object value = values[i]; if (value == null) ps.setNull(i + 1, Types.NULL); else if (value instanceof byte[] bytes) ps.setBytes(i + 1, bytes); else ps.setObject(i + 1, value);
        }
    }
    private static boolean isConstraint(SQLException ex) { return ex.getSQLState() != null && (ex.getSQLState().startsWith("23") || ex.getMessage().toLowerCase(Locale.ROOT).contains("constraint")); }
    private static long now() { return Instant.now().toEpochMilli(); }
    private static long millis(Instant value) { return value.toEpochMilli(); }
    private static Instant instant(long value) { return Instant.ofEpochMilli(value); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static String string(UUID value) { return value == null ? null : value.toString(); }
    private static String checksum(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); } }
    @FunctionalInterface private interface SqlSupplier<T> { T get() throws SQLException; }
    @FunctionalInterface private interface SqlRunnable { void run() throws SQLException; }
    @FunctionalInterface private interface SqlConnectionFunction<T> { T apply(Connection connection) throws SQLException; }
}
