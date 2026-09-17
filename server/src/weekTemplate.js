import { fetchBusyTimes } from './domino.js';
import { config } from './config.js';

function mondayOfWeekContaining(dateStr) {
  const d = new Date(`${dateStr}T00:00:00Z`);
  const day = d.getUTCDay(); // 0=domenica..6=sabato
  const diff = day === 0 ? -6 : 1 - day;
  d.setUTCDate(d.getUTCDate() + diff);
  return d;
}

function mergeIntervals(intervals) {
  const sorted = [...intervals].sort((a, b) => a.start - b.start);
  const merged = [];
  for (const iv of sorted) {
    const last = merged[merged.length - 1];
    if (last && iv.start <= last.end) last.end = Math.max(last.end, iv.end);
    else merged.push({ ...iv });
  }
  return merged;
}

// Offset (in minuti) di config.timezone rispetto a UTC, calcolato a
// mezzogiorno UTC del giorno indicato: un orario di lavoro non è mai così
// vicino alla mezzanotte da rendere ambiguo il calcolo nel giorno del cambio
// ora legale/solare.
function offsetMinutesForDay(dayStartMs) {
  const noonUtc = new Date(dayStartMs + 12 * 3600000);
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone: config.timezone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
  const p = Object.fromEntries(dtf.formatToParts(noonUtc).map((x) => [x.type, x.value]));
  const asUtc = Date.UTC(Number(p.year), Number(p.month) - 1, Number(p.day), Number(p.hour), Number(p.minute), Number(p.second));
  return Math.round((asUtc - noonUtc.getTime()) / 60000);
}

// Analizza il busytime di una settimana e per ciascun giorno determina se è
// chiuso, e in caso contrario l'orario (in minuti UTC dalla mezzanotte di
// QUEL giorno) e le eventuali pause interne (es. pausa pranzo).
function analyzeWeek(weekStartMs, busyIntervals) {
  const days = [];
  for (let d = 0; d < 7; d++) {
    const dayStartMs = weekStartMs + d * 86400000;
    const dayEndMs = dayStartMs + 86400000;

    const clipped = busyIntervals
      .map((iv) => ({ start: Math.max(iv.start, dayStartMs), end: Math.min(iv.end, dayEndMs) }))
      .filter((iv) => iv.start < iv.end);
    const merged = mergeIntervals(clipped);

    if (merged.length === 1 && merged[0].start === dayStartMs && merged[0].end === dayEndMs) {
      days.push({ closed: true });
      continue;
    }

    let startMin = 0;
    let endMin = 1440;
    const breaks = [];
    for (const iv of merged) {
      const s = Math.round((iv.start - dayStartMs) / 60000);
      const e = Math.round((iv.end - dayStartMs) / 60000);
      if (s === 0) startMin = e;
      else if (e === 1440) endMin = s;
      else breaks.push({ startMin: s, endMin: e });
    }

    days.push(startMin < endMin ? { closed: false, startMin, endMin, breaks, dayStartMs } : { closed: true });
  }
  return days;
}

// Converte startMin/endMin/breaks da minuti UTC (del giorno di riferimento)
// a minuti di orario LOCALE: l'orario di lavoro è una regola definita in ora
// locale (es. "9-18"), non in UTC, e va trattata come tale per restare
// corretta indipendentemente da quale ora legale/solare valeva nella
// settimana di riferimento (PROBE_DATE).
function toLocalMinutes(days) {
  return days.map((day) => {
    if (day.closed) return day;
    const offset = offsetMinutesForDay(day.dayStartMs);
    return {
      closed: false,
      startMin: day.startMin + offset,
      endMin: day.endMin + offset,
      breaks: day.breaks.map((b) => ({ startMin: b.startMin + offset, endMin: b.endMin + offset })),
    };
  });
}

// Converte il modello in ora locale nei minuti UTC validi per la settimana
// effettivamente richiesta, usando l'offset di QUELLA settimana: è qui che si
// evita lo sfasamento di un'ora quando PROBE_DATE e la settimana visualizzata
// cadono in stagioni diverse (ora legale vs solare).
function toUtcMinutesForWeek(localDays, targetWeekStartMs) {
  return localDays.map((day, i) => {
    if (day.closed) return { closed: true };
    const dayStartMs = targetWeekStartMs + i * 86400000;
    const offset = offsetMinutesForDay(dayStartMs);
    return {
      closed: false,
      startMin: day.startMin - offset,
      endMin: day.endMin - offset,
      breaks: day.breaks.map((b) => ({ startMin: b.startMin - offset, endMin: b.endMin - offset })),
    };
  });
}

// Deduce il modello settimanale (giorni di apertura, orari, pause) interrogando
// in un'unica chiamata il busytime dell'intera settimana (lun-dom) contenente
// PROBE_DATE, poi lo adatta alla settimana effettivamente richiesta (targetWeekStart)
// tenendo conto dell'eventuale cambio ora legale/solare fra le due. Nessuna
// cache: va richiamata ad ogni apertura della SPA.
export async function getWeekTemplate(cn, targetWeekStart) {
  const probeWeekStart = mondayOfWeekContaining(config.probeDate);
  const probeWeekStartMs = probeWeekStart.getTime();
  const probeWeekEndMs = probeWeekStartMs + 7 * 86400000;

  const busyTimes = await fetchBusyTimes(cn, new Date(probeWeekStartMs).toISOString(), new Date(probeWeekEndMs).toISOString());
  const busyIntervals = busyTimes.map((bt) => ({ start: Date.parse(bt.start), end: Date.parse(bt.end) }));

  const rawDays = analyzeWeek(probeWeekStartMs, busyIntervals);
  const localDays = toLocalMinutes(rawDays);
  return toUtcMinutesForWeek(localDays, targetWeekStart.getTime());
}
