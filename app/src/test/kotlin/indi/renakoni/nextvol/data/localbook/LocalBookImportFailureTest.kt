package indi.renakoni.nextvol.data.localbook

import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.util.zip.ZipException

class LocalBookImportFailureTest {
    private fun reason(block: () -> Unit): LocalBookImportReason {
        val failure = assertThrows(Exception::class.java, block)
        return LocalBookImportFailure.from(failure).reason
    }

    @Test fun txtValidationAndParserFailuresKeepSpecificReasons() {
        assertEquals(LocalBookImportReason.EmptyFile, reason { TxtBookParser.parse(byteArrayOf(), "Book") })
        assertEquals(LocalBookImportReason.NoContent, reason { TxtBookParser.parse("\n  ".toByteArray(), "Book") })
        assertEquals(LocalBookImportReason.UnsupportedEncoding, reason { TxtBookParser.decode(byteArrayOf(65), "unknown") })
        assertEquals(LocalBookImportReason.InvalidEncoding, reason { TxtBookParser.decode(byteArrayOf(0xFF.toByte()), "UTF-8") })
        assertEquals(LocalBookImportReason.InvalidRule, reason { TxtBookParser.parse(byteArrayOf(65), "Book", rule = "(") })
        assertEquals(LocalBookImportReason.RuleTooLong, reason { TxtBookParser.parse(byteArrayOf(65), "Book", rule = "a".repeat(1025)) })
    }

    @Test fun exceptionTypesDetermineTheReasonWithoutMatchingDiagnosticText() {
        assertEquals(LocalBookImportReason.FileAccess, LocalBookImportFailure.from(FileNotFoundException("任意说明")).reason)
        assertEquals(LocalBookImportReason.CorruptEpub, LocalBookImportFailure.from(ZipException("任意说明")).reason)
        assertEquals(LocalBookImportReason.Unknown, LocalBookImportFailure.from(IllegalStateException("The text file is empty.")).reason)
        val cause = IllegalStateException("underlying detail")
        val failure = LocalBookImportFailure.from(LocalBookImportException(LocalBookImportReason.Storage, "诊断", cause))
        assertEquals(LocalBookImportReason.Storage, failure.reason)
        assertTrue(failure.details.contains("underlying detail"))
    }
}
