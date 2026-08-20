package dev.isylxnt.duskcontracts.config;

import dev.isylxnt.duskcontracts.domain.MatchMode;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public record AppConfig(
        String locale, int maxActivePerPlayer, Duration creationCooldown, Duration defaultDuration,
        List<Duration> allowedDurations, long maxRequestAmount, boolean allowPrivateContracts,
        boolean allowOwnFulfillment, MatchMode defaultMatchMode, Set<MatchMode> enabledMatchModes,
        boolean partialEnabled, long minimumContribution, boolean moneyEnabled, int decimalPlaces,
        long minimumRewardMinor, long maximumRewardMinor, Duration expirationSweep, int expirationBatchSize,
        int maxSerializedItemBytes, Duration operationTimeout, Duration sessionTimeout, int maxActionsPerSecond,
        Duration assassinationRepeatKillCooldown, int auditRetentionDays, boolean verboseStartupReport) {
    public AppConfig {
        allowedDurations = List.copyOf(allowedDurations);
        enabledMatchModes = Set.copyOf(enabledMatchModes);
    }
}
