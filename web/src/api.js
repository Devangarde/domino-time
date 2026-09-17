export const tenant = window.location.pathname.split('/').filter(Boolean)[0] || '';
const base = `/api/${tenant}`;

function base64UrlDecode(str) {
  const pad = str.length % 4 === 0 ? '' : '='.repeat(4 - (str.length % 4));
  const base64 = str.replace(/-/g, '+').replace(/_/g, '/') + pad;
  const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
  return new TextDecoder('utf-8').decode(bytes);
}

// Valida lo slug e ricava il nome da mostrare (tra "CN=" e la prima "/" successiva).
export function resolveTenant() {
  if (!tenant) {
    return { error: 'URL non valido: utente non specificato.' };
  }
  try {
    const cn = base64UrlDecode(tenant);
    const match = cn.match(/^CN=([^/]+)/);
    if (!match) throw new Error('formato non valido');
    return { name: match[1] };
  } catch {
    return { error: 'URL non valido: utente non valido.' };
  }
}

async function json(res) {
  const contentType = res.headers.get('content-type') || '';
  if (!contentType.includes('application/json')) {
    throw new Error('Risposta inattesa dal server (link non valido?)');
  }
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || 'Errore imprevisto');
  return data;
}

export function getConfig() {
  return fetch(`${base}/config`).then(json);
}

export function getAvailability(weekOffset = 0) {
  return fetch(`${base}/availability?weekOffset=${weekOffset}`).then(json);
}

export function getCaptcha() {
  return fetch(`${base}/captcha`).then(json);
}

export function book(payload) {
  return fetch(`${base}/book`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }).then(json);
}
