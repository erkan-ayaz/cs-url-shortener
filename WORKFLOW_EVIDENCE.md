# Engineering Workflow Evidence

## Scope And Ownership

This prototype was built with engineer-directed AI assistance. The engineer owns the requirements, decisions, code review, test interpretation, and release decision. AI output is a proposal until reviewed and validated. This record captures representative work and validation; it is not a verbatim transcript of every prompt or a substitute for the repository's commit history.

## Task Decomposition

| Milestone | Intent and acceptance criteria | Evidence/status |
| --- | --- | --- |
| 1. Normalize requirements | Define create, redirect, analytics, expiration, error, and volatile-storage behavior; identify deferred caching and security integrations. | `README.md` and `ARCHITECTURE.md`; H2 is explicitly non-durable. |
| 2. Establish the application | Java 17, Spring Boot MVC, Gradle Wrapper, H2, Flyway, Google Java Format, and JaCoCo. | `build.gradle`, `application.yml`, migration, and `./gradlew check`. |
| 3. Implement the vertical slice | Create links, generate unique Base62 codes, persist with JPA, redirect with `302`, and query aggregate analytics. | MVC controllers, service interface/implementation, JPA entities/repositories, and H2 integration tests. |
| 4. Validate lifecycle and failures | Cover invalid destinations, unknown codes, expired links, analytics bounds, collisions, and database/analytics failures. | Five MockMvc/H2 integration tests, one `RANDOM_PORT` concurrency smoke test, and six Mockito service tests cover API behavior, mixed create/redirect concurrency, range limits, collision retry/exhaustion, create/read data-access errors, and analytics-write failure. A live H2 outage test remains optional follow-up. |
| 5. Add quality gates | Enforce formatting and minimum line coverage locally and in pull requests. | Spotless/Google Java Format, JaCoCo 80%, and `.github/workflows/ci.yml`. Sonar remains deferred. |
| 6. Record operational and release limits | Document timeouts, metrics, logs, volatile data, deferred controls, and the engineer's release decision. | `ARCHITECTURE.md`, `README.md`, and the sign-off record below. |

Dependencies follow the order above: API tests depend on the application/data model; CI invokes the same Gradle `check` task used locally. Do not treat the in-memory H2 deployment as a durable or multi-instance service.

## Traceable Decision Example: Expired Redirect

**Intent:** prove that a known link whose expiration is in the past returns `410 Gone`, does not issue a `Location` redirect, and does not record a redirect event.

**Constraints:** the public create API correctly rejects a past `expires_at`, so creating the expired fixture through `POST /api/v1/links` would test input validation instead of redirect behavior. Keep the test isolated to H2 and avoid sleeping or relying on a short expiration race.

**Prompt iteration and decision record:** this is a representative reconstruction of the work, not a verbatim transcript.

1. **Initial engineer prompt:** “Add an expired-link test by inserting a record whose expiry is one day before now, call its short code, and assert the relevant error.”
2. **AI suggestion:** seed `LinkEntity` directly through the JPA repository; call the real redirect endpoint with MockMvc; assert `410 Gone` and no analytics event.
3. **Engineer refinement:** include an older creation timestamp, verify the response body and emitted `410` log/metric, and avoid sharing the record's exact code with other test cases.
4. **Accepted:** bypass create-request validation only for fixture setup, then exercise the redirect controller/service/repositories through the normal request path. Assert `410`, no `Location`, no redirect event, and the expected observable error signals.
5. **Rejected:** creating the expired record through the public API, because past expiration should be rejected with `400` before persistence. Timing-based tests that sleep for expiration were also rejected as slow and flaky.
6. **Engineer modification:** the committed test uses an eight-character fixture code and derives `createdAt = now - 2 days` and `expiresAt = now - 1 day` at execution time. The AI proposal was reviewed and changed before acceptance.

**Validation:** `LinkApiIntegrationTest.returnsGoneForExpiredLinkWithoutRecordingRedirect` runs with `./gradlew test`; the complete `./gradlew check` also enforces formatting and coverage. Re-run these commands after modifying the expiry path.

## Brownfield Enhancement: Gradle Quality Gates

**Baseline:** the Spring MVC/JPA feature set and H2 integration tests existed, but formatting and line coverage were not enforced by the standard Gradle verification task.

**Change:** add Spotless with Google Java Format and JaCoCo with an 80% line-coverage minimum; wire both into `check`. SonarQube/SonarCloud was not added because it requires separate service, credential, and quality-profile decisions and is explicitly deferred.

**Acceptance and validation:**

```sh
./gradlew spotlessApply
./gradlew check
```

`check` must fail on formatting violations, failing tests, or coverage below the configured threshold. GitHub pull requests run the same `./gradlew check` task in `.github/workflows/ci.yml`. This is the completed brownfield example; Redis caching remains a proposed future scenario, not a completed enhancement.

## Edge-Case And Failure Test Plan

`LinkApiIntegrationTest` and `LinkServiceImplTest` now cover the cases marked implemented below. Database failures are injected as service dependencies; a live H2 outage is not simulated. The remaining cases are explicit follow-up specifications, not claims that they are implemented today.

| Scenario | Status and test setup | Expected behavior |
| --- | --- | --- |
| Excessive analytics interval | Implemented: `LinkApiIntegrationTest` queries a 367-day interval. | `400 Bad Request`; range error returned before database reads. |
| Generated-code collision | Implemented: `LinkServiceImplTest` covers existing-code retry and five-collision exhaustion. | Retry persists the fresh code; exhaustion raises the bounded failure mapped as unavailable. |
| Create/read database failure | Implemented: Mockito injects data-access failures into create persistence and redirect lookup; handler mapping is asserted. | Generic `503`; database details are not exposed. This does not simulate a live database outage. |
| Redirect-event write failure | Implemented: `RedirectEventWriter` throws after an active link resolves. | Service still returns the destination; success metric is incremented and analytics failure is logged. |
| Past/invalid expiration | Follow-up: submit past and over-limit `expires_at` values through the API. | `400 Bad Request`; no link row is persisted. |
| Expired existing link | Implemented: seed an entity with expiration in the past and request its code. | `410 Gone`; no `Location` header and no redirect event. |

Continue using service-level Mockito tests for deterministic collision and injected-failure behavior, and MockMvc/H2 integration tests for HTTP status, serialization, transactions, and migration behavior. Add a live H2 outage test only if the evaluation scope requires infrastructure-failure behavior beyond injected exceptions.

## Lightweight Concurrency Validation

`UrlShortenerLoadTest` starts the application on a random port and uses eight executor workers to issue 96 mixed create/redirect requests through `RestTemplate`. It requires all operations to succeed within a shared 20-second deadline, with five-second HTTP connect/read timeouts and redirect following disabled. This is a repeatable concurrency smoke test for the evaluation app, not a throughput benchmark, capacity claim, or PostgreSQL performance result.

## CI And Dependency Risk Controls

The pull-request workflow checks out the source, installs Temurin Java 17, and runs `./gradlew check`, including the lightweight H2 concurrency smoke test. It uploads JUnit and JaCoCo reports even when checks fail. Dependabot is configured to propose weekly updates for Gradle dependencies and GitHub Actions. Repository administrators should enable Dependabot security alerts/security updates and require the CI check before merge.

Dependabot version updates and vulnerability alerts are the initial dependency-risk controls. OWASP Dependency-Check can be added as a Gradle task or scheduled workflow if policy requires an explicit CVE gate; establish suppression review, severity threshold, and NVD data-cache strategy before making it a blocking check. No scanner result is implied by the current build. Sonar remains deferred.

`UrlShortenerLoadTest` verifies that eight workers can complete 96 mixed create and redirect requests within a 20-second test deadline, using five-second HTTP connect/read timeouts. It is a correctness/concurrency smoke test, not a throughput/latency SLO gate. Before any production claim, establish expected redirect objectives, run repeatable load tests against a durable shared database, and test pool exhaustion, database outage, and recovery. H2 measurements are not representative of PostgreSQL production capacity.

## Final Engineering Summary

- **Plan and rationale:** deliver the create/redirect/analytics vertical slice on Spring Boot MVC, JPA, Flyway, and H2 first; keep durable PostgreSQL, caching, authentication, and reputation scanning out of the prototype scope.
- **Artifacts:** API/service/repository layers, schema migration, H2 configuration, Postman collection, Gradle quality gates, PR CI, Dependabot update proposals, architecture and setup docs, and this workflow evidence.
- **Validation:** `./gradlew check` runs Google Java Format, H2-backed tests, the concurrency smoke test, and the 80% JaCoCo line-coverage gate. It does not provide Sonar analysis, vulnerability scanning, or production-grade load testing.
- **Key risks and limitations:** H2 state is volatile and process-local; management/analytics APIs are unauthenticated; analytics writes are best-effort; no total HTTP deadline, distributed tracing, alert backend, or production-scale load evidence exists.
- **Assumptions:** single-process evaluation, local H2, temporary test data, and evaluation-only exposure. PostgreSQL must be separately added/configured before durable or shared-state operation.
- **Release decision:** the engineer sign-off below is required. The prototype is not declared production-ready.

## Secure AI-Use Guardrails

- Do not provide credentials, access tokens, customer data, production URLs, or other secrets to AI tools. Use synthetic examples and redact logs before sharing them.
- Treat generated code, commands, dependencies, and security claims as untrusted proposals. Review effects and dependency provenance before execution or merge.
- Do not let AI-triggered commands deploy, publish, alter infrastructure, or access production systems. Require explicit engineer review for high-impact actions.
- Validate generated changes with repository tests, formatting/coverage gates, dependency review, and threat-specific reasoning. A passing test suite is not proof of security or production readiness.
- Preserve engineer ownership: the named signatory reviews the final diff, risks, and test evidence and makes the release decision.

## Candidate Submission Review

Complete this self-review as the candidate before submitting the assignment. It records personal ownership and validation; it is not a pull-request approval, external sign-off, or production release decision. Do not claim a check or review that was not actually performed.

> I reviewed the submitted implementation, documentation, and AI-assisted changes. I own the final submitted work and its correctness. I ran `./gradlew check` and reviewed its result. I reviewed the URL validation, invalid/expired-link handling, error responses, dependency configuration, and exposed operational endpoints against the assignment scope. I understand and disclose the limitations listed here: H2 data is volatile, management/analytics APIs have no authentication integration, caching and security integrations are deferred, and production-scale performance against durable shared storage has not been validated. This submission is an evaluation prototype, not a claim of production readiness.

Candidate: `[name]`

Submission date: `[YYYY-MM-DD]`

Submitted revision (optional): `[commit or version, if useful]`

Self-review completed: `[yes/no]`