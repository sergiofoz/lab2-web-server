package es.unizar.webeng.lab2

import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Response payload returned by the Gateway route for `/time`.
 */
data class TimeDTO(
    val time: LocalDateTime,
)

/**
 * Abstraction used by the `/time` Gateway handler to obtain the current time.
 */
interface TimeProvider {
    fun now(): LocalDateTime
}

/**
 * Supplies the current system time to the `/time` Gateway handler.
 */
@Service
class TimeService : TimeProvider {
    override fun now(): LocalDateTime = LocalDateTime.now()
}

/**
 * Converts the provider's time value into the response payload used by `/time`.
 */
fun LocalDateTime.toDTO(): TimeDTO = TimeDTO(time = this)
