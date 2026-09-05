package com.gizmodata.quack.jdbc.codec;

/** Per-message limits. Decoded bytes are conservative allocation accounting, not measured JVM heap. */
public record DecodeLimits(int maxResponseBytes, long maxDecodedBytes, int maxNestingDepth) {

    public static final DecodeLimits DEFAULT = new DecodeLimits(64 * 1024 * 1024, 256L * 1024 * 1024, 64);

    public DecodeLimits {
        if (maxResponseBytes <= 0 || maxDecodedBytes <= 0 || maxNestingDepth < 1 || maxNestingDepth > 128) {
            throw new IllegalArgumentException("Decode limits require positive byte budgets and nesting depth 1..128");
        }
    }
}
