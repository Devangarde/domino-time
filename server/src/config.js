import 'dotenv/config';

function env(name, fallback) {
  const v = process.env[name] ?? fallback;
  if (v === undefined) throw new Error(`Missing required env var ${name}`);
  return v;
}

export const config = {
  dominoBaseUrl: env('DOMINO_BASE_URL', 'http://127.0.0.1'),
  smtpHost: env('DOMINO_SMTP_HOST', '127.0.0.1'),
  smtpPort: Number(env('DOMINO_SMTP_PORT', '25')),
  mailFrom: env('MAIL_FROM'),
  probeDate: env('PROBE_DATE', '2016-12-30'),
  timezone: env('TIMEZONE', 'Europe/Rome'),
  slotMinutes: Number(env('SLOT_MINUTES', '30')),
  subject: env('SUBJECT_TEXT', 'Prenota un appuntamento con me'),
  // Nella versione Domino/Java queste sono i documenti "TipoAppuntamento"
  // response del Profilo (Nome/Descrizione/Durate): qui hardcoded solo per
  // continuare a testare il frontend contro questo backend Node.
  appointmentTypes: [
    { name: 'Telefonata', description: 'Una chiamata telefonica', durations: [15, 30] },
    { name: 'Videochiamata', description: 'Il link di accesso arriva con l\'invito', durations: [15, 30, 60] },
    { name: 'Di persona', description: 'Incontro presso l\'ufficio', durations: [30, 60] },
  ],
  captchaSecret: env('CAPTCHA_SECRET'),
  port: Number(env('APP_PORT', '3000')),
};
