package it.devangarde.time;

import it.devangarde.BadRequestException;
import it.devangarde.ServiceBean;
import lotus.domino.Database;
import lotus.domino.DateTime;
import lotus.domino.Document;
import lotus.domino.NotesCalendar;
import lotus.domino.NotesException;
import lotus.domino.View;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Common base for the APIs tied to a user: resolves the slug from the
 * path (.../week/&lt;slug&gt;, .../create/&lt;slug&gt;), loads the User document from
 * the application's own NSF (not from the mail file), and validates that it
 * exists and is enabled.
 *
 * User document fields: Username, Mailfile, Slug, Enabled, Subject, plus
 * indexed appointment types (ApptName1/ApptDesc1/ApptMins1, ApptName2/...),
 * the optional advance-booking limit (AdvanceLimit checkbox + AdvanceDays
 * number) and the optional per-day working hours override (checkbox
 * Monday..Sunday + time range field TimeDispMonday..TimeDispSunday).
 */
public abstract class TimeServiceBean extends ServiceBean {

    public static class AppointmentType {
        public String name;
        public String description;
        public List<Integer> durations = new ArrayList<>();
    }

    protected String slug;
    protected Document userDoc;
    protected String username;      // canonical name, e.g. CN=Administrator/O=Sandbox
    protected String mailFilePath;  // e.g. mail\administ.nsf
    protected String subject;
    protected List<AppointmentType> appointmentTypes = new ArrayList<>();
    protected Integer advanceDays;  // null = no limit
    protected DayOverride[] overrides = new DayOverride[7]; // index 0 = Monday

    /** Working hours override of one weekday: ranges are local wall-clock minutes from midnight. */
    public static class DayOverride {
        public boolean active;
        public List<int[]> ranges = new ArrayList<>(); // each {startMin, endMin}, sorted
    }

    private static final String[] DAY_NAMES = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private TimeZone timeZone;

    private Database mailDb;
    private NotesCalendar calendar;
    private String userEmail;

    /** Call this as the first line of get()/post() in subclasses. */
    protected void loadProfile() throws Exception {
        this.slug = extractSlug(this.request.getPathInfo());
        if (this.slug.isEmpty()) {
            throw new BadRequestException("invalidUrl");
        }

        View users = this.db.getView("By Slug");
        if (users == null) {
            throw new IllegalStateException("usersViewNotFound");
        }
        try {
            this.userDoc = users.getDocumentByKey(this.slug, true);
        } finally {
            users.recycle();
        }
        if (this.userDoc == null) {
            throw new BadRequestException("userNotFound");
        }

        // Suspended when Enabled is empty (not when it holds "0"/false: it is
        // the mere presence of a value in Enabled that marks the profile as active).
        boolean suspended = this.userDoc.getItemValueString("Enabled").isEmpty();
        if (suspended) {
            throw new BadRequestException("bookingsSuspended");
        }

        this.username = this.userDoc.getItemValueString("Username");
        this.mailFilePath = this.userDoc.getItemValueString("Mailfile");
        this.subject = this.userDoc.getItemValueString("Subject");
        if (this.username == null || this.username.isEmpty() || this.mailFilePath == null || this.mailFilePath.isEmpty()) {
            throw new IllegalStateException("incompleteProfile");
        }

        loadAppointmentTypes();
        if (this.appointmentTypes.isEmpty()) {
            throw new BadRequestException("userNotConfigured");
        }

        loadAdvanceLimit();
        loadOverrides();
    }

    /** AdvanceLimit (checkbox, presence-based like Enabled) + AdvanceDays (number). */
    private void loadAdvanceLimit() throws NotesException {
        this.advanceDays = null;
        if (isChecked(this.userDoc, "AdvanceLimit")) {
            int days = this.userDoc.getItemValueInteger("AdvanceDays");
            if (days > 0) this.advanceDays = days;
        }
    }

    /**
     * First instant NOT bookable because of the advance limit: the start of
     * the day (in the user's time zone) after the last allowed one, where
     * today is day 0. Long.MAX_VALUE when there is no limit.
     */
    protected long advanceCutoffMs() throws NotesException {
        if (this.advanceDays == null) return Long.MAX_VALUE;
        Calendar cal = Calendar.getInstance(getTimeZone());
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, this.advanceDays + 1);
        return cal.getTimeInMillis();
    }

    private static boolean isChecked(Document doc, String field) throws NotesException {
        for (Object v : doc.getItemValue(field)) {
            if (v != null && !v.toString().trim().isEmpty()) return true;
        }
        return false;
    }

    /** Monday..Sunday (checkbox) + TimeDispMonday..TimeDispSunday (multi-value time range). */
    private void loadOverrides() throws NotesException {
        for (int i = 0; i < 7; i++) {
            DayOverride o = new DayOverride();
            o.active = isChecked(this.userDoc, DAY_NAMES[i]);
            if (o.active) o.ranges = readTimeRanges("TimeDisp" + DAY_NAMES[i]);
            this.overrides[i] = o;
        }
    }

    private static final Pattern TIME_OF_DAY = Pattern.compile("(\\d{1,2})[:.](\\d{2})(?:[:.]\\d{2})?\\s*([AaPp][Mm])?");

    /**
     * Reads a time range field as sorted {startMin, endMin} pairs (minutes
     * from midnight).
     *
     * The values are read with getItemValue (and only that: it is the only
     * way that works in practice) and come back as a plain flat list of
     * DateTime objects, not as DateRange objects. Ranges are therefore
     * deduced from the positions: 0 and 1 are the start and end of the first
     * range, 2 and 3 of the second, and so on. A field with an odd number of
     * values is ignored altogether; so are pairs with an unreadable time or
     * an end that is not after the start.
     */
    private List<int[]> readTimeRanges(String field) throws NotesException {
        List<int[]> out = new ArrayList<>();
        Vector<?> values = this.userDoc.getItemValue(field);
        if (values == null) return out;

        List<Integer> minutes = new ArrayList<>();
        for (Object o : values) {
            if (!(o instanceof DateTime)) continue; // e.g. empty field
            DateTime dt = (DateTime) o;
            try {
                minutes.add(parseTimeOfDay(dt.getTimeOnly()));
            } finally {
                dt.recycle();
            }
        }
        if (minutes.size() % 2 != 0) return out;

        for (int i = 0; i < minutes.size(); i += 2) {
            Integer start = minutes.get(i);
            Integer end = minutes.get(i + 1);
            if (start != null && end != null && end > start) {
                out.add(new int[]{start, end});
            }
        }
        out.sort(Comparator.comparingInt(a -> a[0]));
        return out;
    }

    /** "09:00:00", "9.00" or "9:00 AM" (locale dependent) -> minutes from midnight; null if not a time. */
    private static Integer parseTimeOfDay(String text) {
        if (text == null) return null;
        Matcher m = TIME_OF_DAY.matcher(text.trim());
        if (!m.find()) return null;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        String ampm = m.group(3);
        if (ampm != null) h = (h % 12) + (ampm.equalsIgnoreCase("pm") ? 12 : 0);
        return h * 60 + min;
    }

    /**
     * The time zone of the user, from the Timezone field of the
     * CalendarProfile in the mail file; the default zone of the server when
     * it is missing.
     */
    protected TimeZone getTimeZone() throws NotesException {
        if (this.timeZone == null) {
            TimeZone parsed = null;
            Document profile = openMailDb().getProfileDocument("CalendarProfile", "");
            if (profile != null) {
                try {
                    parsed = NotesTimeZone.parse(profile.getItemValueString("Timezone"));
                } finally {
                    profile.recycle();
                }
            }
            this.timeZone = (parsed != null) ? parsed : TimeZone.getDefault();
        }
        return this.timeZone;
    }

    /**
     * Override ranges of weekday d (0 = Monday) of the week starting at
     * monday, converted from local wall-clock minutes to minutes from that
     * day's UTC midnight (using the zone offset of that day, DST included).
     */
    protected List<int[]> overrideRangesUtcMinutes(int d, Date monday) throws NotesException {
        long dayStartMs = monday.getTime() + d * DAY_MS;
        int offset = WeekTemplate.offsetMinutes(getTimeZone(), dayStartMs + DAY_MS / 2);
        List<int[]> out = new ArrayList<>();
        for (int[] r : this.overrides[d].ranges) {
            out.add(new int[]{r[0] - offset, r[1] - offset});
        }
        return out;
    }

    /**
     * pathInfo is everything after the XPage itself, e.g. for
     * /api.xsp/week/&lt;slug&gt; pathInfo is "/week/&lt;slug&gt;": segment 0 is the
     * REST service name (week/create), segment 1 is the slug. Any further
     * segment (/week/&lt;slug&gt;/whatever) is ignored.
     */
    private static String extractSlug(String pathInfo) {
        if (pathInfo == null) return "";
        List<String> segments = new ArrayList<>();
        for (String p : pathInfo.split("/")) {
            if (!p.isEmpty()) segments.add(p);
        }
        return (segments.size() > 1) ? segments.get(1).trim() : "";
    }

    /**
     * Appointment types live directly on the User document as indexed
     * fields: ApptName1/ApptDesc1/ApptMins1, ApptName2/ApptDesc2/ApptMins2,
     * etc. ApptMins is a multi-value TEXT field (e.g. "30", "60", "120").
     * The loop stops at the first counter whose ApptName is empty.
     */
    private void loadAppointmentTypes() throws NotesException {
        int i = 1;
        while (true) {
            String name = this.userDoc.getItemValueString("ApptName" + i);
            if (name == null || name.isEmpty()) break;

            AppointmentType t = new AppointmentType();
            t.name = name;
            t.description = this.userDoc.getItemValueString("ApptDesc" + i);
            for (Object v : this.userDoc.getItemValue("ApptMins" + i)) {
                if (v instanceof Number) {
                    t.durations.add(((Number) v).intValue());
                } else if (v != null) {
                    try {
                        t.durations.add(Integer.parseInt(v.toString().trim()));
                    } catch (NumberFormatException ignored) {
                        // non-numeric value in ApptMins: ignored
                    }
                }
            }
            this.appointmentTypes.add(t);
            i++;
        }
    }

    /** Opens the user's mail as signer (local server only, no multi-server support yet). */
    protected Database openMailDb() throws NotesException {
        if (this.mailDb == null) {
            this.mailDb = this.session.getDatabase(this.session.getServerName(), this.mailFilePath);
            if (this.mailDb == null || !this.mailDb.isOpen()) {
                throw new IllegalStateException("mailFileNotOpened");
            }
        }
        return this.mailDb;
    }

    /**
     * Looks up the user's internet address in the Domino Directory
     * (names.nsf, view "($Users)", keyed by the canonical Username), field
     * InternetAddress. Needed for the iCalendar ORGANIZER line, since the
     * User profile document only stores the canonical name, not an email.
     */
    protected String getUserInternetAddress() throws NotesException {
        if (this.userEmail == null) {
            Database namesDb = this.session.getDatabase(this.session.getServerName(), "names.nsf");
            try {
                View usersView = namesDb.getView("($Users)");
                if (usersView == null) {
                    throw new IllegalStateException("directoryViewNotFound");
                }
                try {
                    Document person = usersView.getDocumentByKey(this.username, true);
                    if (person == null) {
                        throw new IllegalStateException("directoryUserNotFound");
                    }
                    try {
                        this.userEmail = person.getItemValueString("InternetAddress");
                    } finally {
                        person.recycle();
                    }
                } finally {
                    usersView.recycle();
                }
            } finally {
                namesDb.recycle();
            }
        }
        return this.userEmail;
    }

    /**
     * "CN=Administrator/O=Sandbox" -&gt; "Administrator/Sandbox": drops the
     * CN=/O=/OU= prefixes from each component. Matches the CN= parameter
     * format Domino itself uses for ORGANIZER/ATTENDEE in exported
     * iCalendar (verified against a real Notes-exported .ics).
     */
    protected static String shortName(String canonicalName) {
        if (canonicalName == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : canonicalName.split("/")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (sb.length() > 0) sb.append('/');
            sb.append(eq >= 0 ? trimmed.substring(eq + 1) : trimmed);
        }
        return sb.toString();
    }

    protected NotesCalendar getCalendar() throws NotesException {
        if (this.calendar == null) {
            this.calendar = this.session.getCalendar(openMailDb());
        }
        return this.calendar;
    }

    protected int minDuration() {
        int min = Integer.MAX_VALUE;
        for (AppointmentType t : this.appointmentTypes) {
            for (Integer d : t.durations) min = Math.min(min, d);
        }
        return min == Integer.MAX_VALUE ? 15 : min;
    }

    protected static final SimpleDateFormat ISO_UTC = newIsoUtcFormat();

    private static SimpleDateFormat newIsoUtcFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    /** Monday (00:00 UTC) of the week containing anchor. */
    protected static Date mondayOfWeekContaining(Date anchor) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(anchor);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        int day = cal.get(Calendar.DAY_OF_WEEK); // 1=Sunday..7=Saturday
        int diffToMonday = (day == Calendar.SUNDAY) ? -6 : (Calendar.MONDAY - day);
        cal.add(Calendar.DAY_OF_MONTH, diffToMonday);
        return cal.getTime();
    }

    /**
     * Availability (since/before/freeRanges) for the week containing
     * referenceDate, or the current week when referenceDate is null. Shared
     * by WeekServiceBean (browsing) and CreateServiceBean (returning the
     * updated week for the just-booked appointment's own date, in the same
     * response, instead of a separate follow-up call the client would
     * otherwise need to make).
     */
    @SuppressWarnings("unchecked")
	protected JSONObject buildAvailabilityJson(Date referenceDate) throws NotesException {
        Date monday = mondayOfWeekContaining(referenceDate != null ? referenceDate : new Date());
        Date before = new Date(monday.getTime() + 7L * 86400000);

        List<long[]> free = computeFreeRanges(monday);

        JSONArray freeRangesJson = new JSONArray();
        for (long[] r : free) {
            JSONObject rj = new JSONObject();
            rj.put("start", ISO_UTC.format(new Date(r[0])));
            rj.put("end", ISO_UTC.format(new Date(r[1])));
            freeRangesJson.add(rj);
        }

        JSONObject json = new JSONObject();
        json.put("since", ISO_UTC.format(monday));
        json.put("before", ISO_UTC.format(before));
        json.put("freeRanges", freeRangesJson);
        return json;
    }

    /**
     * Free intervals (UTC epoch ms) of the week starting at monday, from
     * Session.freeTimeSearch (which already accounts for the working hours
     * of the calendar profile and for the busy times).
     *
     * On the days with override, only the parts falling inside the ranges
     * set by the user are kept. Overrides are assumed to be a subset of the
     * working hours: whatever falls outside them is simply never free, and
     * no error is raised.
     */
    protected List<long[]> computeFreeRanges(Date monday) throws NotesException {
        List<long[]> free = new ArrayList<>(WeekTemplate.freeRangesForWeek(this.session, this.username, monday, minDuration()));

        for (int d = 0; d < 7; d++) {
            if (!this.overrides[d].active) continue;

            long dayStart = monday.getTime() + d * DAY_MS;
            long dayEnd = dayStart + DAY_MS;

            List<long[]> otherDays = new ArrayList<>();
            List<long[]> thisDay = new ArrayList<>();
            for (long[] r : free) {
                if (r[1] <= dayStart || r[0] >= dayEnd) {
                    otherDays.add(r);
                } else {
                    if (r[0] < dayStart) otherDays.add(new long[]{r[0], dayStart});
                    if (r[1] > dayEnd) otherDays.add(new long[]{dayEnd, r[1]});
                    thisDay.add(new long[]{Math.max(r[0], dayStart), Math.min(r[1], dayEnd)});
                }
            }

            List<long[]> allowed = new ArrayList<>();
            for (int[] r : overrideRangesUtcMinutes(d, monday)) {
                allowed.add(new long[]{dayStart + r[0] * 60000L, dayStart + r[1] * 60000L});
            }

            otherDays.addAll(WeekTemplate.intersect(thisDay, allowed));
            free = otherDays;
        }
        // Days beyond the advance limit simply have no availability.
        long cutoff = advanceCutoffMs();
        List<long[]> limited = new ArrayList<>();
        for (long[] r : free) {
            if (r[0] < cutoff) limited.add(new long[]{r[0], Math.min(r[1], cutoff)});
        }
        return WeekTemplate.merge(limited);
    }

    @Override
    protected void close() {
        try { if (this.calendar != null) this.calendar.recycle(); } catch (Exception ignored) {}
        try { if (this.mailDb != null) this.mailDb.recycle(); } catch (Exception ignored) {}
        try { if (this.userDoc != null) this.userDoc.recycle(); } catch (Exception ignored) {}
        super.close();
    }
}
