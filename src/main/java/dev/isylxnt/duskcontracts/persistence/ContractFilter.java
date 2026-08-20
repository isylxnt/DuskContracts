package dev.isylxnt.duskcontracts.persistence;

import dev.isylxnt.duskcontracts.domain.ContractStatus;
import dev.isylxnt.duskcontracts.domain.RewardType;
import java.util.UUID;
import java.util.Objects;

public record ContractFilter(ContractStatus status, RewardType rewardType, String material, UUID creatorId,
                             Sort sort, UUID viewerId, int page, int pageSize) {
    public enum Sort { NEWEST, EXPIRING, REWARD, PROGRESS }
    public ContractFilter {
        if (page < 0 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid page");
        Objects.requireNonNull(sort, "sort");
        Objects.requireNonNull(viewerId, "viewerId");
    }
    public long offset() { return Math.multiplyExact((long) page, pageSize); }
    public static ContractFilter browse(UUID viewer, int page) {
        return new ContractFilter(ContractStatus.OPEN, null, null, null, Sort.NEWEST, viewer, page, 45);
    }
}
