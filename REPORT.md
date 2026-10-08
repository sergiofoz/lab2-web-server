# Lab 2 Web Server -- Project Report

## What I specified

For the main lab, I planned to:

1. Replace Spring Boot's default error page with my own page. It should show the error status and
   the path that was requested. An unknown path should return HTTP 404.
2. Add `GET /time`. It should return the server time as JSON with a `time` field.
3. Serve the app on port 8443 with HTTPS and HTTP/2.

I planned to check the error page and `/time` with automated tests. I also planned to use `curl`
to check the HTTPS endpoints and confirm HTTP/2.

I proposed two bonus versions on separate branches:

- `feature/rate-limiting-bucket4j`: limit requests by IP with Bucket4j, with a maximum of 50
  tokens and a refill of 10 tokens per second.
- `feature/rate-limiting-spring-gateway`: apply the same limit to `GET /time` using a Spring
  Cloud Gateway MVC route.

For both versions, I planned tests for allowed and blocked requests, separate limits for different
IPs, refill, and simultaneous requests. I also planned k6 spike and stress tests that should show
both HTTP 200 and HTTP 429 responses.

## What I changed

The `main` branch contains the basic lab work:

- `src/main/resources/templates/error.html` shows a custom error page with the status and
  requested path.
- `src/main/kotlin/es/unizar/webeng/lab2/TimeComponent.kt` defines the time response, provider,
  service, and `GET /time` controller.
- `src/main/resources/application.yml` sets port 8443, HTTPS, the keystore, and HTTP/2.
- `src/main/resources/localhost.p12` is the local keystore used by the app.
- `src/test/kotlin/es/unizar/webeng/lab2/ErrorPageTest.kt` checks the custom 404 page.
- `src/test/kotlin/es/unizar/webeng/lab2/TimeControllerTest.kt` checks the `/time` JSON response.
- `src/test/resources/application.yml` turns off TLS for tests.

The bonus work is in two separate branches and is not merged into `main`:

- `feature/rate-limiting-bucket4j` has a custom Bucket4j filter, a bounded Caffeine cache, and
  rate-limit tests.
- `feature/rate-limiting-spring-gateway` has the Gateway setup, the `/time` route and rate limit,
  integration tests, and `k6/rate_limit_test.js`.

On the Gateway branch, I first tried Gateway's built-in Bucket4j filter. The tests showed that
token use was not kept between requests in this setup. The filter also tried to change response
headers that Spring 7 treats as read-only. I changed the route to use a small custom filter with
the Bucket4j proxy manager. It consumes tokens by client IP and adds the remaining-token header
before the response is written.

## Technical decisions

- `/time` returns a small JSON object with a `time` field.
- The error page uses Spring Boot's error information to show the status and path.
- I used a local PKCS12 keystore for HTTPS and enabled HTTP/2. This certificate is for local
  development, not for a public production service.
- In the Bucket4j branch, I chose a local Caffeine cache with a maximum of 10,000 entries.
  Bucket4j keeps a bucket for 10 minutes after it is full again. This is not the same as removing
  it 10 minutes after every request. The cache is local to one app instance, so different server
  instances do not share their limits.
- In the Gateway branch, the Gateway route handles `GET /time`. It calls the shared time provider
  directly instead of sending a new HTTP request to its own `/time` address. This avoids routing
  the request back to the same Gateway route.
- I kept the two bonus versions on separate branches so they can be reviewed separately. The
  `main` branch keeps the basic lab version.
- The k6 script runs one scenario at a time. This avoids having one scenario use tokens needed by
  the other. It skips certificate checks only for the local test certificate.

## How I verified

On `main`, I ran:

```bash
./gradlew check
```

It passed, including the tests and ktlint checks. I started the app with `./gradlew bootRun` and
ran:

```bash
curl -sk --http2 -H 'Accept: text/html' -i https://127.0.0.1:8443/missing
curl -sk --http2 -i https://127.0.0.1:8443/time
```

The first request returned HTTP/2 404 and the custom page with its status, path, and marker. The
second returned HTTP/2 200 with JSON containing `time`.

On `feature/rate-limiting-bucket4j`, I ran the rate-limit tests and `./gradlew check`. The tests
cover allowed and blocked requests, different IPs, refill, route scope, and simultaneous requests.

On `feature/rate-limiting-spring-gateway`, I ran `./gradlew ktlintFormat`, the focused
`TimeGatewayRateLimitTest` tests, and `./gradlew check`. They passed. The tests check the response,
remaining-token header, bucket capacity, HTTP 429, different IPs, refill, route scope,
simultaneous requests, proxy-manager key storage, and the refill settings.

I ran these k6 commands against the Gateway branch:

```bash
k6 run -e SCENARIO=spike k6/rate_limit_test.js
k6 run -e SCENARIO=stress k6/rate_limit_test.js
```

The spike sent 100 requests and got 50 HTTP 200 and 50 HTTP 429 responses. The stress test
scheduled 20 requests per second for 15 seconds. It got 195 HTTP 200 and 106 HTTP 429 responses,
with no dropped iterations. Both runs had no unexpected response codes, and all script checks
passed. k6 also counts HTTP 429 as a failed HTTP request. For this test, 429 is an expected rate
limit response and the script counts it separately.

The first Gateway tests found the problems with the built-in filter and response headers described
above. I changed the filter and ran the Gateway tests and `./gradlew check` again; they passed.

## AI disclosure

| Field | Details |
| --- | --- |
| **Tools / skills** | Copilot SDK in VS Code, used as an AI assistant. Gradle, Git, curl, and k6 were also used for coding or checks. No separate Copilot skill was used. |
| **Purpose** | I used the AI assistant to discuss design options, help write and fix rate-limit code and tests on the two bonus branches and prepare the k6 script. |
| **Representative prompts** | “Compare bucket lifetime options”; “Plan tests for refill and simultaneous requests”; “Implement the approved Gateway route for `/time`”; “Create k6 spike and stress scenarios with checks for HTTP 200 and 429.” |
| **Affected files/sections** | The main-branch app files listed in **What I changed** were already in the repository. AI help was used for the Bucket4j filter and tests on `feature/rate-limiting-bucket4j`, the Gateway setup, route and tests on `feature/rate-limiting-spring-gateway` and `k6/rate_limit_test.js` |
| **Validation steps** | I ran `./gradlew check` on `main` and both bonus branches, ran focused rate-limit tests, checked the main endpoints with curl over HTTPS/HTTP/2, and ran both k6 scenarios. I changed the Gateway filter after the first tests exposed problems. |
| **Citations** | I did not copy external code. The k6 plan was informed by Richard Tan's article “Load Testing Multi-Step Workflows with k6 and Grafana” (28 April 2026), which was provided during our discussion. |
| **Human-reviewed** | I chose the bucket storage policy and approved the Gateway route design. I reviewed the code, tests, and results, and I ran both k6 scenarios. I designed some of the tests main ideas. I ran ./gradlew check and the curl checks and I reviewed their output. I understand how the rate limiter works and its limits: it can restrict requests by IP, but it is not a complete defence against every DoS attack. |
