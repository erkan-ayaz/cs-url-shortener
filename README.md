# URL Shortener

A Spring Boot URL shortener for creating compact links, redirecting visitors, and reporting aggregate usage. The initial implementation uses an automatically created H2 in-memory database, with explicit expiration semantics and bounded database operations. Data is temporary and is lost when the application process stops.

> **Implementation status:** the Spring MVC/JPA API, H2/Flyway configuration, and H2-backed API tests are implemented. H2 data is in-memory and lost at shutdown. Authentication, caching, and PostgreSQL support remain deferred.

## Implemented Stack

- Java 17 and Spring Boot 3 for the HTTP API and application services.
- H2 in-memory as the default database, accessed with Spring Data JPA; Flyway initializes the schema on startup.
- PostgreSQL support is a deferred option. The runner can select it through datasource configuration after the PostgreSQL JDBC driver and database are provided.
- Gradle Wrapper for builds and dependency management.
- JUnit 5, Spring Boot Test, Google Java Format, and JaCoCo. Gradle `check` enforces formatting and at least 80% line coverage.
- Caching and external security integrations are intentionally outside the initial implementation scope.

## Capabilities

- `POST /api/v1/links` creates a short link with an optional expiration.
- `GET /{code}` resolves a public short code and issues a temporary redirect.
- `GET /api/v1/links/{code}/analytics` returns aggregate redirect counts over a requested time window.
- Eight-character Base62 codes are generated from cryptographically secure random values. A database uniqueness constraint is authoritative; collisions are retried.
- Expired links are not redirected and return `410 Gone`. Unknown codes return `404 Not Found`.
- H2 transactions and constraints, bounded datasource connection/JDBC statement timeouts, health checks, Logback logs, and Micrometer metrics provide the initial reliability layers. These database timeouts do not impose a complete end-to-end MVC request deadline. Redirects and analytics use the same in-memory database; no cache is assumed.

The system design is in [ARCHITECTURE.md](ARCHITECTURE.md). Assignment workflow evidence, brownfield validation, test plans, and engineer sign-off are in [WORKFLOW_EVIDENCE.md](WORKFLOW_EVIDENCE.md).

## API At A Glance

The initial API has no authentication integration. Do not expose management or analytics endpoints to an untrusted network until an authentication and authorization integration is added. Public redirects are unauthenticated by design.

Create a link:

```http
POST /api/v1/links
Content-Type: application/json

{
  "url": "https://example.com/articles/engineering",
  "expires_at": "2027-01-01T00:00:00Z"
}
```

Successful creation returns `201 Created` and a body containing `code`, `short_url`, `url`, `created_at`, and `expires_at`. If `expires_at` is omitted, the configured `APP_LINK_DEFAULT_TTL` applies; when no default is configured, the link does not expire. Explicit expirations cannot exceed `APP_LINK_MAX_TTL`.

Follow a link:

```http
GET /Ab3dE9xQ
```

A valid code returns `302 Found` with a `Location` header. A temporary redirect is used rather than `301 Moved Permanently` so that destination changes, revocation, and expiration are not hidden by browser or intermediary caches.

Read analytics:

```http
GET /api/v1/links/Ab3dE9xQ/analytics?from=2026-10-01T00:00:00Z&to=2026-10-08T00:00:00Z
```

Analytics are aggregate and queried from H2. Query bounds are UTC timestamps; the response includes the requested interval and total recorded redirects. Counts are also volatile and disappear when the application stops. Exact response schemas are part of the implementation contract described in [ARCHITECTURE.md](ARCHITECTURE.md).

## Getting Started

### Prerequisites

- JDK 17.
- A free local port for the API (default `8080`). H2 runs in the application process and opens no database server port.

### Database options

H2 in-memory is the default for this phase. Spring Boot opens the database when the application creates its datasource, and Flyway applies the schema migrations during startup. No database installation, Docker Desktop, or external service is needed. The database exists only for the lifetime of the JVM; stopping or restarting the application clears links and analytics.

- **PostgreSQL (deferred):** for durable data or multi-instance deployments, a developer or runner can provide a local PostgreSQL service (for example, installed with Homebrew or Postgres.app) or a managed database. Configure its JDBC URL and credentials in environment variables. PostgreSQL is not required by the current default build; enabling it requires adding the PostgreSQL JDBC driver and verifying the migrations against PostgreSQL.
- **H2 file mode (not the current default):** H2 can store data on disk, but that changes the persistence guarantees and operational behavior. The current configuration intentionally uses `jdbc:h2:mem:` and does not persist data.

### Configure

[`src/main/resources/application.yml`](src/main/resources/application.yml) provides H2 defaults and allows environment variables to override the datasource. Spring Boot infers the JDBC driver from the selected URL; Flyway applies the schema migration and Hibernate validates the mapped schema.

```yaml
spring:
   datasource:
      url: ${SPRING_DATASOURCE_URL:jdbc:h2:mem:shortener;DB_CLOSE_DELAY=-1}
      username: ${SPRING_DATASOURCE_USERNAME:sa}
      password: ${SPRING_DATASOURCE_PASSWORD:}
   jpa:
      hibernate:
         ddl-auto: validate
   flyway:
      enabled: true
```

The following optional settings configure application behavior. Spring Boot does not load a `.env` file by default; export variables in the same terminal used to run Gradle. The datasource variables are optional when using the H2 defaults.

```sh
export SERVER_PORT=8080
export PUBLIC_BASE_URL=http://localhost:8080
export APP_LINK_DEFAULT_TTL=30d
export APP_LINK_MAX_TTL=365d
export APP_LINK_MAX_URL_LENGTH=2048
export DB_CONNECTION_TIMEOUT_MS=2000
export DB_VALIDATION_TIMEOUT_MS=1000
```

For a future PostgreSQL run, set `SPRING_DATASOURCE_URL` to a `jdbc:postgresql:` URL and provide `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`. The PostgreSQL driver and a reachable database must also be available. Production database credentials should come from a secret manager. Authentication, authorization, distributed rate limiting, and destination reputation scanning are deferred integrations, not capabilities of the initial implementation.

### Start the service

The Gradle Wrapper and application are ready to run. The default H2 database is created in memory as Spring initializes the datasource, then Flyway applies the schema migration.

1. Run formatting, tests, and coverage checks:

   ```sh
   ./gradlew clean check
   ```

2. Start the API:

   ```sh
   ./gradlew bootRun
   ```

3. Check readiness and create a link:

   ```sh
   curl -fsS http://localhost:8080/actuator/health/readiness

   curl -i http://localhost:8080/api/v1/links \
     -H 'Content-Type: application/json' \
     -d '{"url":"https://example.com"}'
   ```

4. Follow the returned short URL. Use `curl -i` to inspect the `302` and `Location` header. Stop the application with `Ctrl+C`; its H2 data disappears with the process. Flyway migrations should be backward-compatible and coordinated during production rollouts; avoid unsafe schema changes while multiple application versions are serving traffic.

### Configuration reference

| Variable | Purpose |
| --- | --- |
| `SERVER_PORT` | API bind port. |
| `PUBLIC_BASE_URL` | Origin used to construct returned short URLs. |
| `SPRING_DATASOURCE_URL` | Optional JDBC URL override. Defaults to an H2 in-memory URL; a PostgreSQL URL selects PostgreSQL when its driver is present. |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Optional datasource credentials; H2 defaults to `sa` with an empty password. Use managed secrets for deployed databases. |
| `APP_LINK_DEFAULT_TTL` | Optional default lifetime for a newly created link. |
| `APP_LINK_MAX_TTL` | Maximum permitted lifetime for an explicitly expiring link. |
| `APP_LINK_MAX_URL_LENGTH` | Maximum accepted destination URL length. |
| `DB_CONNECTION_TIMEOUT_MS` | Maximum time Hikari waits to acquire a database connection; defaults to 2000 ms. |
| `DB_VALIDATION_TIMEOUT_MS` | Maximum time Hikari waits to validate a connection; defaults to 1000 ms. |
| JPA query/transaction timeout | Repository query hints and Spring transactions bound database operations to 2 seconds. |

Exact variable names are proposed and must be kept synchronized with the implementation and deployment manifests. No Redis or security-integration configuration is required for this scope.

Actuator exposes `/actuator/health`, `/actuator/health/readiness`, `/actuator/metrics`, and application counters such as `shortener.links.created` and `shortener.redirects`. The metrics endpoint is for local/controlled operations; do not expose it publicly without an access-control boundary.

## Testing Approach

The current suite has four Spring Boot/MockMvc integration tests against H2: create/redirect/analytics, invalid destination, unknown code, and expired link. There are not yet isolated unit tests for the service or code generator. No container runtime or Testcontainers is used. Spotless applies Google Java Format; JaCoCo measures line coverage and fails `check` below 80%.

- **Implemented integration coverage:** datasource startup, Flyway migration, API link creation, `302` redirect, analytics count, invalid destination `400`, unknown code `404`, and expired code `410` without a `Location` header or analytics event.
- **Remaining tests:** isolated Base62 boundaries/randomness, TTL edge cases, analytics range validation, deterministic collision retry/exhaustion, database failure mapping, and redirect-event write failure are listed in [TODO.md](TODO.md).
- **Coverage gate:** JaCoCo enforces at least 80% line coverage across production code during `check`. Coverage is a signal, not a substitute for behavior assertions.
- **Static analysis:** SonarQube/SonarCloud integration, quality profiles, and remote quality gates are deferred; no Sonar plugin, credentials, or server is required.

Useful local verification commands:

```sh
./gradlew test
./gradlew spotlessCheck
./gradlew jacocoTestReport
./gradlew check
```

Run `./gradlew spotlessApply` to apply Google Java Format. The `check` task runs formatting validation, tests, and the JaCoCo 80% line-coverage gate. The HTML report is written to `build/reports/jacoco/test/html/index.html`. Sonar integration is deferred. There is no separate `integrationTest` task; PostgreSQL compatibility tests are deferred until its JDBC driver and datasource are added.

Pull requests run the same Gradle quality gate through [`.github/workflows/ci.yml`](.github/workflows/ci.yml). Dependency and GitHub Actions update proposals are configured in [`.github/dependabot.yml`](.github/dependabot.yml). See [WORKFLOW_EVIDENCE.md](WORKFLOW_EVIDENCE.md) for validation limits and security-scanning follow-up.

## Assumptions And Limitations

- H2 is the source of truth only for the lifetime of one application process. Link records and analytics are lost on shutdown; this is suitable for development/evaluation but not durable production service.
- Codes are random and non-sequential to reduce easy enumeration. They are not authorization tokens; private destinations require access control outside the redirect code.
- Redirects use `302`, not permanent `301`, to preserve the ability to change or revoke destinations and honor expiration promptly.
- Expiration is enforced on every database lookup. Expired rows may be retained for the process lifetime; deletion is not required for correctness.
- Redirect analytics are stored in the same H2 instance and are volatile. Redirects add a database write and this single-process setup does not support shared state across replicas.
- No authentication, authorization, distributed rate limiting, or external destination-scanning integration is included. Do not expose link creation or analytics to untrusted networks until suitable controls are integrated.
- PostgreSQL durability, multi-instance shared state, caching, custom domains, user-facing link management, and high-scale asynchronous analytics are deferred. Configure PostgreSQL before using this service where links must survive restarts or be shared across replicas.
- URL syntax validation alone does not establish destination safety. Reputation scanning, takedown workflows, and abuse operations require separate integrations and operational decisions.
- This is a runnable evaluation prototype, not a production-ready service. H2 data is volatile, and authentication, authorization, caching, durable shared storage, and other deferred integrations are not implemented.