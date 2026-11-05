# To-Do

A task manager with projects, tags, reminders and role-based administration. It is a small product,
built to show how I would structure a Spring Boot service that someone else has to maintain: strict
layers, validated DTO boundaries, one error format, tests that go through HTTP, and a database that is
queried on purpose rather than by accident.

**The problem it solves.** A to-do list is only useful if items can be grouped, filtered and found
again. So every task belongs to one account, can sit in one project and carry several tags, has a
status, a priority and an optional due date; the list page filters, searches, sorts and pages that
data in SQL. Overdue items are surfaced both in the UI and by email, and nothing is ever destroyed by
accident because deletes are soft.

---

## Stack

| Concern | Choice |
| --- | --- |
| Language / framework | Java 21, Spring Boot 3.5.16 (Maven wrapper, no local install needed) |
| Database | PostgreSQL 16, schema under version control with Flyway |
| Persistence | Spring Data JPA / Hibernate, `ddl-auto: validate` |
| Auth | Spring Security 6, stateless JWT (jjwt, HS256), BCrypt passwords |
| API docs | springdoc-openapi, served at `/swagger-ui.html` |
| Frontend | One plain HTML/CSS/JS page, no build step |
| Tests | JUnit 5, Mockito, AssertJ, MockMvc, Testcontainers, embedded PostgreSQL |
| Quality gates | JaCoCo coverage rule, GitHub Actions build |

## Run it

```bash
mvnw.cmd spring-boot:run
```

On macOS or Linux use `./mvnw spring-boot:run`. The command activates the `dev` profile, which starts
its own PostgreSQL on port 5433 (data under `target/`, so it survives restarts), runs the Flyway
migrations and seeds a demo account. No database, Docker or environment variables are required.

Open <http://localhost:8080> and sign in with:

```
demo@todo.local / Summer2026!
```

The seeder creates six tasks across two projects and three tags, in every state, so filters, the
overdue flag and sorting have something to show on first load. `admin@todo.local` uses the same
password and holds the ADMIN role for `/api/admin/**`.

To run it the way it would be deployed instead:

```bash
cp .env.example .env      # then set JWT_SECRET (openssl rand -base64 32)
docker compose up --build # app on 8080, PostgreSQL, and mailpit on 8025 for reminders
```

Tests:

```bash
mvnw.cmd verify
```

That runs the unit and HTTP integration tests, runs the Testcontainers tests wherever Docker is
available, and fails the build if the service layer drops below 70% line coverage.

## Architecture

Requests move through one direction only, and each layer has exactly one job.

```
HTTP
  -> SecurityFilterChain            stateless, no session
  -> JwtAuthenticationFilter        token -> SecurityContext
  -> @RestController                DTO in, DTO out
  -> @Service                       rules, ownership, transactions
  -> Repository                     JPA and hand-written queries
  -> PostgreSQL
```

| Layer | What lives there | What is not allowed there |
| --- | --- | --- |
| `controller` | Path variables, query binding, `@Valid` on request bodies, status codes | Business rules, transactions, entity access |
| `service` | Ownership checks, validation the database cannot express, `@Transactional` boundaries | HTTP types (`HttpStatus` stays out except through `ApiException`) |
| `repository` | JPA interfaces and the few `@Query` statements that need them | Any filtering done in Java |
| `entity` / `dto` | Table mapping / the JSON contract | Entities are never serialized; DTOs are records |
| `mapper` | Entity to DTO, DTO to entity | Persistence or validation |
| `exception` | `ApiException`, `ErrorCodes`, one `@RestControllerAdvice` | Ad hoc `Map.of("error", ...)` in controllers |

Every query is scoped by `owner_id` before anything else, so one account cannot read, change or delete
another account's rows even if it guesses an id. A cross-account read returns 404, not 403, so the API
does not confirm that the row exists.

## API

23 operations, browsable at `/swagger-ui.html` while the app runs.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/auth/register` | Create an account, returns a token immediately |
| `POST` | `/api/auth/login` | Exchange credentials for an access token |
| `GET` | `/api/auth/me` | The signed-in user |
| `GET` | `/api/tasks` | Page through tasks: `status`, `priority`, `projectId`, `tagId`, `overdue`, `q`, `page`, `size`, `sortBy`, `descending` |
| `GET` | `/api/tasks/{id}` | One task with its tags |
| `POST` `PUT` `PATCH` | `/api/tasks`, `/api/tasks/{id}` | Create, replace, or change individual fields |
| `POST` | `/api/tasks/bulk-status` | Set one status on up to 200 selected tasks |
| `GET` | `/api/tasks/stats` | Totals per status plus the overdue count |
| `DELETE` | `/api/tasks/{id}` | Soft delete |
| `GET` `POST` `PUT` `DELETE` | `/api/projects`, `/api/projects/{id}` | Projects; `?force=true` needed to delete one that still has open tasks |
| `GET` `POST` `PUT` `DELETE` | `/api/tags`, `/api/tags/{id}` | Tags, per account |
| `GET` `PUT` `DELETE` | `/api/admin/users...` | List, inspect, change role, remove accounts (ADMIN only) |

Every failure uses the same body:

```json
{
  "timestamp": "2026-11-05T12:34:56.789Z",
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "Bad Request",
  "fields": { "title": "size must be between 1 and 200" }
}
```

`error` is a stable code (`ErrorCodes`), so a client can branch on it instead of matching English text.
The same shape is produced whether the failure came from a service (`ApiException`), from Bean
Validation, from Spring Security in a filter, or from a framework error such as an unreadable body, a
wrong HTTP method or an unknown path.

## Design decisions

**Filtering, sorting and paging happen in one SQL statement.** `TaskRepository.findVisibleWithProject`
takes every filter as a nullable parameter and Hibernate compiles it into a single paged query with a
count query. Doing this in Java would mean loading a user's whole table to skip or sort it.

**`sortBy` is checked against a whitelist and paging is clamped.** Only six properties can be sorted,
and anything else falls back to the due date before reaching Hibernate, so a guessed `sortBy` cannot be
used to probe column names. Page size is clamped to 1..100, and a tiebreaker on `id` keeps page
boundaries stable when two tasks share a due date or one has none.

**N+1 is avoided without breaking pagination.** The listing query `left join fetch`es the project (a
to-one, so the row count is unchanged) and leaves `tags` lazy, relying on `@BatchSize` to load one
page's tags in a single extra statement. Fetching both collections in the same paged query is the
tempting mistake: Hibernate de-duplicates the joined rows, which returns fewer items per page than
asked for and makes the count query wrong. `left` join, not `join`, because a task with no project
still has to appear in the list.

**Writes are optimistic-locked.** The tables carry a `@Version`, the API echoes it in every response,
and a `PUT` that sends a stale version is refused with `VERSION_CONFLICT` (409) instead of silently
overwriting the newer copy. `saveAndFlush` is used when writing so that the version in the response is
the one the row actually holds after that write.

**Deletes are soft, and the consequences are handled.** A deleted task disappears from every read but
the row stays, so a mistaken delete is not data loss and the audit trail stays intact. A purge job
(`PURGE_ENABLED`, off by default) removes rows older than a retention window, and deleting a project
detaches its tasks with one bulk `UPDATE` rather than loading thousands of entities.

**Only the indexes the queries need.** Two migrations create them explicitly. `ix_tasks_owner_listing`
serves the dashboard's `owner_id + deleted + status + due_at`; `ix_tasks_due_open` is a *partial*
index on `due_at` for open rows only, which is exactly what the reminder job scans and is much smaller
than an index over the whole table.

**Authentication is a stateless bearer token.** `POST /api/auth/login` returns an HS256 JWT whose
subject is the user id and whose claims carry the role and display name; `JwtAuthenticationFilter`
parses it, populates the `SecurityContext`, and `@EnableMethodSecurity` keeps `/api/admin/**` behind
the ADMIN role. The signing key comes from `JWT_SECRET`, and the application refuses to start outside
dev/test if it is missing or shorter than 32 bytes.

**Login is rate limited and every request is traceable.** A fixed-window Caffeine limiter protects
`/api/auth/**` (`RATE_LIMIT_PER_MINUTE`, default 10 per minute per address, 429 with `Retry-After`),
and a filter puts a request id into the MDC and the `X-Request-Id` response header, so a user's bug
report can be matched to one block of logs.

**Stats are cached, not re-aggregated per poll.** `GET /api/tasks/stats` is a `@Cacheable` Caffeine
entry per user, held for two minutes, which keeps the dashboard cheap when it reloads after every
edit. The tradeoff is accepted deliberately: a total that can lag by up to 120 seconds is not worth a
`GROUP BY` on every page view, and the list beside it is always current.

**Health does not depend on an optional feature.** `spring.mail.host` defaults to empty so the app can
run without SMTP, and the mail health indicator is switched off for the same reason: an unreachable
relay means no reminder emails, not a dead application.

## Testing

| Kind | Where | What it proves |
| --- | --- | --- |
| Unit | `src/test/java/com/todo/app/service`, `security`, `config` | Branches in the rules: ownership refusals, bulk-status limits, version conflicts, token parsing, rate limiting. Repositories are mocked, so these run in milliseconds. |
| HTTP integration | `TaskApiIntegrationTest`, `AccessControlApiIntegrationTest` | The whole application against a real PostgreSQL that the test JVM starts itself: security chain, JPA mapping, Flyway migrations, SQL queries, JSON contract and error bodies. |
| Container | `ToDoContainerTest` | Runs against the official `postgres:16.4-alpine` image, asserts the migrations and indexes exist, and reads rows back with plain JDBC. Skipped automatically where Docker is absent. |

The integration tests are the reason parts of this project work: they caught a listing query that
dropped tasks without a project, an overdue filter that PostgreSQL would not parse, and an error
envelope that was nested one `ResponseEntity` too deep — none of which the mocked tests could see.

JaCoCo enforces 70% line coverage on `com.todo.app.service`; the service layer currently sits at
**95.1%** (271 of 285 lines) across 91 tests. Reports land in `target/site/jacoco/index.html` and, on
CI, as build artifacts alongside the Surefire reports.

## Configuration

Everything environment-specific is read from variables; nothing secret is committed. `.env` is git
ignored and `.env.example` documents it.

| Variable | Default | Meaning |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` for `spring-boot:run` | `dev` starts its own database, `docker` points at a real one |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | localhost:5432 `todo_dev` | Where PostgreSQL is |
| `JWT_SECRET` | empty (dev generates one under `target/`) | HS256 key, must decode to 32+ bytes outside dev |
| `JWT_EXPIRATION_MINUTES` | 120 | Access-token lifetime |
| `ADMIN_EMAIL` / `DEMO_EMAIL` | `admin@todo.local` / `demo@todo.local` | Seeded accounts, only on an empty database |
| `SEED_DEMO_DATA` | true in dev | Skip the seeder |
| `SMTP_HOST` / `SMTP_PORT` | empty / 1025 | Set them to enable reminder emails |
| `REMINDERS_ENABLED` / `REMINDER_WINDOW` | true / 24h | Ten-minute cron, one mail per task |
| `PURGE_ENABLED` / `PURGE_RETENTION` | false / 30d | Hard-delete soft-deleted rows |
| `RATE_LIMIT_PER_MINUTE` | 10 | Per-address limit on `/api/auth/**` |
| `CORS_ALLOWED_ORIGINS` | dev allows `localhost:3000` and Live Server | Empty disables the CORS source |
| `SERVER_PORT` | 8080 | |

## Layout

```
src/main/java/com/todo/app
  config      profiles, security, rate limiting, request-id filter, caching, dev database
  controller  five REST controllers, no logic
  service     auth, tasks, projects, tags, administration
  repository  four JPA interfaces plus the hand-written queries
  entity      User, Project, Tag, Task on a shared base with audit and version columns
  dto         records for every request and response
  mapper      entity <-> DTO
  security    token issue/parse, JWT filter, user details
  scheduler   reminder mail, soft-delete purge
  exception   ApiException, ErrorCodes, GlobalExceptionHandler, ErrorBodyWriter
src/main/resources
  application.yml + application-dev.yml + application-docker.yml
  db/migration  V1__initial_schema.sql, V2__add_indexes.sql
  static        index.html, css/app.css, js/app.js
src/test/java   unit tests, HTTP integration tests, Testcontainers test
```

Auditing (`created_at`, `updated_at`, `created_by`) is filled in by JPA rather than by hand, and the
Flyway migration owns the schema: Hibernate only validates against it, so a mapping drift fails the
build instead of quietly altering a production table.

## What I would do next

- Refresh tokens and a revocation list, so an access token can be kept short without signing people
  out every two hours.
- Keyset pagination for very large lists; the current offset paging is fine at this scale but degrades
  deep into a result set.
- A second replica plus a distributed reminder lock; today the cron would fire on every instance and
  mail twice, which is why reminders are enabled per deployment rather than always on.
- Metrics and tracing beyond the request id, and a dashboard on the Actuator counters.
- Better search: the current keyword filter is a `LIKE` on title and description, which cannot use a
  normal index; `pg_trgm` or a `tsvector` column would be the next step.
- Frontend build step, accessibility review, and a browser-level test of the list page.

## Git and CI

Work happens on short-lived feature branches and lands through a pull request, even when working
alone, so that the change history reads as a sequence of reviewable steps. Commit messages follow
Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).

`.github/workflows/build.yml` runs `mvnw -B verify` on a push to `main` and on every pull request,
uploads the Surefire and JaCoCo reports as artifacts, and then builds the Docker image so a broken
`Dockerfile` fails the build too.
