# TenderTrack admin portal

The Administrator's website. It runs on the administrator's own computer and
is not part of the Android app: the app has no link to it, and an
administrator who tries to sign in to the app is told to use the portal.

Built with HTML, CSS and JavaScript. `server.js` uses only what comes with
Node.js, so there is nothing to install apart from Node.js itself. No XAMPP,
no PHP, no database on this computer: the data lives in Supabase, the same
project the app uses.

## Run it

1. Install Node.js 20 or newer (the LTS version from nodejs.org).
2. Copy `.env.example` to `.env` and fill in the three values from
   Supabase → Project Settings → API Keys.
3. Double-click `start-admin-portal.bat` (Windows), or run `node server.js`
   in this folder.
4. Open <http://localhost:5050/admin>.

The first sign-in asks you to scan a QR code with Google Authenticator or
Microsoft Authenticator. After that, every sign-in asks for the 6-digit code.

## How it is kept safe

| Protection | Where |
|---|---|
| Reachable from this computer only (listens on 127.0.0.1) | `server.js` |
| Password **and** a 6-digit code; the database gives an administrator no access until the code is entered | `supabase/admin_portal.sql`, `auth_role()` |
| The Supabase secret key stays in `.env` on this computer and never reaches the browser | `server.js` |
| Every change is written to the audit trail, and every sign-in step to the access log | `supabase/admin_portal.sql` |
| No code from the internet; strict Content Security Policy; nothing from the database is ever inserted as HTML | `public/admin` |
| Signed out after inactivity and when the tab closes | `js/portal.js`, `js/client.js` |
| The app refuses administrator accounts, so the portal is the only way in | app `SignInScreen.kt` |

## Files

```
server.js                 serves the portal and does the few jobs that need the secret key
.env.example              copy to .env (never commit .env)
start-admin-portal.bat    double-click to start on Windows
public/admin/
  index.html              sign-in: password, then the 6-digit code
  portal.html             the portal after sign-in
  css/portal.css          the app's colours, Inter font and sizes, in a dark "restricted" variant
  js/client.js            connection to Supabase and to server.js
  js/signin.js            the sign-in steps
  js/portal.js            menu, pages, session check, inactivity sign-out
  js/ui.js                shared building blocks (safe text only), formatting, dialogs, CSV
  js/rules.js             checks that highlight tenders worth a look
  js/pages/*.js           one file per page
  fonts/                  Inter, the app's font
  vendor/supabase.js      supabase-js, kept locally
```
