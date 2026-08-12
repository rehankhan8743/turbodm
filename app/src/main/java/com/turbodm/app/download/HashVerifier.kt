package com.turbodm.app.download

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes a SHA-256 hash of a file on disk. Runs on the caller's thread; the
 * engine wraps this in `withContext(Dispatchers.IO)`.
 *
 * The digest is read in 64 KB chunks so memory cost stays flat regardless of
 * file size. Total wall time is roughly `size / disk-throughput` plus a tiny
 * fixed overhead for the digest finalization.
 */
@Singleton
class HashVerifier @Inject constructor() {

    /**
     * Reads [file] and returns its SHA-256 as a lowercase hex string. Throws
     * [java.io.IOException] if the file can't be read.
     */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    /** Case-insensitive equality on hex strings of equal length. */
    fun matches(computed: String, expected: String): Boolean {
        if (computed.length != expected.length) return false
        var diff = 0
        for (i in computed.indices) {
            // Char.xor isn't a stdlib op; coerce to Int, xor, fold back into diff.
            diff = diff or (computed[i].lowercaseChar().code xor expected[i].lowercaseChar().code)
        }
        return diff == 0
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
