# domino-time

MVP di prenotazione appuntamenti (telefonata/videochiamata) integrato con HCL Domino via `/api/freebusy/busytime` (anonymous). Nessun database, nessuna scrittura su Domino: la conferma invia un'email di notifica via SMTP diretto al server Domino; l'evento va creato manualmente in Notes come Meeting (solo le Meeting risultano "busy" nel free/busy).

## Struttura

- `server/` — API Express (freebusy, orario di lavoro, captcha, invio email)
- `web/` — SPA Vite + Vue 3

## URL multi-tenant

```
https://time.miaazienda.it/<base64url(CN=Nome Cognome/O=TuaOrg)>
```

Genera lo slug con:

```bash
node -e "console.log(Buffer.from('CN=Nome Cognome/O=TuaOrg').toString('base64url'))"
```

## Sviluppo

```bash
npm install
cp server/.env.example server/.env   # personalizza i valori
npm run dev:server                   # API su :3000
npm run dev:web                      # SPA su :5173, proxy /api verso :3000
```

Apri `http://localhost:5173/<slug>`.

## Build & avvio produzione

```bash
npm run build     # genera web/dist
npm start         # Express serve API + SPA sulla stessa porta (APP_PORT)
```

## Deploy dietro Apache

Vedi `deploy/apache.conf` (reverse proxy verso `APP_PORT`) e `deploy/fail2ban-filter.conf` + `deploy/fail2ban-jail.local` per il ban automatico su abusi (booking falliti/ripetuti, captcha errati, enumerazione tenant).
