# Assignment TODO

Open gaps identified against the assignment. Completed items are retained for traceability.

1. [x] Record the AI-assisted engineering workflow: task decomposition and dependencies, a representative prompt/decision trace, secure AI-use rules, and an engineer sign-off template. The engineer must complete the sign-off fields; this is not self-approved by AI.
2. [x] Demonstrate a completed brownfield quality-gate enhancement with validation. See `WORKFLOW_EVIDENCE.md`; Redis caching remains deferred.
3. [ ] Add behavior-focused tests for analytics range limits, code-collision retries, database failures, and redirect-event write failures; the invalid-destination and expired-link cases are already covered. Maintain coverage beyond the 80% floor.
4. [x] Reconcile reliability claims with implemented Hikari connection/validation timeouts, Jakarta Persistence query hints, Spring transaction timeouts, Actuator/Micrometer metrics, and documented limits. A complete end-to-end synchronous MVC deadline is not implemented or claimed.
5. [x] Remove the stale README statement that the repository has no runnable service.
6. [ ] Add and validate performance/load-test gates against a durable shared database. PR CI and weekly Dependabot update workflows are configured; repository security alerts must be enabled by an administrator. OWASP Dependency-Check remains a policy-driven follow-up, and Sonar integration remains deferred.