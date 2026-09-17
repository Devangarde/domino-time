package it.devangarde.time;

import it.devangarde.BadRequestException;
import org.json.simple.JSONObject;
import it.devangarde.captcha.CaptchaService;
import lotus.domino.Database;
import lotus.domino.DateRange;
import lotus.domino.DateTime;
import lotus.domino.Document;
import lotus.domino.NotesCalendar;
import lotus.domino.NotesCalendarEntry;
import lotus.domino.NotesException;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Vector;

/**
 * POST .../api.xsp/create/&lt;slug&gt;
 *
 * Expected payload:
 * { "start": "ISO-8601", "end": "ISO-8601", "type": "...", "name": "...",
 *   "email": "...", "captcha": { "token": "...", "user": "..." } }
 *
 * Duration is not needed in the payload: it is derived from start/end.
 *
 * WARNING (to verify with a real test before going to production):
 * - the ORGANIZER/ATTENDEE format of the VEVENT used here is minimal (no
 *   explicit ORGANIZER, ATTENDEE as mailto:); Domino might prefer a
 *   different format for an external attendee or for an entry with no
 *   organizer.
 * - CS_WRITE_DISABLE_IMPLICIT_SCHEDULING should create the entry without
 *   immediately sending invite notices (draft); needs to be confirmed that
 *   the observed behavior really corresponds to a draft that is editable
 *   and can be "sent" later on from the Notes client.
 */
public class CreateServiceBean extends TimeServiceBean {

    private static final SimpleDateFormat ICAL_UTC = newIcalFormat();

    private static SimpleDateFormat newIcalFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    /**
     * getString/getObject do not exist on it.devangarde.JSONObject (they
     * belonged to a superclass no longer in use): minimal reads via
     * .get(key), assuming JSONObject is a Map (like org.json.simple.JSONObject).
     */
    private static Optional<String> getString(JSONObject obj, String key) {
        Object v = obj.get(key);
        return (v instanceof String) ? Optional.of((String) v) : Optional.empty();
    }

    private static Optional<JSONObject> getObject(JSONObject obj, String key) {
        Object v = obj.get(key);
        return (v instanceof JSONObject) ? Optional.of((JSONObject) v) : Optional.empty();
    }

    public void post() throws Exception {
        loadProfile();

        if (this.payload == null) {
            throw new BadRequestException("Missing data");
        }

        String startIso = getString(this.payload, "start").orElseThrow(() -> new BadRequestException("Missing data"));
        String endIso = getString(this.payload, "end").orElseThrow(() -> new BadRequestException("Missing data"));
        String type = getString(this.payload, "type").orElseThrow(() -> new BadRequestException("Missing data"));
        String requesterName = getString(this.payload, "name").orElseThrow(() -> new BadRequestException("Missing data"));
        String requesterEmail = getString(this.payload, "email").orElseThrow(() -> new BadRequestException("Missing data"));

        verifyCaptcha();

        Date start;
        Date end;
        try {
            start = Date.from(Instant.parse(startIso));
            end = Date.from(Instant.parse(endIso));
        } catch (Exception e) {
            throw new BadRequestException("Invalid date/time");
        }
        if (!start.before(end)) {
            throw new BadRequestException("Invalid time range");
        }

        // Re-check the slot at confirmation time: same principle already
        // validated in the Node version (avoids double bookings in the
        // window between loading the grid and clicking confirm), here based
        // on freeTimeSearch instead of the REST FreeBusy.
        if (!isSlotFree(start, end)) {
            throw new BadRequestException("The chosen slot is no longer available, please reload the page.");
        }

        String uid = UUID.randomUUID().toString();
        String ical = buildIcalEvent(uid, start, end, type, requesterName, requesterEmail);

        NotesCalendar calendar = getCalendar();
        NotesCalendarEntry entry = calendar.createEntry(ical, NotesCalendar.CS_WRITE_DISABLE_IMPLICIT_SCHEDULING);
        entry.recycle();

        sendNotification(type, start, end, requesterName, requesterEmail);

        this.body.put("message", "Request sent. The professional will review it and send you an invite to confirm.");
    }

    private void verifyCaptcha() throws BadRequestException {
        JSONObject captchaObj = getObject(this.payload, "captcha")
                .orElseThrow(() -> new BadRequestException("Security check required"));
        String token = getString(captchaObj, "token")
                .orElseThrow(() -> new BadRequestException("Security check required"));
        String answer = getString(captchaObj, "user")
                .orElseThrow(() -> new BadRequestException("Security check required"));

        CaptchaService captchaService = new CaptchaService("TODO"); // TODO
        if (!captchaService.verify(token, answer)) {
            throw new BadRequestException("Security check failed");
        }
    }

    private boolean isSlotFree(Date start, Date end) throws NotesException {
        int minutes = (int) ((end.getTime() - start.getTime()) / 60000);
        if (minutes <= 0) return false;

        DateTime dtStart = null;
        DateTime dtEnd = null;
        DateRange window = null;
        Vector<?> ranges;
        try {
            dtStart = this.session.createDateTime(start);
            dtEnd = this.session.createDateTime(end);
            window = this.session.createDateRange(dtStart, dtEnd);
            ranges = this.session.freeTimeSearch(window, minutes, this.username, false);
        } finally {
            if (window != null) window.recycle();
            if (dtStart != null) dtStart.recycle();
            if (dtEnd != null) dtEnd.recycle();
        }

        if (ranges == null) return false;
        for (Object o : ranges) {
            DateRange dr = (DateRange) o;
            DateTime s = null;
            DateTime e = null;
            try {
                s = dr.getStartDateTime();
                e = dr.getEndDateTime();
                if (s.toJavaDate().getTime() <= start.getTime() && e.toJavaDate().getTime() >= end.getTime()) {
                    return true;
                }
            } finally {
                if (s != null) s.recycle();
                if (e != null) e.recycle();
                dr.recycle();
            }
        }
        return false;
    }

    private String buildIcalEvent(String uid, Date start, Date end, String type, String requesterName, String requesterEmail) {
        String dtStamp = ICAL_UTC.format(new Date());
        String dtStart = ICAL_UTC.format(start);
        String dtEnd = ICAL_UTC.format(end);

        return "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//devangarde//domino-time//IT\r\n"
                + "METHOD:PUBLISH\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:" + uid + "\r\n"
                + "DTSTAMP:" + dtStamp + "\r\n"
                + "DTSTART:" + dtStart + "\r\n"
                + "DTEND:" + dtEnd + "\r\n"
                + "SUMMARY:" + escape(type + " with " + requesterName) + "\r\n"
                + "DESCRIPTION:" + escape("Request from " + requesterName + " <" + requesterEmail + ">") + "\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n");
    }

    private void sendNotification(String type, Date start, Date end, String requesterName, String requesterEmail) throws NotesException {
        Database mailDb = openMailDb();
        Document memo = mailDb.createDocument();
        try {
            memo.replaceItemValue("Form", "Memo");
            memo.replaceItemValue("SendTo", this.username);
            memo.replaceItemValue("Subject", "New appointment request from " + requesterName);

            StringBuilder body = new StringBuilder();
            body.append("Type: ").append(type).append('\n');
            body.append("Requested date/time (UTC): ").append(start).append(" - ").append(end).append('\n');
            body.append("Requester: ").append(requesterName).append(" <").append(requesterEmail).append(">\n\n");
            body.append("A draft has been created in your calendar: open Notes to review it and send the invite to the client.");
            memo.replaceItemValue("Body", body.toString());

            memo.send(false);
        } finally {
            memo.recycle();
        }
    }
}
