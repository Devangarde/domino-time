import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { config } from './config.js';
import { decodeTenant } from './tenant.js';
import { getWeekTemplate } from './weekTemplate.js';
import { fetchBusyTimes, sendBookingNotification } from './domino.js';
import { createCaptcha, verifyCaptcha } from './captcha.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const webDist = path.join(__dirname, '../../web/dist');

const app = express();
app.use(express.json());

function addDays(date, days) {
  const d = new Date(date);
  d.setUTCDate(d.getUTCDate() + days);
  return d;
}

function mondayOfCurrentWeek() {
  const d = new Date();
  d.setUTCHours(0, 0, 0, 0);
  const day = d.getUTCDay(); // 0=domenica..6=sabato
  const diffToMonday = day === 0 ? -6 : 1 - day;
  return addDays(d, diffToMonday);
}

app.get('/api/:tenant/config', (req, res) => {
  res.json({ subject: config.subject, types: config.appointmentTypes, slotMinutes: config.slotMinutes });
});

app.get('/api/:tenant/availability', async (req, res) => {
  try {
    const cn = decodeTenant(req.params.tenant);
    const weekOffset = Math.max(0, Number(req.query.weekOffset || 0));

    const since = addDays(mondayOfCurrentWeek(), weekOffset * 7);
    const before = addDays(since, 7);

    const [template, busyTimes] = await Promise.all([
      getWeekTemplate(cn, since),
      fetchBusyTimes(cn, since.toISOString(), before.toISOString()),
    ]);

    res.json({
      template,
      slotMinutes: config.slotMinutes,
      since: since.toISOString(),
      before: before.toISOString(),
      busyTimes,
    });
  } catch (err) {
    res.status(400).json({ message: err.message });
  }
});

app.get('/api/:tenant/captcha', (req, res) => {
  res.json(createCaptcha());
});

app.post('/api/:tenant/book', async (req, res) => {
  try {
    const cn = decodeTenant(req.params.tenant);
    const { start, end, type, duration, name, email, captchaToken, captchaAnswer } = req.body;

    if (!verifyCaptcha(captchaToken, captchaAnswer)) {
      return res.status(400).json({ message: 'Captcha non valido' });
    }
    if (!start || !end || !type || !duration || !name || !email) {
      return res.status(400).json({ message: 'Dati mancanti' });
    }

    // Ri-verifica lo slot al momento della conferma: evita doppie prenotazioni
    // nella finestra tra il caricamento della griglia e il click di conferma.
    // Controlla la sovrapposizione reale: alcuni server FreeBusy restituiscono
    // blocchi non perfettamente ritagliati sui bordi dell'intervallo richiesto.
    const startMs = Date.parse(start);
    const endMs = Date.parse(end);
    const busyTimes = await fetchBusyTimes(cn, start, end);
    const conflict = busyTimes.some((bt) => startMs < Date.parse(bt.end) && endMs > Date.parse(bt.start));
    if (conflict) {
      return res.status(409).json({ message: 'Lo slot scelto non è più disponibile, ricarica la pagina.' });
    }

    await sendBookingNotification({ cn, type, duration, start, name, email });
    res.json({ message: "Richiesta inviata. Riceverai un invito da confermare via email a breve." });
  } catch (err) {
    res.status(400).json({ message: err.message });
  }
});

// La root non identifica alcun professionista: niente SPA, una pagina d'errore esplicita.
app.get('/', (req, res) => {
  res.status(400).type('html').send(`<!doctype html>
<html lang="it">
<head>
  <meta charset="UTF-8" />
  <title>Link non valido</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css" />
</head>
<body>
  <main class="container">
    <p>URL non valido: utente non specificato.</p>
  </main>
</body>
</html>`);
});

app.use(express.static(webDist));
app.get('*', (req, res) => {
  res.sendFile(path.join(webDist, 'index.html'));
});

app.listen(config.port, () => {
  console.log(`domino-time in ascolto sulla porta ${config.port}`);
});
