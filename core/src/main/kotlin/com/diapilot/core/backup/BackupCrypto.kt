package com.diapilot.core.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for a backup file: the on-disk format `DPBK1`.
 *
 * WHY IT EXISTS. A backup lands in public Downloads, in a third-party cloud
 * folder and on a self-hosted server — every one of them readable by whatever
 * else has access to that place, and every one of them the whole medical
 * history. The password never leaves the phone; the file is useless without it.
 *
 * WHY CHUNKS AND NOT ONE `CipherOutputStream`. AES-GCM must verify the tag
 * before it may release any plaintext, so both the JDK provider and Android's
 * Conscrypt buffer the ENTIRE input inside `Cipher.update` and only emit in
 * `doFinal`. A 600 MB database through one GCM stream is a 600 MB heap
 * allocation, i.e. an OutOfMemoryError on a phone. The file is therefore a
 * sequence of independently authenticated chunks (the construction of
 * libsodium's `secretstream` and Tink's streaming AEAD): each chunk is at most
 * [DEFAULT_CHUNK_BYTES] of plaintext, so the working set is one chunk whatever
 * the file size. The chunk counter is the low half of each chunk's nonce, so
 * a chunk cannot be moved to another position; the header is the AAD of every
 * chunk, so it cannot be edited; the last chunk is flagged in its AAD, so the
 * file cannot be truncated at a chunk boundary without the tag failing.
 *
 * FORMAT (all integers big-endian, unsigned):
 * ```
 *  offset size field
 *  0      5    magic "DPBK1" (ASCII)
 *  5      4    PBKDF2 iteration count
 *  9      4    chunk plaintext size in bytes (every chunk but the last is exactly this long)
 *  13     16   PBKDF2 salt, random per file
 *  29     8    nonce prefix, random per file
 *  37     ...  chunks: AES-256-GCM(plaintext[i]) || 16-byte tag, for i = 0, 1, 2, ...
 * ```
 * Key: PBKDF2-HMAC-SHA256(UTF-8(password), salt, iterations, 32 bytes).
 * Chunk i: nonce = prefix(8) || i as 4-byte counter; AAD = header(37) || flag,
 * where flag is `0x01` for the last chunk and `0x00` otherwise. The last chunk
 * holds whatever remains, possibly nothing (then it is the 16-byte tag alone).
 *
 * WHY PBKDF2 IS WRITTEN OUT HERE. `SecretKeyFactory("PBKDF2WithHmacSHA256")`
 * exists on both the JDK and Android 8+, but the two providers have not always
 * agreed on how a non-ASCII password becomes bytes. The derivation is a dozen
 * lines over `HmacSHA256`; spelling it out makes the file decryptable by any
 * PBKDF2 implementation, and the RFC 7914 vector in the tests pins it.
 *
 * Wrong password and tampering are indistinguishable by construction (there
 * is no password check value to attack offline); both surface as
 * [BackupAuthException]. A file that is not in this format at all surfaces as
 * [BackupFormatException]; [isEncrypted] tells the two apart before any work.
 */
object BackupCrypto {

    /** First bytes of every encrypted backup; `1` is the format version. */
    val MAGIC: ByteArray = "DPBK1".toByteArray(Charsets.US_ASCII)

    /** Appended to the plaintext file name: `x.sqlite` becomes `x.sqlite.enc`. */
    const val FILE_SUFFIX = ".enc"

    const val DEFAULT_ITERATIONS = 200_000
    const val DEFAULT_CHUNK_BYTES = 1 shl 20
    const val SALT_BYTES = 16
    const val NONCE_PREFIX_BYTES = 8
    const val TAG_BYTES = 16
    const val HEADER_BYTES = 5 + 4 + 4 + SALT_BYTES + NONCE_PREFIX_BYTES
    const val KEY_BYTES = 32

    /**
     * Bounds on what a header may ask for. A crafted header saying "ten
     * billion iterations" or "a two-gigabyte chunk" must be refused, not obeyed:
     * the file is untrusted input.
     */
    const val MAX_ITERATIONS = 10_000_000
    const val MIN_ITERATIONS = 1
    const val MAX_CHUNK_BYTES = 16 shl 20

    private const val NONCE_BYTES = NONCE_PREFIX_BYTES + 4
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FLAG_MORE: Byte = 0
    private const val FLAG_LAST: Byte = 1

    /** True when [head] (at least the first [MAGIC] bytes of a file) is ours. */
    fun isEncrypted(head: ByteArray): Boolean =
        head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    /**
     * Wraps [out]: bytes written to the result are encrypted under [password]
     * and land in [out] in the format above. The header is written immediately;
     * `close()` writes the final chunk and closes [out] — a stream that is not
     * closed is a file that was never finished, and the reader will say so.
     */
    fun encrypting(
        out: OutputStream,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
        random: SecureRandom = SecureRandom(),
    ): OutputStream {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range: $iterations" }
        require(chunkBytes in 1..MAX_CHUNK_BYTES) { "chunk size out of range: $chunkBytes" }
        require(password.isNotEmpty()) { "empty password" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).putInt(iterations).putInt(chunkBytes).put(salt).put(prefix)
            .array()
        val key = deriveKey(password, salt, iterations)
        out.write(header)
        return EncryptingStream(out, key, header, chunkBytes)
    }

    /**
     * Wraps [input], which must start at the header: reading the result yields
     * the plaintext. The header is read and the key derived here, so a file
     * that is not ours fails at the call, not at the first read. A wrong
     * password or a modified byte fails at the read that reaches the affected
     * chunk — the first read for the password — with [BackupAuthException].
     */
    fun decrypting(input: InputStream, password: CharArray): InputStream {
        val header = ByteArray(HEADER_BYTES)
        val got = readFully(input, header, 0, HEADER_BYTES)
        if (got < HEADER_BYTES || !isEncrypted(header)) throw BackupFormatException("not an encrypted DiaPilot backup")
        val buf = ByteBuffer.wrap(header, MAGIC.size, HEADER_BYTES - MAGIC.size)
        val iterations = buf.int
        val chunkBytes = buf.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupFormatException("iteration count out of range")
        if (chunkBytes !in 1..MAX_CHUNK_BYTES) throw BackupFormatException("chunk size out of range")
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val key = deriveKey(password, salt, iterations)
        return DecryptingStream(input, key, header, chunkBytes)
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018 §5.2) for a single output block: the key is
     * 32 bytes and so is the HMAC, so there is exactly one block to compute.
     */
    internal fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val passwordBytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password)).let {
            ByteArray(it.remaining()).also(it::get)
        }
        try {
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            check(t.size == KEY_BYTES)
            return t
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun nonce(prefixSource: ByteArray, counter: Int): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES)
            .put(prefixSource, HEADER_BYTES - NONCE_PREFIX_BYTES, NONCE_PREFIX_BYTES)
            .putInt(counter)
            .array()

    private fun cipher(mode: Int, key: ByteArray, header: ByteArray, counter: Int, last: Boolean): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce(header, counter)))
            updateAAD(header)
            updateAAD(byteArrayOf(if (last) FLAG_LAST else FLAG_MORE))
        }

    /** Reads until [len] bytes or EOF; returns how many arrived. */
    private fun readFully(input: InputStream, dst: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(dst, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private class EncryptingStream(
        private val out: OutputStream,
        private val key: ByteArray,
        private val header: ByteArray,
        private val chunkBytes: Int,
    ) : OutputStream() {
        private val buf = ByteArray(chunkBytes)
        private var filled = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            var pos = off
            var remaining = len
            while (remaining > 0) {
                // A full buffer is flushed only when MORE data arrives, so the
                // chunk that happens to end exactly at the end of the file is
                // still written as the last one.
                if (filled == chunkBytes) emit(last = false)
                val n = minOf(remaining, chunkBytes - filled)
                System.arraycopy(b, pos, buf, filled, n)
                filled += n
                pos += n
                remaining -= n
            }
        }

        private fun emit(last: Boolean) {
            if (counter < 0) throw IOException("too many chunks")
            val c = cipher(Cipher.ENCRYPT_MODE, key, header, counter, last)
            out.write(c.doFinal(buf, 0, filled))
            counter++
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                emit(last = true)
                out.flush()
            } finally {
                key.fill(0)
                out.close()
            }
        }
    }

    private class DecryptingStream(
        private val input: InputStream,
        private val key: ByteArray,
        private val header: ByteArray,
        chunkBytes: Int,
    ) : InputStream() {
        /** One chunk plus its tag plus one look-ahead byte that tells "more follows". */
        private val cbuf = ByteArray(chunkBytes + TAG_BYTES + 1)
        private var carried = 0
        private var plain: ByteArray = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos == plain.size) {
                if (finished) return -1
                nextChunk()
            }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        private fun nextChunk() {
            val got = carried + readFully(input, cbuf, carried, cbuf.size - carried)
            carried = 0
            val last = got < cbuf.size
            val chunkLen = if (last) got else cbuf.size - 1
            if (chunkLen < TAG_BYTES) throw BackupFormatException("truncated: chunk $counter is incomplete")
            val c = cipher(Cipher.DECRYPT_MODE, key, header, counter, last)
            plain = try {
                c.doFinal(cbuf, 0, chunkLen)
            } catch (e: GeneralSecurityException) {
                throw BackupAuthException("wrong password or damaged file (chunk $counter)", e)
            }
            pos = 0
            counter++
            if (last) {
                finished = true
            } else {
                cbuf[0] = cbuf[cbuf.size - 1]
                carried = 1
            }
        }

        override fun close() {
            key.fill(0)
            input.close()
        }
    }
}

/** The bytes are not a `DPBK1` file, or the file ends before its last chunk. */
class BackupFormatException(message: String) : IOException(message)

/** A tag failed: the password is wrong or the ciphertext was modified. */
class BackupAuthException(message: String, cause: Throwable) : IOException(message, cause)
