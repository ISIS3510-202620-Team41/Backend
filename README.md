# Backend

REST API for Team 41's Android app. Spring Boot 4 + Java 21.

---

## Running it

```bash
./mvnw spring-boot:run
```

On Windows PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

It starts at `http://localhost:8080`. No database to install: it uses H2 in a local file (`./data/appdb.mv.db`), created automatically on first run.

```bash
./mvnw test
```

120 tests cover every feature end to end, plus unit tests for the pure logic (free-slot calculator, geo math, preference engine, token encryption, Google response parsing) and for the analytics publisher, which runs against a fake local HTTP server. The Google API is mocked in tests, and analytics sending is disabled in the `test` profile, so they need no network access, credentials or running engine.

On startup (outside the `test` profile) the server creates a few sample activities around Bogotá if there are no upcoming ones, so the search endpoints return something right away. See [Activities](#activities).

### Environment variables

Everything that changes between machines is read from environment variables, mapped in `application.properties` as `${VARIABLE:default}`. Every variable has a development default, so **nothing is required to start the server locally**. Set them when you need Google Calendar, when you run outside your own machine, or when you want to change a behavior.

| Variable | Needed for | Purpose | Default (development only) |
|---|---|---|---|
| `JWT_SECRET` | Anything outside your machine | Signs the tokens. **Minimum 32 characters** | an insecure development one |
| `PUBLIC_BASE_URL` | Images from an emulator or phone | Prefix of image URLs. Use `http://10.0.2.2:8080` for the Android emulator | `http://localhost:8080` |
| `GOOGLE_CLIENT_ID` | Google login and Google Calendar | OAuth client ID. Audience accepted for Google ID tokens, and client used to exchange Calendar auth codes | empty |
| `GOOGLE_CLIENT_SECRET` | Google Calendar | Client secret, used to exchange Calendar auth codes. **Never commit it** | empty |
| `GOOGLE_REDIRECT_URI` | Web-flow codes only | Leave empty for native Android/Flutter codes | empty |
| `TOKEN_ENCRYPTION_KEY` | Google Calendar | 32 random bytes in Base64. Encrypts the stored Google refresh tokens | empty |
| `ANALYTICS_ENABLED` | — | Master switch. `false` stops all event sending | `true` |
| `ANALYTICS_URL` | A non-local engine | Base URL of the analytics engine **as seen by the backend** (not by the app) | `http://localhost:8000` |
| `ANALYTICS_INGEST_KEY` | Engines with an ingest key | Sent as the `X-API-Key` header. Leave empty if the engine has no key | empty |

**Spring Boot does not read `.env` files by itself.** `.env.example` is a template that lists the variables; copy it to `.env` (which is git-ignored) as your own notes, then pass the values to the process in one of these ways:

```bash
# Bash / Git Bash
export JWT_SECRET="a-random-string-of-32-or-more-characters"
export ANALYTICS_ENABLED=false
./mvnw spring-boot:run
```

```powershell
# Windows PowerShell
$env:JWT_SECRET = "a-random-string-of-32-or-more-characters"
$env:ANALYTICS_ENABLED = "false"
.\mvnw.cmd spring-boot:run
```

Variables set this way only live in that terminal session. In an IDE, put them in the run configuration's *Environment variables* field instead.

**Any property can be overridden the same way**: uppercase it and turn dots and dashes into underscores. For example, `app.schedule.day-end` becomes `APP_SCHEDULE_DAY_END`. This is handy for one-off changes without editing the file (see the table below).

The server **will not start** if `JWT_SECRET` is shorter than 32 characters. That is deliberate: failing at startup is better than silently issuing weak tokens. Generate it like the encryption key below (any random string of 32+ characters works).

Two features degrade gracefully instead of failing:

- **Google Calendar is optional.** Without `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` and `TOKEN_ENCRYPTION_KEY`, the app starts normally and the two Google sync endpoints answer `503` with a clear message. Everything else keeps working.
- **Analytics never blocks the API.** If the engine is down or the key is wrong, requests are served as usual and each lost event leaves a warning in the log. Set `ANALYTICS_ENABLED=false` to silence them when you are not running the engine.

Generate the encryption key once and keep it (`openssl rand -base64 32`, or in PowerShell):

```powershell
$b = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Convert]::ToBase64String($b)
```

If the key is lost or changed, the stored refresh tokens can no longer be decrypted and each user has to connect Google again.

### Application properties

These have defaults and normally need no changes (`application.properties`):

| Property | Default | Meaning |
|---|---|---|
| `app.schedule.default-timezone` | `America/Bogota` | Zone that defines "a day" when the client sends no `tz` |
| `app.schedule.day-start` / `day-end` | `06:00` / `22:00` | Window in which free slots are computed |
| `app.schedule.ics-past-days` / `ics-future-days` | `30` / `180` | Recurrence expansion window for `.ics` files |
| `app.schedule.google-past-days` / `google-future-days` | `30` / `180` | Event window downloaded from Google Calendar |
| `app.analytics.enabled` | `true` | Master switch for event sending (env var `ANALYTICS_ENABLED`) |
| `app.analytics.url` | `http://localhost:8000` | Analytics engine base URL (env var `ANALYTICS_URL`) |
| `app.analytics.ingest-key` | empty | `X-API-Key` sent to the engine (env var `ANALYTICS_INGEST_KEY`) |

To try a one-off change, override with an environment variable. For example, `APP_SCHEDULE_DAY_START=00:00` and `APP_SCHEDULE_DAY_END=23:59` make free time available around the clock, which is useful for testing late at night.

---

## Project structure

Package-by-feature. Inside each module the layers are strict: `controller` → `service` → `repository` → `domain`.

```text
com.group41.backend
├── auth/        JWT authentication, refresh tokens, Google ID token login
├── user/        profile, friendships and preferences
│   ├── domain/  User, Friendship, FriendshipStatus, UserPreference
│   ├── repository/  service/  controller/
├── schedule/    time blocks, .ics import, Google Calendar sync, free slots
│   ├── domain/  TimeBlock, ScheduleSource, GoogleCredential
│   ├── repository/  service/  controller/
├── activity/    activities, geolocation and recommendations
│   ├── domain/  Activity, UserActivity, Category
│   ├── repository/  service/  controller/
├── analytics/   AnalyticsPublisher: non-blocking event emitter to the analytics engine
├── common/      global exception handler, ApiError
├── config/      SecurityConfig, WebConfig
├── security/    JwtAuthFilter, RestAuthenticationEntryPoint
└── storage/     AvatarStorageService
```

---

## API contract

Everything lives under `/api`. JSON, except image and `.ics` uploads which are `multipart/form-data`. Every route requires a Bearer token except register, login, Google login, refresh and logout.

### Authentication

| Method | Route | Auth | What it does |
|---|---|---|---|
| `POST` | `/api/auth/register` | — | Creates the account and returns the token pair |
| `POST` | `/api/auth/login` | — | Returns the token pair |
| `POST` | `/api/auth/google` | — | Verifies a Google ID token and returns the token pair |
| `POST` | `/api/auth/refresh` | — | Exchanges the refresh token for a new pair |
| `POST` | `/api/auth/logout` | — | Revokes the refresh token |

### User

| Method | Route | Auth | What it does |
|---|---|---|---|
| `GET` | `/api/users/me` | Bearer | The user's profile |
| `PATCH` | `/api/users/me` | Bearer | Edits `name` and/or `bio` |
| `POST` | `/api/users/me/avatar` | Bearer | Uploads or replaces the photo |
| `DELETE` | `/api/users/me/avatar` | Bearer | Removes the photo |

### Friends

| Method | Route | Params | What it does |
|---|---|---|---|
| `POST` | `/api/friends/requests` | `{ "addresseeEmail": "..." }` | Sends a friend request |
| `GET` | `/api/friends/requests/pending` | — | Incoming requests waiting for an answer |
| `PATCH` | `/api/friends/requests/{id}` | `{ "accept": true }` | Accepts or rejects a request you received |
| `GET` | `/api/friends` | — | Accepted friends, ordered by name |
| `GET` | `/api/friends/gaps` | `?dateTime=2026-10-02T15:00:00Z` | Friends with no active time block at that instant |

### Schedules

| Method | Route | Params | What it does |
|---|---|---|---|
| `GET` | `/api/schedules/me/gaps` | `?date=2026-10-02` and optional `&tz=` | Free time slots of the user on a day |
| `POST` | `/api/schedules/sync/ics` | `multipart/form-data` (`file`) and optional `?tz=` | Parses an `.ics` file and replaces the user's `ICS` blocks |
| `POST` | `/api/schedules/sync/google` | `{ "authCode": "..." }` and optional `?tz=` | Exchanges the code, stores the encrypted refresh token and imports events |
| `POST` | `/api/schedules/sync/google/refresh` | optional `?tz=` | Re-syncs using the stored token, without involving the mobile app |

### Activities

| Method | Route | Params | What it does |
|---|---|---|---|
| `POST` | `/api/activities` | JSON body | Creates an activity |
| `GET` | `/api/activities` | `?lat=4.6&lon=-74.0&radius=1.0` | Nearby upcoming activities, closest first |
| `GET` | `/api/activities/recommendations` | `?lat=4.6&lon=-74.0` and optional `&radius=`, `&tz=` | Activities ranked by preferences and distance. Also returns the `X-Recommendation-Id` and `X-Free-Time-Minutes` response headers (see [Analytics](#analytics)) |
| `POST` | `/api/activities/{id}/join` | optional `?recommendationId=` and `&freeTimeMinutes=` | Joins the activity and updates the user's preferences. The two params are only sent when joining from a recommendations list |
| `DELETE` | `/api/activities/{id}/leave` | — | Cancels the enrollment |

### Bodies

`POST /api/auth/register`

```json
{ "email": "sebas@test.com", "password": "micontrasena123", "name": "Sebastian" }
```

`POST /api/auth/login`

```json
{ "email": "sebas@test.com", "password": "micontrasena123" }
```

`POST /api/auth/google`

```json
{ "idToken": "<Google ID token>" }
```

The backend verifies the signature, issuer, audience (`GOOGLE_CLIENT_ID`), expiration and `email_verified` using Google's official library. If the email already exists it is linked automatically after the token is verified; otherwise the user is created.

`POST /api/auth/refresh` and `POST /api/auth/logout`

```json
{ "refreshToken": "..." }
```

The first three respond with the same shape:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "x9Kd2...",
  "expiresInSeconds": 900,
  "user": {
    "id": "uuid",
    "email": "sebas@test.com",
    "name": "Sebastian",
    "bio": null,
    "avatarUrl": null
  }
}
```

`PATCH /api/users/me` is a partial update: whatever you do not send stays the same. To clear the bio send `""`, not `null`.

```json
{ "name": "Sebastian", "bio": "Design student" }
```

`POST /api/users/me/avatar` is `multipart/form-data` with a field named **`file`**. It returns the updated `user`.

`POST /api/friends/requests` and `PATCH /api/friends/requests/{id}` respond with a friend request seen from the caller's side. `user` is **always the other person**: the addressee when you send, the sender when you list your pending requests.

```json
{
  "id": "uuid",
  "status": "PENDING",
  "user": { "id": "uuid", "email": "ana@test.com", "name": "Ana", "bio": null, "avatarUrl": null },
  "createdAt": "2026-10-02T20:24:14Z"
}
```

`GET /api/friends` and `GET /api/friends/gaps` return a list of `user` objects like the one above.

`GET /api/schedules/me/gaps`

```json
{
  "date": "2026-10-05",
  "timezone": "America/Bogota",
  "free": [
    { "start": "2026-10-05T08:15:00-05:00", "end": "2026-10-05T09:30:00-05:00" },
    { "start": "2026-10-05T15:20:00-05:00", "end": "2026-10-05T16:45:00-05:00" }
  ]
}
```

`POST /api/schedules/sync/ics` responds with:

```json
{ "imported": 892, "skippedEvents": 0, "unsupportedRecurrences": 0 }
```

`POST /api/schedules/sync/google` and `.../refresh` respond with:

```json
{ "imported": 150, "skippedEvents": 0, "calendars": 2 }
```

`POST /api/activities`

```json
{
  "title": "Five-a-side football",
  "description": "Friendly match",
  "category": "DEPORTES",
  "startTime": "2026-10-05T17:00:00-05:00",
  "endTime": "2026-10-05T19:00:00-05:00",
  "locationName": "University courts",
  "latitude": 4.6382,
  "longitude": -74.0840
}
```

`category` is one of `DEPORTES`, `ESTUDIO`, `CULTURA`, `ENTRETENIMIENTO`. `description` is optional.

Activities are returned as:

```json
{
  "id": "uuid",
  "title": "Five-a-side football",
  "description": "Friendly match",
  "category": "DEPORTES",
  "startTime": "2026-10-05T17:00:00-05:00",
  "endTime": "2026-10-05T19:00:00-05:00",
  "locationName": "University courts",
  "latitude": 4.6382,
  "longitude": -74.0840,
  "distanceKm": 0.35,
  "participants": 3,
  "joined": false
}
```

`distanceKm` is `null` when there is no reference point (on create and join). `joined` says whether **the caller** is enrolled. `/recommendations` wraps each activity as `{ "activity": { ... }, "score": 0.68 }`.

### Errors

Always the same shape, so the client parses it once:

```json
{ "timestamp": "...", "status": 401, "message": "Credenciales invalidas", "fields": null }
```

`fields` only comes in validation `400`s, with the detail per field.

| Code | When |
|---|---|
| `400` | Failed validation, malformed request or invalid parameter (a non-UUID id, a bad date or time zone), or the file is not an image / not a valid `.ics` |
| `401` | No token, invalid/expired token, or wrong credentials |
| `404` | Unknown email, unknown activity, or a friend request that does not exist **or is not addressed to you** |
| `409` | Email already registered, duplicate or crossed friend request, already friends, already answered request, already enrolled, activity already ended, or Google access lost / missing scope |
| `413` | The image is larger than 5 MB |
| `415` | Image format not allowed |
| `429` | Too many login attempts |
| `502` | Google did not answer or rejected the server's credentials |
| `503` | Google Calendar is not configured on the server |

---

## Friends

Rules that were not in the original spec, in case a client depends on them:

- **Unknown email:** `404`. This reveals which emails exist, which is unavoidable when adding friends by email. It only works with a valid session.
- **Request to yourself:** `400`.
- **Already friends, or a request already pending in either direction:** `409`.
- **If someone rejects your request**, you cannot send it again (`409`). The person who rejected, however, can send you one later.
- **Only the addressee can answer.** Anyone else, including the sender, gets `404` rather than `403`, so the existence of a request is never revealed.
- **Answering an already answered request:** `409`.
- **The email is normalized** (trimmed on the server, lower-cased). Spaces *inside* the JSON value are rejected by validation, like in registration.

`GET /api/friends/gaps` only says **who is free** at an instant. It never exposes event titles or times. "Busy" means `start <= instant < end`: a block includes its start and excludes its end.

---

## Schedules

A schedule is a set of **busy** `TimeBlock`s per user. Free slots are never stored: they are computed by subtracting the blocks from the day window, so there is a single source of truth.

### Free slots

- **Time zone:** `America/Bogota` by default, with an optional `?tz=` taking any IANA zone (`Europe/Madrid`, `UTC`...). An invalid zone gives `400`. "The day" depends on the zone, and the answer is formatted in it. Calculations use instants, so blocks stored with another zone are still correct.
- **Window:** 06:00–22:00 by default (configurable). Blocks that spill outside it are clipped, overlapping blocks are merged, and a block that ends exactly when another starts leaves no gap.
- **Dates:** stored as `ZonedDateTime` and returned as ISO 8601 text with offset, for example `2026-10-02T06:00:00-05:00`.

### `.ics` import

- Replaces **all** of the user's `ICS` blocks. The file is parsed *before* anything is deleted, so a broken file never wipes the existing schedule.
- Recurrences are expanded from 30 days back to 180 days ahead, honoring `EXDATE`. A moved instance (`RECURRENCE-ID`) replaces the original.
- `CANCELLED` and `TRANSPARENT` events are ignored (they do not take your time).
- All-day events block the whole day. If they repeat, only the first occurrence is imported and they are counted in `unsupportedRecurrences`.
- Floating times (no zone) are interpreted in `tz`, Bogotá by default.
- An event that fails to process is counted in `skippedEvents` and does not abort the import.
- Uses `ical4j` 3.2.x, configured in `src/main/resources/ical4j.properties` (in-memory time zone cache, relaxed parsing for real-world files from Outlook or universities).

```bash
curl -X POST http://localhost:8080/api/schedules/sync/ics \
  -H "Authorization: Bearer <TOKEN>" \
  -F "file=@calendar.ics"
```

### Google Calendar

Server-to-server authorization, so syncing does not depend on the mobile app staying open:

1. The mobile app requests offline access with **both** scopes (see below) through its native libraries and gets a one-time `serverAuthCode`.
2. It sends the code to `POST /api/schedules/sync/google`.
3. The backend exchanges it for tokens at Google, stores the **refresh token encrypted** (AES-256-GCM) and downloads the events.
4. From then on, `POST /api/schedules/sync/google/refresh` re-syncs using the stored token.

Details:

- **Required scopes:** `https://www.googleapis.com/auth/calendar.events.readonly` to read events, and `https://www.googleapis.com/auth/calendar.calendarlist.readonly` to list the user's calendars. With only the first one, sync answers `409` asking to reconnect, rather than silently importing just the primary calendar.
- **Which calendars are read:** the primary one, plus every other calendar the user has *visible* in Google Calendar (for example a secondary calendar called "Personal"). Hidden calendars and holiday, birthday and weather calendars are skipped, because their all-day events would mark whole days as busy. The response's `calendars` field says how many were read.
- **Which events count:** cancelled, "free" (transparent), declined and `workingLocation` events are ignored. All-day events block the whole day. Google expands recurrences (`singleEvents=true`).
- **Replaces only** `GOOGLE_CALENDAR` blocks; `ICS` and `MANUAL` blocks are untouched.
- **No refresh token returned:** Google only issues one on the first consent. If one is already stored it is kept; if the user never connected, the request is `400`.
- **Revoked access:** if Google rejects the stored token (or it can no longer be decrypted because the key changed), it is deleted and the response is `409`; the user has to connect again.
- **Not synchronous-safe by design:** the sync runs inside the request. A database transaction is only held for the final replacement of blocks, not during the calls to Google.
- **No Google SDK:** the backend calls Google's REST API directly with Spring's `RestClient`, so there are no extra dependencies.
- **Native codes** carry no `redirect_uri`: leave `GOOGLE_REDIRECT_URI` empty. Set it only if the client uses the web flow.

#### Google Cloud setup

1. Create a project and enable **Google Calendar API**.
2. Configure the OAuth consent screen. While in "Testing" mode, add your account as a test user and add both scopes under *Data access*. In that mode Google may expire refresh tokens after about 7 days.
3. Create an OAuth client of type **Web application**. Its Client ID is the `serverClientId` the mobile app uses, and its ID and secret go in `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`.

#### Getting a code by hand (to test without the app)

Add `http://localhost:8080/oauth/callback` as an authorized redirect URI on the client, start the server with `GOOGLE_REDIRECT_URI=http://localhost:8080/oauth/callback`, and open this URL in a browser (replace `YOUR_CLIENT_ID`):

```text
https://accounts.google.com/o/oauth2/v2/auth?client_id=YOUR_CLIENT_ID&redirect_uri=http://localhost:8080/oauth/callback&response_type=code&scope=https://www.googleapis.com/auth/calendar.events.readonly%20https://www.googleapis.com/auth/calendar.calendarlist.readonly&access_type=offline&prompt=consent
```

After accepting, the browser lands on an error page: that is expected. Copy the value between `code=` and `&scope` from the address bar (turn `%2F` back into `/`) and send it immediately: codes are single-use and expire within minutes.

---

## Activities

An activity has a title, description, category, start/end (`ZonedDateTime`), location name and coordinates. `createdBy` records who created it (`null` for seeded ones).

- **Creating:** the end must be after the start and **in the future**, and coordinates must be valid (`latitude` in [-90, 90], `longitude` in [-180, 180]).
- **Search (`GET /api/activities`):**
  - `radius` is in **kilometers**, default 1, maximum 50. A larger or non-positive value gives `400`.
  - Only activities that have not ended are returned, closest first.
  - The database does a fast **Bounding Box** filter (`BETWEEN` on latitude and longitude, widened by `cos(latitude)` for longitude) and the exact distance is refined in memory with Haversine. The box does not cover the poles or the antimeridian, which is irrelevant for this use case.
- **Join:** `404` if the activity does not exist, `409` if you are already enrolled or it already ended. A unique constraint also guards against two simultaneous requests.
- **Leave:** `204`. If you were not enrolled, `404`.

### Preference engine (time decay)

A user's affinity for a category is derived from the activities they joined in the **last 90 days**:

```text
P_c = sum over each joined activity i of category c of  1 / (1 + d_i)
```

`d_i` is the number of whole days since the user **joined** activity `i`, so something joined today contributes `1`, yesterday `0.5`, three days ago `0.25`. The score is cached in `UserPreference` and **fully recomputed from the enrollments** on every join *and* leave, so it can never drift from the real data. The recalculation is synchronous (one user's rows only).

### Recommendations

`GET /api/activities/recommendations` takes the nearby candidates (default radius 5 km), **excludes the ones you already joined**, and ranks them with:

```text
score = 0.6 × affinity + 0.4 × proximity
affinity  = score of the activity's category / the user's highest category score   (0 for a new user)
proximity = 1 − distance / radius
```

A user with no preferences gets purely distance-ordered results. At most 20 are returned, each with its `score` (0 to 1).

### Sample data

Outside the `test` profile, an `ActivitySeeder` creates 8 activities around Bogotá (universities, libraries, museums, parks) when no upcoming activity exists, so it does not duplicate them on every restart. Coordinates are approximate. The example `lat=4.6&lon=-74.0` from the spec is far from all of them; try something like `lat=4.6382&lon=-74.0840&radius=2`.

---

## Analytics

The backend sends product events to the analytics engine so the team can answer business questions on the dashboard. It follows one rule: **whoever already holds the information emits the event.**

| Event | Emitted by | Why |
|---|---|---|
| `recommendation_shown` | **Backend** | It builds the list and knows the user's free time |
| `recommended_activity_selected` | **Backend** | It receives the `join` |
| `friend_availability_used` | **Backend** | It serves `/api/friends/gaps` |
| `app_loading_time`, `screen_view`, `crash` | Mobile apps, directly to the engine | Only the client knows them. The backend never sees these |

The full event contract (fields, status codes, rules for the apps) lives in `analytics-contract.md`.

```text
App ◀── recommendations + X-Recommendation-Id ──────────── Backend ── recommendation_shown ──▶ Engine
App ── join?recommendationId=...&freeTimeMinutes=... ────▶ Backend ── recommended_activity_selected ──▶ Engine
```

### What the backend sends

Every event carries `eventType`, `eventId` (a new UUID per event, so the engine can drop duplicates), `userId` (the same UUID as `user.id` in the auth responses, which joins backend and app events per user) and `timestamp` (UTC, ISO 8601). It is posted to `POST {ANALYTICS_URL}/events`, with `X-API-Key` when `ANALYTICS_INGEST_KEY` is set. No personal data is sent beyond the user id.

| Event | When | Extra fields |
|---|---|---|
| `recommendation_shown` | Each `GET /api/activities/recommendations` that returns **at least one** activity. An empty list does not count | `recommendationId`, `freeTimeMinutes` |
| `recommended_activity_selected` | A **successful** `join` that carries a valid `recommendationId` | `activityId`, `category`, `recommendationId`, `freeTimeMinutes` (omitted if unknown) |
| `friend_availability_used` | Each successful `GET /api/friends/gaps` | `action`, always `viewed` for now |

### Recommendation headers and the join parameters

The body of `/recommendations` is unchanged (still a plain list), so no existing client breaks. The data that links a selection to its list travels in two **response headers**:

| Header | Meaning |
|---|---|
| `X-Recommendation-Id` | UUID generated for this load of the list. Present even when the list is empty |
| `X-Free-Time-Minutes` | Minutes the user has left free from **now**, inside the day window (06:00–22:00 by default). `0` if they are busy right now or it is outside the window |

If the user joins an activity **from that list**, the app echoes both values on the join:

```text
POST /api/activities/{id}/join?recommendationId=<X-Recommendation-Id>&freeTimeMinutes=<X-Free-Time-Minutes>
```

If the user joins from somewhere else (for example the nearby search), the app sends neither and no event is emitted. The app should also send `?tz=` to `/recommendations` if the user's zone is not Bogotá; an invalid zone gives `400`.

Rules worth knowing:

- **Analytics data from the client can never block a join.** A `recommendationId` that is not a UUID is ignored (the join succeeds, no event). A `freeTimeMinutes` that is not an integer from 0 to 1440 is dropped and the event goes out without it.
- **No double counting.** A failed join (`404`, `409`) emits nothing, so a repeated join cannot count twice.
- **`freeTimeMinutes` is the time remaining from now**, not the length of the whole gap, and a recommended activity may not fit inside it.

### It never slows the API down

Events leave through a single background thread with a bounded queue (1000). Connect and read timeouts are 1 s and 2 s. If the engine is down, slow or rejects an event, the event is **dropped** and a warning is logged (`No se pudo enviar el evento <type> al engine: <reason>`); the request that produced it is never delayed or failed. Dropped events are not retried.

### Checking the pipeline end to end

With the engine running and a Bearer token (see the free-time note below):

```bash
# 1. Load recommendations and read the two headers
curl -i "http://localhost:8080/api/activities/recommendations?lat=4.6382&lon=-74.0840&radius=2&tz=America/Bogota" \
  -H "Authorization: Bearer <TOKEN>"

# 2. Join one of the returned activities, echoing both header values
curl -X POST "http://localhost:8080/api/activities/<ACTIVITY_ID>/join?recommendationId=<X-Recommendation-Id>&freeTimeMinutes=<X-Free-Time-Minutes>" \
  -H "Authorization: Bearer <TOKEN>"

# 3. Check friend availability
curl "http://localhost:8080/api/friends/gaps?dateTime=2026-10-02T15:00:00Z" \
  -H "Authorization: Bearer <TOKEN>"
```

On a fresh engine database, the dashboard should then show for that user: 1 recommendation load shown, 1 selection linked to the same `recommendationId`, and 1 user of friend availability. Events are sent asynchronously, so give it a couple of seconds.

If something does not show up, look at the backend log for the warning above: a `401` means the ingest key does not match, a `422` means the engine rejected a field (its own log has the detail), and *connection refused* means the engine is not running or `ANALYTICS_URL` points elsewhere.

**Free time is `0` outside 06:00–22:00 Bogotá**, even with an empty schedule. That is expected, not a bug. To test at night, start the backend with `APP_SCHEDULE_DAY_START=00:00` and `APP_SCHEDULE_DAY_END=23:59`.

---