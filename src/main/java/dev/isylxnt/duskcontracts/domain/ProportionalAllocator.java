package dev.isylxnt.duskcontracts.domain;

import java.math.BigInteger;

public final class ProportionalAllocator {
    private ProportionalAllocator() {}

    public static long allocate(long fundedMinor, long totalQuantity, long deliveredBefore, long contribution) {
        if (fundedMinor < 0 || totalQuantity <= 0 || deliveredBefore < 0 || contribution <= 0 || deliveredBefore + contribution > totalQuantity)
            throw new DomainException(DomainException.Kind.VALIDATION, "Invalid proportional allocation inputs");
        BigInteger reward = BigInteger.valueOf(fundedMinor);
        BigInteger total = BigInteger.valueOf(totalQuantity);
        BigInteger before = reward.multiply(BigInteger.valueOf(deliveredBefore)).divide(total);
        BigInteger after = deliveredBefore + contribution == totalQuantity
                ? reward
                : reward.multiply(BigInteger.valueOf(deliveredBefore + contribution)).divide(total);
        return after.subtract(before).longValueExact();
    }
}
