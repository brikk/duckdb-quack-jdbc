package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuackResultSetConversionTest {
    @Test
    void integralValuesStayExactWhenReadAsBigDecimal() throws Exception {
        for (Number value : new Number[]{(byte) -128, (short) 32767, Integer.MAX_VALUE,
                9_007_199_254_740_993L, -9_007_199_254_740_993L, Long.MIN_VALUE, Long.MAX_VALUE,
                new BigInteger("18446744073709551617")}) {
            try (var rs = result(LogicalTypeId.BIGINT, value)) {
                assertTrue(rs.next());
                BigDecimal expected = new BigDecimal(value.toString());
                assertEquals(expected, rs.getBigDecimal(1));
                assertEquals(expected, rs.getObject(1, BigDecimal.class));
            }
        }
    }

    @Test
    void bigIntegerConversionDoesNotNarrowStringsThroughLong() throws Exception {
        for (String value : new String[]{"18446744073709551616", "-18446744073709551617",
                "18446744073709551616.75", "-18446744073709551616.75", "1E40"}) {
            try (var rs = result(LogicalTypeId.VARCHAR, value)) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal(value).toBigInteger(), rs.getObject(1, BigInteger.class));
            }
        }
        for (Number value : new Number[]{Math.scalb(1.0, 60), -Math.scalb(1.0, 63),
                Math.scalb(1.0f, 60), 1e30, -1e30, 1.5, -1.5}) {
            try (var rs = result(LogicalTypeId.DOUBLE, value)) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal(value.doubleValue()).toBigInteger(), rs.getObject(1, BigInteger.class));
            }
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            try (var rs = result(LogicalTypeId.DOUBLE, value)) {
                assertTrue(rs.next());
                assertThrows(SQLException.class, () -> rs.getObject(1, BigInteger.class));
            }
        }
    }

    @Test
    void decimalScaleAndNullConversionsArePreserved() throws Exception {
        BigDecimal decimal = new BigDecimal("123.4500");
        try (var rs = result(LogicalTypeId.DECIMAL, decimal)) {
            assertTrue(rs.next());
            assertEquals(decimal, rs.getBigDecimal(1));
        }
        try (var rs = result(LogicalTypeId.BIGINT, null)) {
            assertTrue(rs.next());
            assertNull(rs.getBigDecimal(1));
            assertTrue(rs.wasNull());
            assertNull(rs.getObject(1, BigInteger.class));
            assertTrue(rs.wasNull());
        }
    }

    private static QuackResultSet result(LogicalTypeId id, Object value) {
        LogicalType type = LogicalType.of(id);
        DataChunk chunk = new DataChunk(1, List.of(type), List.of(
                new DecodedVector.ObjectVec(type, new Object[]{value})));
        QuackSession session = new QuackSession(QuackUri.parse("jdbc:quack://example.test"), request -> {
            throw new AssertionError("Unexpected I/O");
        });
        var response = new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                List.of(type), List.of("value"), false, List.of(chunk), new HugeIntParts(0, 0));
        return new QuackResultSet(null, new QuackSession.Cursor(session, response));
    }
}
