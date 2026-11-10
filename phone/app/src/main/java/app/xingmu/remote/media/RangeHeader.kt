package app.xingmu.remote.media

data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

object RangeHeader {
    fun parse(value: String?, totalLength: Long): ByteRange? {
        if (value.isNullOrBlank()) return null
        if (totalLength <= 0 || !value.startsWith("bytes=") || value.contains(',')) {
            throw IllegalArgumentException("invalid range")
        }
        val requested = value.removePrefix("bytes=").trim()
        val separator = requested.indexOf('-')
        if (separator < 0) throw IllegalArgumentException("invalid range")
        val startText = requested.substring(0, separator).trim()
        val endText = requested.substring(separator + 1).trim()
        val range = if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull()?.coerceAtMost(totalLength)
                ?: throw IllegalArgumentException("invalid range")
            if (suffix <= 0) throw IllegalArgumentException("invalid range")
            ByteRange(totalLength - suffix, totalLength - 1)
        } else {
            val start = startText.toLongOrNull() ?: throw IllegalArgumentException("invalid range")
            val end = if (endText.isEmpty()) totalLength - 1 else endText.toLongOrNull()
                ?: throw IllegalArgumentException("invalid range")
            if (start < 0 || start >= totalLength || end < start) {
                throw IllegalArgumentException("invalid range")
            }
            ByteRange(start, end.coerceAtMost(totalLength - 1))
        }
        return range
    }
}
