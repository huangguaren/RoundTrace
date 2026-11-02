package com.roundtrace.app.export

import com.roundtrace.app.core.export.ImportFailureReason
import com.roundtrace.app.core.export.ImportLimitException
import com.roundtrace.app.core.export.ImportLimits
import com.roundtrace.app.core.export.ImportTextReader
import com.roundtrace.app.core.export.ImportTextReadResult
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ImportTextReaderTest {
    @Test
    fun exactByteBudgetCanBeRead() {
        val text = "x".repeat(ImportLimits.MAX_BYTES)
        assertEquals(text, ImportTextReader.read(ByteArrayInputStream(text.toByteArray())))
    }

    @Test
    fun oversizedStreamStopsAfterOneProbeByte() {
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int { consumed += 1; return 'x'.code }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill('x'.code.toByte(), offset, offset + length)
                consumed += length
                return length
            }
        }
        assertThrows(ImportLimitException::class.java) { ImportTextReader.read(input) }
        assertEquals(ImportLimits.MAX_BYTES + 1, consumed)
    }

    @Test
    fun documentOpenAndReadHappenAwayFromCallingThreadAndCloseStream() = runTest {
        val caller = Thread.currentThread()
        var closed = false
        val result = ImportTextReader.readDocument {
            assertNotEquals(caller, Thread.currentThread())
            object : ByteArrayInputStream("{\"说明\":\"正常\"}".toByteArray()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    assertNotEquals(caller, Thread.currentThread())
                    return super.read(buffer, offset, length)
                }
                override fun close() { closed = true; super.close() }
            }
        }
        assertEquals("{\"说明\":\"正常\"}", (result as ImportTextReadResult.Text).value)
        assertTrue(closed)
    }

    @Test
    fun documentReadLimitMapsToExplicitFailure() = runTest {
        val result = ImportTextReader.readDocument {
            ByteArrayInputStream(ByteArray(ImportLimits.MAX_BYTES + 1))
        }
        assertEquals(ImportFailureReason.RESOURCE_LIMIT_EXCEEDED, (result as ImportTextReadResult.Rejected).reason)
    }

    @Test
    fun missingOrRevokedDocumentMapsToReadFailure() = runTest {
        val missing = ImportTextReader.readDocument { null }
        val revoked = ImportTextReader.readDocument { throw SecurityException("revoked") }
        assertEquals(ImportFailureReason.READ_FAILED, (missing as ImportTextReadResult.Rejected).reason)
        assertEquals(ImportFailureReason.READ_FAILED, (revoked as ImportTextReadResult.Rejected).reason)
    }

    @Test
    fun cancelledReadIsNotReportedAsFileFailure() = runTest {
        try {
            ImportTextReader.readDocument { throw CancellationException("cancelled") }
            fail("取消必须传播")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
