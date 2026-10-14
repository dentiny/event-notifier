package dev.remember.app;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/** Calendar rules in local wall time, with stable values for SQLite and JSON. */
enum Recurrence {
    ONCE("once"),
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    YEARLY("yearly");

    private final String value;
    Recurrence(String value) { this.value = value; }
    String value() { return value; }

    static Recurrence fromValue(String value) {
        for (Recurrence rule : values()) if (rule.value.equals(value)) return rule;
        throw new IllegalArgumentException("Invalid recurrence rule: " + value);
    }

    LocalDateTime next(LocalDateTime anchor, LocalDateTime after, boolean inclusive) {
        if (acceptable(anchor, after, inclusive)) return anchor;
        return switch (this) {
            case ONCE -> null;
            case DAILY, WEEKLY -> {
                long days = this == DAILY ? 1 : 7;
                long periods = Duration.between(anchor, after).getSeconds() / (days * 86400);
                LocalDateTime candidate = anchor.plusDays(periods * days);
                yield acceptable(candidate, after, inclusive) ? candidate : anchor.plusDays((periods + 1) * days);
            }
            case MONTHLY -> {
                YearMonth month = YearMonth.from(after);
                if (month.isBefore(YearMonth.from(anchor))) month = YearMonth.from(anchor);
                while (true) {
                    LocalDateTime candidate = month.atDay(Math.min(anchor.getDayOfMonth(), month.lengthOfMonth())).atTime(anchor.toLocalTime());
                    if (!candidate.isBefore(anchor) && acceptable(candidate, after, inclusive)) yield candidate;
                    month = month.plusMonths(1);
                }
            }
            case YEARLY -> {
                int year = Math.max(anchor.getYear(), after.getYear());
                while (true) {
                    LocalDate date = LocalDate.of(year, anchor.getMonth(), 1);
                    LocalDateTime candidate = date.withDayOfMonth(Math.min(anchor.getDayOfMonth(), date.lengthOfMonth())).atTime(anchor.toLocalTime());
                    if (!candidate.isBefore(anchor) && acceptable(candidate, after, inclusive)) yield candidate;
                    year++;
                }
            }
        };
    }
    private static boolean acceptable(LocalDateTime candidate, LocalDateTime after, boolean inclusive) {
        return candidate.isAfter(after) || (inclusive && candidate.equals(after));
    }
}
