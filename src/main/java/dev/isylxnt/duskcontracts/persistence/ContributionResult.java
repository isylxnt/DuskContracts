package dev.isylxnt.duskcontracts.persistence;

import java.util.UUID;

public record ContributionResult(UUID operationId, long acceptedAmount, long payoutMinor, boolean completed, long newVersion) { }
