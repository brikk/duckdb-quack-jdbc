package com.gizmodata.quack.jdbc.codec;

import java.math.BigInteger;

public record HugeIntParts(long upper, long lower) {

    private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(64);
    private static final BigInteger UINT64_MASK = TWO_POW_64.subtract(BigInteger.ONE);

    public static HugeIntParts ofSigned(BigInteger value) {
        BigInteger normalized = value.mod(TWO_POW_64.shiftLeft(64));
        BigInteger lower = normalized.and(UINT64_MASK);
        BigInteger upperUnsigned = normalized.shiftRight(64).and(UINT64_MASK);
        BigInteger upperSigned = upperUnsigned.bitLength() > 63
                ? upperUnsigned.subtract(TWO_POW_64)
                : upperUnsigned;
        return new HugeIntParts(upperSigned.longValueExact(), lower.longValueExact());
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
