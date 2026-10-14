package dev.remember.app;

import java.time.LocalDateTime;
import java.util.Objects;

public final class RecurrenceTest {
    private static int assertions;
    private static void expect(String expected, String anchor, String rule, String after, boolean inclusive) {
        LocalDateTime actual = Recurrence.next(LocalDateTime.parse(anchor), rule, LocalDateTime.parse(after), inclusive);
        LocalDateTime wanted = expected == null ? null : LocalDateTime.parse(expected);
        if (!Objects.equals(actual, wanted)) throw new AssertionError(rule + ": expected " + wanted + ", got " + actual);
        assertions++;
    }
    public static void main(String[] args) {
        expect("2026-02-28T09:30:00", "2024-02-29T09:30:00", "yearly", "2026-02-28T09:30:00", true);
        expect("2027-02-28T09:30:00", "2024-02-29T09:30:00", "yearly", "2026-02-28T09:30:00", false);
        expect("2028-02-29T09:30:00", "2024-02-29T09:30:00", "yearly", "2027-03-01T00:00:00", true);
        expect("2026-02-28T09:30:00", "2026-01-31T09:30:00", "monthly", "2026-02-01T00:00:00", true);
        expect("2026-03-31T09:30:00", "2026-01-31T09:30:00", "monthly", "2026-02-28T09:30:00", false);
        expect("2026-04-30T09:30:00", "2026-01-31T09:30:00", "monthly", "2026-04-01T00:00:00", true);
        expect("2027-01-31T09:30:00", "2026-01-31T09:30:00", "monthly", "2026-12-31T09:30:00", false);
        expect("2026-01-02T09:00:00", "2026-01-01T09:00:00", "daily", "2026-01-02T09:00:00", true);
        expect("2026-01-03T09:00:00", "2026-01-01T09:00:00", "daily", "2026-01-02T09:00:00", false);
        expect("2026-01-03T09:00:00", "2026-01-01T09:00:00", "daily", "2026-01-02T09:00:01", true);
        expect("2026-01-08T09:00:00", "2026-01-01T09:00:00", "weekly", "2026-01-08T09:00:00", true);
        expect("2026-01-15T09:00:00", "2026-01-01T09:00:00", "weekly", "2026-01-08T09:00:01", true);
        expect("2027-01-01T09:00:00", "2027-01-01T09:00:00", "monthly", "2026-01-01T00:00:00", true);
        expect("2026-01-01T09:00:00", "2026-01-01T09:00:00", "once", "2026-01-01T09:00:00", true);
        expect(null, "2026-01-01T09:00:00", "once", "2026-01-01T09:00:00", false);
        expect(null, "2026-01-01T09:00:00", "once", "2026-01-01T09:00:01", true);
        try { Recurrence.next(LocalDateTime.now(), "invalid", LocalDateTime.now(), true); throw new AssertionError("Invalid rule accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        System.out.println("PASS: " + assertions + " Java recurrence assertions");
    }
}
