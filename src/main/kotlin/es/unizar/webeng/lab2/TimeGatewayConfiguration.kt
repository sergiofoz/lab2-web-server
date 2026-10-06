package es.unizar.webeng.lab2

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.proxy.AsyncProxyManager
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.util.MultiValueMap
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.function.HandlerFilterFunction
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Makes Spring Cloud Gateway the entry point for `/time` and applies its per-client rate limit.
 */
@Configuration
class TimeGatewayConfiguration(
    private val timeProvider: TimeProvider,
    private val proxyManager: AsyncProxyManager<String>,
) {
    private val bucketConfiguration = timeRateLimitBucketConfiguration()

    /**
     * Routes GET `/time` through Bucket4j before generating the existing time response.
     *
     * This is an in-process route, not an HTTP proxy: the route calls the shared time provider
     * directly, avoiding a self-forward to `/time` that could match this route again.
     */
    @Bean
    fun timeRateLimitRoute(): RouterFunction<ServerResponse> =
        GatewayRouterFunctions
            .route("time-rate-limit")
            .GET("/time") {
                ServerResponse.ok().body(timeProvider.now().toDTO())
            }.filter(bucket4jRateLimitFilter())
            .build()

    /**
     * Applies Bucket4j within the Gateway filter chain using the configured keyed proxy manager.
     *
     * With Gateway 5.0.3 and Spring Boot 4.1, the convenience filter did not retain consumption
     * between requests, and its success-path header update was incompatible with Spring 7's
     * read-only response headers. This filter uses the same Bucket4j proxy API directly so each
     * request atomically consumes from the shared per-IP bucket.
     */
    private fun bucket4jRateLimitFilter(): HandlerFilterFunction<ServerResponse, ServerResponse> =
        HandlerFilterFunction { request, next ->
            val clientKey = request.servletRequest().remoteAddr
            val bucket =
                proxyManager
                    .builder()
                    .build(clientKey) {
                        CompletableFuture.completedFuture(bucketConfiguration)
                    }
            val probe = bucket.tryConsumeAndReturnRemaining(1).get()
            val response =
                if (probe.isConsumed) {
                    // Only an admitted request invokes the handler that produces the time DTO.
                    next.handle(request)
                } else {
                    ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS).build()
                }

            MutableResponseHeaders(response).apply {
                headers().add(RATE_LIMIT_REMAINING_HEADER, probe.remainingTokens.toString())
            }
        }

    private companion object {
        const val BUCKET_CAPACITY = 50L
        const val REFILL_TOKENS = 10L
        const val RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining"
        val REFILL_PERIOD: Duration = Duration.ofSeconds(1)
    }
}

/**
 * Builds the exact capacity and greedy refill policy used by the Gateway rate-limit filter.
 *
 * Kept separate so tests can verify the policy without relying on elapsed wall-clock time.
 */
internal fun timeRateLimitBucketConfiguration(): BucketConfiguration =
    BucketConfiguration
        .builder()
        .addLimit(
            Bandwidth
                .builder()
                .capacity(50L)
                .refillGreedy(10L, Duration.ofSeconds(1))
                .build(),
        ).build()

/**
 * Keeps headers mutable until the Gateway route writes the response.
 *
 * Spring Framework 7 exposes response headers as read-only after building a `ServerResponse`.
 * The rate-limit filter needs to add the remaining-token header after the handler has run, so this
 * adapter copies the headers and transfers them to the servlet response before writing its body.
 */
private class MutableResponseHeaders(
    private val delegate: ServerResponse,
) : ServerResponse {
    private val mutableHeaders = HttpHeaders().apply { putAll(delegate.headers()) }

    override fun statusCode() = delegate.statusCode()

    override fun headers(): HttpHeaders = mutableHeaders

    override fun cookies(): MultiValueMap<String, Cookie> = delegate.cookies()

    override fun writeTo(
        request: HttpServletRequest,
        response: HttpServletResponse,
        context: ServerResponse.Context,
    ): ModelAndView? {
        mutableHeaders.headerSet().forEach { header ->
            header.value.forEach { value ->
                response.addHeader(header.key, value)
            }
        }
        return delegate.writeTo(request, response, context)
    }
}
