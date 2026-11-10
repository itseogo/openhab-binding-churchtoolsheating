package org.openhab.binding.churchtoolsheating.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

public class ChurchToolsAPITest {

    @Test
    public void testParseDateVariousFormats() {
        ChurchToolsAPI api = new ChurchToolsAPI("https://example.church.tools", "dummy-token");

        // Format 1: ISO 8601 with UTC Z
        ZonedDateTime zdt1 = api.parseDate("2026-11-10T14:30:00Z");
        assertNotNull(zdt1);
        assertEquals(2026, zdt1.getYear());
        assertEquals(11, zdt1.getMonthValue());
        assertEquals(10, zdt1.getDayOfMonth());

        // Format 2: ISO 8601 with Offset
        ZonedDateTime zdt2 = api.parseDate("2026-11-10T15:30:00+01:00");
        assertNotNull(zdt2);
        assertEquals(15, zdt2.getHour());

        // Format 3: Without offset (LocalDateTime)
        ZonedDateTime zdt3 = api.parseDate("2026-11-10T18:00:00");
        assertNotNull(zdt3);
        assertEquals(18, zdt3.getHour());

        // Format 4: Space instead of T
        ZonedDateTime zdt4 = api.parseDate("2026-11-10 20:00:00");
        assertNotNull(zdt4);
        assertEquals(20, zdt4.getHour());

        // Format 5: Date only (all-day event)
        ZonedDateTime zdt5 = api.parseDate("2026-12-24");
        assertNotNull(zdt5);
        assertEquals(2026, zdt5.getYear());
        assertEquals(12, zdt5.getMonthValue());
        assertEquals(24, zdt5.getDayOfMonth());

        // Null or empty handling
        assertNull(api.parseDate(null));
        assertNull(api.parseDate(""));
        assertNull(api.parseDate("invalid-date-string"));
    }
}
