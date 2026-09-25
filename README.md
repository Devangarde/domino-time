# Domino Time

Self-service appointment booking for HCL Domino.

Give each Domino user a short public link (e.g. `https://time.company.com/schedule/jdoe`). Anyone with the link — no Notes account required — sees the user's real availability, picks an appointment type, duration and slot, and sends a request protected by a captcha.

The request lands in the user's own Notes calendar as a **draft meeting**: the user reviews it and sends the invite from Notes when ready. A notification memo is also delivered to the user's mailbox.

![Domino Time booking page](screenshot.png)

## How it works

- Availability is computed from the user's real calendar, working hours and Out of Office settings.
- The calendar is read and written through the Domino Java API `NotesCalendar` and `NotesCalendarEntry`. Entries are created as meetings (ORGANIZER + ATTENDEE) with implicit scheduling disabled, so nothing is sent until the user decides.
- The slot is checked again at confirmation time to avoid double bookings.
- The captcha is stateless: an AES-GCM encrypted token, no server-side session and no fonts required on the server.
- Services are XPages REST services (`CustomServiceBean`), exposed as `api.xsp/week/<slug>`, `api.xsp/create/<slug>` and `api.xsp/captcha`.

Tested with Domino server **14**, **14.5** and **14.5.1**.

## Setup

First-time setup of the server and the database.

### 1. Database

- Requires HCL Domino 14 or later.
- Sign the database with an ID that can read the users' mail files and the Domino Directory: the services run as the signer.
- The ACL must include `Anonymous` with Reader access. No additional attributes are needed.

### 2. Internet Site: allow Anonymous access

The Internet Site that serves the database can be shared with other applications or dedicated to Domino Time. In both cases it must allow anonymous access. In the Internet Site document, open the **Security** tab and set **Anonymous** to **Yes**:

- in the **TCP authentication** section, if Domino exposes HTTP;
- in the **TLS authentication** section, if Domino exposes (also) HTTPS.

### 3. Web Site Rule

Public URLs have two levels: `<keyword>/<user slug>`, e.g. `/time.nsf/schedule/jdoe`. The **keyword is fully custom**: it can be any word you like (`schedule`, `book`, `meet`...), it only has to match between the rule and the URLs you hand out.

Create a new **Web Site Rule** with these fields:

| Field | Value |
| --- | --- |
| Type of rule | `Substitution` |
| Incoming URL pattern | `/time.nsf/schedule/*` |
| Replacement pattern | `/time.nsf/index.html#` |

In general: incoming pattern `<DbPath>/<keyword>/*`, replacement pattern `<DbPath>/index.html#`.

### 4. Reverse proxy (recommended)

The service is meant for **anonymous** users, so a reverse proxy in front of Domino is strongly recommended, to apply rate limiting and other protections against mass requests.

With a dedicated host (e.g. `time.company.com`) the URLs can be shortened to `https://time.company.com/schedule/<slug>`.

**Apache**

```apache
ProxyPassMatch "^/(.+)$" "http://<domino>/time.nsf/$1"
ProxyPassReverse "/" "http://<domino>/time.nsf/"
```

**nginx**

```nginx
location / {
    proxy_pass http://<domino>/time.nsf/;
    proxy_redirect http://<domino>/time.nsf/ /;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

## Setting up users

Create a **User** document (from Notes) for every user who accepts bookings. The user's public URL is `<DbPath>/<keyword>/<slug>`.

| Field | Description |
| --- | --- |
| `Username` | Canonical name of the Domino user (as in the Domino Directory) |
| `Mailfile` | Path of the user's mail database |
| `Slug` | The short-URL part identifying the user, e.g. `jdoe` |
| `Subject` | Welcome text shown on the booking page |
| `Enabled` | Checkbox. When cleared, online booking is suspended for this user |
| `Appointment types` | Up to three appointment types: name, description and allowed durations (30 to 120 minutes, e.g. `30, 60, 120`) |

The user's e-mail address is read from the Domino Directory (`InternetAddress` item of the Person document).

## Third-party components and attributions

- [json-simple](https://code.google.com/archive/p/json-simple/) (`org.json.simple`), Apache License 2.0. It is included in the database; see `THIRD-PARTY-NOTICES.txt` for its license text.
- HCL Domino / XPages Extension Library APIs, provided by the Domino server.

## License

Domino Time (Java sources and NSF design) is released under the [Apache License 2.0](LICENSE). You may use, modify and redistribute it for free, provided that the copyright and attribution notices are kept.

The web front-end is distributed compiled inside the NSF. Its sources are not part of this repository: please get in touch if you are interested.
