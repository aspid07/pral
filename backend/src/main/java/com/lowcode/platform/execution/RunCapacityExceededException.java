package com.lowcode.platform.execution;

/** Ревью CTO, п.1.5 — превышен лимит одновременных run на инстанс. */
public class RunCapacityExceededException extends RuntimeException {
    public RunCapacityExceededException(String message) {
        super(message);
    }
}
