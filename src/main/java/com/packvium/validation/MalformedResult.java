package com.packvium.validation;

/**
 * The documents do not have the shape the result contract requires: a contract break, not a
 * packing defect, so {@link ResultValidator#validate} returns no issue list.
 *
 * <p>{@link #kind()} is {@link Kind#CONTRACT} when the reference validator refuses the result with
 * this message, and {@link Kind#SHAPE} when the documents cannot be read the way the contract
 * reads them at all -- a missing key, a value of the wrong type, text that is not JSON -- in which
 * case the message is this port's own account of it.
 */
public final class MalformedResult extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Why a result could not be judged. */
    public enum Kind {
        /** The contract names this refusal; its message is the one every Packvium validator gives. */
        CONTRACT("contract"),
        /** The documents could not be read as the contract reads them. */
        SHAPE("shape");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        /** {@code contract} or {@code shape}. */
        public String wireName() {
            return wireName;
        }
    }

    private final Kind kind;

    public MalformedResult(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    static MalformedResult contract(String message) {
        return new MalformedResult(Kind.CONTRACT, message);
    }

    static MalformedResult shape(String message) {
        return new MalformedResult(Kind.SHAPE, message);
    }

    public Kind kind() {
        return kind;
    }
}
