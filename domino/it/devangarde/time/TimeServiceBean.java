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
 * Base comune per le API legate a un professionista: risolve lo slug in path
 * (.../week/&lt;slug&gt;, .../create/&lt;slug&gt;), carica il documento Profilo dall'NSF
 * dell'applicazione (non dalla posta) e valida che esista e non sia sospeso.
 *
 * NOTA: nomi di form/campi assunti in base a quanto descritto a voce (Profilo:
 * Username, Mailfile, Slug, Sospeso, Frase; Tipologia: Nome, Descrizione,
 * Durate, come documenti response del Profilo). Vanno allineati ai nomi reali
 * una volta creati i form in Domino Designer.
 */
public abstract class TimeServiceBean extends ServiceBean {

    public static class AppointmentType {
        public String name;
        public String description;
        public List<Integer> durations = new ArrayList<>();
    }

    protected String slug;
    protected Document profileDoc;
    protected String username;      // canonical name, es. CN=Administrator/O=Sandbox
    protected String mailFilePath;  // es. mail\administ.nsf
    protected String greeting;
    protected List<AppointmentType> appointmentTypes = new ArrayList<>();

    private Database mailDb;
    private NotesCalendar calendar;

    /** Da chiamare come prima riga di get()/post() nelle sottoclassi. */
    protected void loadProfile() throws Exception {
        String pathInfo = this.request.getPathInfo();
        this.slug = (pathInfo == null) ? "" : pathInfo.replaceFirst("^/+", "").trim();
        if (this.slug.isEmpty()) {
            throw new BadRequestException("Professionista non specificato");
        }

        View bySlug = this.db.getView("BySlug");
        if (bySlug == null) {
            throw new IllegalStateException("Vista 'BySlug' non trovata nell'applicazione");
        }
        try {
            this.profileDoc = bySlug.getDocumentByKey(this.slug, true);
        } finally {
            bySlug.recycle();
        }
        if (this.profileDoc == null) {
            throw new BadRequestException("Professionista non trovato");
        }

        boolean suspended = "1".equals(this.profileDoc.getItemValueString("Sospeso"));
        if (suspended) {
            throw new BadRequestException("Prenotazioni temporaneamente sospese");
        }

        this.username = this.profileDoc.getItemValueString("Username");
        this.mailFilePath = this.profileDoc.getItemValueString("Mailfile");
        this.greeting = this.profileDoc.getItemValueString("Frase");
        if (this.greeting == null || this.greeting.isEmpty()) {
            this.greeting = "Prenota un appuntamento con me";
        }
        if (this.username == null || this.username.isEmpty() || this.mailFilePath == null || this.mailFilePath.isEmpty()) {
            throw new IllegalStateException("Profilo incompleto per slug " + this.slug);
        }

        loadAppointmentTypes();
    }

    /** Assume le tipologie come documenti response del profilo (form "TipoAppuntamento"). */
    private void loadAppointmentTypes() throws NotesException {
        DocumentCollection responses = this.profileDoc.getResponses();
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
                            // valore non numerico nel campo Durate: ignorato
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

    /** Apre come signer la mail del professionista (server locale, non multi-server per ora). */
    protected Database openMailDb() throws NotesException {
        if (this.mailDb == null) {
            this.mailDb = this.session.getDatabase(this.session.getServerName(), this.mailFilePath);
            if (this.mailDb == null || !this.mailDb.isOpen()) {
                throw new IllegalStateException("Impossibile aprire la mail: " + this.mailFilePath);
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
        try { if (this.profileDoc != null) this.profileDoc.recycle(); } catch (Exception ignored) {}
        super.close();
    }
}
