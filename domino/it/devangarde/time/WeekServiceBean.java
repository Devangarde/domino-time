package it.devangarde.time;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * GET .../api.xsp/week/&lt;slug&gt;?weekOffset=N&full=true
 *
 * full=true should be used only on the first call: it also returns the
 * profile's "static" data (subject, types+durations, weekly hour template)
 * that the client will cache in Vue state; subsequent calls (changing week)
 * can omit full and receive only the free intervals for the requested week.
 *
 * NOTE: PROBE_DATE here is a convenience constant; it should be moved to an
 * app-level (or per-profile) configuration instead of being hardcoded.
 */
public class WeekServiceBean extends TimeServiceBean {

    private static final String PROBE_DATE = "2016-12-30"; // arbitrary weekday, no known holiday

    private static final SimpleDateFormat ISO_UTC = newIsoFormat();
    private static final SimpleDateFormat DAY_FORMAT = newDayFormat();

    private static SimpleDateFormat newIsoFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static SimpleDateFormat newDayFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    public void get() throws Exception {
        loadProfile();

        boolean full = "true".equalsIgnoreCase(this.queryString.get("full"));
        int weekOffset = 0;
        try {
            String raw = this.queryString.get("weekOffset");
            if (raw != null) weekOffset = Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            // non-numeric weekOffset: stay on the current week
        }

        Date targetMonday = mondayOfWeek(new Date(), weekOffset);
        Date targetBefore = new Date(targetMonday.getTime() + 7L * 86400000);

        if (full) {
            this.body.put("profile", buildProfileJson());
        }

        int minAppointmentDuration = minDuration();
        List<long[]> free = WeekTemplate.freeRangesForWeek(this.session, this.username, targetMonday, minAppointmentDuration);

        JSONArray freeRangesJson = new JSONArray();
        for (long[] r : free) {
            JSONObject rj = new JSONObject();
            rj.put("start", ISO_UTC.format(new Date(r[0])));
            rj.put("end", ISO_UTC.format(new Date(r[1])));
            freeRangesJson.add(rj);
        }

        this.body.put("since", ISO_UTC.format(targetMonday));
        this.body.put("before", ISO_UTC.format(targetBefore));
        this.body.put("freeRanges", freeRangesJson);
    }

    private JSONObject buildProfileJson() throws Exception {
        JSONObject profileJson = new JSONObject();
        profileJson.put("subject", this.subject);

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
        Date probeMonday = mondayOfWeek(DAY_FORMAT.parse(PROBE_DATE), 0);
        WeekTemplate.Day[] template = WeekTemplate.deriveTemplate(this.session, this.username, probeMonday, 1);

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

        return profileJson;
    }

    /** Monday (00:00 UTC) of anchor's week, shifted by weekOffset weeks. */
    private static Date mondayOfWeek(Date anchor, int weekOffset) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(anchor);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        int day = cal.get(Calendar.DAY_OF_WEEK); // 1=Sunday..7=Saturday
        int diffToMonday = (day == Calendar.SUNDAY) ? -6 : (Calendar.MONDAY - day);
        cal.add(Calendar.DAY_OF_MONTH, diffToMonday + weekOffset * 7);
        return cal.getTime();
    }
}
