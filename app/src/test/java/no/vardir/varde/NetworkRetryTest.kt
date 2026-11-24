package no.vardir.varde

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetworkRetryTest {
    @Test fun retriesDnsFailureAndReturnsResult() = runBlocking {
        var now = 0L
        var attempts = 0
        var failures = 0
        val result = retryNetworkUntil(
            deadline = 30000L, intervalMillis = 5000L,
            now = { now }, wait = { now += it },
            onFailure = { failures++ }
        ) {
            attempts++
            if (attempts == 1) throw java.net.UnknownHostException("github.com")
            "authorized"
        }
        assertEquals("authorized", result)
        assertEquals(2, attempts)
        assertEquals(1, failures)
        assertEquals(5000L, now)
    }

    @Test fun stopsAtDeadlineWhenNetworkRemainsUnavailable() = runBlocking {
        var now = 0L
        var attempts = 0
        try {
            retryNetworkUntil(
                deadline = 10000L, intervalMillis = 5000L,
                now = { now }, wait = { now += it }, onFailure = {}
            ) {
                attempts++
                throw IOException("offline")
            }
            fail("Expected network failure")
        } catch (e: IOException) {
            assertEquals(2, attempts)
            assertEquals(10000L, now)
        }
    }

    @Test fun doesNotRetryNonNetworkErrors() = runBlocking {
        var attempts = 0
        try {
            retryNetworkUntil(10000L, 5000L, { 0L }, {}, {}) {
                attempts++
                throw IllegalStateException("denied")
            }
            fail("Expected failure")
        } catch (e: IllegalStateException) {
            assertEquals(1, attempts)
        }
    }
}
