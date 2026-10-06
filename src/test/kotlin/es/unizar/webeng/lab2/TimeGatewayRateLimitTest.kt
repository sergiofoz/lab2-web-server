package es.unizar.webeng.lab2

import io.github.bucket4j.distributed.proxy.AsyncProxyManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cloud.gateway.server.mvc.filter.Bucket4jFilterFunctions
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Exercises the real Spring MVC Gateway route, Bucket4j filter, and response handler.
 *
 * Each scenario uses its own remote IP so bucket state cannot leak between test cases.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureTestRestTemplate
class TimeGatewayRateLimitTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var proxyManager: AsyncProxyManager<String>

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restClient: TestRestTemplate

    @Test
    fun firstRequestReturnsTimeAndRemainingTokenHeader() {
        mockMvc
            .perform(timeRequest("192.0.2.1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.time").exists())
            .andExpect(header().string(Bucket4jFilterFunctions.DEFAULT_HEADER_NAME, "49"))
    }

    @Test
    fun realHttpRequestsConsumeTokensFromTheSameRemoteAddress() {
        val uri = "http://127.0.0.1:$port/time"
        val first = restClient.getForEntity(uri, String::class.java)
        val second = restClient.getForEntity(uri, String::class.java)

        assertEquals(HttpStatus.OK, first.statusCode)
        assertEquals(HttpStatus.OK, second.statusCode)
        assertEquals("49", first.headers.getFirst(Bucket4jFilterFunctions.DEFAULT_HEADER_NAME))
        assertEquals("48", second.headers.getFirst(Bucket4jFilterFunctions.DEFAULT_HEADER_NAME))
    }

    @Test
    fun allowsInitialCapacityThenRejectsWhenTheBucketIsExhausted() {
        val clientIp = "192.0.2.2"

        repeat(INITIAL_BUCKET_CAPACITY) {
            mockMvc.perform(timeRequest(clientIp)).andExpect(status().isOk)
        }

        assertClientIsEventuallyRateLimited(clientIp)
    }

    @Test
    fun differentClientIpsHaveIndependentBuckets() {
        val exhaustedIp = "192.0.2.3"
        val independentIp = "192.0.2.4"

        assertClientIsEventuallyRateLimited(exhaustedIp)

        mockMvc
            .perform(timeRequest(independentIp))
            .andExpect(status().isOk)
            .andExpect(header().string(Bucket4jFilterFunctions.DEFAULT_HEADER_NAME, "49"))
    }

    @Test
    fun bucketAllowsRequestsAgainAfterRefill() {
        val clientIp = "192.0.2.5"
        assertClientIsEventuallyRateLimited(clientIp)

        // Greedy refill adds tokens continuously; 250 ms is enough for at least one token.
        Thread.sleep(REFILL_WAIT.toMillis())

        mockMvc.perform(timeRequest(clientIp)).andExpect(status().isOk)
    }

    @Test
    fun otherMethodsDoNotConsumeGetTimeTokens() {
        val clientIp = "192.0.2.6"

        // POST /time does not match the GET Gateway route and therefore has no rate-limit token.
        val postResponse =
            mockMvc
                .perform(post("/time").with(remoteAddress(clientIp)))
                .andReturn()
                .response
        assertFalse(postResponse.status == HttpStatus.TOO_MANY_REQUESTS.value())

        repeat(INITIAL_BUCKET_CAPACITY) {
            mockMvc.perform(timeRequest(clientIp)).andExpect(status().isOk)
        }
        assertClientIsEventuallyRateLimited(clientIp)
    }

    @Test
    fun concurrentRequestsFromOneIpCannotBypassTheBucket() {
        val clientIp = "192.0.2.7"
        val ready = CountDownLatch(CONCURRENT_WORKERS)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT_WORKERS)

        try {
            val requests =
                (0 until CONCURRENT_REQUESTS).map {
                    executor.submit<Int> {
                        ready.countDown()
                        check(start.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            "Timed out waiting for the concurrent request start signal"
                        }
                        mockMvc
                            .perform(timeRequest(clientIp))
                            .andReturn()
                            .response.status
                    }
                }

            assertTrue(
                ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Not all request workers became ready",
            )
            val startedAt = System.nanoTime()
            start.countDown()

            val statuses =
                requests.map { request ->
                    request.get(REQUEST_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            val elapsedSeconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
            val successfulRequests = statuses.count { it == HttpStatus.OK.value() }
            // Allow for fractional greedy refill and requests arriving at the timing boundary.
            val maximumSuccessfulRequests =
                INITIAL_BUCKET_CAPACITY +
                    ceil(REFILL_TOKENS_PER_SECOND * elapsedSeconds).toInt() +
                    REFILL_TOLERANCE

            assertTrue(statuses.any { it == HttpStatus.TOO_MANY_REQUESTS.value() })
            assertTrue(
                successfulRequests <= maximumSuccessfulRequests,
                "Allowed $successfulRequests requests, above the $maximumSuccessfulRequests " +
                    "capacity/refill bound for this request window",
            )
            assertTrue(
                statuses.all {
                    it == HttpStatus.OK.value() || it == HttpStatus.TOO_MANY_REQUESTS.value()
                },
                "Gateway should answer each concurrent request with either 200 or 429",
            )
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun bucketConfigurationHasFiftyCapacityAndGreedyTenPerSecondRefill() {
        val bandwidth = timeRateLimitBucketConfiguration().bandwidths.single()

        assertEquals(INITIAL_BUCKET_CAPACITY.toLong(), bandwidth.capacity)
        assertEquals(REFILL_TOKENS_PER_SECOND.toLong(), bandwidth.refillTokens)
        assertEquals(Duration.ofSeconds(1).toNanos(), bandwidth.refillPeriodNanos)
        assertTrue(bandwidth.isGready)
    }

    @Test
    fun proxyManagerRetainsTokensForRepeatedRequestsWithTheSameKey() {
        val key = "proxy-manager-${System.nanoTime()}"
        val firstBucket = bucketFor(key)
        val secondBucket = bucketFor(key)

        assertEquals(49L, firstBucket.tryConsumeAndReturnRemaining(1).get().remainingTokens)
        assertEquals(48L, secondBucket.tryConsumeAndReturnRemaining(1).get().remainingTokens)
    }

    private fun assertClientIsEventuallyRateLimited(clientIp: String) {
        repeat(INITIAL_BUCKET_CAPACITY + MAXIMUM_REQUESTS_AFTER_INITIAL_CAPACITY) {
            val response =
                mockMvc
                    .perform(timeRequest(clientIp))
                    .andReturn()
                    .response
            if (response.status == HttpStatus.TOO_MANY_REQUESTS.value()) {
                assertEquals(
                    "0",
                    response.getHeader(Bucket4jFilterFunctions.DEFAULT_HEADER_NAME),
                )
                return
            }
            assertEquals(HttpStatus.OK.value(), response.status)
        }
        throw AssertionError("The bucket should eventually reject requests for client $clientIp")
    }

    private fun bucketFor(key: String) =
        proxyManager
            .builder()
            .build(key) {
                java.util.concurrent.CompletableFuture
                    .completedFuture(timeRateLimitBucketConfiguration())
            }

    private fun timeRequest(clientIp: String): MockHttpServletRequestBuilder = get("/time").with(remoteAddress(clientIp))

    private fun remoteAddress(clientIp: String): RequestPostProcessor =
        RequestPostProcessor { request ->
            request.remoteAddr = clientIp
            request
        }

    private companion object {
        const val INITIAL_BUCKET_CAPACITY = 50
        const val REFILL_TOKENS_PER_SECOND = 10
        const val MAXIMUM_REQUESTS_AFTER_INITIAL_CAPACITY = 30
        const val CONCURRENT_WORKERS = 32
        const val CONCURRENT_REQUESTS = 160
        const val REFILL_TOLERANCE = 2
        const val READY_TIMEOUT_SECONDS = 10L
        const val REQUEST_COMPLETION_TIMEOUT_SECONDS = 30L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        val REFILL_WAIT: Duration = Duration.ofMillis(250)
    }
}
