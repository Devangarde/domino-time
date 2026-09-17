package it.devangarde.time;

import it.devangarde.BadRequestException;
import it.devangarde.ServiceBean;
import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.DocumentCollection;
import lotus.domino.NotesCalendar;
import lotus.domino.NotesException;
import lotus.domino.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Common base for the APIs tied to a professional: resolves the slug from the
 * path (.../week/&lt;slug&gt;, .../create/&lt;slug&gt;), loads the User document from
 * the application's own NSF (not from the mail file), and validates that it
 * exists and is enabled.
 *
 * NOTE: form/field names assumed from what was described verbally (User:
 * Username, Mailfile, Slug, Enabled, Subject; appointment type: Nome,
 * Descrizione, Durate, as response documents of the profile). These need to
 * be aligned with the real names once the forms are created in Domino
 * Designer.
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

    /** Call this as the first line of get()/post() in subclasses. */
    protected void loadProfile() throws Exception {
        this.slug = extractSlug(this.request.getPathInfo());
        if (this.slug.isEmpty()) {
            throw new BadRequestException("Invalid URL");
        }

        View users = this.db.getView("By Slug");
        if (users == null) {
            throw new IllegalStateException("View 'Users' not found in the application");
        }
        try {
            this.userDoc = users.getDocumentByKey(this.slug, true);
        } finally {
            users.recycle();
        }
        if (this.userDoc == null) {
            throw new BadRequestException("User not found");
        }

        // Suspended when Enabled is empty (not when it holds "0"/false: it is
        // the mere presence of a value in Enabled that marks the profile as active).
        boolean suspended = this.userDoc.getItemValueString("Enabled").isEmpty();
        if (suspended) {
            throw new BadRequestException("Bookings are temporarily suspended");
        }

        this.username = this.userDoc.getItemValueString("Username");
        this.mailFilePath = this.userDoc.getItemValueString("Mailfile");
        this.subject = this.userDoc.getItemValueString("Subject");
        if (this.subject == null || this.subject.isEmpty()) {
            this.subject = "Book an appointment with me";
        }
        if (this.username == null || this.username.isEmpty() || this.mailFilePath == null || this.mailFilePath.isEmpty()) {
            throw new IllegalStateException("Incomplete profile for slug " + this.slug);
        }

        loadAppointmentTypes();
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

    /** Assumes appointment types are response documents of the profile (form "TipoAppuntamento"). */
    private void loadAppointmentTypes() throws NotesException {
        DocumentCollection responses = this.userDoc.getResponses();
        try {
            Document doc = responses.getFirstDocument();
            while (doc != null) {
                AppointmentType t = new AppointmentType();
                t.name = doc.getItemValueString("Nome");
                t.description = doc.getItemValueString("Descrizione");
                for (Object v : doc.getItemValue("Durate")) {
                    if (v instanceof Number) {
                        t.durations.add(((Number) v).intValue());
                    } else if (v != null) {
                        try {
                            t.durations.add(Integer.parseInt(v.toString().trim()));
                        } catch (NumberFormatException ignored) {
                            // non-numeric value in the Durate field: ignored
                        }
                    }
                }
                this.appointmentTypes.add(t);

                Document next = responses.getNextDocument(doc);
                doc.recycle();
                doc = next;
            }
        } finally {
            responses.recycle();
        }
    }

    /** Opens the professional's mail as signer (local server only, no multi-server support yet). */
    protected Database openMailDb() throws NotesException {
        if (this.mailDb == null) {
            this.mailDb = this.session.getDatabase(this.session.getServerName(), this.mailFilePath);
            if (this.mailDb == null || !this.mailDb.isOpen()) {
                throw new IllegalStateException("Unable to open mail file: " + this.mailFilePath);
            }
        }
        return this.mailDb;
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

    @Override
    protected void close() {
        try { if (this.calendar != null) this.calendar.recycle(); } catch (Exception ignored) {}
        try { if (this.mailDb != null) this.mailDb.recycle(); } catch (Exception ignored) {}
        try { if (this.userDoc != null) this.userDoc.recycle(); } catch (Exception ignored) {}
        super.close();
    }
}
