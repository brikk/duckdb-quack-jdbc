package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated("Changes the JVM default locale")
class QuackColumnLookupTest {
    @ParameterizedTest
    @MethodSource("labelPairs")
    void matchesWithoutExpandingOrNormalizingAndPreservesRawMetadata(
            String left, String right, boolean sameColumn) throws SQLException {
        assertEquals(sameColumn, left.equalsIgnoreCase(right));
        assertEquals(sameColumn, String.CASE_INSENSITIVE_ORDER.compare(left, right) == 0);
        for (List<String> labels : List.of(List.of(left, right), List.of(right, left))) {
            try (var rs = result(labels, "first", "second")) {
                assertEquals(1, rs.findColumn(labels.get(0)));
                assertEquals(sameColumn ? 1 : 2, rs.findColumn(labels.get(1)));
                var metadata = rs.getMetaData();
                assertEquals(2, metadata.getColumnCount());
                for (int i = 0; i < labels.size(); i++) {
                    assertEquals(labels.get(i), metadata.getColumnLabel(i + 1));
                    assertEquals(labels.get(i), metadata.getColumnName(i + 1));
                }
                assertTrue(rs.next());
                assertEquals("first", rs.getString(labels.get(0)));
                assertEquals(sameColumn ? "first" : "second", rs.getString(labels.get(1)));
                assertEquals("second", rs.getString(2), "Duplicates remain accessible by index");
            }
        }
    }

    private static Stream<Arguments> labelPairs() {
        return Stream.of(
                Arguments.of("name", "name", true),
                Arguments.of("MiXeD", "mixed", true),
                Arguments.of("", "", true),
                Arguments.of("stra\u00dfe", "strasse", false),
                Arguments.of("stra\u00dfe", "STRASSE", false),
                Arguments.of("stra\u00dfe", "STRA\u00dfE", true),
                Arguments.of("stra\u00dfe", "STRA\u1e9eE", true),
                Arguments.of("\ufb03", "FFI", false),
                Arguments.of("I", "i", true),
                Arguments.of("I", "\u0130", true),
                Arguments.of("I", "\u0131", true),
                Arguments.of("i", "\u0130", true),
                Arguments.of("i", "\u0131", true),
                Arguments.of("\u0130", "\u0131", true),
                Arguments.of("\u0130", "i\u0307", false),
                Arguments.of("\u03a3", "\u03c3", true),
                Arguments.of("\u03a3", "\u03c2", true),
                Arguments.of("\u03c3", "\u03c2", true),
                Arguments.of("K", "\u212a", true),
                Arguments.of("S", "\u017f", true),
                Arguments.of("\u00c9", "\u00e9", true),
                Arguments.of("\u00e9", "e\u0301", false),
                Arguments.of("\u00c5", "\u212b", true),
                Arguments.of("\u540d_\u00c4_\u03a3", "\u540d_\u00e4_\u03c2", true),
                // JDK 17 compares supplementary code points, not just individual UTF-16 chars.
                Arguments.of("\uD801\uDC00", "\uD801\uDC28", true),
                Arguments.of("\uD801\uDC01", "\uD801\uDC28", false),
                Arguments.of("\uD83A\uDD00", "\uD83A\uDD22", true),
                Arguments.of("A\uD801\uDC00\u00c9", "a\uD801\uDC28\u00e9", true),
                Arguments.of("\uD83D\uDE00", "\uD83D\uDE01", false),
                Arguments.of("a\"B'`[C].*\\d", "A\"b'`[c].*\\D", true),
                Arguments.of("\"Name\"", "name", false),
                Arguments.of(" name ", "name", false));
    }

    @Test
    void lookupIsIndependentOfLocaleAtConstructionAndAfterLocaleChanges() throws SQLException {
        Locale original = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        List<Locale> locales = List.of(Locale.ROOT, Locale.US, Locale.GERMANY,
                Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("az-AZ"),
                Locale.forLanguageTag("lt-LT"));
        List<String> labels = List.of("ID", "id", "\u0130D", "\u0131d", "stra\u00dfe",
                "strasse", "i\u0307d", "MiXeD");
        String[] queries = {"ID", "Id", "id", "\u0130d", "\u0131D", "STRA\u00dfE",
                "stra\u1e9ee", "STRASSE", "Strasse", "I\u0307D", "mixed"};
        int[] expected = {1, 1, 1, 1, 1, 5, 5, 6, 6, 7, 8};
        try {
            for (Locale constructionLocale : locales) {
                Locale.setDefault(constructionLocale);
                try (var rs = result(labels, labels.toArray())) {
                    assertTrue(rs.next());
                    for (Locale lookupLocale : locales) {
                        Locale.setDefault(lookupLocale);
                        for (int i = 0; i < queries.length; i++) {
                            String context = constructionLocale + " -> " + lookupLocale + ": " + queries[i];
                            assertEquals(expected[i], rs.findColumn(queries[i]), context);
                            assertEquals(labels.get(expected[i] - 1), rs.getString(queries[i]), context);
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labelGetters")
    void supportedLabelGettersShareLookupErrorsAndFirstMatch(String name, LabelGetter getter)
            throws SQLException {
        try (var rs = result(List.of("stra\u00dfe", "STRA\u1e9eE"), null, "wrong column")) {
            String query = "STRA\u00dfE";
            assertEquals("Not on a row", assertThrows(SQLException.class,
                    () -> getter.get(rs, query)).getMessage(), name);
            assertTrue(rs.next());
            getter.get(rs, query);
            assertTrue(rs.wasNull(), "The first case-insensitive match must win, even when null");
            getter.get(rs, "STRA\u1e9eE");
            assertTrue(rs.wasNull(), "An exact match must not take priority over the first match");
            for (String unknown : new String[]{"strasse", "missing", null}) {
                assertEquals("Unknown column: " + unknown, assertThrows(SQLException.class,
                        () -> rs.findColumn(unknown)).getMessage());
                assertEquals("Unknown column: " + unknown, assertThrows(SQLException.class,
                        () -> getter.get(rs, unknown)).getMessage(), name);
            }
            assertFalse(rs.next());
            assertEquals("Not on a row", assertThrows(SQLException.class,
                    () -> getter.get(rs, query)).getMessage(), name);
        }
    }

    @SuppressWarnings("deprecation")
    private static Stream<Arguments> labelGetters() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        return Stream.of(
                Arguments.of("getString", (LabelGetter) QuackResultSet::getString),
                Arguments.of("getBoolean", (LabelGetter) QuackResultSet::getBoolean),
                Arguments.of("getByte", (LabelGetter) QuackResultSet::getByte),
                Arguments.of("getShort", (LabelGetter) QuackResultSet::getShort),
                Arguments.of("getInt", (LabelGetter) QuackResultSet::getInt),
                Arguments.of("getLong", (LabelGetter) QuackResultSet::getLong),
                Arguments.of("getFloat", (LabelGetter) QuackResultSet::getFloat),
                Arguments.of("getDouble", (LabelGetter) QuackResultSet::getDouble),
                Arguments.of("getBigDecimal", (LabelGetter) QuackResultSet::getBigDecimal),
                Arguments.of("getBigDecimal(scale)", (LabelGetter) (rs, label) -> rs.getBigDecimal(label, 2)),
                Arguments.of("getBytes", (LabelGetter) QuackResultSet::getBytes),
                Arguments.of("getDate", (LabelGetter) QuackResultSet::getDate),
                Arguments.of("getDate(Calendar)", (LabelGetter) (rs, label) -> rs.getDate(label, cal)),
                Arguments.of("getTime", (LabelGetter) QuackResultSet::getTime),
                Arguments.of("getTime(Calendar)", (LabelGetter) (rs, label) -> rs.getTime(label, cal)),
                Arguments.of("getTimestamp", (LabelGetter) QuackResultSet::getTimestamp),
                Arguments.of("getTimestamp(Calendar)", (LabelGetter) (rs, label) -> rs.getTimestamp(label, cal)),
                Arguments.of("getObject", (LabelGetter) QuackResultSet::getObject),
                Arguments.of("getObject(Class)", (LabelGetter) (rs, label) -> rs.getObject(label, String.class)),
                Arguments.of("getObject(Map)", (LabelGetter) (rs, label) -> rs.getObject(label, Map.of())),
                Arguments.of("getArray", (LabelGetter) QuackResultSet::getArray),
                Arguments.of("getBlob", (LabelGetter) QuackResultSet::getBlob),
                Arguments.of("getBinaryStream", (LabelGetter) QuackResultSet::getBinaryStream),
                Arguments.of("getAsciiStream", (LabelGetter) QuackResultSet::getAsciiStream),
                Arguments.of("getCharacterStream", (LabelGetter) QuackResultSet::getCharacterStream),
                Arguments.of("getNString", (LabelGetter) QuackResultSet::getNString),
                Arguments.of("getNCharacterStream", (LabelGetter) QuackResultSet::getNCharacterStream),
                Arguments.of("getURL", (LabelGetter) QuackResultSet::getURL));
    }

    @FunctionalInterface
    private interface LabelGetter {
        Object get(QuackResultSet rs, String label) throws SQLException;
    }

    private static QuackResultSet result(List<String> labels, Object... values) {
        assertEquals(labels.size(), values.length);
        LogicalType type = LogicalType.of(LogicalTypeId.VARCHAR);
        List<LogicalType> types = Collections.nCopies(labels.size(), type);
        List<DecodedVector> columns = new ArrayList<>();
        for (Object value : values) columns.add(new DecodedVector.ObjectVec(type, new Object[]{value}));
        var response = new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                types, labels, false, List.of(new DataChunk(1, types, columns)), new HugeIntParts(1, 2));
        QuackTransport transport = request -> { throw new AssertionError("Unexpected request: " + request); };
        var session = new QuackSession(QuackUri.parse("jdbc:quack://example.test"), transport);
        return new QuackResultSet(null, new QuackSession.Cursor(session, response));
    }
}
