package es.unizar.webeng.lab2

import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * Objeto de transferencia de datos para la hora del servidor.
 */
data class TimeDTO(
    val time: LocalDateTime,
)

/**
 * Proveedor abstraído para la obtención de fecha y hora.
 */
interface TimeProvider {
    fun now(): LocalDateTime
}

/**
 * Servicio que implementa la hora del sistema.
 */
@Service
class TimeService : TimeProvider {
    override fun now(): LocalDateTime = LocalDateTime.now()
}

/**
 * Función de extensión para transformar LocalDateTime a DTO.
 */
fun LocalDateTime.toDTO(): TimeDTO = TimeDTO(time = this)

/**
 * Controlador REST que expone el endpoint /time.
 */
@RestController
class TimeController(
    private val service: TimeProvider,
) {
    @GetMapping("/time")
    fun time(): TimeDTO = service.now().toDTO()
}
