package dev.isylxnt.duskcontracts.persistence;

import java.time.Instant;
import java.util.UUID;

public record ContributionSummary(UUID contractId,String contractShortId,String material,long totalAmount,long amount,long payoutMinor,
        Instant createdAt,UUID targetId,boolean assassination){ }
