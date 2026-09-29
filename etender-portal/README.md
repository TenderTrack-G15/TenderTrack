# eTender Demo Portal

A **demonstration** procurement website for the TenderTrack student project. It
plays the part of the government's tender website: companies register, find
tenders and **bid** here; departments close bidding, compare bids and **award**
here. TenderTrack itself takes no part in bidding.

It is **not a government website**. It copies the usual layout and steps of a
tender portal (tabs for advertised, closed and awarded tenders, expandable
rows, tender detail pages, supplier registration), but not any real site's
name, logo or branding. Every page says it is a demonstration.

It uses the **same Supabase database** as the TenderTrack app, so a tender
published in the app appears here, and an award made here shows up in the app.

Built with HTML, CSS and JavaScript. `server.js` uses only what comes with
Node.js 20 or newer: nothing to install, no XAMPP, no PHP.

## Run it

1. Run `supabase/etender_awards.sql` once in the Supabase SQL Editor.
2. Copy `.env.example` to `.env` and fill in the three Supabase values.
3. Double-click `start-etender-portal.bat` (Windows), or run `node server.js`
   in this folder.
4. Open <http://localhost:5070>.

## Who does what

| Person | On this portal | In TenderTrack |
|---|---|---|
| Supplier | Registers the company (once), bids, sees bid results and the demo mailbox | Finds and follows tenders, **claims an award with the emailed code**, updates deliverables |
| Procurement officer | Closes bidding, compares bids, awards, re-sends a code, verifies deliverables | Registers and publishes tenders, verifies supplier registrations |

## The award code

1. The officer awards a bid on the Department console.
2. The **database** creates a random 10-digit code and stores only a bcrypt
   hash of it. The plain code goes into `email_outbox`, a table that only this
   server (with the secret key) can read.
3. This server emails the code (SMTP settings in `.env`) or, without SMTP,
   puts it in the **Demo mailbox** on this computer. Then it removes the code
   from the database.
4. The supplier enters the code in TenderTrack (Awards → Claim award). The
   database checks it: 14 days, 5 attempts, then locked. A correct code moves
   the tender to *In progress* and unlocks the deliverables.

The department never sees the code, and neither does anyone reading the
database.

## How it is kept safe

| Protection | Where |
|---|---|
| Reachable from this computer only (listens on 127.0.0.1, checks the Host header) | `server.js` |
| The secret key stays in `.env`; the browser only gets the public key | `server.js`, `/config.js` |
| Every rule is checked by the database: who may bid, award, claim, verify | `supabase/etender_awards.sql` |
| No code from the internet; strict Content Security Policy; nothing from the database is inserted as HTML | `public/` |
| The demo mailbox shows each email only to the account it was sent for | `server.js`, `email_outbox.to_user` |
| Email passwords never appear in logs or error messages | `server.js` |
| Real email only to allowed addresses (the sender's own domain, plus `EMAIL_ALLOWLIST`), never to the made-up sample companies | `server.js` |
| An officer can verify a registration but not change the email a code is sent to; a contract cannot be started before the code is claimed, even by editing the table directly | `supabase/etender_awards.sql` |

## Files

```
server.js                   serves the portal; registers suppliers; sends the email outbox
.env.example                copy to .env (never commit .env)
start-etender-portal.bat    double-click to start on Windows
public/
  index.html … mailbox.html one page each: home, tenders, tender, register, sign in,
                            account, bid, department console, demo mailbox, how it works
  css/portal.css            the portal's look, with the app's Inter font
  js/client.js              connection to Supabase and to server.js
  js/layout.js              header, navigation and footer
  js/ui.js                  shared building blocks (safe text only), formatting, dialogs
  js/tenders-data.js        tender lists and tender packs
  js/pages/*.js             one file per page
  fonts/                    Inter
  vendor/supabase.js        supabase-js, kept locally
data/mailbox.json           the demo mailbox (created when needed; never commit it)
```
