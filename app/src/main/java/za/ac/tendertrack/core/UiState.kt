package za.ac.tendertrack.core

/**
 * What every screen's data load can be in. Having one type means every screen
 * renders its loading, error and empty cases the same way.
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

inline fun <T> UiState<T>.onSuccess(block: (T) -> Unit): UiState<T> {
    if (this is UiState.Success) block(data)
    return this
}

fun <T> UiState<T>.dataOrNull(): T? = (this as? UiState.Success)?.data

/**
 * Result of an action the user triggered (save, award, resolve). Distinct from
 * [UiState] because an action failing should not blank out the screen.
 */
sealed interface ActionState {
    data object Idle : ActionState
    data object Running : ActionState
    data class Failed(val message: String) : ActionState
    data class Succeeded(val message: String) : ActionState
}

/** Turns an exception into something worth showing a user. */
fun Throwable.friendlyMessage(): String {
    val raw = message ?: return "An unexpected error occurred. Please try again."
    return when {
        raw.contains("Unable to resolve host", true) ||
            raw.contains("timeout", true) ||
            raw.contains("failed to connect", true) ->
            "Cannot reach the server. Check your internet connection and try again."
        raw.contains("Invalid login credentials", true) ->
            "That email address and password do not match an account."
        raw.contains("row-level security", true) || raw.contains("permission denied", true) ->
            "Your account does not have permission to do that."
        raw.contains("duplicate key", true) ->
            "That record already exists."
        else -> raw
    }
}
