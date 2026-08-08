package com.turbodm.app.download

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Decides whether a failed chunk should be retried, and how long to wait.
 *
 * Permanent failures (HTTP 4xx other than 408/429, malformed responses, etc.) fail
 * the download immediately. Transient failures retry with exponential backoff up
 * to [maxAttempts]. The backoff sequence is bounded so we don't sleep for hours
 * on a flaky network.
 */
class RetryPolicy(
    val maxAttempts: Int = 5,
    private val baseDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 30_000L
) {

    sealed interface Outcome {
        data object Permanent : Outcome
        data class Transient(val attempt: Int, val delayMs: Long) : Outcome
        data object Exhausted : Outcome
    }

    fun classify(throwable: Throwable, httpStatus: Int?, attempt: Int): Outcome {
        if (attempt >= maxAttempts) return Outcome.Exhausted
        val status = httpStatus
        if (status != null) {
            if (status in 400..499 && status != 408 && status != 429) return Outcome.Permanent
            if (status in 500..599) return retryAfter(attempt)
            if (status == 408 || status == 429) return retryAfter(attempt)
            if (status in 200..399) return Outcome.Permanent // not an error — caller bug
        }
        return when (throwable) {
            is SocketTimeoutException -> retryAfter(attempt)
            is UnknownHostException -> retryAfter(attempt)
            is SSLException -> retryAfter(attempt)
            is IOException -> retryAfter(attempt)
            else -> Outcome.Permanent
        }
    }

    private fun retryAfter(attempt: Int): Outcome.Transient {
        // 1s, 2s, 4s, 8s, 16s, capped at maxDelayMs
        val raw = baseDelayMs shl attempt.coerceAtLeast(0)
        val capped = raw.coerceAtMost(maxDelayMs)
        // tiny jitter so synchronized retries don't lockstep
        val jitter = (Math.random() * 0.2 * capped).toLong()
        return Outcome.Transient(attempt + 1, capped + jitter)
    }

    suspend fun awaitBackoff(outcome: Outcome.Transient) {
        delay(outcome.delayMs)
    }
}
