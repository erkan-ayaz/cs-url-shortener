# Architecture

## Purpose And Status

This document describes the implemented URL shortener built with Java 17, Spring Boot 3, Spring Data JPA, H2, and Flyway. Gradle Wrapper is the build tool. The application includes MVC controllers, JPA entities/repositories, a service interface and implementation, startup configuration, migration, and H2-backed API tests. The initial mode is a single application process with a volatile H2 in-memory database. Caching and security integrations are deliberately excluded from the implementation scope. PostgreSQL is a deferred datasource option for durable or shared storage; the initial mode requires no database server, Docker Desktop, or Testcontainers.

## System Overview

```text
                         +----------------------+
 Client ---------------->| Spring Boot REST API |
                         +----------+-----------+
                                    |
             +----------------------+-----------------------+
             |                                              |
       POST /api/v1/links                              GET /{code}
             |                                              |
  validate URL and expiry                         H2 SELECT by code
             |                                              |
  generate random Base62                           validate active / expiry
             |                                              |
  H2 INSERT + unique constraint                     record analytics in H2
             |                                              |
       201 + short URL                                  HTTP 302 + Location
                                                            |
                                               H2 aggregate query
                                               GET /api/v1/links/{code}/analytics
```

### Components And Ownership

- **API layer:** Spring MVC controllers validate request contracts and translate application outcomes into HTTP responses. Redirect handling is public and intentionally narrow. Authentication, authorization, and rate-limiting integrations are not included in this scope.
- **Short-code generator:** Uses a cryptographically secure random source to select uniformly from the $62^8$ possible eight-character strings, then encodes the integer as fixed-width Base62. Leading zero digits are preserved. Generation does not depend on database sequence values.
- **Application services:** Spring services own code generation, URL/expiry policy, persistence orchestration, redirect resolution, and analytics behavior. Keep controllers thin and test domain decisions independently.
- **Persistence:** The default datasource is H2 in-memory, selected in `application.yml` and overridable through standard Spring datasource environment variables. Spring Data JPA and parameterized queries provide persistence; Flyway initializes the schema. A unique primary key/constraint on `code` is the final collision arbiter. The database is created when Spring opens the datasource and exists only for the process lifetime.
- **Analytics:** Redirect observations are stored in the same H2 instance and queried as aggregates. This avoids a separate service but adds database write load to the redirect path; all analytics are lost on shutdown.
- **Operations:** Spring Boot Actuator exposes liveness/readiness and Micrometer metrics. Logback writes application logs to the console. Distributed tracing and alert delivery are not configured. Do not log full destination URLs or API secrets.
- **Deferred options:** PostgreSQL via a separately supplied JDBC driver and database provides durable/shared state; Redis caching can be considered after moving to a shared database. External identity/API-key management, distributed rate limiting, and external URL reputation scanning are also deferred. The security section distinguishes in-scope secure coding from these deferred integrations.

### Data Model

`links` contains `code` (primary key), `destination_url`, `created_at`, nullable `expires_at`, and nullable `disabled_at`. Store timestamps in UTC. Destination URLs are validated and stored in their accepted representation; do not repeatedly transform a URL in ways that change its semantics. Ownership and moderation fields depend on future authentication and abuse-management requirements and are not part of the initial schema.

`redirect_events` store observed redirects in H2, with a generated event ID, link code, and UTC timestamp. Analytics aggregate by link and time bucket. These records share the link database's process lifetime. Retention and any privacy-sensitive fields must be defined before collecting them; the initial design does not require raw IP addresses or referrers.

## Request Lifecycles

### Create

1. Parse the JSON body and validate URL scheme (`http` or `https`), length, syntax, and policy. Authentication and rate-limiting integrations are deferred.
2. Resolve `expires_at`: apply the configured default if present (the documented local default is 30 days); reject an expiration in the past or beyond the maximum lifetime. If no default is configured, omission means no expiration.
3. Generate a candidate code using secure randomness and fixed-width Base62.
4. Insert the link in the configured datasource (H2 by default). If the unique constraint reports a collision, generate another code and retry a small bounded number of times. Return `503` if generation/insertion cannot complete; never overwrite an existing code.
5. Commit before responding. In the default mode, H2 is the persistence authority for this process only; the records are not durable across restarts.
6. Return `201 Created` with the code, canonical short URL, destination, and timestamps. A caller retry can create another link unless an idempotency key is supported; adding idempotency is recommended for clients that retry non-idempotent requests.

### Redirect

1. Validate the path code. Public redirects do not require authentication; rate-limiting integration is deferred.
2. Read the link from the configured datasource (H2 by default) using a bounded query timeout.
3. Return `404 Not Found` for unknown codes, `410 Gone` for known expired links, and `404` or `410` by explicit policy for disabled links. Return `503` rather than reporting a miss when the database cannot determine link state.
4. For an active link, attempt to record a redirect event in the configured datasource, then return `302 Found` with `Location` set to the validated destination. Analytics persistence failure is logged and metered but does not suppress a valid redirect; this best-effort behavior should be confirmed against the assignment's required analytics guarantees.

Use **302** as the default. A `301` is inappropriate for mutable or expiring links because clients and proxies can cache it beyond the service's control. A `307` is also viable; choose it if method preservation matters. The redirect endpoint should normally accept only `GET` and `HEAD` to avoid replaying arbitrary request methods at destinations.

### Analytics

The API queries aggregates from the configured datasource. The default implementation has no worker or message broker: redirect event persistence is performed directly against H2. This keeps setup simple but makes redirects write-heavy and may add latency. Analytics and links are both lost when the process stops. If analytics writes are best-effort, failures must be observable; if stronger delivery guarantees are required, use a durable datasource and consider an outbox or event broker as a separate design decision.

## API Contracts

The initial implementation does not integrate authentication or authorization. Link creation and analytics are therefore not suitable for exposure to untrusted networks. Public redirects remain unauthenticated by design. Add an identity/API-key integration and ownership model before exposing management operations in production.

### Create link

`POST /api/v1/links`

Request:

```json
{
  "url": "https://example.com/path?x=1",
  "expires_at": "2027-01-01T00:00:00Z"
}
```

`expires_at` is optional and RFC 3339/UTC. Response `201`:

```json
{
  "code": "Ab3dE9xQ",
  "short_url": "https://sho.rt/Ab3dE9xQ",
  "url": "https://example.com/path?x=1",
  "created_at": "2026-10-13T12:00:00Z",
  "expires_at": "2027-01-01T00:00:00Z"
}
```

Expected errors: `400` malformed request or invalid URL/expiry, `409` idempotency conflict if idempotency is implemented, and `503` when safe creation is unavailable. Authentication and rate-limit responses do not exist until those integrations are added.

### Redirect

`GET /{code}` and `HEAD /{code}` return `302` plus `Location` for active links. Errors are `404` unknown/disabled (subject to disclosure policy), `410` expired, and `503` when the service cannot determine link state safely. Error responses must not expose database details. A `429` response becomes applicable only if rate limiting is integrated later.

### Analytics

`GET /api/v1/links/{code}/analytics?from=<RFC3339>&to=<RFC3339>` returns `200` with `code`, normalized `from`/`to` timestamps, and `redirect_count`. The query interval is half-open (`from <= occurred_at < to`). The implementation returns a total count only; bucketed analytics are not implemented. Return `400` for invalid or excessive ranges and `404` for unknown codes. Because authorization is not integrated, this endpoint must not be publicly exposed where usage data is sensitive.

## Base62 Code Generation

The alphabet is `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz` (62 symbols). In Java, use `SecureRandom` to generate a candidate integer uniformly in `[0, 62^8)`; rejection-sample out-of-range values rather than applying modulo to random bytes. Convert the accepted integer to Base62 and left-pad with the alphabet's zero digit to exactly eight characters. Do not use `java.util.Random`, timestamps, or counters.

The namespace contains $62^8 \approx 2.18 \times 10^{14}$ codes. Random generation does not guarantee uniqueness: the database constraint does. With $n$ allocated codes, the approximate collision probability is $n(n-1)/(2 \cdot 62^8)$ while that probability is small. Monitor collision retries and reconsider code length as volume grows. Do not reveal whether candidate codes exist through a management interface.

## Scenario Breakdown

### 1. Greenfield: establish the core

**Initial design:** Define the three API operations, a stable error schema, link lifecycle states, and persistence contract before optimizing. Run one Spring Boot instance with H2 as the authority for links and analytics during development/evaluation. Start with an indexed primary key on `code`; add indexes only where query plans justify them. Authentication, authorization, rate limiting, and URL reputation integrations are intentionally deferred.

**Code strategy:** Use the fixed-width secure-random Base62 approach above. It produces non-sequential codes and avoids a centralized ID allocator. Enforce a unique database constraint and bounded collision retry. Sequential database-ID encoding is simpler and collision-free but leaks approximate volume and makes enumeration trivial; it is not the selected default.

**Initial acceptance gates:** API schema tests, URL and expiry validation, collision simulation, Flyway migration tests on H2, redirect status behavior, analytics persistence tests, and the eight-worker concurrency smoke test. The smoke test exercises one local process but does not establish capacity or a production SLO; production load testing against a durable datasource is deferred. Keep persistence behind a repository boundary so a durable datasource can be selected later without changing HTTP semantics.

### 2. Brownfield: proposed Redis evolution (deferred)

**Observed pressure:** The implemented baseline uses one process-local H2 database. That is intentionally volatile and cannot share links across instances. The first evolution for durable multi-instance operation is selecting PostgreSQL through configuration; once PostgreSQL read volume becomes a measured bottleneck, Redis caching can be considered. Both changes are deferred from the initial scope.

**Evolution:** First add the PostgreSQL JDBC driver and configure a developer-managed or hosted PostgreSQL datasource through environment variables. Validate Flyway migrations and JPA behavior against PostgreSQL. If measurements then show redirect-read pressure, introduce Redis as a cache keyed by code. Cache active link records with an expiry no later than the link's own expiration; briefly negative-cache misses. Preserve PostgreSQL as the source of truth and fallback on cache misses. Each transition requires separate implementation and operational review.

**Consistency and invalidation:** After PostgreSQL and Redis are introduced, link creation commits in PostgreSQL before cache fill. Any future destination update, disable, or deletion must invalidate the key after commit; use transactional outbox/retry or a short maximum cache TTL if invalidation delivery must survive process failure. Expiry is checked on every lookup, not inferred solely from Redis TTL. The accepted model is read-after-write from the creating API response and bounded staleness for cached updates; revocation-sensitive links may require synchronous invalidation or versioned cache entries.

**Failure behavior:** Redis errors trigger bounded database fallback, not unbounded retries. Use a circuit breaker, pool limits, timeouts, request concurrency limits, and load shedding; fail with `503` when database fallback risks cascading failure. Track cache hit ratio, Redis latency/errors, DB pool saturation, fallback rate, and hot keys. Test Redis outage behavior and cache stampedes before rollout. Roll out behind a feature flag or staged configuration and compare database read load and redirect latency.

### 3. Ambiguous: turn underspecified policy into decisions

The assignment does not prescribe expiration, unsafe-destination handling, or dead-link semantics. Normalize them as explicit questions, defaults, acceptance criteria, and owner decisions rather than burying assumptions in code.

| Open question | Proposed default | Actionable acceptance criteria |
| --- | --- | --- |
| Do links expire by default? What range is allowed? | Use the configured default (30 days in local configuration); reject past timestamps and enforce a configurable maximum lifetime. If the default is unset, omission means no expiry. | Omitted, valid, past, and over-limit expiry cases are tested; if a cache is added later, its TTL never extends past link expiry; expired links return `410`. |
| Does the service inspect destination content? | Validate scheme and syntax synchronously; do not fetch destinations in the request path. External reputation scanning is a deferred security integration. | Reject non-HTTP(S), credentials in authority, invalid host/URL, and overlong input. Before adding a scanner, define restricted egress and controls against private or metadata address ranges. |
| What is a dead link? | Unknown code is `404`; known expired link is `410`; disabled link is non-redirecting and does not disclose moderation details. A destination returning an error does not change the short-link record automatically. | API and monitoring distinguish missing, expired, disabled, and upstream destination failure; dead-destination checks, if introduced, are asynchronous and do not make redirects depend on the target being reachable. |
| Can users edit or delete links? | Not in the initial public contract. Model disable/update semantics before exposing them. | Any mutation has an owner check, audit trail, cache invalidation strategy, and documented consistency bound. |
| What does analytics guarantee? | Aggregate counts in the selected datasource; H2 counts are volatile and best-effort. | Analytics persistence failures are measurable; the API documents that counts may omit redirects during database write failures and all H2 counts disappear at shutdown; retention is defined before durable storage is enabled. |

Record decisions in an ADR when product answers change these defaults. Convert each accepted row to API/schema behavior, tests, operational metrics, and documentation before release.

## Validation, Risk Control, And Security

### Threat controls

- **In-scope secure coding:** Validate URL scheme, syntax, length, and expiry; use parameterized Spring Data/JPA queries; apply database constraints; return generic errors; bound datasource connection acquisition and JDBC statement execution; and use least-privilege database credentials. These datasource timeouts do not enforce a full end-to-end synchronous MVC request deadline and do not replace authentication or a security integration.
- **Malicious redirects and phishing:** Reject `javascript:`, `data:`, embedded credentials, malformed hosts, and control characters. A syntactically valid URL can still be malicious. Abuse reporting, moderation/deny-list services, and destination reputation scanning are deferred integrations.
- **SSRF:** Redirecting a user does not itself fetch the target. If reputation scanning or preview fetching is later added, isolate it from the API, restrict outbound egress, block loopback/private/link-local/reserved and cloud metadata ranges after DNS resolution, defend against DNS rebinding, and cap redirects, response size, and time.
- **Authentication and abuse controls:** Authentication, authorization, API-key management, distributed rate limiting, bot detection, quotas, and abuse operations are out of current scope. Random codes reduce predictability but are not authorization. Management and analytics endpoints must not be exposed to untrusted networks until appropriate controls are integrated.
- **Privacy:** Do not persist raw IP addresses or full referrers by default. Define analytics retention and deletion behavior before launch.
- **Denial of service:** Enforce body and URL limits, datasource connection/query timeouts, connection-pool bounds, and concurrency budgets. A total synchronous HTTP request deadline, cache, and distributed rate limiter are not implemented.

### Reliability And Consistency

- **Datasource unavailable:** Creation and redirect resolution return `503`. In the default mode, H2 is created with the application; failure to initialize it prevents readiness. There is no cache from which to serve stale data.
- **Process restart:** H2 in-memory contents are discarded when the JVM exits. Links and analytics are not durable. Select PostgreSQL before requiring restart survival or multiple application instances to share state.
- **Database-backed redirects:** Every redirect requires a datasource read and an analytics write attempt. Bound connection pools and query timeouts, measure load, and shed traffic with `503` rather than allowing an unbounded queue.
- **Analytics:** Redirect-event persistence is best-effort and may fail independently of a valid redirect. Record failures in metrics/logs without including sensitive destination data. A durable outbox or broker is a future option if stronger delivery guarantees are required.
- **Health and deploys:** Liveness reports process health. Readiness reflects whether the instance can safely serve its contract and should be removed from traffic when database access is unavailable for uncached requests. Apply backward-compatible migrations before code that depends on new columns; use expand/migrate/contract for destructive changes.

## Operational Signals

Actuator/Micrometer exposes `http.server.requests` and datasource/Hikari metrics. Application counters are `shortener.links.created` and `shortener.redirects`; redirect counters have bounded `outcome` and HTTP `status` tags. Logback records link creation at INFO, redirect success at TRACE, expected redirect errors at INFO, and redirect server errors at WARN. Code-collision retry counts, analytics write failures, and expiration outcomes are not currently emitted as metrics. Avoid `code`, destination URL, and raw IP as metric labels. Distributed tracing, request-ID correlation, and alert delivery are not configured; add them when an operational backend and SLOs are selected.

Hikari connection acquisition and validation timeouts are configurable in milliseconds. Repository query hints and Spring transaction timeouts bound database operations to two seconds. These settings bound database waits but do not guarantee a total wall-clock deadline for synchronous MVC requests. Size the connection pool and timeout values against the selected datasource and expected workload.

The Gradle `check` lifecycle enforces Google Java Format and a minimum 80% JaCoCo line-coverage ratio. SonarQube/SonarCloud analysis and remote quality gates are deferred; the build has no Sonar plugin or credentials requirement.

## Scaling Path And Known Limits

The H2 default is a single-process development/evaluation mode: each JVM has an isolated database, and state is lost on exit. It is not suitable for horizontally scaled replicas. Select PostgreSQL through configuration when durable shared state is needed; then scale stateless Spring Boot replicas against that shared database. Redis caching remains a deferred option to reduce repeated reads if measurements justify its consistency and operational costs. Partitioning, read replicas, regional routing, a durable event log, and longer codes should be driven by measured traffic and service-level objectives, not added preemptively.

This baseline does not provide durable persistence, caching, authentication or authorization integrations, distributed rate limiting, security scanning, multi-region strong consistency, exact-once analytics, custom domains, link ownership UI, or destination availability monitoring. Management and analytics endpoints are not safe for public exposure without the deferred security integrations. The service also cannot guarantee a `302` is honored by every client or prevent a user from visiting a malicious destination. Those are explicit follow-ups, not properties implied by short-code generation.