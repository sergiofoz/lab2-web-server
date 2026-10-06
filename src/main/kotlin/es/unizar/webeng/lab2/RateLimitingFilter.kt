package es.unizar.webeng.lab2

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bucket
import io.github.bucket4j.ConsumptionProbe
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Limits requests to `/time` independently for each client IP address.
 *
 * Caffeine removes buckets after ten minutes without access and bounds the
 * number of stored IP addresses. An evicted IP receives a fresh bucket if it
 * later makes another request.
 */
@Component
class RateLimitingFilter : OncePerRequestFilter() {
    /**
     * Keeps buckets local to this application instance while bounding memory
     * usage and cleaning up IP addresses that have been inactive.
     */
    private val buckets: Cache<String, Bucket> =
        Caffeine
            .newBuilder()
            .expireAfterAccess(CACHE_IDLE_MINUTES, TimeUnit.MINUTES)
            .maximumSize(MAXIMUM_CACHED_IPS)
            .build()

    /**
     * Excludes all other routes so the rate limit affects only `/time`.
     *
     * We compare against the path from the HTTP request (not only servletPath)
     * because MockMvc and servlet containers can expose the resource path in
     * slightly different ways during tests and runtime.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return path != TIME_PATH
    }

    /**
     * Charges one token to the bucket for the remote IP, then either continues
     * normal request processing or returns the rate-limit response.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Use the socket peer address as the client identity, as required.
        val clientIp = request.remoteAddr

        // Cache.get creates a bucket atomically when this IP is first seen.
        val bucket = buckets.get(clientIp) { createBucket() }
        val probe = bucket.tryConsumeAndReturnRemaining(1)

        if (probe.isConsumed) {
            // Report the balance after consuming this request's token.
            response.setHeader(REMAINING_HEADER, probe.remainingTokens.toString())
            filterChain.doFilter(request, response)
        } else {
            // Reject without invoking the endpoint and report the wait time.
            writeRateLimitResponse(response, probe)
        }
    }

    /**
     * Constructs a full bucket that can absorb short bursts of up to 50 calls
     * and then refills greedily at ten tokens per second.
     */
    private fun createBucket(): Bucket =
        Bucket
            .builder()
            .addLimit { limit ->
                limit
                    .capacity(BUCKET_CAPACITY)
                    .refillGreedy(REFILL_TOKENS, Duration.ofSeconds(REFILL_PERIOD_SECONDS))
            }.build()

    /**
     * Writes the specified plain-text 429 response and rounds the retry delay
     * upward so clients are not told to retry before a token is available.
     */
    private fun writeRateLimitResponse(
        response: HttpServletResponse,
        probe: ConsumptionProbe,
    ) {
        val waitNanos = probe.nanosToWaitForRefill
        val retryAfterSeconds =
            waitNanos / NANOS_PER_SECOND +
                if (waitNanos % NANOS_PER_SECOND == 0L) {
                    0L
                } else {
                    1L
                }

        response.status = TOO_MANY_REQUESTS_STATUS
        response.contentType = MediaType.TEXT_PLAIN_VALUE
        response.setHeader(RETRY_AFTER_HEADER, retryAfterSeconds.toString())
        response.writer.append(TOO_MANY_REQUESTS_BODY)
    }

    private companion object {
        const val TIME_PATH = "/time"
        const val BUCKET_CAPACITY = 50L
        const val REFILL_TOKENS = 10L
        const val REFILL_PERIOD_SECONDS = 1L
        const val TOO_MANY_REQUESTS_STATUS = 429
        const val CACHE_IDLE_MINUTES = 10L
        const val MAXIMUM_CACHED_IPS = 10_000L
        const val NANOS_PER_SECOND = 1_000_000_000L

        const val REMAINING_HEADER = "X-Rate-Limit-Remaining"
        const val RETRY_AFTER_HEADER = "X-Rate-Limit-Retry-After-Seconds"
        const val TOO_MANY_REQUESTS_BODY = "Too many requests"
    }
}
