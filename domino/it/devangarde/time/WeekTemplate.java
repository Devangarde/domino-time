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
 * Deduce il modello settimanale (giorni di apertura, orari, pause) a partire
 * dagli intervalli LIBERI restituiti da Session.freeTimeSearch, invece che dai
 * blocchi occupati come nella versione REST/Node del progetto: qui il primo e
 * l'ultimo intervallo libero del giorno definiscono apertura/chiusura, i vuoti
 * tra un intervallo libero e il successivo sono le pause (es. pranzo).
 *
 * ATTENZIONE (da verificare contro un server Domino reale prima di fidarsi):
 * - la documentazione HCL non specifica esplicitamente se freeTimeSearch
 *   ritaglia gli intervalli liberi sui bordi della finestra richiesta; qui lo
 *   facciamo comunque manualmente per sicurezza, ma se il comportamento reale
 *   fosse diverso (es. nessun risultato quando un giorno è parzialmente fuori
 *   finestra) l'algoritmo andrebbe rivisto.
 * - nessuna correzione esplicita per il cambio ora legale/solare: DateTime è
 *   nativamente timezone-aware, ma se emergesse lo stesso sfasamento di un'ora
 *   già risolto nella versione Node (settimana di riferimento e settimana
 *   visualizzata in stagioni diverse), va aggiunta qui la stessa correzione
 *   basata sull'offset calcolato giorno per giorno.
 */
public class WeekTemplate {

    public static class Day {
        public boolean closed;
        public int startMin;
        public int endMin;
        public List<int[]> breaks = new ArrayList<>(); // ciascuno {startMin, endMin}
    }

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** Modello dei 7 giorni (indice 0 = lunedì di probeMonday) dedotto dalla settimana di riferimento. */
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

    /** Intervalli liberi (ms assoluti, UTC epoch) nella settimana richiesta, per il controllo di disponibilità reale. */
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
