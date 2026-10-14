# Load Testing

## Purpose And Scope

The repository includes `UrlShortenerLoadTest` as a lightweight integration smoke test for the interview prototype. It starts Spring Boot on a random port, seeds one short link, and uses an `ExecutorService` with eight workers to issue 96 mixed create and redirect requests through `RestTemplate`. Requests have five-second connect/read timeouts, redirects are not automatically followed, and the test requires all work to finish within a shared 20-second deadline.

Run just this test with:

```sh
./gradlew test --tests com.example.urlshortener.UrlShortenerLoadTest
```

Run it with formatting, all other tests, and the coverage gate using:

```sh
./gradlew check
```

This check answers a narrow question: do concurrent requests complete successfully in the current local setup without exceeding a generous deadline? It does **not** establish requests per second, latency percentiles, a service-level objective, sustained-load stability, or production capacity. Its fixed workload and test-machine timing make it unsuitable as a comparative benchmark.

## Why H2 Is Used

H2 in-memory is the default because it starts with the application, needs no separate server or container runtime, and makes local tests quick and repeatable. That keeps the assignment focused on the API, JPA model, and engineer-led validation.

H2 is process-local and volatile. Its SQL planner, locking, transaction behavior, storage, and connection costs do not represent PostgreSQL under production load. A passing H2 smoke test is not evidence that a multi-instance deployment or durable database meets a performance target.

## Transition To PostgreSQL Load Validation

PostgreSQL is a deferred runtime option. Before using this code for durable or production-like performance testing:

1. Add the PostgreSQL JDBC driver to `build.gradle` (for example, `runtimeOnly 'org.postgresql:postgresql'`) and resolve the production database/version as an explicit project decision.
2. Provide a reachable, isolated PostgreSQL database and externalize `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`. Use a secret manager for shared environments; require TLS and least-privilege credentials where supported. Do not commit credentials.
3. Select PostgreSQL through the configured datasource URL and run Flyway migrations against PostgreSQL. Review every migration for dialect compatibility and validate Hibernate mappings against the migrated schema before load testing.
4. Configure HikariCP deliberately. Spring Boot's JPA starter already brings HikariCP into the runtime. Set and measure `maximumPoolSize`, `minimumIdle`, connection acquisition timeout, and validation timeout against the database connection budget and application concurrency. Avoid setting pool size equal to the load-generator thread count without measuring database capacity.
5. Validate indexes against PostgreSQL query plans. The current migration defines a primary-key index on `links.code`, an index on `links.expires_at`, and a composite index on `redirect_events(link_code, occurred_at)` for time-bounded analytics. Use representative data and `EXPLAIN (ANALYZE, BUFFERS)` to confirm these help the actual query shapes; revise or add indexes based on measured plans and write overhead.
6. Run a repeatable workload against the durable database with a controlled ramp-up, warm-up, steady-state period, and a representative mix of create, hot-code redirect, cold-code redirect, and analytics requests. Record throughput, p50/p95/p99 latency, error rate, JVM CPU/memory/GC, Hikari active/pending connections, database CPU/IO/locks, and query latency.
7. Repeat under pool saturation, database slowdown/unavailability, and recovery. Record the tested dataset size, workload mix, host/database sizing, configuration, and test-tool version so results can be compared later.

The current Hikari connection and validation timeouts are externally configurable. Repository query hints and Spring transaction timeouts bound database operations; they are not an end-to-end HTTP deadline. A PostgreSQL load test must verify the effective settings and resulting behavior rather than infer them from configuration alone.

## Tooling Options

- **Native Java concurrency test (chosen here):** uses the project's existing Spring test environment and JDK, needs no extra load-test dependency or service, runs in `./gradlew check`, and is easy to maintain beside the integration tests. It is appropriate for a small concurrency smoke test, not detailed performance analysis.
- **Gatling:** a code-defined scenario can be reviewed and versioned with the repository, supports ramped virtual-user workloads, and produces latency/throughput reports. It adds a separate simulation/runtime configuration and should target a durable database environment for useful results.
- **Apache JMeter:** a mature, broadly used load-testing tool with a GUI and portable test plans. It is useful for exploratory protocols and non-developer-authored scenarios, but adds JMeter installation, `.jmx` test-plan maintenance, and a separate load-generator process.

The native Java test was selected to keep the evaluation repository self-contained and runnable with its existing Gradle/JDK setup. If the project gains explicit throughput or latency objectives, use Gatling, JMeter, or another dedicated load tool against a production-like PostgreSQL environment and keep the small integration smoke test as a fast regression check.

## Current Limitations

- CI runs the H2 concurrency smoke test but does not enforce throughput or latency thresholds.
- PostgreSQL support requires adding its JDBC driver and validating migrations; no PostgreSQL benchmark has been run.
- The current workload is short and fixed, with no sustained-load, soak, spike, or large-dataset coverage.
- H2 results must not be presented as PostgreSQL or production capacity measurements.