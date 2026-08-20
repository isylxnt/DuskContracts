package dev.isylxnt.duskcontracts.persistence;

public record StorageStats(long openContracts, long pendingClaims, long ambiguousOperations, int schemaVersion, long latencyMillis) { }
