package dev.isylxnt.duskcontracts.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(long minorUnits, int decimalPlaces) implements Comparable<Money> {
    public Money {
        if (minorUnits < 0) throw new DomainException(DomainException.Kind.VALIDATION, "Money cannot be negative");
        if (decimalPlaces < 0 || decimalPlaces > 8) throw new DomainException(DomainException.Kind.VALIDATION, "Invalid decimal places");
    }

    public static Money parse(String input, int places) {
        Objects.requireNonNull(input, "input");
        if (!input.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1," + places + "})?"))
            throw new DomainException(DomainException.Kind.VALIDATION, "Invalid monetary amount");
        try {
            var value = new BigDecimal(input).setScale(places, RoundingMode.UNNECESSARY);
            return new Money(value.movePointRight(places).longValueExact(), places);
        } catch (ArithmeticException ex) {
            throw new DomainException(DomainException.Kind.VALIDATION, "Monetary amount is out of range", ex);
        }
    }

    public BigDecimal decimal() { return BigDecimal.valueOf(minorUnits, decimalPlaces); }
    @Override public int compareTo(Money other) {
        if (decimalPlaces != other.decimalPlaces) throw new IllegalArgumentException("Different scales");
        return Long.compare(minorUnits, other.minorUnits);
    }
}
