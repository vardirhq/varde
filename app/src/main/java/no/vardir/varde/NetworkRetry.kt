package no.vardir.varde

import java.io.IOException

/**
 * Retry transient I/O errors without abandoning a device code that may already
 * have been authorized. The caller owns the overall authorization deadline.
 */
internal suspend fun <T> retryNetworkUntil(
    deadline: Long,
    intervalMillis: Long,
    now: () -> Long,
    wait: suspend (Long) -> Unit,
    onFailure: () -> Unit,
    request: suspend () -> T
): T {
    while (true) {
        try {
            return request()
        } catch (e: IOException) {
            onFailure()
            val remaining = deadline - now()
            if (remaining <= 0) throw e
            wait(minOf(intervalMillis, remaining))
            if (now() >= deadline) throw e
        }
    }
}
