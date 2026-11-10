package app.xingmu.remote.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RangeHeaderTest {
    @Test
    fun parsesOpenClosedAndSuffixRanges() {
        assertNull(RangeHeader.parse(null, 100))
        assertEquals(ByteRange(10, 19), RangeHeader.parse("bytes=10-19", 100))
        assertEquals(ByteRange(90, 99), RangeHeader.parse("bytes=-10", 100))
        assertEquals(ByteRange(95, 99), RangeHeader.parse("bytes=95-", 100))
    }

    @Test
    fun clampsEndAndRejectsInvalidRanges() {
        assertEquals(ByteRange(90, 99), RangeHeader.parse("bytes=90-200", 100))
        assertThrows(IllegalArgumentException::class.java) { RangeHeader.parse("bytes=100-", 100) }
        assertThrows(IllegalArgumentException::class.java) { RangeHeader.parse("bytes=10-1", 100) }
    }
}
