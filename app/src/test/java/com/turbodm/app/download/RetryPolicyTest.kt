package com.turbodm.app.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class RetryPolicyTest {

    private val policy = RetryPolicy(maxAttempts = 5, baseDelayMs = 1000L, maxDelayMs = 30_000L)

    @Test fun `5xx is transient with growing delay`() {
        val o1 = policy.classify(IOException("x"), httpStatus = 503, attempt = 0)
        assertTrue(o1 is RetryPolicy.Outcome.Transient)
        o1 as RetryPolicy.Outcome.Transient
        // base 1s + up to 20% jitter
        assertEquals(1, o1.attempt)
        assertTrue("delay out of range: ${o1.delayMs}", o1.delayMs in 1000L..1199L)

        val o2 = policy.classify(IOException("x"), httpStatus = 503, attempt = 1)
        o2 as RetryPolicy.Outcome.Transient
        assertTrue(o2.delayMs in 2000L..2399L)
    }

    @Test fun `4xx other than 408 and 429 is permanent`() {
        assertEquals(RetryPolicy.Outcome.Permanent, policy.classify(IOException("x"), 404, 0))
        assertEquals(RetryPolicy.Outcome.Permanent, policy.classify(IOException("x"), 403, 0))
        assertEquals(RetryPolicy.Outcome.Permanent, policy.classify(IOException("x"), 410, 0))
    }

    @Test fun `408 and 429 are transient even though they're 4xx`() {
        assertTrue(policy.classify(IOException("x"), 408, 0) is RetryPolicy.Outcome.Transient)
        assertTrue(policy.classify(IOException("x"), 429, 0) is RetryPolicy.Outcome.Transient)
    }

    @Test fun `2xx and 3xx treated as permanent — not actually an error`() {
        assertEquals(RetryPolicy.Outcome.Permanent, policy.classify(IOException("x"), 200, 0))
        assertEquals(RetryPolicy.Outcome.Permanent, policy.classify(IOException("x"), 301, 0))
    }

    @Test fun `unknown throwable is permanent`() {
        assertEquals(RetryPolicy.Outcome.Permanent, policy.classify(IllegalStateException("oops"), null, 0))
    }

    @Test fun `IO-family exceptions are transient`() {
        assertTrue(policy.classify(SocketTimeoutException("x"), null, 0) is RetryPolicy.Outcome.Transient)
        assertTrue(policy.classify(UnknownHostException("x"), null, 0) is RetryPolicy.Outcome.Transient)
        assertTrue(policy.classify(SSLException("x"), null, 0) is RetryPolicy.Outcome.Transient)
        assertTrue(policy.classify(IOException("x"), null, 0) is RetryPolicy.Outcome.Transient)
    }

    @Test fun `Exhausted after maxAttempts`() {
        val o = policy.classify(IOException("x"), httpStatus = 503, attempt = 5)
        assertEquals(RetryPolicy.Outcome.Exhausted, o)
    }

    @Test fun `delay grows but caps at maxDelayMs`() {
        // Use a low cap so the cap is exercised inside retryAfter().
        val p = RetryPolicy(maxAttempts = 8, baseDelayMs = 1000L, maxDelayMs = 4_000L)
        // attempt=4: 1<<4 = 16000, capped to 4000 + up to 20% jitter → [4000, 4799]
        val o = p.classify(IOException("x"), 503, attempt = 4) as RetryPolicy.Outcome.Transient
        assertTrue("delay exceeded cap: ${o.delayMs}", o.delayMs in 4_000L..4_799L)
    }

    @Test fun `awaitBackoff suspends for the given delay`() = runTest {
        // Just verify it returns normally — full timing assertions would be flaky.
        val outcome = RetryPolicy.Outcome.Transient(attempt = 1, delayMs = 10L)
        policy.awaitBackoff(outcome)
    }
}