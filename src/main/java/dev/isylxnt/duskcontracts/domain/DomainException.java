package dev.isylxnt.duskcontracts.domain;

public final class DomainException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Kind { VALIDATION, CONFLICT, TRANSIENT, PERMANENT, AMBIGUOUS }
    private final Kind kind;
    public DomainException(Kind kind, String message) { super(message); this.kind = kind; }
    public DomainException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
