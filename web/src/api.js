// Lo slug non viene più decodificato/validato lato client (non è più un
// base64 di un CN): è un identificativo opaco, l'ultimo segmento del path,
// e la sua validità la decide solo il server (.../api.xsp/week/<slug>).
export const slug = window.location.pathname.split('/').filter(Boolean).pop() || '';

const base = '/api.xsp';

function dominoFetch(urlPart, options = {}) {
	const headers = { 'Content-Type': 'application/json; charset=UTF-8', ...options.headers };
	return fetch(`${base}${urlPart}`, { ...options, headers });
}

async function json(res) {
	const contentType = res.headers.get('content-type') || '';
	if (!contentType.includes('application/json')) {
		throw new Error('unexpectedResponse');
	}
	const data = await res.json();
	if (!res.ok) throw new Error(data.message || 'unexpected');
	return data;
}

// full=true va richiesto solo alla prima chiamata: restituisce anche subject,
// nome, tipologie+durate e modello orario, da cachare lato client.
// date: qualunque istante ISO-8601 dentro la settimana desiderata (il server
// calcola lunedì..domenica); omesso, il server usa la settimana corrente.
// Niente più weekOffset: per navigare, il client passa since±7 giorni.
export function getWeek({ date, full = false } = {}) {
	if (slug.length < 1) throw Error('missingUrlPart');
	const qs = new URLSearchParams();
	if (date) qs.set('date', date);
	if (full) qs.set('full', 'true');
	const query = qs.toString();
	return dominoFetch(`/week/${slug}${query ? `?${query}` : ''}`).then(json);
}

export function getCaptcha() {
	return dominoFetch('/captcha').then(json);
}

export function book(payload) {
	return dominoFetch(`/create/${slug}`, {
		method: 'POST',
		body: JSON.stringify(payload),
	}).then(json);
}
