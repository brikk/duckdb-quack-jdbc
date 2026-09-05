package com.gizmodata.quack.jdbc.codec;

import java.math.BigInteger;

public record HugeIntParts(long upper, long lower) {

    private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(64);
    private static final BigInteger UINT64_MASK = TWO_POW_64.subtract(BigInteger.ONE);

    public static HugeIntParts ofSigned(BigInteger value) {
        // The upper word enforces the signed 128-bit range; the lower word holds raw bits.
        return new HugeIntParts(value.shiftRight(64).longValueExact(), value.longValue());
    }

    public BigInteger toSignedBigInteger() {
        BigInteger upperBig = BigInteger.valueOf(upper);
        BigInteger lowerBig = BigInteger.valueOf(lower).and(UINT64_MASK);
        return upperBig.shiftLeft(64).or(lowerBig);
    }

    public BigInteger toUnsignedBigInteger() {
        BigInteger upperBig = BigInteger.valueOf(upper).and(UINT64_MASK);
        BigInteger lowerBig = BigInteger.valueOf(lower).and(UINT64_MASK);
        return upperBig.shiftLeft(64).or(lowerBig);
    }
}
