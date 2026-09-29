package it.devangarde.time;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/**
 * Converts the Notes time zone string stored in the CalendarProfile
 * (field "Timezone"), e.g.
 * <pre>Z=-1$DO=1$DL=3 -1 1 10 -1 1$ZX=134$ZN=W. Europe</pre>
 * into a java.util.TimeZone.
 *
 * <ul>
 * <li>Z: hours WEST of GMT for the standard time (so -1 means GMT+1);</li>
 * <li>DO: 1 when daylight saving time is observed;</li>
 * <li>DL: DST rule as "startMonth startWeek startWeekday endMonth endWeek endWeekday"
 *     (week -1 = last, weekday 1 = Sunday); the switch hour is not stored, 02:00
 *     local time is assumed;</li>
 * <li>ZX, ZN: Notes internal zone index and name, not needed here.</li>
 * </ul>
 */
final class NotesTimeZone {

    private NotesTimeZone() {}

    /** null when the string is empty or cannot be understood. */
    static TimeZone parse(String notesTimeZone) {
        if (notesTimeZone == null || notesTimeZone.trim().isEmpty()) return null;

        Map<String, String> kv = new HashMap<>();
        for (String part : notesTimeZone.split("\\$")) {
            int eq = part.indexOf('=');
            if (eq > 0) kv.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        if (!kv.containsKey("Z")) return null;

        try {
            int rawOffsetMs = (int) Math.round(-Double.parseDouble(kv.get("Z")) * 3600000);
            String id = "Notes/" + kv.getOrDefault("ZN", "custom");

            String dl = kv.get("DL");
            if (!"1".equals(kv.get("DO")) || dl == null) {
                return new SimpleTimeZone(rawOffsetMs, id);
            }
            String[] p = dl.trim().split("\\s+");
            if (p.length < 6) return new SimpleTimeZone(rawOffsetMs, id);

            int twoAm = 2 * 3600000;
            return new SimpleTimeZone(rawOffsetMs, id,
                    Integer.parseInt(p[0]) - 1, week(p[1]), Integer.parseInt(p[2]), twoAm, SimpleTimeZone.WALL_TIME,
                    Integer.parseInt(p[3]) - 1, week(p[4]), Integer.parseInt(p[5]), twoAm, SimpleTimeZone.WALL_TIME,
                    3600000);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int week(String s) {
        int w = Integer.parseInt(s);
        return w >= 5 ? -1 : w; // some Notes versions use 5 for "last"
    }

    /**
     * Best-effort IANA id (e.g. "Europe/Rome") behaving like the given zone,
     * for clients that need a named zone: the server's own default zone is
     * preferred when it matches, otherwise the first matching zone in
     * alphabetical order. null when nothing matches.
     */
    static String guessIanaId(TimeZone tz) {
        TimeZone def = TimeZone.getDefault();
        if (sameBehaviour(tz, def)) return def.getID();

        String[] ids = TimeZone.getAvailableIDs(tz.getRawOffset());
        Arrays.sort(ids);
        for (String id : ids) {
            if (id.indexOf('/') > 0 && !id.startsWith("Etc/") && sameBehaviour(tz, TimeZone.getTimeZone(id))) {
                return id;
            }
        }
        return null;
    }

    private static boolean sameBehaviour(TimeZone a, TimeZone b) {
        int year = LocalDate.now().getYear();
        for (int y = year; y <= year + 1; y++) {
            for (int m = 1; m <= 12; m++) {
                long t = LocalDate.of(y, m, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                if (a.getOffset(t) != b.getOffset(t)) return false;
            }
        }
        return true;
    }
}
