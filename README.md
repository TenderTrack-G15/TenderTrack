# TenderTrack — Procurement Officer app

Android front end for the Procurement Officer role of the IT Tender Tracking
System, built with Kotlin and Jetpack Compose against a Supabase backend.

---

## Opening the project

1. Open Android Studio (Ladybug or newer) and choose **Open**, then select this
   folder. Let Gradle sync finish.
2. Press **Run**. The app launches straight away — see *Sample data* below.

Requirements: JDK 17, Android Gradle Plugin 8.7, `compileSdk 35`, `minSdk 26`.

## Connecting Supabase

Without credentials the app runs on bundled demo data, so it opens on a clean
machine. To point it at a real project:

1. Create a project at supabase.com.
2. Open **SQL Editor > New query**, paste the whole of `supabase/schema.sql`,
   and run it. This creates the tables, the role enum, the guarded functions
   and the row-level security policies.
3. Copy your project URL and *anon* key from **Project Settings > API** into
   `gradle.properties`:

   ```properties
   SUPABASE_URL=https://xxxxxxxxxxxx.supabase.co
   SUPABASE_ANON_KEY=eyJhbGciOi...
   ```

4. Create a user under **Authentication > Users**, then promote them:

   ```sql
   update profiles
   set role = 'procurement_officer',
       full_name = 'T. Mokoena',
       department = 'Gauteng Dept of e-Government'
   where email = 'you@example.com';
   ```

5. Rebuild. The app now reads and writes live data.

Never put the `service_role` key in the app. The anon key is public by design;
what protects the data is row-level security, not key secrecy.

## Sample data

When `SUPABASE_URL` or `SUPABASE_ANON_KEY` is blank, `ServiceLocator` hands out
the `Sample*` repositories instead of the `Supabase*` ones. Sign in with any
email address and a password of at least 8 characters. Awarding a tender,
recording a payment and resolving a flag all mutate the in-memory data, so the
screens behave correctly during a demo.

---

## Screens

| # | Screen | Purpose | Requirements |
|---|---|---|---|
| 1 | Sign in | Email and password. Role comes from the account, never a button. | FR16, Security NFR |
| 2 | Dashboard | Lifecycle counts, budget, fund utilisation, open flags. | FR2, FR6–8, FR11 |
| 3 | Navigation drawer | Grouped: Tenders, Suppliers & Awards, Finance, Oversight. | FR6–8, FR10, FR12, FR17 |
| 4 | All Tenders | Search, status filter, closing dates and budgets. | FR13 |
| 5 | Register / Edit Tender | One validated form for both. | FR2, FR3 |
| 6 | Tender Detail | Publish, close, award, pay, edit — only what the state allows. | FR2, FR5 |
| 7 | Update Lifecycle | Stepper; only the next state is selectable. | FR2, FR5 |
| 8 | Award Tender | Issues the 10-digit code, server-side. | FR4 |
| 9 | Supplier Registrations | Verification queue with its own statuses. | FR1 |
| 10 | Review Registration | Verify, or decline with a reason the supplier sees. | FR1, Usability NFR |
| 11 | Record Payment | Blocks over-payment before submission. | FR10, FR12 |
| 12 | Fund Utilisation | Allocated, committed, disbursed, per contract. | FR11 |
| 13 | Flags & Compliance | Severity, rule, assignment, age. | FR6, FR7 |
| 14 | Flag Investigation | Notes, audit trail, outcome. | FR7, FR8 |
| 15 | Notifications | Flags, deadlines, unclaimed codes, payments. | FR15 |
| 16 | Reports | Six report types, date range, CSV / Excel / PDF share. | FR17 |

---

## How consistency is enforced

No screen sets a colour, font size or padding of its own. Everything comes from
three token files, so changing a value there changes it everywhere:

- `ui/theme/Color.kt` — the palette, including the five status treatments.
- `ui/theme/Type.kt` — the type scale, using Inter bundled in `res/font`.
- `ui/theme/Dimens.kt` — spacing, control heights and corner radii.

Screens are then assembled from shared components in `ui/components/`:
`AppScaffold` (app bar, gutters), `AppCard`, `KeyValueRow`, `StatTile`,
`AppTextField`, `AppDropdownField`, `PrimaryButton`, `StatusBadge`,
`NoteBanner`, `LifecycleStepper`, and the `LoadingState` / `ErrorState` /
`EmptyState` trio that every data screen uses.

## Structure

```
za/ac/tendertrack/
├─ core/            Format, Validate, UiState, ActionState
├─ data/
│  ├─ model/        Models.kt — entities and the status enums
│  ├─ repo/         one file per area: interface + Supabase impl + sample impl
│  ├─ sample/       SampleData.kt — the offline data set
│  ├─ SupabaseModule.kt
│  └─ ServiceLocator.kt
└─ ui/
   ├─ theme/        Color, Type, Dimens, Theme
   ├─ components/   the shared building blocks
   ├─ nav/          Routes, AppDrawer, NavGraph
   └─ screens/      one file per screen, each with its ViewModel
```

Each screen owns a ViewModel holding an immutable state data class. Loads use
`UiState` (Loading / Success / Error); user actions use `ActionState` so a failed
save shows a snackbar instead of blanking the screen.

---

## Where the review comments are answered in code

- **Role must come from the session, not a button.** There is no role selector
  anywhere. `SignInScreen` authenticates, then reads the role from `profiles`.
  In `schema.sql`, `handle_new_user()` always creates a supplier, the
  `profiles_update_self` policy forbids changing your own role, and `auth_role()`
  backs every other policy.
- **One award-code mechanism (FR4).** `award_tender()` generates the code, stores
  only its hash, and queues it for delivery. The `award_codes` policy makes it
  readable by the awarded supplier alone, so the officer's device cannot see it.
  `AwardTenderScreen` never displays a code.
- **One status vocabulary (FR2).** `TenderStatus` is the only tender vocabulary
  and `next()` is the only way forward; `advance_tender_status()` enforces the
  same rule in Postgres. Supplier verification uses the separate
  `SupplierVerificationStatus`.
- **No bid submission.** Nothing in this app accepts a bid.
- **Navigation gaps.** Record Payment, Fund Utilisation, Flags & Compliance and
  Reports are all in the drawer, grouped.
- **Rejection needs a reason.** `SupplierReviewViewModel.reject()` will not submit
  without one, and the hint says the supplier reads it verbatim.
- **Over-payment (FR12).** Checked live in `RecordPaymentUiState.wouldExceedAward`,
  again in `record_payment()`, and again by the `paid_within_award` constraint.

## Known limitations

- Date fields are typed as `dd/mm/yyyy` rather than using a picker dialog.
- Reports export as CSV content; the PDF option shares the same data rather than
  rendering a laid-out PDF.
- Evaluation Committee scoring screens are a separate role and not in this module.
