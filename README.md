# Job Search Companion

Local learning reference: [living architecture](ARCHITECTURE.local.md). This unversioned file is intentionally ignored by Git and is updated alongside architecture changes.

Personal job application tracker. The React/REST foundation and local MongoDB replica set are configured; single-account setup, session login, and basic application tracking are implemented.

## Prerequisites

- Java 21 (Maven Wrapper is included)
- Node.js 26 and npm
- Colima, Docker CLI and Compose for the MongoDB setup in step 2

## Run locally

From the repository root, start the database before the backend:

```sh
# Start the local container runtime if it is stopped.
colima start
# Initialize the replica set and wait until it can accept writes.
docker compose up -d --wait
```

Then start the backend:

```sh
./mvnw spring-boot:run
```

In another terminal, start the frontend:

```sh
cd frontend
npm ci
npm run dev
```

Open http://127.0.0.1:5173. On first launch, create your local account, then sign in. After login, the Applications workspace lets you add, view, edit, search, filter, and sort jobs.
Vite proxies `/api` to http://127.0.0.1:8080, so no cross-origin configuration is needed.
Both development servers bind to localhost. Stop either with Ctrl+C.

`GET /api/health` reports application availability, not database readiness. MongoDB readiness is checked by Compose; the health page remains independent of database availability. The CSRF and first-run setup endpoints are public; account setup closes after one owner is created. Other API routes require authentication. CSRF protection applies to login, setup, logout, and other writes.

## Verify

```sh
./mvnw verify
cd frontend
npm run build
npm run lint
```

## Layout

- `src/main/java/com/example/webapp/config`: backend configuration
- `src/main/java/com/example/webapp/system`: application availability endpoint
- `frontend/src/api`: frontend HTTP functions and response types
- `frontend/src`: React UI and routing

The frontend build currently outputs to `frontend/dist`; serving it through Spring Boot will be configured in the packaging stage. CSV import is not implemented yet.

## MongoDB (local development)

The pinned MongoDB image runs a single-member replica set named `rs0`. Transactions need a replica set; one member provides transaction support, not high availability. The named `mongodb-data` volume preserves data when containers are restarted or recreated.

This laptop-only setup has no database authentication. Only loopback port `127.0.0.1:27017` is published; do not expose it on a network. Other processes on your laptop can access it. A shared or hosted environment will require authentication and a separate configuration.

Connect from MongoDB Compass using:

```text
mongodb://localhost:27017/job_search_companion?replicaSet=rs0
```

Collections and their domain indexes will be created as the features are implemented. An empty database may not appear in Compass until the first record is written. Override `MONGODB_URI` to use another environment; Spring Boot 4 reads `spring.mongodb.uri`.

```sh
# Check readiness and initialization logs.
docker compose ps -a
docker compose logs mongodb-init
# Verify actual commits and rollbacks in a separate test database.
./mvnw verify -Pmongo-integration
# Stop containers while retaining records.
docker compose stop
# Resume; initialization also works with an existing replica set.
docker compose up -d --wait
```

Avoid `docker compose down -v`: it deletes the persistent database volume. Database files are inside Colima's virtual disk, not the source directory. Colima/VM deletion can also remove them; backup and restore tooling will be added in the daily-use stage.

## Account and session login

1. Start MongoDB, Spring Boot, and Vite using the commands above.
2. Open `http://127.0.0.1:5173` consistently (avoid switching between localhost and 127.0.0.1, which use different cookies).
3. Choose a username (3–40 letters, numbers, dots, underscores, or hyphens), a password of at least 12 characters and at most 72 UTF-8 bytes, and a timezone such as `Asia/Kolkata`.
4. After account creation, sign in with those credentials. Usernames are case-insensitive; passwords are case-sensitive.
5. Refresh the page to restore the session, or use **Sign out** to invalidate it.

No default password is generated and no password needs to be entered in source files or environment variables. The `users` collection stores a BCrypt hash with a random salt (cost 12), the username, timezone, and creation time. Its fixed `owner` ID prevents a second account even if setup requests race. There is no public registration or password-reset flow in this version.

Sessions are stored in backend memory and expire after 30 minutes of inactivity. Restarting the backend signs you out; your account remains in MongoDB. The session cookie is HttpOnly and SameSite=Lax. Secure cookies are disabled only for local HTTP; enable them when deploying with HTTPS. Never expose the first-run setup endpoint to a network without a provisioning mechanism.

### Authentication API

| Method | Endpoint | Behavior |
| --- | --- | --- |
| GET | `/api/csrf` | Returns a session-bound token and its header name |
| GET | `/api/session/setup-status` | Indicates whether the one-time setup is available |
| POST | `/api/session/setup` | Accepts username, password, and timezone as JSON; returns 201 |
| POST | `/api/session/login` | Accepts form-encoded username/password; sets session cookie; returns 204 |
| GET | `/api/session` | Returns owner ID, username, and timezone; anonymous requests get 401 |
| POST | `/api/session/logout` | Invalidates the session and deletes its cookie; returns 204 |

The frontend requests a fresh CSRF token before each write because login and logout rotate it. Tokens and credentials are not stored in localStorage. All requests go through Vite's same-origin proxy. Authentication/filter and validation errors share `{ code, message, fieldErrors }` responses.

Future application/action services must use `CurrentAccount.id()` when querying and saving records, rather than trusting a browser-supplied owner ID. Those domain endpoints are not implemented yet.

### Login verification

`./mvnw verify` exercises real Spring Security filters with mocked account storage: successful/failed login, CSRF rejection and rotation, session restoration, logout, validation, and closed setup. `./mvnw verify -Pmongo-integration` additionally verifies password persistence and the unique owner constraint using the isolated `job_search_companion_auth_it` database. It never creates a test account in your actual application database.

## Application tracking

After signing in, open `/applications`. Save a role with company, role, posting URL, source, location, description, status, and an optional application date. A date is required for any status other than Saved. Source names are free text with suggestions, so job platforms do not need hardcoded backend enums.

The list supports literal case-insensitive search across company, role, and location; status/source filters; six sort fields; ascending/descending order; and page sizes of 10, 20, or 50. Filter state lives in the URL so refresh and browser navigation preserve it. Open a company name for details or use Edit to change a record.

Editable fields are submitted together. Edits include the last-read version: an older tab receives a 409 conflict instead of overwriting newer changes. The form offers an explicit reload that discards stale edits. Dates remain calendar dates; detail timestamps use the account timezone. Job descriptions are plain text and are never rendered as HTML.

### Application API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/applications` | Owner-scoped list; query parameters: search, status, source, page, size, sort, direction |
| GET | `/api/applications/sources` | Source values used by the current owner |
| POST | `/api/applications` | Create from a full application form; returns 201 with a DTO and Location header |
| GET | `/api/applications/{id}` | Read one owned application |
| PATCH | `/api/applications/{id}` | Replace editable form fields with an optimistic version check; returns the updated DTO |

All endpoints require login; POST/PATCH also require CSRF. The server derives `userId`, timestamps, and IDs rather than trusting browser input. A missing or foreign-owned application returns the same 404. Limits: company/role/location 160 characters, source 80, posting URL 2,048, description 30,000, search 120, page size at most 50. Posting URLs must be HTTP(S) without embedded credentials.

MongoDB's `applications` collection has owner/update, owner/status/update, and owner/source/update indexes, created on first list/create/source access. Text search uses escaped literal regexes, suited to this personal dataset; large-scale full-text search is not configured.

Run `./mvnw verify -Pmongo-integration` to additionally check the application API, ownership isolation, filtering, pagination, validation, indexes, CSRF, and stale/concurrent edits in `job_search_companion_applications_it`. No personal application records are used by those checks. Status history and notes are implemented in the next section. Follow-ups, deletion, and CSV import remain outside this stage.

## Notes, history, and board view

Use the **List / Board** toggle on Applications. The choice is stored in the URL as `view=board` and shares search, source/status filters, and ordering with the list. The board loads 20 cards per status column, with a total count and **Load more**; it does not silently show only the list's current page. Drag a card to another column on desktop, or use **Move to** on a card for touch/keyboard operation. Card order follows the chosen sort, not a manual ranking.

When a saved job has no application date, moving it to another status opens a date prompt. Cancelling leaves the record unchanged. Existing dates are preserved by board moves; use Edit to change dates. Version conflicts show an error and a reload option. Moving to the same status is a no-op.

Application details include **Notes & history**. Add plain-text notes up to 10,000 characters. The newest-first timeline combines notes, creation events, and status transitions with timestamps in your account timezone. **Load older activity** retrieves more history. Notes are append-only and do not change the application's version. This version does not support editing or deleting notes.

Creation and every actual status change (from either Edit or Board) record an immutable event in the `activities` collection. Application and event writes share a MongoDB transaction; history failure rolls back the application write. Collections/indexes are prepared before opening the transaction. Old records are not backfilled with guessed history; they start recording events from this release onward.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/applications/{id}/activities?page=0&size=20` | Owner-scoped timeline, newest first, at most 50 items per page |
| POST | `/api/applications/{id}/notes` | JSON `{ content }`; append a note and return 201 JSON |
| PATCH | `/api/applications/{id}/status` | JSON `{ status, version, appliedOn? }`; atomically update status and history |

Authentication and CSRF protect the new writes. Foreign-owned records still return 404. The compound `owner_application_timeline` index covers owner, application ID, timestamp, and event ID. Run `./mvnw verify -Pmongo-integration` for real transaction rollback, version/date/no-op, timeline, note validation, pagination, and ownership checks in `job_search_companion_activity_it`.

## Keeping MongoDB Compass tidy after tests

Integration test classes now check that they are connected to an exact approved test database before running fixtures, then drop that complete database after the class finishes (including normal test failures). This removes leftover empty collections and indexes as well as records. The personal `job_search_companion` database is never an approved cleanup target. A crashed or forcibly stopped JVM can still leave test databases behind.

After tests/browser checks have stopped, remove known leftovers with:

```sh
# Exact-name cleanup for the project's known integration and browser-test databases.
docker compose exec -T mongodb mongosh --nodb --quiet --file /dev/stdin < scripts/cleanup-test-databases.js
```

The script uses a fixed list rather than a broad name-prefix match. Future browser checks should invoke it after stopping their test servers. MongoDB's admin/config/local databases are normal system databases and remain present. Refresh Compass after cleanup.

## Actions, follow-ups, and interviews

Open an application and use **Actions & interviews → Add action**. Types are Follow-up, Interview, Preparation, Assignment, and Other. A title and due date are required; details are optional (up to 10,000 characters). Interviews can include an exact time on the due date in the account timezone. The backend converts that local time to a UTC instant; the browser's own timezone is not used to interpret your entry. Nonexistent/ambiguous daylight-saving times are rejected instead of silently shifted.

Pending actions can be edited/rescheduled, completed, or cancelled. Completed/cancelled records remain readable and cannot be edited or completed again. Views filter Pending, Completed, Cancelled, or All, load 20 at a time, and sort by due date/time. An Overdue badge means a pending due date is before today in the account timezone. Completing a follow-up never creates another; the next follow-up must be added manually.

Moving an application to Accepted, Rejected, or Withdrawn (through Board or Edit) cancels all its pending actions, preserves completed actions, and records cancellation reasons. This happens in the same MongoDB transaction as the application status/history changes. Reopening allows new actions but does not revive cancelled ones. Closed applications reject new actions and completion of pending work.

The `actions` collection lives inside the existing `job_search_companion` database. It has owner/application/status/due and owner/status/due indexes for detail views and the future Today dashboard. Action history stores before/after snapshots so rescheduling does not rewrite earlier events. Each action has its own optimistic version. Action writes also advance the parent application's version and updatedAt; this prevents a racing action write from committing after application closure. An open application edit may consequently require reload after an action changes elsewhere. Notes still do not advance the parent version.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/applications/{id}/actions` | Owner-scoped actions; optional status, page, size (max 50) |
| GET | `/api/actions` | Same owner-scoped list across applications, for future dashboard work |
| GET | `/api/actions/{id}` | One owned action |
| POST | `/api/applications/{id}/actions` | Create pending action; returns 201 DTO |
| PATCH | `/api/actions/{id}` | Full editable fields and current version |
| POST | `/api/actions/{id}/complete` | JSON `{ version }`; complete a pending action |
| POST | `/api/actions/{id}/cancel` | JSON `{ version }`; cancel a pending action |

Create/edit JSON fields: title, type, dueOn (YYYY-MM-DD), scheduledTime (optional HH:mm, interviews only), details, and version (required for editing). Ownership, status, audit timestamps, and completion/cancellation timestamps are server-assigned. Writes require login and CSRF. Conflict responses offer reload instead of silently overwriting another edit. The Today dashboard and notifications are not implemented in this stage.

Action integration tests use `job_search_companion_actions_it`, which is removed after the test class. `scripts/cleanup-test-databases.js` also knows this exact name and the isolated step-6 browser database.

## Today dashboard (step 7)

After login, **Today** is the landing page (`/today`). It brings together pending actions from every application:

- **Overdue:** due before today.
- **Today:** due on the current account date.
- **Upcoming:** due tomorrow through seven days from today, inclusive.

Each section shows company, role, action type and schedule, with **Complete**, **Reschedule**, and **Open application** controls. Sections load 20 actions at a time independently. Completing or rescheduling uses the same version checks and timeline history as the application detail page. Completing never generates a follow-up.

`GET /api/dashboard/actions?section=OVERDUE|TODAY|UPCOMING&page=0` returns an owner-scoped page, count, server date and account timezone. Dates are computed on the backend in the account timezone; the dashboard refreshes every minute and when refocused. Completed/cancelled actions and actions more than seven days away are excluded. Interview times use the account timezone. This is an in-app dashboard without email or background notifications.

## Visual theme

The interface uses a forest-green sidebar, warm ivory canvas, muted gold accents and rounded white cards. Desktop navigation becomes a compact header on mobile. Local font stacks provide sans-serif body text and serif heading accents without external font downloads. The theme covers login/setup, Today, list/board, application forms/details, actions and timeline; existing features and APIs are unchanged.

## All Actions

Open **All actions** in navigation (`/actions`) to plan across every application, including dates beyond the Today dashboard. Defaults: Pending, no date restrictions, earliest due first. Search matches action title, company or role literally and case-insensitively. Combine type/status filters with inclusive **Due from / Due through** dates. Choose All statuses to review completed/cancelled work. Filters and pagination remain in the URL; page sizes are 10, 20 or 50.

Pending actions support Complete, Edit/reschedule and Cancel; terminal actions are read-only. Open application leads to details/history and manual action creation. Changes refresh related views, and stale edits offer reload. Completing an action never creates a new follow-up.

`GET /api/actions/search` accepts optional status, type, from, to, search (max 120 characters), page and size (max 50). Results include action DTO plus company/role and pagination totals. Date bounds are inclusive calendar dates; interviews still display in the account timezone. The existing `/api/actions` contract is unchanged.

## Weekly calendar

Open **Calendar** (`/calendar`) for a Monday–Sunday schedule. Use Previous week, This week or Next week; a `week=YYYY-MM-DD` URL opens the containing week and survives refresh. Pending actions are shown by default; All statuses includes completed/cancelled history as read-only.

Each day separates **Date-only actions** (including interviews without a time) from **Scheduled interviews**, ordered by account-local time. Times and today's highlight follow the account timezone, even if your browser uses a different timezone. All seven days, including weekends, are shown. Complete, reschedule or open an application's details from a card; new actions are still created manually within applications. Mobile shows a vertical daily agenda; desktop has an internally scrolling weekly grid.

The calendar reuses `/api/actions/search` with inclusive week dates and retrieves every result page. It refreshes every minute/on focus and shares action-cache invalidation with the other screens. No new backend endpoints, collections or dependencies are needed. This is an in-app schedule, without external calendar synchronization or automatic reminders.

The calendar now uses a familiar weekly time grid: weekday headers, a separate all-day row, and timed interview blocks on hourly lines. Click a block for details, completion or rescheduling. Overlapping blocks appear side by side; mobile scrolls within the grid. The full 24-hour range is available. Interview duration is not yet stored, so blocks mark start times rather than measured meeting lengths.

### Application milestones on the calendar

The all-day row also shows **Applied — company** on the application's recorded application date and **Interviewed — company** on each completed interview action's scheduled date. Wednesday's application remains visible after Friday's interview; later status changes don't replace these cards. Pending/cancelled interviews do not count as interviewed. Interviewing status alone does not establish that an interview occurred.

Recorded offers, acceptance, rejection and withdrawal appear on their history-event date in the account timezone. Milestones stay visible independently of the **Show actions** status filter; click one to open application details. In All statuses, a completed interview can have both its timed action block and an all-day Interviewed milestone. Applied dates follow corrections to the application record; history events use immutable snapshots. Older records can show Applied if an application date is known, but interview/status history isn't invented.

`GET /api/calendar/milestones?from=YYYY-MM-DD&to=YYYY-MM-DD` returns owner-scoped milestone cards for an inclusive range of up to seven days. It reads existing applications and activity history; no new collection is created.
