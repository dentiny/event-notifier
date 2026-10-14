package dev.remember.app;

import java.time.LocalDateTime;
import java.util.Objects;

public final class RecurrenceTest {
    private static int assertions;
    private static void expect(String expected, String anchor, Recurrence rule, String after, boolean inclusive) {
        LocalDateTime actual = rule.next(LocalDateTime.parse(anchor), LocalDateTime.parse(after), inclusive);
        LocalDateTime wanted = expected == null ? null : LocalDateTime.parse(expected);
        if (!Objects.equals(actual, wanted)) throw new AssertionError(rule + ": expected " + wanted + ", got " + actual);
        assertions++;
    }
    public static void main(String[] args) {
        expect("2016-02-07T09:00:00", "2015-02-07T09:00:00", Recurrence.YEARLY, "2015-02-07T09:00:00", false);
        expect("2017-02-07T09:00:00", "2015-02-07T09:00:00", Recurrence.YEARLY, "2016-02-07T09:00:00", false);
        expect("2027-02-07T09:00:00", "2015-02-07T09:00:00", Recurrence.YEARLY, "2026-10-13T12:00:00", true);
        expect("2026-02-28T09:30:00", "2024-02-29T09:30:00", Recurrence.YEARLY, "2026-02-28T09:30:00", true);
        expect("2027-02-28T09:30:00", "2024-02-29T09:30:00", Recurrence.YEARLY, "2026-02-28T09:30:00", false);
        expect("2028-02-29T09:30:00", "2024-02-29T09:30:00", Recurrence.YEARLY, "2027-03-01T00:00:00", true);
        expect("2026-02-28T09:30:00", "2026-01-31T09:30:00", Recurrence.MONTHLY, "2026-02-01T00:00:00", true);
        expect("2026-03-31T09:30:00", "2026-01-31T09:30:00", Recurrence.MONTHLY, "2026-02-28T09:30:00", false);
        expect("2026-04-30T09:30:00", "2026-01-31T09:30:00", Recurrence.MONTHLY, "2026-04-01T00:00:00", true);
        expect("2027-01-31T09:30:00", "2026-01-31T09:30:00", Recurrence.MONTHLY, "2026-12-31T09:30:00", false);
        expect("2026-01-02T09:00:00", "2026-01-01T09:00:00", Recurrence.DAILY, "2026-01-02T09:00:00", true);
        expect("2026-01-03T09:00:00", "2026-01-01T09:00:00", Recurrence.DAILY, "2026-01-02T09:00:00", false);
        expect("2026-01-03T09:00:00", "2026-01-01T09:00:00", Recurrence.DAILY, "2026-01-02T09:00:01", true);
        expect("2026-01-08T09:00:00", "2026-01-01T09:00:00", Recurrence.WEEKLY, "2026-01-08T09:00:00", true);
        expect("2026-01-15T09:00:00", "2026-01-01T09:00:00", Recurrence.WEEKLY, "2026-01-08T09:00:01", true);
        expect("2027-01-01T09:00:00", "2027-01-01T09:00:00", Recurrence.MONTHLY, "2026-01-01T00:00:00", true);
        expect("2026-01-01T09:00:00", "2026-01-01T09:00:00", Recurrence.ONCE, "2026-01-01T09:00:00", true);
        expect(null, "2026-01-01T09:00:00", Recurrence.ONCE, "2026-01-01T09:00:00", false);
        expect(null, "2026-01-01T09:00:00", Recurrence.ONCE, "2026-01-01T09:00:01", true);
        try { Recurrence.fromValue("invalid"); throw new AssertionError("Invalid rule accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        System.out.println("PASS: " + assertions + " Java recurrence assertions");
    }
}
