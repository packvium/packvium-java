package com.packvium.locks;

/**
 * A lock set no request could satisfy, refused before any solve.
 *
 * <p>The distinction decides what an operator is told. A lock the <em>solve</em> could not honour
 * is an answer -- a {@link LockedResolve} with {@code preserved == false} and the lock named in
 * {@code missing} -- because whether it fits is a question about a request. A lock set that
 * contradicts <em>itself</em> is not a question at all: two locks whose boxes overlap cannot both
 * hold against any request, in any container, under any solver, and a lock on an item the request
 * does not contain is a typo. Answering either with a solve would return an emptied container and
 * call it a result.
 *
 * <p>This is the same line the execution plan draws when it refuses a loading order that is not a
 * permutation.
 */
public final class LockSetError extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** @param message the contradiction, worded as the reference engine words it */
    public LockSetError(String message) {
        super(message);
    }
}
