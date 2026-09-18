// Lo slug non viene più decodificato/validato lato client (non è più un
// base64 di un CN): è un identificativo opaco, l'ultimo segmento del path,
// e la sua validità la decide solo il server (.../api.xsp/week/<slug>).
export const slug = window.location.pathname.split('/').filter(Boolean).pop() || '';

const base = '/api.xsp';

async function json(res) {
  const contentType = res.headers.get('content-type') || '';
  if (!contentType.includes('application/json')) {
    throw new Error('Unexpected response from the server');
  }
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || 'Unexpected error');
  return data;
}

// full=true va richiesto solo alla prima chiamata: restituisce anche subject,
// nome, tipologie+durate e modello orario, da cachare lato client.
export function getWeek({ weekOffset = 0, full = false } = {}) {
  const qs = new URLSearchParams({ weekOffset: String(weekOffset) });
  if (full) qs.set('full', 'true');
  return fetch(`${base}/week/${slug}?${qs}`).then(json);
}

export function getCaptcha() {
  return fetch(`${base}/captcha`).then(json);
}

export function book(payload) {
  return fetch(`${base}/create/${slug}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }).then(json);
}
