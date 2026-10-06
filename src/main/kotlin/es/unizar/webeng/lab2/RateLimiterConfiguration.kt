package es.unizar.webeng.lab2

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.caffeine.CaffeineProxyManager
import io.github.bucket4j.distributed.proxy.AsyncProxyManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Provides the local Bucket4j storage used by Spring Cloud Gateway's rate-limit filter.
 */
@Configuration
class RateLimiterConfiguration {
    /**
     * Creates an asynchronous proxy manager backed by a bounded in-memory Caffeine cache.
     *
     * Bucket4j retains each entry until its bucket has fully refilled and then for the
     * configured duration. The size limit prevents the cache from growing without bound.
     */
    @Bean
    fun caffeineProxyManager(): AsyncProxyManager<String> {
        val cacheBuilder: Caffeine<Any, Any> = Caffeine.newBuilder().maximumSize(MAXIMUM_CACHE_SIZE)
        return CaffeineProxyManager<String>(cacheBuilder, KEEP_AFTER_REFILL_DURATION).asAsync()
    }

    private companion object {
        const val MAXIMUM_CACHE_SIZE = 10_000L
        val KEEP_AFTER_REFILL_DURATION: Duration = Duration.ofMinutes(10)
    }
}
