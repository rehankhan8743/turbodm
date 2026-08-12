package com.turbodm.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HashVerifierTest {

    @get:Rule val tmp = TemporaryFolder()

    private val verifier = HashVerifier()

    @Test fun `sha256 of empty file matches known digest`() {
        val f = tmp.newFile("empty.bin").also { it.writeBytes(ByteArray(0)) }
        // SHA-256 of empty input is the well-known constant e3b0c442...
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            verifier.sha256(f)
        )
    }

    @Test fun `sha256 of abc matches known digest`() {
        val f = tmp.newFile("abc.txt").also { it.writeBytes("abc".toByteArray()) }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            verifier.sha256(f)
        )
    }

    @Test fun `sha256 of 1 MB deterministic across runs`() {
        val f = tmp.newFile("big.bin")
        val bytes = ByteArray(1024 * 1024) { (it and 0xFF).toByte() }
        f.writeBytes(bytes)
        val first = verifier.sha256(f)
        val second = verifier.sha256(f)
        assertEquals(first, second)
        assertEquals(64, first.length)
    }

    @Test fun `matches is case-insensitive on equal-length hex`() {
        val lower = "abcdef0123456789".repeat(4)
        val upper = lower.uppercase()
        assertTrue(verifier.matches(lower, upper))
    }

    @Test fun `matches returns false for different content of same length`() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        assertFalse(verifier.matches(a, b))
    }

    @Test fun `matches returns false for length mismatch without iterating`() {
        // Length check must short-circuit — if it walked both strings we'd
        // crash on OutOfBounds. Passing a 4-char string vs a 64-char one.
        assertFalse(verifier.matches("abcd", "abcd".padEnd(64, '0')))
    }

    @Test fun `matches returns false when only first byte differs`() {
        val a = "0".repeat(64)
        val b = "1" + "0".repeat(63)
        assertFalse(verifier.matches(a, b))
    }
}