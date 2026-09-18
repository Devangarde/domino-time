<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue';
import moment from 'moment';
import { getWeek, getCaptcha, book } from './api.js';

// Il side-effect import 'moment/locale/it' non è affidabile con il bundling
// di Vite/Rollup (può registrare la locale su un'istanza CJS diversa da
// questa): definiamo noi i nomi su questa stessa istanza di moment.
moment.updateLocale('it', {
  months: 'gennaio_febbraio_marzo_aprile_maggio_giugno_luglio_agosto_settembre_ottobre_novembre_dicembre'.split('_'),
  weekdays: 'domenica_lunedì_martedì_mercoledì_giovedì_venerdì_sabato'.split('_'),
});
moment.locale('it');

// Nessuna validazione/decodifica dello slug lato client: è un identificativo
// opaco (vedi api.js), la sua validità la decide solo il server alla prima
// chiamata a /week. fatalError blocca l'intera pagina (link non valido,
// professionista non trovato, prenotazioni sospese); error è invece un
// errore "recuperabile" (es. cambio settimana fallito) mostrato in linea.
const fatalError = ref('');
const error = ref('');

const cfg = ref({ name: '', subject: '', types: [], template: [] });
const weekOffset = ref(0);
const availability = ref(null);
const loading = ref(false);

const form = ref({ type: '', duration: null, name: '', email: '' });

// selectedSlot: { dayIndex, dayStartMs, m } — m = minuti dalla mezzanotte del giorno
const selectedSlot = ref(null);
const cardStep = ref('contact'); // contact -> done
const captcha = ref(null);
const captchaAnswer = ref('');
const bookingError = ref('');
const booking = ref(false);
const cardEl = ref(null);

onMounted(async () => {
  try {
    const data = await getWeek({ full: true });
    applyProfile(data.profile);
    applyWeek(data);
  } catch (e) {
    fatalError.value = e.message || 'Unable to load the page.';
  }
});

watch(weekOffset, async () => {
  loading.value = true;
  error.value = '';
  try {
    const data = await getWeek({ weekOffset: weekOffset.value });
    applyWeek(data);
  } catch (e) {
    error.value = 'Impossibile recuperare la disponibilità.';
  } finally {
    loading.value = false;
  }
});

function applyProfile(profile) {
  cfg.value = profile;
  const firstType = profile.types[0];
  if (firstType) {
    form.value.type = firstType.name;
    form.value.duration = firstType.durations[0];
  }
}

function applyWeek(data) {
  availability.value = { since: data.since, before: data.before, freeRanges: data.freeRanges };
}

const selectedType = computed(() => cfg.value.types.find((t) => t.name === form.value.type) || null);

function selectType(t) {
  form.value.type = t.name;
  if (!t.durations.includes(form.value.duration)) {
    form.value.duration = t.durations[0];
  }
}

function isFree(startMs, endMs, freeRanges) {
  return freeRanges.some((r) => Date.parse(r.start) <= startMs && Date.parse(r.end) >= endMs);
}

// L'assenza ufficio non è riflessa da freeTimeSearch: arriva cachata in cfg
// (letta una sola volta alla prima chiamata full=true) e va applicata a
// mano su ogni settimana, come i freeRanges.
function overlapsOutOfOffice(startMs, endMs) {
  const ooo = cfg.value.outOfOffice;
  if (!ooo) return false;
  return startMs < Date.parse(ooo.end) && endMs > Date.parse(ooo.start);
}

// breaks arriva come coppie [startMin, endMin], non oggetti: coerente col
// JSON prodotto da WeekTemplate.java lato server.
function inBreak(day, m, durationMin) {
  return day.breaks.some((b) => m < b[1] && m + durationMin > b[0]);
}

// Griglia stile "Google Calendar": righe = orari (dall'apertura più anticipata
// alla chiusura più tarda tra i giorni aperti), colonne = i 7 giorni della
// settimana. La durata è quella scelta sopra la griglia: cambiarla ricalcola
// quali slot risultano prenotabili.
const grid = computed(() => {
  if (!availability.value || !cfg.value.template.length) return { rows: [], columns: [] };
  const { since, freeRanges } = availability.value;
  const template = cfg.value.template;
  const sinceMs = Date.parse(since);
  const now = Date.now();
  // Senza una durata selezionabile (es. nessuna tipologia configurata) la
  // griglia va comunque disegnata: semplicemente ogni cella risulta non
  // prenotabile, invece di far sparire l'intero calendario.
  const duration = form.value.duration;

  const columns = template.map((day, i) => ({ dayStartMs: sinceMs + i * 86400000, closed: day.closed }));

  const openDays = template.filter((d) => !d.closed);
  if (!openDays.length) return { rows: [], columns };

  const rowStart = Math.min(...openDays.map((d) => d.startMin));
  const rowEnd = Math.max(...openDays.map((d) => d.endMin));

  const rows = [];
  for (let m = rowStart; m < rowEnd; m += 30) {
    const cells = template.map((day, i) => {
      if (day.closed) return { state: 'closed' };
      if (!duration) return { state: 'outside' };
      if (m < day.startMin || m + duration > day.endMin) return { state: 'outside' };
      if (inBreak(day, m, duration)) return { state: 'break' };

      const startMs = columns[i].dayStartMs + m * 60000;
      const endMs = startMs + duration * 60000;
      if (startMs < now) return { state: 'past' };
      if (overlapsOutOfOffice(startMs, endMs)) return { state: 'busy' };
      return isFree(startMs, endMs, freeRanges) ? { state: 'free' } : { state: 'busy' };
    });
    rows.push({ m, label: fmtTime(columns[0].dayStartMs + m * 60000), cells });
  }
  return { rows, columns };
});

const gridTemplateColumns = computed(() => `2.5rem repeat(${grid.value.columns.length}, 1fr) 2.5rem`);

function capitalize(s) {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

const monthYearLabel = computed(() => {
  if (!availability.value) return '';
  return capitalize(moment.utc(availability.value.since).format('MMMM YYYY'));
});

function tzOffsetLabel(date) {
  try {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Europe/Rome', timeZoneName: 'shortOffset' }).formatToParts(date);
    const tzName = parts.find((p) => p.type === 'timeZoneName');
    return tzName ? `${tzName.value} · Europe/Rome` : 'Europe/Rome';
  } catch {
    return 'Europe/Rome';
  }
}

const timezoneLabel = computed(() => tzOffsetLabel(availability.value ? new Date(availability.value.since) : new Date()));

const slotRange = computed(() => {
  if (!selectedSlot.value || !form.value.duration) return null;
  const startMs = selectedSlot.value.dayStartMs + selectedSlot.value.m * 60000;
  return { startMs, endMs: startMs + form.value.duration * 60000 };
});

// Con durata > 30 minuti, la selezione "fonde" visivamente tutte le celle di
// 30' comprese nell'intervallo scelto, così l'occupazione reale del tempo
// resta visibile invece di evidenziare solo lo slot di partenza.
function isSlotActive(dayIndex, m) {
  if (!selectedSlot.value || selectedSlot.value.dayIndex !== dayIndex) return false;
  const duration = form.value.duration || 30;
  return m >= selectedSlot.value.m && m < selectedSlot.value.m + duration;
}

async function selectSlot(dayIndex, dayStartMs, m) {
  selectedSlot.value = { dayIndex, dayStartMs, m };
  bookingError.value = '';
  form.value.name = '';
  form.value.email = '';
  captchaAnswer.value = '';
  captcha.value = await getCaptcha();
  cardStep.value = 'contact';
  await nextTick();
  cardEl.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function cancelSelection() {
  selectedSlot.value = null;
}

async function confirmBooking() {
  booking.value = true;
  bookingError.value = '';
  try {
    await book({
      start: new Date(slotRange.value.startMs).toISOString(),
      end: new Date(slotRange.value.endMs).toISOString(),
      type: form.value.type,
      name: form.value.name,
      email: form.value.email,
      captcha: { token: captcha.value.token, user: captchaAnswer.value },
    });
    cardStep.value = 'done';

    // Rinfresca la disponibilità: lo slot appena prenotato deve risultare
    // occupato in griglia. Best-effort: se fallisce non invalida la
    // prenotazione già andata a buon fine.
    try {
      applyWeek(await getWeek({ weekOffset: weekOffset.value }));
    } catch {
      // ignorato: la griglia resterà quella precedente fino al prossimo cambio settimana
    }
  } catch (e) {
    bookingError.value = e.message;
    captcha.value = await getCaptcha();
    captchaAnswer.value = '';
  } finally {
    booking.value = false;
  }
}

function fmtDay(ms) {
  return new Date(ms).toLocaleDateString('it-IT', { weekday: 'short', day: '2-digit', month: '2-digit', timeZone: 'UTC' });
}
function fmtDayLong(ms) {
  return moment.utc(ms).format('dddd D MMMM YYYY');
}
function fmtTime(ms) {
  return new Date(ms).toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Rome' });
}
</script>

<template>
  <main class="container">
    <p v-if="fatalError" class="error">{{ fatalError }}</p>

    <template v-else>
      <header class="app-header">
        <div class="who">
          <div class="avatar" aria-hidden="true">👤</div>
          <strong>{{ cfg.name }}</strong>
        </div>
        <div class="subject">{{ cfg.subject }}</div>
        <div></div>
      </header>

      <p v-if="error" class="error">{{ error }}</p>

      <section class="selectors">
        <div class="cards">
          <button
            v-for="t in cfg.types"
            :key="t.name"
            type="button"
            class="type-card"
            :class="{ selected: form.type === t.name }"
            @click="selectType(t)"
          >
            <span class="card-title">{{ t.name }}</span>
            <span v-if="t.description" class="card-desc">{{ t.description }}</span>
          </button>
        </div>

        <div v-if="selectedType" class="cards">
          <button
            v-for="d in selectedType.durations"
            :key="d"
            type="button"
            class="duration-card"
            :class="{ selected: form.duration === d }"
            @click="form.duration = d"
          >
            {{ d }} min
          </button>
        </div>
      </section>

      <article class="grid-card">
        <div class="grid-topbar">
          <div class="month-label">{{ monthYearLabel }}</div>
          <div class="tz-label">{{ timezoneLabel }}</div>
        </div>

        <div v-if="grid.rows.length" class="cal-grid" :style="{ gridTemplateColumns }">
          <button class="nav-arrow" :disabled="weekOffset === 0" @click="weekOffset--" aria-label="Settimana precedente">&lsaquo;</button>
          <div v-for="(col, ci) in grid.columns" :key="'h' + ci" class="cal-head" :class="{ closed: col.closed }">
            {{ fmtDay(col.dayStartMs) }}
          </div>
          <button class="nav-arrow" @click="weekOffset++" aria-label="Settimana successiva">&rsaquo;</button>

          <template v-for="row in grid.rows" :key="row.m">
            <div class="cal-time">{{ row.label }}</div>
            <button
              v-for="(cell, ci) in row.cells"
              :key="row.m + '-' + ci"
              class="cal-cell"
              :class="[cell.state, { active: isSlotActive(ci, row.m) }]"
              :disabled="cell.state !== 'free'"
              @click="selectSlot(ci, grid.columns[ci].dayStartMs, row.m)"
            ></button>
            <div></div>
          </template>
        </div>
        <p v-else-if="!loading">Nessun giorno aperto in questa settimana.</p>
      </article>

      <article v-if="selectedSlot" ref="cardEl" class="booking-card">
        <button class="close-x" aria-label="Annulla" @click="cancelSelection">&times;</button>

        <form v-if="cardStep === 'contact'" @submit.prevent="confirmBooking">
          <p>
            <strong>{{ fmtDayLong(selectedSlot.dayStartMs) }}, {{ fmtTime(slotRange.startMs) }} - {{ fmtTime(slotRange.endMs) }}</strong>
          </p>
          <label>Nome e cognome
            <input v-model="form.name" required />
          </label>
          <label>Email
            <input type="email" v-model="form.email" required />
          </label>
          <img v-if="captcha" class="captcha-img" :src="captcha.image" alt="captcha" />
          <label>Testo dell'immagine
            <input v-model="captchaAnswer" required autocomplete="off" />
          </label>
          <p v-if="bookingError" class="error">{{ bookingError }}</p>
          <button :aria-busy="booking" type="submit">Conferma richiesta</button>
        </form>

        <div v-else-if="cardStep === 'done'">
          <h3>Richiesta inviata</h3>
          <p>Riceverai a breve un invito calendario da <strong>confermare</strong> all'indirizzo indicato.</p>
        </div>
      </article>

      <footer class="app-footer">Domino Time</footer>
    </template>
  </main>
</template>

<style scoped>
.app-header {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 1rem;
  margin-block-end: 1.5rem;
}
.who {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}
.avatar {
  width: 2.2rem;
  height: 2.2rem;
  border-radius: 50%;
  background: var(--dt-selected);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.1rem;
  flex-shrink: 0;
}
.subject {
  text-align: center;
  font-size: 1.1rem;
}

.selectors {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;
  margin-block-end: 1.5rem;
}
.cards {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.type-card,
.duration-card {
  display: flex;
  flex-direction: column;
  text-align: left;
  background: transparent;
  color: var(--dt-text);
  border: 1px solid var(--pico-muted-border-color);
  border-radius: var(--pico-border-radius);
  padding: 0.5rem 0.9rem;
  cursor: pointer;
}
.card-title {
  font-weight: 500;
}
.card-desc {
  font-size: 0.8rem;
  color: var(--dt-muted);
}
.type-card.selected,
.duration-card.selected {
  background: var(--dt-selected);
  border-color: var(--dt-accent);
}

.grid-topbar {
  position: relative;
  margin-block-end: 1rem;
}
.month-label {
  text-align: center;
  font-weight: 600;
}
.tz-label {
  position: absolute;
  right: 0;
  top: 0;
  font-size: 0.75rem;
  color: var(--dt-muted);
}

.cal-grid {
  display: grid;
  gap: 3px;
  align-items: stretch;
}
.nav-arrow {
  background: none;
  border: none;
  font-size: 1.5rem;
  line-height: 1;
  color: var(--dt-text);
  cursor: pointer;
  padding: 0;
}
.nav-arrow:disabled {
  opacity: 0.3;
  cursor: default;
}
.cal-head {
  font-size: 0.75rem;
  text-align: center;
  padding-block: 0.35rem;
  font-weight: 600;
}
.cal-head.closed {
  opacity: 0.35;
}
.cal-time {
  font-size: 0.7rem;
  text-align: right;
  padding-right: 0.4rem;
  color: var(--dt-muted);
  white-space: nowrap;
  align-self: center;
}
.cal-cell {
  min-height: 1.6rem;
  padding: 0;
  margin: 0;
  border-radius: 4px;
  border: none;
  background: var(--dt-accent);
  cursor: pointer;
}
.cal-cell.closed,
.cal-cell.outside {
  background: transparent;
  cursor: default;
}
.cal-cell.break {
  background: repeating-linear-gradient(45deg, var(--pico-muted-border-color), var(--pico-muted-border-color) 4px, transparent 4px, transparent 8px);
  cursor: default;
}
.cal-cell.busy,
.cal-cell.past {
  background: var(--pico-muted-border-color);
  cursor: default;
}
.cal-cell.active {
  background: var(--dt-active);
  box-shadow: 0 0 0 2px var(--dt-text) inset;
}

.booking-card {
  margin-block-start: 2rem;
  position: relative;
}
.close-x {
  position: absolute;
  top: 0.5rem;
  right: 0.75rem;
  background: none;
  border: none;
  font-size: 1.5rem;
  line-height: 1;
  padding: 0;
  cursor: pointer;
}
.captcha-img {
  display: block;
  border-radius: var(--pico-border-radius);
}

.app-footer {
  text-align: center;
  color: var(--dt-muted);
  font-size: 0.85rem;
  margin-block: 2rem 1rem;
}

.error {
  color: var(--pico-del-color);
}
</style>
