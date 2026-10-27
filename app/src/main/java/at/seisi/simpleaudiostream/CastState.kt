package at.seisi.simpleaudiostream

import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide casting state, observed by the UI and the Quick Settings tile. */
object CastState {
    sealed interface Status {
        data object Idle : Status
        data class Connecting(val speaker: String) : Status
        data class Casting(val speaker: String) : Status
        data class Error(val message: String) : Status
    }

    val status = MutableStateFlow<Status>(Status.Idle)

    /** Speaker volume 0–100 while casting, null otherwise. */
    val volume = MutableStateFlow<Int?>(null)

    val isActive: Boolean
        get() = status.value.let { it is Status.Connecting || it is Status.Casting }
}
