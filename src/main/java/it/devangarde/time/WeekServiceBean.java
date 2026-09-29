package it.devangarde.time;

import it.devangarde.BadRequestException;
import lotus.domino.Database;
import lotus.domino.DateTime;
import lotus.domino.Document;
import lotus.domino.NotesException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * GET .../api.xsp/week/{slug}slug?date=ISO-8601&full=true
 *
 * date is any instant within the desired week (server resolves it to that
 * week's Monday..Sunday); omitted, it defaults to the current week. There is
 * no "week offset" concept: to browse, the client simply shifts the last
 * received `since` by &plusmn;7 days and passes that as `date`.
 *
 * full=true should be used only on the first call: it also returns the
 * profile's "static" data (subject, types+durations, weekly hour template)
 * that the client will cache in Vue state; subsequent calls (changing week)
 * can omit full and receive only the free intervals for the requested week.
 *
 * The time zone comes from the CalendarProfile of the user (see
 * TimeServiceBean.getTimeZone). The profile also carries "timeZone" (best
 * effort IANA id, null when unknown) and, when the limit is enabled,
 * "advanceDays".
 *
 * NOTE: PROBE_DATE here is a convenience constant; it should be moved to an
 * app-level (or per-profile) configuration instead of being hardcoded.
 */
public class WeekServiceBean extends TimeServiceBean {

    private static final String PROBE_DATE = "2016-12-30"; // arbitrary weekday, no known holiday

    private static final SimpleDateFormat DAY_FORMAT = newDayFormat();

    private static SimpleDateFormat newDayFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    @SuppressWarnings("unchecked")
    public void get() throws Exception {
        loadProfile();

        boolean full = "true".equalsIgnoreCase(this.queryString.get("full"));
        Date referenceDate = parseDateParam(this.queryString.get("date"));

        // Error only when the WHOLE week is beyond the advance limit; a week
        // that is partly inside is returned, its later days just have no
        // availability.
        Date monday = mondayOfWeekContaining(referenceDate != null ? referenceDate : new Date());
        if (monday.getTime() >= advanceCutoffMs()) {
            throw new BadRequestException("weekBeyondAdvanceLimit");
        }

        JSONObject availability = buildAvailabilityJson(referenceDate);
        this.body.put("since", availability.get("since"));
        this.body.put("before", availability.get("before"));
        this.body.put("freeRanges", availability.get("freeRanges"));

        if (full) {
            Date targetMonday = mondayOfWeekContaining(referenceDate != null ? referenceDate : new Date());
            this.body.put("profile", buildProfileJson(targetMonday));
        }
    }

    /** null (-> defaults to the current week) when absent or not a valid ISO-8601 instant. */
    private static Date parseDateParam(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Date.from(Instant.parse(raw));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildProfileJson(Date targetMonday) throws Exception {
        JSONObject profileJson = new JSONObject();
        profileJson.put("name", displayName(this.username));
        profileJson.put("org", displayOrg(this.username));
        profileJson.put("subject", this.subject);
		profileJson.put("hash", convertToMD5(getUserInternetAddress().toLowerCase()));

        JSONArray typesJson = new JSONArray();
        for (AppointmentType t : this.appointmentTypes) {
            JSONObject tj = new JSONObject();
            tj.put("name", t.name);
            tj.put("description", t.description);
            JSONArray durationsJson = new JSONArray();
            durationsJson.addAll(t.durations);
            tj.put("durations", durationsJson);
            typesJson.add(tj);
        }
        profileJson.put("types", typesJson);

        // minimum granularity (1 minute) so short boundaries/breaks are not
        // lost in the structural derivation: different from minDuration()
        // used for real availability, which instead reflects the minimum
        // bookable duration.
        Date probeMonday = mondayOfWeekContaining(DAY_FORMAT.parse(PROBE_DATE));
        TimeZone tz = getTimeZone();
        WeekTemplate.Day[] template = WeekTemplate.deriveTemplate(this.session, this.username, probeMonday, targetMonday, tz, 1);
        for (int d = 0; d < 7; d++) {
            if (this.overrides[d].active) {
                template[d] = WeekTemplate.dayFromRanges(overrideRangesUtcMinutes(d, targetMonday));
            }
        }
        profileJson.put("timeZone", NotesTimeZone.guessIanaId(tz));
        if (this.advanceDays != null) {
            profileJson.put("advanceDays", this.advanceDays);
        }

        JSONArray templateJson = new JSONArray();
        for (WeekTemplate.Day d : template) {
            JSONObject dj = new JSONObject();
            dj.put("closed", d.closed);
            if (!d.closed) {
                dj.put("startMin", d.startMin);
                dj.put("endMin", d.endMin);
                JSONArray breaksJson = new JSONArray();
                for (int[] b : d.breaks) {
                    JSONArray pair = new JSONArray();
                    pair.add(b[0]);
                    pair.add(b[1]);
                    breaksJson.add(pair);
                }
                dj.put("breaks", breaksJson);
            }
            templateJson.add(dj);
        }
        profileJson.put("template", templateJson);

        JSONObject outOfOffice = buildOutOfOfficeJson();
        if (outOfOffice != null) {
            profileJson.put("outOfOffice", outOfOffice);
        }

        return profileJson;
    }

    /**
     * Out of Office is not reflected by freeTimeSearch: it must be read and
     * applied by hand from the "OutOfOfficeProfile" profile document in the
     * user's own mail file. Active when CurrentStatus=1 AND BookBusyTime=1.
     * FirstDayOut/FirstDayBack give the absence date range; if ShowHours=1
     * (text), StartTime/EndTime narrow the first/last day to specific hours,
     * otherwise the absence covers those days in full.
     *
     * The computed start is rounded DOWN and the end rounded UP to the
     * nearest 30-minute slot boundary, so a booking can never straddle into
     * the absence: e.g. FirstDayOut 18/09 + StartTime 15:10 -> boundary
     * 18/09 15:00, so with a 30-minute duration the last bookable start that
     * day is 14:30.
     *
     * Returns null when Out of Office is not currently active.
     */
    @SuppressWarnings("unchecked")
    private JSONObject buildOutOfOfficeJson() throws NotesException {
        Database mailDb = openMailDb();
        Document ooo = mailDb.getProfileDocument("OutOfOfficeProfile", "");
        if (ooo == null) return null;
        try {
            if (!isOne(ooo, "CurrentStatus") || !isOne(ooo, "BookBusyTime")) return null;

            Date firstDayOut = firstDateTime(ooo, "FirstDayOut");
            Date firstDayBack = firstDateTime(ooo, "FirstDayBack");
            if (firstDayOut == null || firstDayBack == null) return null;

            TimeZone tz = getTimeZone();
            Date start;
            Date end;
            if (isOne(ooo, "ShowHours")) {
                Date startTime = firstDateTime(ooo, "StartTime");
                Date endTime = firstDateTime(ooo, "EndTime");
                start = roundToSlot(combineDateAndTime(firstDayOut, startTime, tz), 30, false, tz);
                end = roundToSlot(combineDateAndTime(firstDayBack, endTime, tz), 30, true, tz);
            } else {
                Calendar s = Calendar.getInstance(tz);
                s.setTime(firstDayOut);
                s.set(Calendar.HOUR_OF_DAY, 0);
                s.set(Calendar.MINUTE, 0);
                s.set(Calendar.SECOND, 0);
                s.set(Calendar.MILLISECOND, 0);
                start = s.getTime();

                Calendar e = Calendar.getInstance(tz);
                e.setTime(firstDayBack);
                e.add(Calendar.DAY_OF_MONTH, 1);
                e.set(Calendar.HOUR_OF_DAY, 0);
                e.set(Calendar.MINUTE, 0);
                e.set(Calendar.SECOND, 0);
                e.set(Calendar.MILLISECOND, 0);
                end = e.getTime();
            }

            JSONObject json = new JSONObject();
            json.put("start", ISO_UTC.format(start));
            json.put("end", ISO_UTC.format(end));
            return json;
        } finally {
            ooo.recycle();
        }
    }

    /** True if the (possibly multi-value) field holds "1", as text or as a number. */
    private static boolean isOne(Document doc, String field) throws NotesException {
        for (Object v : doc.getItemValue(field)) {
            if (v instanceof Number && ((Number) v).intValue() == 1) return true;
            if (v != null && "1".equals(v.toString().trim())) return true;
        }
        return false;
    }

    /** First value of a date/time field, as a java.util.Date, recycling the underlying DateTime object(s). */
    private static Date firstDateTime(Document doc, String field) throws NotesException {
        Vector<?> values = doc.getItemValueDateTimeArray(field);
        if (values == null || values.isEmpty()) return null;
        Date result = null;
        for (Object o : values) {
            DateTime dt = (DateTime) o;
            try {
                if (result == null) result = dt.toJavaDate();
            } finally {
                dt.recycle();
            }
        }
        return result;
    }

    /** Combines datePart's calendar date with timePart's time-of-day, in the given time zone. */
    private static Date combineDateAndTime(Date datePart, Date timePart, TimeZone tz) {
        Calendar dCal = Calendar.getInstance(tz);
        dCal.setTime(datePart);
        Calendar tCal = Calendar.getInstance(tz);
        tCal.setTime(timePart);

        Calendar out = Calendar.getInstance(tz);
        out.set(dCal.get(Calendar.YEAR), dCal.get(Calendar.MONTH), dCal.get(Calendar.DAY_OF_MONTH),
                tCal.get(Calendar.HOUR_OF_DAY), tCal.get(Calendar.MINUTE), 0);
        out.set(Calendar.MILLISECOND, 0);
        return out.getTime();
    }

    /** Rounds down (or up) to the nearest slotMinutes boundary, in the given time zone. */
    private static Date roundToSlot(Date d, int slotMinutes, boolean roundUp, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz);
        cal.setTime(d);
        boolean hasSubMinute = cal.get(Calendar.SECOND) != 0 || cal.get(Calendar.MILLISECOND) != 0;
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        int remainder = cal.get(Calendar.MINUTE) % slotMinutes;
        if (remainder == 0 && !hasSubMinute) return cal.getTime();

        cal.add(Calendar.MINUTE, roundUp ? (slotMinutes - remainder) : -remainder);
        return cal.getTime();
    }

    private static final Pattern CN_PATTERN = Pattern.compile("^CN=([^/]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern O_PATTERN = Pattern.compile("\\/O=([^/]+)", Pattern.CASE_INSENSITIVE);

    private static String displayName(String name) {
        if (name == null) return "";
        Matcher m = CN_PATTERN.matcher(name.trim());
        return m.find() ? m.group(1) : name;
    }
    
    private static String displayOrg(String name) {
        if (name == null) return "";
        Matcher m = O_PATTERN.matcher(name.trim());
        return m.find() ? m.group(1) : name;
    }

	private String convertToMD5(String input) {
        try {
            // 1. Get an MD5 MessageDigest instance
            MessageDigest md = MessageDigest.getInstance("MD5");
            
            // 2. Compute the hash into a byte array
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            
            // 3. Convert the byte array to a hexadecimal string
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
            
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("md5AlgorithmNotFound", e);
        }
    }
}
