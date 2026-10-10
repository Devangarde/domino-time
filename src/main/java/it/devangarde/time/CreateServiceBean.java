package it.devangarde.time;

import it.devangarde.BadRequestException;
import com.ibm.commons.util.io.json.JsonJavaObject;
import it.devangarde.captcha.CaptchaService;
import lotus.domino.Database;
import lotus.domino.DateTime;
import lotus.domino.Document;
import lotus.domino.RichTextItem;
import lotus.domino.NotesException;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
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
 * The appointment is written directly as an "Appointment" document in the
 * mail file of the user (see createDraft), with the same items as a draft
 * saved from Notes/Verse. The requester is only stored in EnterSendTo, so
 * nothing is sent until the user sends the invitation from Notes.
 *
 * WARNING (to verify with a real test before going to production): check
 * that the document behaves as a draft in Notes (editable, "Delete" instead
 * of "Cancel", no notice sent to the requester when deleted) and that it
 * can be sent later on.
 */
public class CreateServiceBean extends TimeServiceBean {

    /**
     * Strict typed reads: unlike JsonJavaObject.getString/getAsObject, a value
     * of the wrong JSON type counts as missing instead of being converted
     * (e.g. "name": 123 is rejected, not turned into "123").
     */
    private static Optional<String> getString(JsonJavaObject obj, String key) {
        Object v = obj.get(key);
        return (v instanceof String) ? Optional.of((String) v) : Optional.empty();
    }

    private static Optional<JsonJavaObject> getObject(JsonJavaObject obj, String key) {
        Object v = obj.get(key);
        return (v instanceof JsonJavaObject) ? Optional.of((JsonJavaObject) v) : Optional.empty();
    }

    public void post() throws Exception {
        loadProfile();

        if (this.payload == null) {
            throw new BadRequestException("missingData");
        }

        String startIso = getString(this.payload, "start").orElseThrow(() -> new BadRequestException("missingData"));
        String endIso = getString(this.payload, "end").orElseThrow(() -> new BadRequestException("missingData"));
        String type = getString(this.payload, "type").orElseThrow(() -> new BadRequestException("missingData"));
        String requesterName = getString(this.payload, "name").orElseThrow(() -> new BadRequestException("missingData"));
        String requesterEmail = getString(this.payload, "email").orElseThrow(() -> new BadRequestException("missingData"));
        String notes = getString(this.payload, "notes").orElse("");

        verifyCaptcha();

        Date start;
        Date end;
        try {
            start = Date.from(Instant.parse(startIso));
            end = Date.from(Instant.parse(endIso));
        } catch (Exception e) {
            throw new BadRequestException("invalidDateTime");
        }
        if (!start.before(end)) {
            throw new BadRequestException("invalidTimeRange");
        }

        if (start.getTime() >= advanceCutoffMs()) {
            throw new BadRequestException("beyondAdvanceLimit");
        }

        // Re-check the slot at confirmation time (avoids double bookings in
        // the window between loading the grid and clicking confirm), with
        // the very same availability logic used to draw the grid.
        if (!isSlotFree(start, end)) {
            throw new BadRequestException("slotNotAvailable");
        }

        Document docEntry = createDraft(start, end, type, requesterName, requesterEmail, notes);
        try {
            sendNotification(type, start, end, requesterName, requesterEmail, notes, docEntry);
        } finally {
            docEntry.recycle();
        }

        // Returns the updated week (same shape as WeekServiceBean) for the
        // appointment's own date directly in this response: saves the client
        // a separate follow-up /week call, which would also risk showing
        // stale data if freeTimeSearch/the scheduling task hasn't caught up
        // yet with the entry just created.
        JsonJavaObject availability = buildAvailabilityJson(start);
        this.body.put("since", availability.get("since"));
        this.body.put("before", availability.get("before"));
        this.body.put("freeRanges", availability.get("freeRanges"));

        this.body.put("ok", true);
    }

    private void verifyCaptcha() throws BadRequestException, NotesException {
        JsonJavaObject captchaObj = getObject(this.payload, "captcha")
                .orElseThrow(() -> new BadRequestException("captchaRequired"));
        String token = getString(captchaObj, "token")
                .orElseThrow(() -> new BadRequestException("captchaRequired"));
        String answer = getString(captchaObj, "user")
                .orElseThrow(() -> new BadRequestException("captchaRequired"));

        CaptchaService captchaService = new CaptchaService(getSalt());
        if (!captchaService.verify(token, answer)) {
            throw new BadRequestException("captchaFailed");
        }
    }

    private boolean isSlotFree(Date start, Date end) throws NotesException {
        if (!start.before(end)) return false;
        for (long[] r : computeFreeRanges(mondayOfWeekContaining(start))) {
            if (r[0] <= start.getTime() && r[1] >= end.getTime()) return true;
        }
        return false;
    }

    /**
     * Creates the appointment as a plain "Appointment" document in the mail
     * file of the user, with the items a draft made from Notes/Verse has,
     * instead of going through NotesCalendarEntry (whose entries are not
     * treated as drafts: e.g. deleting them sends cancellation notices).
     * The requester is only stored in EnterSendTo, so nothing is sent until
     * the user sends the invitation from Notes.
     */
    private Document createDraft(Date start, Date end, String type, String requesterName, String requesterEmail, String notes) throws NotesException {
        Document doc = openMailDb().createDocument();
        DateTime dtStart = null;
        DateTime dtEnd = null;
        RichTextItem body = null;
        boolean created = false;
        try {
            doc.replaceItemValue("Form", "Appointment");

            for (String field : new String[]{"$altPrincipal", "AltChair", "Chair", "From", "Principal"}) {
                doc.replaceItemValue(field, this.username);
            }
            doc.replaceItemValue("EnterSendTo", requesterEmail);

            dtStart = toNotesDateTime(start);
            dtEnd = toNotesDateTime(end);
            for (String field : new String[]{"CalendarDateTime", "StartDate", "StartTime", "StartDateTime"}) {
                doc.replaceItemValue(field, dtStart);
            }
            for (String field : new String[]{"EndDate", "EndTime", "EndDateTime"}) {
                doc.replaceItemValue(field, dtEnd);
            }

            String notesTimeZone = getNotesTimeZone();
            if (notesTimeZone != null) {
                for (String field : new String[]{"LocalTimeZone", "StartTimeZone", "EndTimeZone"}) {
                    doc.replaceItemValue(field, notesTimeZone);
                }
            }

            doc.replaceItemValue("$PublicAccess", "1");
            doc.replaceItemValue("Alarms", "0");
            doc.replaceItemValue("AppointmentType", "3");
            doc.replaceItemValue("DeliveryPriority", "N");
            doc.replaceItemValue("DeliveryReport", "B");
            doc.replaceItemValue("Encrypt", "0");
            doc.replaceItemValue("Importance", "2");
            Vector<String> excludeFromView = new Vector<>();
            excludeFromView.add("S");
            excludeFromView.add("D");
            doc.replaceItemValue("ExcludeFromView", excludeFromView);
            doc.replaceItemValue("Subject", type + " with " + requesterName);

            String text = (notes != null) ? notes.trim() : "";
            if (!text.isEmpty()) {
                body = doc.createRichTextItem("Body");
                body.appendText(text);
                body.update();
            }

            doc.replaceItemValue("ApptUNID", doc.getUniversalID());
			doc.replaceItemValue("SequenceNum", 1);
			doc.replaceItemValue("UpdateSeq", 1);
			doc.replaceItemValue("$CSVersion", "2");
            doc.save(true, false);

            created = true;
            return doc;
        } finally {
            if (body != null) body.recycle();
            if (dtStart != null) dtStart.recycle();
            if (dtEnd != null) dtEnd.recycle();
            if (!created) doc.recycle();
        }
    }

    /**
     * DateTime for the given instant. session.createDateTime(Date) reads the
     * date in the time zone of the JVM but builds the value in the one of
     * the Notes session: when the two differ (e.g. JVM on Europe/Rome and
     * Notes on GMT) the instant ends up shifted by the difference. The GMT
     * time of the result is therefore compared with the wanted instant and
     * the value is adjusted if needed. If the GMT text cannot be read the
     * value is left as created.
     */
    private DateTime toNotesDateTime(Date instant) throws NotesException {
        DateTime dt = this.session.createDateTime(instant);
        SimpleDateFormat gmtFormat = new SimpleDateFormat("M/d/yyyy h:mm:ss a", Locale.US);
        gmtFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date actual = gmtFormat.parse(dt.getGMTTime(), new ParsePosition(0));
        if (actual != null) {
            long diffSeconds = (instant.getTime() - actual.getTime()) / 1000;
            if (diffSeconds != 0) dt.adjustSecond((int) diffSeconds);
        }
        return dt;
    }

    /** "Fri 2 Oct 2026, 09:00 - 10:00 (+02:00)" in the time zone of the user. */
    private String formatRequestedTime(Date start, Date end) throws NotesException {
        TimeZone tz = getTimeZone();
        SimpleDateFormat first = new SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);
        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
        SimpleDateFormat offset = new SimpleDateFormat("XXX", Locale.ENGLISH);
        first.setTimeZone(tz);
        time.setTimeZone(tz);
        offset.setTimeZone(tz);
        return first.format(start) + " - " + time.format(end) + " (" + offset.format(start) + ")";
    }

    private void sendNotification(String type, Date start, Date end, String requesterName, String requesterEmail, String notes, Document docEntry) throws NotesException {
        Database mailDb = openMailDb();
        Document memo = mailDb.createDocument();
        RichTextItem body = null;
        try {
        	
        	String requesterNameAndEmail = 
        			requesterName + " <" + requesterEmail + ">";
            memo.replaceItemValue("Form", "Memo");
            memo.replaceItemValue("SendTo", this.username);
            memo.replaceItemValue("Subject", "New appointment request: " + type);
            memo.replaceItemValue("Principal", requesterName);
            memo.replaceItemValue("ReplyTo", requesterNameAndEmail);

            body = memo.createRichTextItem("Body");
            body.appendText("Type: " + type);
            body.addNewLine(1);
            body.appendText("Requested date/time: " + formatRequestedTime(start, end));
            body.addNewLine(1);
            body.appendText("Requester: " + requesterName + " <" + requesterEmail + ">");
            body.addNewLine(2);
            if (notes != null && !notes.trim().isEmpty()) {
                body.appendText("Notes:");
                body.addNewLine();
                body.appendText(notes.trim());
                body.addNewLine(2);
            }
            body.appendText("A draft has been created in your calendar: ");
            body.appendDocLink(docEntry, "Calendar entry");
            body.addNewLine(1);
            body.appendText("Nothing has been sent to the requester yet. Open the draft to review it, then send the invitation to confirm the appointment (or delete it to decline).");
            body.addNewLine(2);
            body.appendText("- Domino Time");
            body.addNewLine(1);
            body.update();

            memo.send(false);
        } finally {
        	if (body != null) body.recycle();
            memo.recycle();
        }
    }
}
