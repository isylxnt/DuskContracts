package dev.isylxnt.duskcontracts.domain;

import java.time.Duration;
import java.util.Locale;

public final class Parsers {
    private Parsers() {}
    public static long positiveAmount(String value, long maximum) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw new DomainException(DomainException.Kind.VALIDATION, "Invalid amount");
        try {
            long parsed = Long.parseLong(value);
            if (parsed > maximum) throw new DomainException(DomainException.Kind.VALIDATION, "Amount exceeds maximum");
            return parsed;
        } catch (NumberFormatException ex) { throw new DomainException(DomainException.Kind.VALIDATION, "Amount is out of range", ex); }
    }
    public static Duration duration(String value) {
        if (value == null || !value.toLowerCase(Locale.ROOT).matches("[1-9][0-9]*(s|m|h|d)"))
            throw new DomainException(DomainException.Kind.VALIDATION, "Invalid duration");
        long number = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (Character.toLowerCase(value.charAt(value.length() - 1))) {
            case 's' -> Duration.ofSeconds(number); case 'm' -> Duration.ofMinutes(number);
            case 'h' -> Duration.ofHours(number); case 'd' -> Duration.ofDays(number);
            default -> throw new IllegalStateException();
        };
    }
}
