package dev.remember.app;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/** Calendar rules in local wall time; the scheduler resolves the phone's time zone. */
final class Recurrence {
    static void validate(String rule) {
        switch (rule) {
            case "once": case "daily": case "weekly": case "monthly": case "yearly": return;
            default: throw new IllegalArgumentException("重复方式无效");
        }
    }
    static LocalDateTime next(LocalDateTime anchor, String rule, LocalDateTime after, boolean inclusive) {
        validate(rule);
        if (acceptable(anchor, after, inclusive)) return anchor;
        switch (rule) {
            case "once": return null;
            case "daily": case "weekly": {
                long days = rule.equals("daily") ? 1 : 7;
                long periods = Duration.between(anchor, after).getSeconds() / (days * 86400);
                LocalDateTime candidate = anchor.plusDays(periods * days);
                return acceptable(candidate, after, inclusive) ? candidate : anchor.plusDays((periods + 1) * days);
            }
            case "monthly": {
                YearMonth month = YearMonth.from(after);
                if (month.isBefore(YearMonth.from(anchor))) month = YearMonth.from(anchor);
                while (true) {
                    LocalDateTime candidate = month.atDay(Math.min(anchor.getDayOfMonth(), month.lengthOfMonth())).atTime(anchor.toLocalTime());
                    if (!candidate.isBefore(anchor) && acceptable(candidate, after, inclusive)) return candidate;
                    month = month.plusMonths(1);
                }
            }
            case "yearly": {
                int year = Math.max(anchor.getYear(), after.getYear());
                while (true) {
                    LocalDate date = LocalDate.of(year, anchor.getMonth(), 1);
                    LocalDateTime candidate = date.withDayOfMonth(Math.min(anchor.getDayOfMonth(), date.lengthOfMonth())).atTime(anchor.toLocalTime());
                    if (!candidate.isBefore(anchor) && acceptable(candidate, after, inclusive)) return candidate;
                    year++;
                }
            }
            default: throw new IllegalArgumentException("重复方式无效");
        }
    }
    private static boolean acceptable(LocalDateTime candidate, LocalDateTime after, boolean inclusive) {
        return candidate.isAfter(after) || (inclusive && candidate.equals(after));
    }
    private Recurrence() {}
}
