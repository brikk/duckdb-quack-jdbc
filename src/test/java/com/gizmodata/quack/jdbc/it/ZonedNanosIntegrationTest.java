package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class ZonedNanosIntegrationTest {
    private QuackServerFixture server;
    @BeforeAll void start() throws Exception { server = QuackServerFixture.tryStart(); assertNotNull(server); }
    @AfterAll void stop() { if (server != null) server.close(); }

    @Test
    void zonedNanosAreBinaryExactAcrossTimezonesNullsEndpointsAndAppend() throws Exception {
        try (var c = (QuackConnection) DriverManager.getConnection(server.jdbcUrl()); var s = c.createStatement()) {
            if (c.session().protocolVersion() != 3) return; // This logical type was added in DuckDB 2.0.
            long[] ticks = {Long.MIN_VALUE + 2, -1_000_000_001L, -1, 0, 1, Long.MAX_VALUE - 1};
            OffsetDateTime[] values = new OffsetDateTime[ticks.length + 1];
            for (int i = 0; i < ticks.length; i++) {
                values[i] = Instant.ofEpochSecond(Math.floorDiv(ticks[i], 1_000_000_000L),
                        Math.floorMod(ticks[i], 1_000_000_000L)).atOffset(ZoneOffset.UTC);
            }
            s.execute("CREATE TEMP TABLE nanos (v TIMESTAMPTZ_NS)");
            LogicalType type = LogicalType.of(LogicalTypeId.TIMESTAMP_TZ_NS);
            c.session().appendChunk("main", "nanos", new DataChunk(values.length, List.of(type),
                    List.of(new DecodedVector.ObjectVec(type, values))));
            for (String zone : List.of("UTC", "America/New_York", "Asia/Kathmandu")) {
                s.execute("SET TimeZone='" + zone + "'");
                try (var rows = s.executeQuery("SELECT v, epoch_ns(v) FROM nanos ORDER BY v NULLS LAST")) {
                    assertEquals("TIMESTAMPTZ_NS", rows.getMetaData().getColumnTypeName(1));
                    assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, rows.getMetaData().getColumnType(1));
                    assertEquals(OffsetDateTime.class.getName(), rows.getMetaData().getColumnClassName(1));
                    assertEquals(9, rows.getMetaData().getScale(1));
                    assertEquals(35, rows.getMetaData().getPrecision(1));
                    int row = 0;
                    for (OffsetDateTime value : values) {
                        assertTrue(rows.next());
                        assertEquals(value, rows.getObject(1, OffsetDateTime.class));
                        if (value == null) {
                            assertTrue(rows.wasNull());
                            assertNull(rows.getTimestamp(1));
                        } else {
                            assertEquals(ticks[row], rows.getLong(2), "server-side epoch nanos must match APPEND bytes");
                            assertEquals(value.toInstant(), rows.getTimestamp(1).toInstant());
                            assertEquals(value.toInstant(), rows.getTimestamp(1,
                                    Calendar.getInstance(TimeZone.getTimeZone(zone))).toInstant());
                        }
                        row++;
                    }
                    assertFalse(rows.next());
                }
            }
            try (var rows = s.executeQuery("SELECT CAST('1969-12-31 23:59:59.999999999+00' AS TIMESTAMPTZ_NS)")) {
                assertTrue(rows.next());
                assertEquals(Instant.ofEpochSecond(-1, 999999999).atOffset(ZoneOffset.UTC), rows.getObject(1));
            }
            for (String infinity : List.of("infinity", "-infinity")) {
                SQLException error = assertThrows(SQLException.class,
                        () -> s.executeQuery("SELECT '" + infinity + "'::TIMESTAMPTZ_NS"));
                assertTrue(error.getMessage().contains("Infinite TIMESTAMP_TZ_NS"), error.getMessage());
            }
        }
    }
}
