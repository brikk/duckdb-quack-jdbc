package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackUnsupportedTypeException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.sql.JdbcTypeMap;
import com.gizmodata.quack.jdbc.sql.QuackResultSetMetaData;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfoType;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BinaryLogicalTypeTest {
    private static final LogicalTypeId[] UNSUPPORTED = {LogicalTypeId.BIGNUM, LogicalTypeId.TYPE, LogicalTypeId.AGGREGATE_STATE};

    @Test
    void unsupportedBinaryLogicalValuesNeverBecomeUtf8OrSequenceNumbers() {
        for (LogicalTypeId id : UNSUPPORTED) {
            for (VectorType encoding : new VectorType[]{VectorType.FLAT, VectorType.CONSTANT, VectorType.DICTIONARY, VectorType.SEQUENCE}) {
                for (String hex : new String[]{"80000100", "800004075bcd15", "7ffffbf8a432ea", "80001101" + "00".repeat(16)}) {
                    byte[] raw = HexFormat.of().parseHex(hex);
                    assertThrows(QuackUnsupportedTypeException.class, () -> decode(LogicalType.of(id), raw, encoding, true));
                }
            }
        }
    }

    @Test
    void unsupportedBinaryAppendRejectsValuesButNullsAndEmptyVectorsKeepMetadata() throws Exception {
        for (LogicalTypeId id : UNSUPPORTED) {
            LogicalType type = LogicalType.of(id);
            for (Object value : new Object[]{new BigInteger("123456789"), "123456789", new byte[]{(byte) 0x80, 0, 1, 0}}) {
                assertThrows(QuackUnsupportedTypeException.class, () -> encode(type, new Object[]{value}));
            }
            assertNull(VectorCodec.decodeVector(new BinaryReader(encode(type, new Object[]{null})), type, 1).getObject(0));
            assertEquals(0, VectorCodec.decodeVector(new BinaryReader(encode(type, new Object[0])), type, 0).size());
            for (VectorType encoding : new VectorType[]{VectorType.FLAT, VectorType.CONSTANT, VectorType.DICTIONARY}) {
                DecodedVector nulls = decode(type, new byte[]{(byte) 0xff}, encoding, false);
                for (int i = 0; i < nulls.size(); i++) assertNull(nulls.getObject(i));
            }
            assertEquals(Types.OTHER, JdbcTypeMap.toJdbcType(type));
            assertEquals(id.name(), JdbcTypeMap.typeName(type));
            assertEquals(Object.class.getName(), new QuackResultSetMetaData(List.of("v"), List.of(type)).getColumnClassName(1));
        }
    }

    @Test
    void textualAliasesAndSupportedBinaryPayloadsRemainUnchanged() {
        String text = "text \u00e9 \u4e2d";
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        for (LogicalType type : new LogicalType[]{LogicalType.of(LogicalTypeId.VARCHAR), LogicalType.of(LogicalTypeId.CHAR),
                LogicalType.of(LogicalTypeId.VARCHAR, new ExtraTypeInfo.Generic(ExtraTypeInfoType.GENERIC, Optional.of("JSON")))}) {
            assertEquals(text, decode(type, utf8, VectorType.FLAT, true).getObject(0));
            assertEquals(text, VectorCodec.decodeVector(new BinaryReader(encode(type, new Object[]{text})), type, 1).getObject(0));
        }
        for (LogicalTypeId id : new LogicalTypeId[]{LogicalTypeId.BLOB, LogicalTypeId.BIT, LogicalTypeId.GEOMETRY}) {
            byte[] raw = id == LogicalTypeId.BIT ? new byte[]{5, (byte) 0xfd} : new byte[]{(byte) 0xff, 0, (byte) 0xfe};
            LogicalType type = LogicalType.of(id);
            assertArrayEquals(raw, (byte[]) decode(type, raw, VectorType.FLAT, true).getObject(0));
            assertArrayEquals(raw, (byte[]) VectorCodec.decodeVector(new BinaryReader(encode(type, new Object[]{raw})), type, 1).getObject(0));
        }
    }

    private static byte[] encode(LogicalType type, Object[] values) {
        BinaryWriter writer = new BinaryWriter();
        VectorCodec.encodeVector(writer, type, new DecodedVector.ObjectVec(type, values));
        return writer.toByteArray();
    }

    private static DecodedVector decode(LogicalType type, byte[] raw, VectorType encoding, boolean valid) {
        BinaryWriter writer = new BinaryWriter();
        writer.writeObject(obj -> {
            if (encoding != VectorType.FLAT) obj.writeField(90, () -> obj.writeUleb(encoding.wireId()));
            if (encoding == VectorType.SEQUENCE) {
                obj.writeField(91, () -> obj.writeSleb(1));
                obj.writeField(92, () -> obj.writeSleb(0));
                return;
            }
            if (encoding == VectorType.DICTIONARY) {
                obj.writeField(91, () -> obj.writeBlob(new byte[8]));
                obj.writeField(92, () -> obj.writeUleb(1));
            }
            obj.writeField(100, () -> obj.writeBool(!valid));
            if (!valid) obj.writeField(101, () -> obj.writeBlob(new byte[8]));
            obj.writeField(102, () -> { obj.writeUleb(1); obj.writeStringBytes(raw); });
        });
        BinaryReader reader = new BinaryReader(writer.toByteArray());
        DecodedVector result = VectorCodec.decodeVector(reader, type, encoding == VectorType.FLAT ? 1 : 2);
        reader.assertEof();
        return result;
    }
}
