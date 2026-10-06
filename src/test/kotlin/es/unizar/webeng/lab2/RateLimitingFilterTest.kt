package es.unizar.webeng.lab2

import com.github.benmanes.caffeine.cache.Cache
import io.github.bucket4j.Bucket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Integration tests for the IP-based rate-limiting filter and its HTTP responses.
 *
 * Each scenario uses a distinct remote address so its bucket starts full and
 * test outcomes do not depend on the order in which JUnit executes methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitingFilterTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var rateLimitingFilter: RateLimitingFilter

    /**
     * Sets a Bucket's determinated state.
     */
    private fun setBucketForIp(
        clientIp: String,
        bucket: Bucket,
    ) {
        val bucketsField = RateLimitingFilter::class.java.getDeclaredField("buckets")
        bucketsField.isAccessible = true
        val buckets = bucketsField.get(rateLimitingFilter) as Cache<String, Bucket>
        buckets.put(clientIp, bucket)
    }

    /**
     * Creates a bucket and uses up the initial balance.
     */
    private fun exhaustedBucket(): Bucket {
        val bucket =
            Bucket
                .builder()
                .addLimit { limit ->
                    limit
                        .capacity(BUCKET_CAPACITY.toLong())
                        .refillGreedy(REFILL_TOKENS_PER_SECOND.toLong(), Duration.ofSeconds(1L))
                }.build()

        repeat(BUCKET_CAPACITY) { require(bucket.tryConsume(1)) }
        return bucket
    }

    /**
     * A permitted request should continue to the controller and expose the
     * remaining balance after consuming one of the IP's 50 tokens.
     */
    @Test
    fun permitsRequestAndReportsRemainingTokens() {
        val result =
            mockMvc
                .perform(get(TIME_PATH).with(remoteAddress("198.51.100.1")))
                .andReturn()

        assertEquals(200, result.response.status)
        assertNotNull(result.response.getHeader(REMAINING_HEADER))
        assertEquals("49", result.response.getHeader(REMAINING_HEADER))
        assertTrue(result.response.contentAsString.contains("\"time\""))
    }

    /**
     * Creating an exhausted bucket deterministically ensures the next request from
     * that IP is rejected with the expected status, body and retry hint.
     */
    @Test
    fun rejectsRequestsAfterBucketCapacityIsConsumed() {
        val clientIp = "198.51.100.2"
        setBucketForIp(clientIp, exhaustedBucket())

        val result =
            mockMvc
                .perform(get(TIME_PATH).with(remoteAddress(clientIp)))
                .andReturn()

        assertEquals(429, result.response.status)
        assertEquals(TOO_MANY_REQUESTS_BODY, result.response.contentAsString)
        assertTrue(
            result.response
                .getHeader(RETRY_AFTER_HEADER)
                ?.toLongOrNull()
                ?.let { it > 0 } == true,
        )
    }

    /**
     * Buckets are keyed by remote IP, so exhausting one client's quota must
     * not consume tokens belonging to another address.
     */
    @Test
    fun keepsRateLimitsIndependentForDifferentIps() {
        val exhaustedIp = "198.51.100.3"
        setBucketForIp(exhaustedIp, exhaustedBucket())

        val otherClientResult =
            mockMvc
                .perform(get(TIME_PATH).with(remoteAddress("198.51.100.4")))
                .andReturn()

        assertEquals(200, otherClientResult.response.status)
        assertEquals("49", otherClientResult.response.getHeader(REMAINING_HEADER))
    }

    /**
     * The filter is scoped to `/time`: an exhausted IP should still be able to
     * reach Spring's normal handling of a different, nonexistent route.
     */
    @Test
    fun doesNotRateLimitOtherRoutes() {
        val clientIp = "198.51.100.5"
        setBucketForIp(clientIp, exhaustedBucket())

        val otherRouteResult =
            mockMvc
                .perform(get("/not-a-rate-limited-route").with(remoteAddress(clientIp)))
                .andReturn()

        assertEquals(404, otherRouteResult.response.status)
        assertFalse(otherRouteResult.response.containsHeader(REMAINING_HEADER))
        assertFalse(otherRouteResult.response.containsHeader(RETRY_AFTER_HEADER))
    }

    /**
     * We drain the bucket and then poll until the refill restores at least one
     * token. This keeps the test deterministic and still validates the real
     * behavior of the refill cycle.
     */
    @Test
    fun permitsRequestAgainAfterTokensAreRefilled() {
        val clientIp = "198.51.100.6"
        setBucketForIp(clientIp, exhaustedBucket())

        val initialRejectedResult =
            mockMvc
                .perform(get(TIME_PATH).with(remoteAddress(clientIp)))
                .andReturn()
        assertEquals(429, initialRejectedResult.response.status)

        val deadline = System.nanoTime() + REFILL_TEST_TIMEOUT_SECONDS * NANOS_PER_SECOND
        var responseStatus = 429

        while (responseStatus == 429 && System.nanoTime() < deadline) {
            Thread.sleep(REFILL_POLL_INTERVAL_MILLIS)
            responseStatus =
                mockMvc
                    .perform(get(TIME_PATH).with(remoteAddress(clientIp)))
                    .andReturn()
                    .response.status
        }

        assertEquals(200, responseStatus, "A request should be admitted after enough time has elapsed")
    }

    /**
     * Many simultaneous requests from one address must all receive valid
     * limiter responses. The successful count is bounded by the initial
     * capacity plus tokens that could refill during the measured test window;
     * the exact count is intentionally not fixed because execution takes time.
     */
    @Test
    fun handlesConcurrentRequestsFromTheSameIp() {
        val clientIp = "198.51.100.7"
        val requestCount = CONCURRENT_REQUEST_COUNT
        val executor = Executors.newFixedThreadPool(CONCURRENT_WORKER_COUNT)
        val startGate = CountDownLatch(1)

        try {
            val futures =
                (1..requestCount).map {
                    executor.submit<RateLimitResponse> {
                        startGate.await()
                        val response =
                            mockMvc
                                .perform(get(TIME_PATH).with(remoteAddress(clientIp)))
                                .andReturn()
                                .response

                        RateLimitResponse(
                            status = response.status,
                            remaining = response.getHeader(REMAINING_HEADER),
                            retryAfter = response.getHeader(RETRY_AFTER_HEADER),
                            body = response.contentAsString,
                        )
                    }
                }

            val startedAt = System.nanoTime()
            startGate.countDown()
            val responses =
                futures.map {
                    it.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            val elapsedNanos = System.nanoTime() - startedAt
            val allowedResponses = responses.filter { it.status == 200 }
            val rejectedResponses = responses.filter { it.status == 429 }
            val maximumRefillsDuringTest =
                ceil(elapsedNanos.toDouble() / NANOS_PER_SECOND * REFILL_TOKENS_PER_SECOND).toInt()

            assertEquals(requestCount, allowedResponses.size + rejectedResponses.size)
            assertTrue(allowedResponses.isNotEmpty(), "Some concurrent requests should be admitted")
            assertTrue(rejectedResponses.isNotEmpty(), "Excess concurrent requests should be rejected")
            assertTrue(
                allowedResponses.all {
                    it.remaining?.toLongOrNull() in 0L until BUCKET_CAPACITY
                },
                "Every admitted response should include a valid remaining-token count",
            )
            assertTrue(
                rejectedResponses.all {
                    it.body == TOO_MANY_REQUESTS_BODY &&
                        it.retryAfter?.toLongOrNull()?.let { seconds -> seconds > 0 } == true
                },
                "Every rejected response should include the documented body and retry header",
            )
            assertTrue(
                allowedResponses.size <= BUCKET_CAPACITY + maximumRefillsDuringTest,
                "The limiter must not admit more tokens than capacity plus possible refills",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Sets the servlet request's actual remote address so tests exercise the
     * same client identity source used by the filter.
     */
    private fun remoteAddress(ipAddress: String): RequestPostProcessor =
        RequestPostProcessor { request ->
            request.remoteAddr = ipAddress
            request
        }

    private data class RateLimitResponse(
        val status: Int,
        val remaining: String?,
        val retryAfter: String?,
        val body: String,
    )

    private companion object {
        const val TIME_PATH = "/time"
        const val BUCKET_CAPACITY = 50
        const val REMAINING_HEADER = "X-Rate-Limit-Remaining"
        const val RETRY_AFTER_HEADER = "X-Rate-Limit-Retry-After-Seconds"
        const val TOO_MANY_REQUESTS_BODY = "Too many requests"
        const val REFILL_TOKENS_PER_SECOND = 10
        const val REFILL_POLL_INTERVAL_MILLIS = 50L
        const val REFILL_TEST_TIMEOUT_SECONDS = 8L
        const val MAX_REQUESTS_IN_EXHAUSTION_CHECK = 70
        const val CONCURRENT_REQUEST_COUNT = 200
        const val CONCURRENT_WORKER_COUNT = 32
        const val CONCURRENT_TEST_TIMEOUT_SECONDS = 15L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
