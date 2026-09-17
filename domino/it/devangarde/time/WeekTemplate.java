package it.devangarde.time;

import lotus.domino.DateRange;
import lotus.domino.DateTime;
import lotus.domino.NotesException;
import lotus.domino.Session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Vector;

/**
 * Derives the weekly template (open days, hours, breaks) from the FREE
 * intervals returned by Session.freeTimeSearch, rather than from busy blocks
 * as in the REST/Node version of the project: here the first and last free
 * interval of the day define opening/closing, and the gaps between one free
 * interval and the next are the breaks (e.g. lunch).
 *
 * WARNING (to verify against a real Domino server before trusting it):
 * - HCL's documentation does not explicitly state whether freeTimeSearch
 *   clips free intervals to the edges of the requested window; we do it
 *   manually here just in case, but if the real behavior differs (e.g. no
 *   result when a day is partially outside the window) the algorithm would
 *   need to be revisited.
 * - no explicit correction for daylight saving time: DateTime is natively
 *   timezone-aware, but if the same one-hour offset already fixed in the
 *   Node version shows up here (reference week and displayed week in
 *   different seasons), the same per-day offset-based correction would need
 *   to be added.
 */
public class WeekTemplate {

    public static class Day {
        public boolean closed;
        public int startMin;
        public int endMin;
        public List<int[]> breaks = new ArrayList<>(); // each {startMin, endMin}
    }

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** Model of the 7 days (index 0 = Monday of probeMonday) derived from the reference week. */
    public static Day[] deriveTemplate(Session session, String cn, Date probeMonday, int minDurationMinutes) throws NotesException {
        Date probeEnd = new Date(probeMonday.getTime() + 7 * DAY_MS);
        List<long[]> free = rawFreeRanges(session, cn, probeMonday, probeEnd, minDurationMinutes);

        Day[] days = new Day[7];
        for (int d = 0; d < 7; d++) {
            long dayStartMs = probeMonday.getTime() + d * DAY_MS;
            days[d] = buildDay(free, dayStartMs, dayStartMs + DAY_MS);
        }
        return days;
    }

    /** Free intervals (absolute ms, UTC epoch) in the requested week, for the real availability check. */
    public static List<long[]> freeRangesForWeek(Session session, String cn, Date weekMonday, int minDurationMinutes) throws NotesException {
        Date weekEnd = new Date(weekMonday.getTime() + 7 * DAY_MS);
        return rawFreeRanges(session, cn, weekMonday, weekEnd, minDurationMinutes);
    }

    private static Day buildDay(List<long[]> free, long dayStartMs, long dayEndMs) {
        List<long[]> dayFree = new ArrayList<>();
        for (long[] f : free) {
            long s = Math.max(f[0], dayStartMs);
            long e = Math.min(f[1], dayEndMs);
            if (s < e) dayFree.add(new long[]{s, e});
        }
        dayFree.sort(Comparator.comparingLong(a -> a[0]));

        Day day = new Day();
        if (dayFree.isEmpty()) {
            day.closed = true;
            return day;
        }

        day.closed = false;
        day.startMin = (int) ((dayFree.get(0)[0] - dayStartMs) / 60000);
        day.endMin = (int) ((dayFree.get(dayFree.size() - 1)[1] - dayStartMs) / 60000);

        for (int i = 0; i < dayFree.size() - 1; i++) {
            long gapStart = dayFree.get(i)[1];
            long gapEnd = dayFree.get(i + 1)[0];
            if (gapStart < gapEnd) {
                day.breaks.add(new int[]{
                        (int) ((gapStart - dayStartMs) / 60000),
                        (int) ((gapEnd - dayStartMs) / 60000),
                });
            }
        }
        return day;
    }

    private static List<long[]> rawFreeRanges(Session session, String cn, Date windowStart, Date windowEnd, int minDurationMinutes) throws NotesException {
        DateTime dtStart = null;
        DateTime dtEnd = null;
        DateRange window = null;
        Vector<?> ranges;
        try {
            dtStart = session.createDateTime(windowStart);
            dtEnd = session.createDateTime(windowEnd);
            window = session.createDateRange(dtStart, dtEnd);
            ranges = session.freeTimeSearch(window, minDurationMinutes, cn, false);
        } finally {
            if (window != null) window.recycle();
            if (dtStart != null) dtStart.recycle();
            if (dtEnd != null) dtEnd.recycle();
        }

        List<long[]> result = new ArrayList<>();
        if (ranges == null) return result;

        for (Object o : ranges) {
            DateRange dr = (DateRange) o;
            DateTime s = null;
            DateTime e = null;
            try {
                s = dr.getStartDateTime();
                e = dr.getEndDateTime();
                long sMs = Math.max(s.toJavaDate().getTime(), windowStart.getTime());
                long eMs = Math.min(e.toJavaDate().getTime(), windowEnd.getTime());
                if (sMs < eMs) result.add(new long[]{sMs, eMs});
            } finally {
                if (s != null) s.recycle();
                if (e != null) e.recycle();
                dr.recycle();
            }
        }
        return result;
    }
}
