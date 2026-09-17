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
 * Payload atteso:
 * { "start": "ISO-8601", "end": "ISO-8601", "type": "...", "name": "...",
 *   "email": "...", "captcha": { "token": "...", "user": "..." } }
 *
 * La durata non serve nel payload: si ricava da start/end.
 *
 * ATTENZIONE (da verificare con un test reale prima di andare in produzione):
 * - il formato ORGANIZER/ATTENDEE del VEVENT qui usato e' minimale (nessun
 *   ORGANIZER esplicito, ATTENDEE come mailto:); Domino potrebbe preferire un
 *   formato diverso per un attendee esterno o per una entry senza organizer.
 * - CS_WRITE_DISABLE_IMPLICIT_SCHEDULING dovrebbe creare la entry senza
 *   inviare subito le notice di invito (bozza); da confermare che il
 *   comportamento osservato corrisponda davvero a una bozza modificabile e
 *   "inviabile" in un secondo momento dal client Notes.
 */
public class CreateServiceBean extends TimeServiceBean {

    private static final SimpleDateFormat ICAL_UTC = newIcalFormat();

    private static SimpleDateFormat newIcalFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    /**
     * getString/getObject non esistono su it.devangarde.JSONObject (erano di
     * una superclass non più in uso): letture minime via .get(key), assumendo
     * che JSONObject sia una Map (come org.json.simple.JSONObject).
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
            throw new BadRequestException("Dati mancanti");
        }

        String startIso = getString(this.payload, "start").orElseThrow(() -> new BadRequestException("Dati mancanti"));
        String endIso = getString(this.payload, "end").orElseThrow(() -> new BadRequestException("Dati mancanti"));
        String type = getString(this.payload, "type").orElseThrow(() -> new BadRequestException("Dati mancanti"));
        String requesterName = getString(this.payload, "name").orElseThrow(() -> new BadRequestException("Dati mancanti"));
        String requesterEmail = getString(this.payload, "email").orElseThrow(() -> new BadRequestException("Dati mancanti"));

        verifyCaptcha();

        Date start;
        Date end;
        try {
            start = Date.from(Instant.parse(startIso));
            end = Date.from(Instant.parse(endIso));
        } catch (Exception e) {
            throw new BadRequestException("Data/ora non valida");
        }
        if (!start.before(end)) {
            throw new BadRequestException("Intervallo non valido");
        }

        // Ri-verifica lo slot al momento della conferma: stesso principio già
        // validato nella versione Node (evita doppie prenotazioni nella
        // finestra tra caricamento griglia e click di conferma), qui basato
        // su freeTimeSearch invece che sulla REST FreeBusy.
        if (!isSlotFree(start, end)) {
            throw new BadRequestException("Lo slot scelto non è più disponibile, ricarica la pagina.");
        }

        String uid = UUID.randomUUID().toString();
        String ical = buildIcalEvent(uid, start, end, type, requesterName, requesterEmail);

        NotesCalendar calendar = getCalendar();
        NotesCalendarEntry entry = calendar.createEntry(ical, NotesCalendar.CS_WRITE_DISABLE_IMPLICIT_SCHEDULING);
        entry.recycle();

        sendNotification(type, start, end, requesterName, requesterEmail);

        this.body.put("message", "Richiesta inviata. Il professionista la esaminerà e ti invierà l'invito da confermare.");
    }

    private void verifyCaptcha() throws BadRequestException {
        JSONObject captchaObj = getObject(this.payload, "captcha")
                .orElseThrow(() -> new BadRequestException("Verifica di sicurezza richiesta"));
        String token = getString(captchaObj, "token")
                .orElseThrow(() -> new BadRequestException("Verifica di sicurezza richiesta"));
        String answer = getString(captchaObj, "user")
                .orElseThrow(() -> new BadRequestException("Verifica di sicurezza richiesta"));

        CaptchaService captchaService = new CaptchaService("TODO"); // TODO
        if (!captchaService.verify(token, answer)) {
            throw new BadRequestException("Verifica di sicurezza fallita");
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
                + "SUMMARY:" + escape(type + " con " + requesterName) + "\r\n"
                + "DESCRIPTION:" + escape("Richiesta da " + requesterName + " <" + requesterEmail + ">") + "\r\n"
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
            memo.replaceItemValue("Subject", "Nuova richiesta di appuntamento da " + requesterName);

            StringBuilder body = new StringBuilder();
            body.append("Tipo: ").append(type).append('\n');
            body.append("Data/ora richiesta (UTC): ").append(start).append(" - ").append(end).append('\n');
            body.append("Richiedente: ").append(requesterName).append(" <").append(requesterEmail).append(">\n\n");
            body.append("È stata creata una bozza nel tuo calendario: apri Notes per rivederla e inviare l'invito al cliente.");
            memo.replaceItemValue("Body", body.toString());

            memo.send(false);
        } finally {
            memo.recycle();
        }
    }
}
