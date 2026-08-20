package dev.isylxnt.duskcontracts.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ClaimService { CompletableFuture<Integer> pendingCount(UUID playerId); }
