# AI-Assisted Prompt Record

## Introduction

This document summarizes the engineer's prompts during development of the URL Shortener assignment. It shows how the requested scope evolved from architecture and documentation into implementation, validation, and delivery. Entries are concise paraphrases of the conversation, not verbatim transcripts. They are evidence of engineer-directed use in this session, not a claim that AI independently owned or approved the work.

## Prompt Timeline

| Stage | Prompt intent | Scope decision or resulting artifact |
| --- | --- | --- |
| 1. Assignment framing | Produce enterprise-quality README and architecture documentation covering APIs, setup, testing, assumptions, and greenfield/brownfield/ambiguous scenarios. | Established the initial documentation and system-design expectations. |
| 2. Stack correction | Replace the initially proposed Python stack with Spring Boot; keep caching and security integrations out of scope. | Documentation was revised to describe a Java service and explicitly deferred integrations. |
| 3. Runtime and database constraints | Target Java 17 and Gradle; avoid Docker Desktop and Testcontainers; compare database options. | Selected an H2 in-memory default, with PostgreSQL documented as a deferred durable option. |
| 4. Runtime configuration | Configure the application so datasource choice can be supplied through `application.yml` and environment variables. | Added H2 startup configuration; PostgreSQL requires its driver and an available database. |
| 5. Build setup | Create the Gradle project files and wrapper. | Added Java 17 toolchain, Spring Boot dependencies, H2/Flyway, and the Gradle Wrapper. |
| 6. API implementation | Build Spring MVC endpoints with JPA entities/repositories, a service interface and implementation, and redirect handling. | Implemented create, redirect, and analytics paths with H2 persistence and error responses. |
| 7. Quality gates | Apply Google Java Format, require at least 80% line coverage, and defer Sonar integration. | Added Spotless and JaCoCo gates to Gradle `check`; Sonar remains deferred. |
| 8. Assignment review | Compare the repository against the supplied interview assignment and identify missing criteria. | Recorded gaps in the workflow evidence, test plan, and load-testing documentation, then resolved or retained them as explicit limitations. |
| 9. Iterative reliability work | Correct the stale README status, add Logback and INFO events, then add TRACE redirect logs and logs for redirect errors. | Added console logging and tests asserting expected redirect log events. |
| 10. Expiration behavior | Validate an expired redirect by inserting an already-expired record and requesting its code. | Seeded an H2 entity directly and asserted `410`, no `Location`, no analytics event, and the expected log/metric. |
| 11. API test tooling | Create a Postman collection and local environment for exercising the app. | Added create, redirect, analytics, invalid-scheme, unknown-code, and excessive-range requests. |
| 12. Assignment evidence | Add workflow evidence, brownfield validation, test plans, CI, and dependency-update strategy. | Added `WORKFLOW_EVIDENCE.md`, a GitHub Actions workflow, Dependabot configuration, and a final engineer-owned submission review template. |
| 13. Submission-context correction | Remove wording that implied an existing pull request or external approval. | Reframed the closing section as a candidate submission self-review, with no fabricated sign-off. |
| 14. Documentation reconciliation | Check all Markdown files against the implementation and assignment requirements. | Corrected claims about tests, analytics buckets, tracing, alerts, and verification commands. |
| 15. Repository delivery | Stage relevant project files and push the assignment work. | Committed and pushed the verified project state; the follow-on documentation in this file is not included in that earlier commit. |

## Iteration Example

The expired-link test illustrates engineer-led refinement:

1. The engineer proposed inserting a link whose expiration was one day in the past and calling its short code.
2. The implementation path was refined to seed the JPA entity directly, because the public create API should reject past expiration values before persistence.
3. The test was strengthened to assert `410 Gone`, no `Location` header, no redirect event, and observable logging/metrics.
4. The engineer requested correction when a test fixture initially exceeded the configured maximum lifetime; the fixture was changed to an expiration within policy.

The test is in `LinkApiIntegrationTest.returnsGoneForExpiredLinkWithoutRecordingRedirect` and runs under `./gradlew check`.

## Ownership And Limits

The prompts constrained the task, technology choices, deferred integrations, and validation expectations. AI suggestions were treated as proposals: the engineer reviewed changes, corrected mismatches, and ran the repository's formatting, test, and coverage gates. This summary does not replace review of the source diff, the engineer's completion of `WORKFLOW_EVIDENCE.md`, or production security/performance validation. H2 remains volatile; authentication, caching, Sonar, and production-scale load validation remain deferred or outstanding as documented in `WORKFLOW_EVIDENCE.md` and `LOAD_TESTING.md`.