# Postman

## Import

Import `URL-Shortener.postman_collection.json` and `Local.postman_environment.json` into Postman, then select the **URL Shortener - Local** environment.

## Run

Start the app with `./gradlew bootRun`. The default server URL is `http://localhost:8080`; update the environment's `baseUrl` if the port or host differs.

Run the requests in this order for a complete happy path:

1. **Create link** creates a link that expires 60 days from the request and saves `shortCode` and `shortUrl` into the environment.
2. **Redirect created link** checks the actual `302` response and `Location` header. Redirect auto-follow is disabled for this request.
3. **Read link analytics** checks that the redirect was counted.

The remaining requests independently check an unsupported URL scheme, an unknown code, and an analytics interval beyond the configured maximum. The collection does not create an expired record; expired-link behavior is covered by the H2 integration test because the public API rejects past expiration values. Analytics currently returns a total count for the half-open interval `from <= occurred_at < to`, not time buckets.

The API currently has no authentication integration. H2 is in-memory, so created links and analytics disappear when the app stops. These requests are local development checks, not a production security test suite.
