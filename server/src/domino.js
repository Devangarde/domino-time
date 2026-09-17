import nodemailer from 'nodemailer';
import { config } from './config.js';

function toISODateTime({ date, time }) {
  return `${date}T${time}Z`;
}

// Domino non accetta i millisecondi nell'ISO-8601 (Date#toISOString() li
// include sempre, es. "2026-09-17T00:00:00.000Z") e risponde con
// "Unparseable date": li rimuoviamo prima di ogni chiamata.
function toDominoTimestamp(iso) {
  return iso.replace(/\.\d{3}Z$/, 'Z');
}

export async function fetchBusyTimes(cn, sinceISO, beforeISO) {
  const url = new URL('/api/freebusy/busytime', config.dominoBaseUrl);
  url.searchParams.set('since', toDominoTimestamp(sinceISO));
  url.searchParams.set('before', toDominoTimestamp(beforeISO));
  url.searchParams.set('name', cn);

  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Errore Domino freebusy (${res.status})`);
  }
  const data = await res.json();
  return (data.busyTimes || []).map((bt) => ({
    start: toISODateTime(bt.start),
    end: toISODateTime(bt.end),
  }));
}

const transporter = nodemailer.createTransport({
  host: config.smtpHost,
  port: config.smtpPort,
  secure: false,
});

export async function sendBookingNotification({ cn, type, duration, start, name, email }) {
  const startLocal = new Date(start).toLocaleString('it-IT', { timeZone: 'Europe/Rome' });
  const typeLabel = type === 'video' ? 'Videochiamata' : 'Telefonata';

  await transporter.sendMail({
    from: config.mailFrom,
    to: cn,
    subject: `Nuova richiesta di appuntamento da ${name}`,
    text: [
      `Tipo: ${typeLabel}`,
      `Durata: ${duration} minuti`,
      `Data/ora richiesta: ${startLocal}`,
      `Richiedente: ${name} <${email}>`,
      '',
      "Crea la riunione in Notes con il cliente come invitato per inviare l'invito di conferma.",
    ].join('\n'),
  });
}
