<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue';
import { getWeek, getCaptcha, book } from './api.js';
import { useI18n } from 'vue-i18n'
import moment from 'moment';
import 'moment/dist/locale/it'

const { t, te, locale } = useI18n()

// The server and api.js report errors as fixed camelCase codes (see the
// "error" section of locales.json); anything unknown falls back to "unexpected".
function errorText(e) {
	const code = e && e.message;
	return code && te(`error.${code}`) ? t(`error.${code}`) : t('error.unexpected');
}

if (locale.value != 'en')
	moment.locale(locale.value);

// Nessuna validazione/decodifica dello slug lato client: è un identificativo
// opaco (vedi api.js), la sua validità la decide solo il server alla prima
// chiamata a /week. fatalError blocca l'intera pagina (link non valido,
// professionista non trovato, prenotazioni sospese); error è invece un
// errore "recuperabile" (es. cambio settimana fallito) mostrato in linea.
const fatalError = ref('');
const error = ref('');

const cfg = ref({ name: '', subject: '', types: [], template: [] });
const availability = ref(null);
const loading = ref(false);
const DAY_MS = 86400000;
// Lunedì (00:00 UTC) della settimana corrente, calcolato una sola volta: usato
// solo per disabilitare "settimana precedente" prima di questa settimana.
const thisWeekMondayMs = mondayOfWeekContaining(Date.now());

const form = ref({ type: '', duration: null, name: '', email: '', notes: '' });

// selectedSlot: { dayIndex, dayStartMs, m } — m = minuti dalla mezzanotte del giorno
const selectedSlot = ref(null);
const cardStep = ref('contact'); // contact -> done
const captcha = ref(null);
const captchaAnswer = ref('');
const captchaExpired = ref(false);
let captchaTimer = null;
const bookingError = ref('');
const booking = ref(false);
const cardEl = ref(null);

onMounted(async () => {
	try {
		const data = await getWeek({ full: true });
		applyProfile(data.profile);
		applyWeek(data);
	} catch (e) {
		fatalError.value = errorText(e);
	}
});

/** Lunedì (00:00 UTC) della settimana contenente anchorMs, stessa regola del server. */
function mondayOfWeekContaining(anchorMs) {
	const d = new Date(anchorMs);
	d.setUTCHours(0, 0, 0, 0);
	const day = d.getUTCDay(); // 0=domenica..6=sabato
	const diff = day === 0 ? -6 : 1 - day;
	d.setUTCDate(d.getUTCDate() + diff);
	return d.getTime();
}

const canGoBack = computed(() => !availability.value || Date.parse(availability.value.since) > thisWeekMondayMs);

async function loadWeek(dateIso) {
	loading.value = true;
	error.value = '';
	try {
		applyWeek(await getWeek({ date: dateIso }));
	} catch (e) {
		error.value = t('message.loadWeekError');
	} finally {
		loading.value = false;
		cancelSelection()
	}
}

function goToPreviousWeek() {
	loadWeek(new Date(Date.parse(availability.value.since) - 7 * DAY_MS).toISOString());
}

function goToNextWeek() {
	loadWeek(new Date(Date.parse(availability.value.since) + 7 * DAY_MS).toISOString());
}

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

function stopCaptchaTimer() {
	if (captchaTimer) {
		clearTimeout(captchaTimer);
		captchaTimer = null;
	}
}

// Carica un nuovo captcha e arma il timer che, alla scadenza (campo exp),
// disattiva l'immagine e propone il link di rigenerazione.
async function loadCaptcha() {
	stopCaptchaTimer();
	captchaExpired.value = false;
	captchaAnswer.value = '';
	const c = await getCaptcha();
	captcha.value = c;
	const remaining = Date.parse(c.exp) - Date.now();
	if (remaining <= 0) {
		captchaExpired.value = true;
	} else {
		captchaTimer = setTimeout(() => {
			captchaTimer = null;
			captchaExpired.value = true;
		}, remaining);
	}
}

async function refreshCaptcha() {
	try {
		await loadCaptcha();
	} catch (e) {
		bookingError.value = errorText(e);
	}
}

// Senza slot selezionato (selezione annullata) il timer non serve più.
watch(selectedSlot, (v) => {
	if (v === null) stopCaptchaTimer();
});
watch(cardStep, (v) => {
	if (v === 'done') stopCaptchaTimer();
});
onBeforeUnmount(stopCaptchaTimer);

async function selectSlot(dayIndex, dayStartMs, m) {
	selectedSlot.value = { dayIndex, dayStartMs, m };
	bookingError.value = '';
	form.value.name = '';
	form.value.email = '';
	form.value.notes = '';
	cardStep.value = 'contact';
	await loadCaptcha();
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
		const data = await book({
			start: new Date(slotRange.value.startMs).toISOString(),
			end: new Date(slotRange.value.endMs).toISOString(),
			type: form.value.type,
			name: form.value.name,
			email: form.value.email,
			notes: form.value.notes,
			captcha: { token: captcha.value.token, user: captchaAnswer.value },
		});
		cardStep.value = 'done';

		// /create restituisce già la settimana aggiornata (stessa forma di
		// /week, per la data dell'appuntamento appena creato): nessuna
		// seconda chiamata, ed evita di mostrare dati non aggiornati se
		// freeTimeSearch non riflette subito l'entry appena creata.
		applyWeek(data);
	} catch (e) {
		bookingError.value = errorText(e);
		await refreshCaptcha();
	} finally {
		booking.value = false;
	}
}

function fmtDayLong(ms) {
	return moment.utc(ms).format('dddd D MMMM YYYY');
}
function fmtTime(ms) {
	return new Date(ms).toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Rome' });
}
</script>

<template>
	<div class="site-header"></div>
	<main class="container">
		<p v-if="fatalError" class="error">{{ fatalError }}</p>

		<template v-else>
			<header class="app-header">
				<div class="who">
					<div class="avatar" aria-hidden="true" :style="{
						backgroundImage: `url('https://gravatar.com/avatar/${cfg.hash}?s=64')`
					}"></div>
					<div class="name">{{ cfg.name }}</div>
					<div class="subject">{{ cfg.subject }}</div>
				</div>
			</header>

			<p v-if="error" class="error">{{ error }}</p>

			<section class="selectors">
				<h6 class="selector-title">Tipo appuntamento</h6>
				<div>
					<button v-for="t in cfg.types" :key="t.name" type="button" class="type-card"
						:class="{ selected: form.type === t.name }" @click="selectType(t)">
						<span class="card-title">{{ t.name }}</span>
						<span v-if="t.description" class="card-desc">{{ t.description }}</span>
					</button>
				</div>

					<h6 class="selector-title">Durata</h6>
				<div v-if="selectedType">
					<button v-for="d in selectedType.durations" :key="d" type="button" class="duration-card"
						:class="{ selected: form.duration === d }" @click="form.duration = d">
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
					<button class="nav-arrow" :disabled="!canGoBack" @click="goToPreviousWeek"
						:aria-label="t('message.previousWeek')">
						<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
							stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
							<path d="M15 6l-6 6 6 6" />
						</svg>
					</button>
					<div v-for="(col, ci) in grid.columns" :key="'h' + ci" class="cal-head"
						:class="{ closed: col.closed }">
						<div class="weekday">{{ moment.utc(col.dayStartMs).format('ddd') }}</div>
						<div class="day">{{ moment.utc(col.dayStartMs).format('D') }}</div>
					</div>
					<button class="nav-arrow" @click="goToNextWeek" :aria-label="t('message.nextWeek')">
						<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
							stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
							<path d="M9 6l6 6-6 6" />
						</svg>
					</button>

					<template v-for="row in grid.rows" :key="row.m">
						<div></div>
						<button v-for="(cell, ci) in row.cells" :key="row.m + '-' + ci" class="cal-cell"
							:class="[cell.state, { active: isSlotActive(ci, row.m) }]" :disabled="cell.state !== 'free'"
							@click="selectSlot(ci, grid.columns[ci].dayStartMs, row.m)">
							{{ cell.state === 'free' ? row.label : '' }}
						</button>
						<div></div>
					</template>
				</div>
				<p v-else-if="!loading">{{ t('message.noOpenDays') }}</p>
			</article>

			<article v-if="selectedSlot" ref="cardEl" class="booking-card">
				<button class="close-x" :aria-label="t('message.cancel')" @click="cancelSelection">&times;</button>

				<form v-if="cardStep === 'contact'" @submit.prevent="confirmBooking">
					<p>
						<strong>{{ fmtDayLong(selectedSlot.dayStartMs) }}, {{ fmtTime(slotRange.startMs) }} - {{
							fmtTime(slotRange.endMs) }}</strong>
					</p>
					<label>{{ t('message.fullName') }}
						<input v-model="form.name" required autocomplete="name" />
					</label>
					<label>{{ t('message.email') }}
						<input type="email" v-model="form.email" required autocomplete="email" />
					</label>
					<label>{{ t('message.notes') }}
						<textarea v-model="form.notes" rows="3"></textarea>
					</label>
					<img v-if="captcha" class="captcha-img" :class="{ expired: captchaExpired }" :src="captcha.image"
						:alt="t('message.captchaAlt')" />
					<p v-if="captchaExpired" class="captcha-expired">
						<a href="#" @click.prevent="refreshCaptcha">{{ t('message.captchaExpired') }}</a>
					</p>
					<label>{{ t('message.captchaLabel') }}
						<input v-model="captchaAnswer" required autocomplete="off" :disabled="captchaExpired" />
					</label>
					<p v-if="bookingError" class="error">{{ bookingError }}</p>
					<button :aria-busy="booking" :disabled="captchaExpired" type="submit">{{ t('message.confirmRequest') }}</button>
				</form>

				<div v-else-if="cardStep === 'done'">
					<h3>{{ t('message.requestSent') }}</h3>
					<p v-html="t('message.requestSentDetail')"></p>
				</div>
			</article>

			<footer class="app-footer">Domino Time</footer>
		</template>
	</main>
</template>
