package es.unizar.webeng.lab2

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import org.springframework.cloud.gateway.server.mvc.filter.Bucket4jFilterFunctions
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import java.time.Duration

/**
 * Makes Spring Cloud Gateway the entry point for `/time` and applies its per-client rate limit.
 */
@Configuration
class TimeGatewayConfiguration(
    private val timeProvider: TimeProvider,
) {
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
                // This handler only runs after the rate-limit filter has consumed a token.
                ServerResponse.ok().body(timeProvider.now().toDTO())
            }.filter(
                Bucket4jFilterFunctions.rateLimit { config ->
                    // Keep the initial burst at 50 while refilling at the required sustained rate.
                    config
                        .setCapacity(BUCKET_CAPACITY)
                        .setPeriod(REFILL_PERIOD)
                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
                        .setKeyResolver { request ->
                            // All requests from the same remote IP share one token bucket.
                            request.servletRequest().remoteAddr
                        }.setConfigurationBuilder {
                            BucketConfiguration
                                .builder()
                                .addLimit(
                                    Bandwidth
                                        .builder()
                                        .capacity(BUCKET_CAPACITY)
                                        .refillGreedy(REFILL_TOKENS, REFILL_PERIOD)
                                        .build(),
                                ).build()
                        }
                },
            ).build()

    private companion object {
        const val BUCKET_CAPACITY = 50L
        const val REFILL_TOKENS = 10L
        val REFILL_PERIOD: Duration = Duration.ofSeconds(1)
    }
}
