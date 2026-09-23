package it.devangarde.time;

import it.devangarde.BadRequestException;
import it.devangarde.ServiceBean;
import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.NotesCalendar;
import lotus.domino.NotesException;
import lotus.domino.View;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Common base for the APIs tied to a user: resolves the slug from the
 * path (.../week/&lt;slug&gt;, .../create/&lt;slug&gt;), loads the User document from
 * the application's own NSF (not from the mail file), and validates that it
 * exists and is enabled.
 *
 * User document fields: Username, Mailfile, Slug, Enabled, Subject, plus
 * indexed appointment types (ApptName1/ApptDesc1/ApptMins1, ApptName2/...).
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
        if (this.subject == null || this.subject.isEmpty()) {
            this.subject = "Book an appointment with me";
        }
        if (this.username == null || this.username.isEmpty() || this.mailFilePath == null || this.mailFilePath.isEmpty()) {
            throw new IllegalStateException("incompleteProfile");
        }

        loadAppointmentTypes();
        if (this.appointmentTypes.isEmpty()) {
            throw new BadRequestException("userNotConfigured");
        }
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
    protected JSONObject buildAvailabilityJson(Date referenceDate) throws NotesException {
        Date monday = mondayOfWeekContaining(referenceDate != null ? referenceDate : new Date());
        Date before = new Date(monday.getTime() + 7L * 86400000);

        List<long[]> free = WeekTemplate.freeRangesForWeek(this.session, this.username, monday, minDuration());

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

    @Override
    protected void close() {
        try { if (this.calendar != null) this.calendar.recycle(); } catch (Exception ignored) {}
        try { if (this.mailDb != null) this.mailDb.recycle(); } catch (Exception ignored) {}
        try { if (this.userDoc != null) this.userDoc.recycle(); } catch (Exception ignored) {}
        super.close();
    }
}
