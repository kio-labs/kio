/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kio.compression

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.SuspendIo
import kio.async.buffered
import kio.async.io.buffered
import kio.async.io.openInMemoryPipe
import kio.async.readIntLe
import kio.async.readLongLe
import kio.async.readShortLe
import kio.async.readString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.IOException
import kotlin.let

private const val LOCAL_FILE_HEADER_SIGNATURE = 0x4034b50
private const val CENTRAL_FILE_HEADER_SIGNATURE = 0x2014b50
private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x6054b50
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
private const val ZIP64_EOCD_RECORD_SIGNATURE = 0x06064b50

internal const val COMPRESSION_METHOD_DEFLATED = 8
internal const val COMPRESSION_METHOD_STORED = 0

/** General Purpose Bit Flags, Bit 0. Set if the file is encrypted. */
private const val BIT_FLAG_ENCRYPTED = 1 shl 0

/**
 * General purpose bit flags that this implementation handles. Strict enforcement of additional
 * flags may break legitimate use cases.
 */
private const val BIT_FLAG_UNSUPPORTED_MASK = BIT_FLAG_ENCRYPTED

/** Max size of entries and archives without zip64. */
private const val MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE = 0xffffffffL

private const val HEADER_ID_ZIP64_EXTENDED_INFO = 0x1
private const val HEADER_ID_NTFS_EXTRA = 0x000a
private const val HEADER_ID_EXTENDED_TIMESTAMP = 0x5455

expect class FileHandle

suspend fun SuspendIo.readZipEntries(fd: FileHandle): List<ZipEntry> {
    val io = this

    val offset = io.fileSize(fd) - 22
    if (offset < 0L) {
        throw IOException("not a zip: size=${io.fileSize(fd)}")
    }

    var record = io.positionalFileSource(offset, fd).buffered().let { source ->
        if (source.readIntLe() != END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            throw IOException("EOCD not found")
        }

        source.readEocdRecord()
    }

    // If this is a zip64, read a zip64 central directory record.
    val zip64LocatorOffset = offset - 20 // zip64 end of central directory locator is 20 bytes.
    io.positionalFileSource(zip64LocatorOffset, fd).buffered().let { zip64LocatorSource ->
        if (zip64LocatorSource.readIntLe() == ZIP64_LOCATOR_SIGNATURE) {
            val diskWithCentralDir = zip64LocatorSource.readIntLe()
            val zip64EocdRecordOffset = zip64LocatorSource.readLongLe()
            val numDisks = zip64LocatorSource.readIntLe()
            if (numDisks != 1 || diskWithCentralDir != 0) {
                throw IOException("unsupported zip: spanned")
            }

            io.positionalFileSource(zip64EocdRecordOffset, fd).buffered().let { zip64EocdSource ->
                val zip64EocdSignature = zip64EocdSource.readIntLe()
                if (zip64EocdSignature != ZIP64_EOCD_RECORD_SIGNATURE) {
                    throw IOException("bad zip: expected ${ZIP64_EOCD_RECORD_SIGNATURE} but was ${zip64EocdSignature}")
                }
                record = zip64EocdSource.readZip64EocdRecord(record)
            }
        }
    }

    // Seek to the first central directory entry and read all of the entries.
    val entries = mutableListOf<ZipEntry>()
    io.positionalFileSource(record.centralDirectoryOffset, fd).buffered().let { source ->
        for (i in 0 until record.entryCount) {
            val entry = source.readCentralDirectoryZipEntry()
            if (entry.offset >= record.centralDirectoryOffset) {
                throw IOException("bad zip: local file header offset >= central directory offset")
            }

            entries += entry
        }
    }

    return entries
}

suspend fun ZipEntry.zipEntrySource(io: SuspendIo, fd: FileHandle): AsyncSource {
    val entry = this
    val source = io.positionalFileSource(entry.offset, fd).buffered()
    source.skipLocalHeader()

    return when (entry.compressionMethod) {
        COMPRESSION_METHOD_STORED -> {
            source.fixed(entry.size).buffered()
        }

        COMPRESSION_METHOD_DEFLATED -> {
            source.fixed(entry.compressedSize).buffered().deflateSource().buffered()
        }

        else -> error("Unspport compressionMethod=${entry.compressionMethod}")
    }
}

/** Writes a classic ZIP archive sequentially. The caller owns the sink and entry sources. */
suspend fun AsyncSink.writeZip(block: suspend WriteZipScope.() -> Unit) {
    val scope = WriteZipScopeImpl(this)
    try {
        block(scope)
        scope.finish()
    } finally {
        scope.finished = true
    }
}

interface WriteZipScope {
    suspend fun file(path: String, source: AsyncSource)
}

suspend fun WriteZipScope.file(
    path: String,
    block: suspend (AsyncSink) -> Unit
) = coroutineScope {
    val conn = openInMemoryPipe().buffered()
    launch {
        block(conn.sink)
        conn.sink.flush()
        conn.sink.close()
    }

    file(path, conn.source)
}

/**
 * This class prefers NTFS timestamps, then extended timestamps, then the base ZIP timestamps.
 */
data class ZipEntry(
    /**
     * Absolute path of this entry. If the raw name on disk contains relative paths like `..`, they
     * are not present in this path.
     */
    val canonicalPath: String,

    /** True if this entry is a directory. When encoded directory entries' names end with `/`. */
    val isDirectory: Boolean = false,

    /** The comment on this entry. Empty if there is no comment. */
    val comment: String = "",

    /** The CRC32 of the uncompressed data, or -1 if not set. */
    val crc: Long = -1L,

    /** The compressed size in bytes, or -1 if unknown. */
    val compressedSize: Long = -1L,

    /** The uncompressed size in bytes, or -1 if unknown. */
    val size: Long = -1L,

    /** Either [COMPRESSION_METHOD_DEFLATED] or [COMPRESSION_METHOD_STORED]. */
    val compressionMethod: Int = -1,

    val offset: Long = -1L,

    /**
     * The base ZIP format tracks the [last modified timestamp][FileMetadata.lastModifiedAtMillis]. It
     * does not track [created timestamps][FileMetadata.createdAtMillis] or [last accessed
     * timestamps][FileMetadata.lastAccessedAtMillis].
     *
     * This format has severe limitations:
     *
     *  * Timestamps are 16-bit values stored with 2-second precision. Some zip encoders (WinZip,
     *    PKZIP) round up to the nearest 2 seconds; other encoders (Java) round down.
     *
     *  * Timestamps before 1980-01-01 cannot be represented. They cannot represent dates after
     *    2107-12-31.
     *
     *  * Timestamps are stored in local time with no time zone offset. If the time zone offset
     *    changes – due to daylight savings time or the zip file being sent to another time zone –
     *    file times will be incorrect. The file time will be shifted by the difference in time zone
     *    offsets between the encoder and decoder.
     */
    val dosLastModifiedAtDate: Int = -1,
    val dosLastModifiedAtTime: Int = -1,

    /**
     * NTFS timestamps (0x000a) support creation time, last access time, and last modified time.
     * These timestamps are stored with 100-millisecond precision using UTC.
     */
    val ntfsLastModifiedAtFiletime: Long? = null,
    val ntfsLastAccessedAtFiletime: Long? = null,
    val ntfsCreatedAtFiletime: Long? = null,

    /**
     * Extended timestamps (0x5455) are stored as signed 32-bit timestamps with 1-second precision.
     * These cannot express dates beyond 2038-01-19.
     */
    val extendedLastModifiedAtSeconds: Int? = null,
    val extendedLastAccessedAtSeconds: Int? = null,
    val extendedCreatedAtSeconds: Int? = null,
) {
    val children = mutableListOf<String>()

    internal fun copy(
        extendedLastModifiedAtSeconds: Int?,
        extendedLastAccessedAtSeconds: Int?,
        extendedCreatedAtSeconds: Int?,
    ) = ZipEntry(
        canonicalPath = canonicalPath,
        isDirectory = isDirectory,
        comment = comment,
        crc = crc,
        compressedSize = compressedSize,
        size = size,
        compressionMethod = compressionMethod,
        offset = offset,
        dosLastModifiedAtDate = dosLastModifiedAtDate,
        dosLastModifiedAtTime = dosLastModifiedAtTime,
        ntfsLastModifiedAtFiletime = ntfsLastModifiedAtFiletime,
        ntfsLastAccessedAtFiletime = ntfsLastAccessedAtFiletime,
        ntfsCreatedAtFiletime = ntfsCreatedAtFiletime,
        extendedLastModifiedAtSeconds = extendedLastModifiedAtSeconds,
        extendedLastAccessedAtSeconds = extendedLastAccessedAtSeconds,
        extendedCreatedAtSeconds = extendedCreatedAtSeconds,
    )

    internal val lastAccessedAtMillis: Long?
        get() = when {
            ntfsLastAccessedAtFiletime != null -> filetimeToEpochMillis(ntfsLastAccessedAtFiletime)
            extendedLastAccessedAtSeconds != null -> extendedLastAccessedAtSeconds * 1000L
            else -> null
        }

    internal val lastModifiedAtMillis: Long?
        get() = when {
            ntfsLastModifiedAtFiletime != null -> filetimeToEpochMillis(ntfsLastModifiedAtFiletime)
            extendedLastModifiedAtSeconds != null -> extendedLastModifiedAtSeconds * 1000L
            dosLastModifiedAtTime != -1 -> {
                dosDateTimeToEpochMillis(dosLastModifiedAtDate, dosLastModifiedAtTime)
            }
            else -> null
        }

    internal val createdAtMillis: Long?
        get() = when {
            ntfsCreatedAtFiletime != null -> filetimeToEpochMillis(ntfsCreatedAtFiletime)
            extendedCreatedAtSeconds != null -> extendedCreatedAtSeconds * 1000L
            else -> null
        }
}

/**
 * Converts from the Microsoft [filetime] format to the Java epoch millis format.
 *
 *  * Filetime's unit is 100 nanoseconds, and 0 is 1601-01-01T00:00:00Z.
 *  * Java epoch millis' unit is 1 millisecond, and 0 is 1970-01-01T00:00:00Z.
 *
 * See also https://learn.microsoft.com/en-us/windows/win32/api/minwinbase/ns-minwinbase-filetime
 */
internal fun filetimeToEpochMillis(filetime: Long): Long {
    // There's 11,644,473,600,000 milliseconds between 1601-01-01T00:00:00Z and 1970-01-01T00:00:00Z.
    //   val years = 1_970 − 1_601
    //   val leapYears = floor(years / 4) − floor(years / 100)
    //   val days = (years * 365) + leapYears
    //   val millis = days * 24 * 60 * 60 * 1_000
    return filetime / 10_000 - 11_644_473_600_000L
}

/**
 * Converts a 32-bit DOS date+time to milliseconds since epoch. Note that this function interprets
 * a value with no time zone as a value with the local time zone.
 */
internal fun dosDateTimeToEpochMillis(date: Int, time: Int): Long? {
    if (time == -1) {
        return null
    }

    return datePartsToEpochMillis(
        year = 1980 + (date shr 9 and 0x7f),
        month = date shr 5 and 0xf,
        day = date and 0x1f,
        hour = time shr 11 and 0x1f,
        minute = time shr 5 and 0x3f,
        second = time and 0x1f shl 1,
    )
}

/**
 * Roll our own date math because Kotlin doesn't include a built-in date math API, and the
 * kotlinx.datetime library doesn't offer a stable release at this time.
 *
 * Also, we don't necessarily want to take on that dependency for Okio.
 *
 * This implementation assumes UTC.
 *
 * This code is broken for years before 1970. It doesn't implement subtraction for leap years.
 *
 * This code is broken for out-of-range values. For example, it doesn't correctly implement leap
 * year offsets when the month is -24 or when the day is -365.
 */
internal fun datePartsToEpochMillis(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
): Long {
    // Make sure month is in 1..12, adding or subtracting years as necessary.
    val rawMonth = month
    val month = (month - 1).mod(12) + 1
    val year = year + (rawMonth - month) / 12

    // Start with the cumulative number of days elapsed preceding the current year.
    var dayCount = (year - 1970) * 365L

    // Adjust by leap years. Years that divide 4 are leap years, unless they divide 100 but not 400.
    val leapYear = if (month > 2) year else year - 1
    dayCount += (leapYear - 1968) / 4 - (leapYear - 1900) / 100 + (leapYear - 1600) / 400

    // Add the cumulative number of days elapsed preceding the current month.
    dayCount += when (month) {
        1 -> 0
        2 -> 31
        3 -> 59
        4 -> 90
        5 -> 120
        6 -> 151
        7 -> 181
        8 -> 212
        9 -> 243
        10 -> 273
        11 -> 304
        else -> 334
    }

    // Add the cumulative number of days that precede the current day.
    dayCount += (day - 1)

    // Add hours + minutes + seconds for the current day.
    val hourCount = dayCount * 24 + hour
    val minuteCount = hourCount * 60 + minute
    val secondCount = minuteCount * 60 + second
    return secondCount * 1_000L
}

// End of central directory record
private suspend fun AsyncSource.readEocdRecord(): EocdRecord {
    val diskNumber = readShortLe().toInt() and 0xffff
    val diskWithCentralDir = readShortLe().toInt() and 0xffff
    val entryCount = (readShortLe().toInt() and 0xffff).toLong()
    val totalEntryCount = (readShortLe().toInt() and 0xffff).toLong()
    if (entryCount != totalEntryCount || diskNumber != 0 || diskWithCentralDir != 0) {
        throw IOException("unsupported zip: spanned")
    }
    skip(4) // skip central directory size.
    val centralDirectoryOffset = readIntLe().toLong() and 0xffffffffL
    val commentByteCount = readShortLe().toInt() and 0xffff

    return EocdRecord(
        entryCount = entryCount,
        centralDirectoryOffset = centralDirectoryOffset,
        commentByteCount = commentByteCount,
    )
}

private suspend fun AsyncSource.readZip64EocdRecord(regularRecord: EocdRecord): EocdRecord {
    skip(12) // size of central directory record (8) + version made by (2) + version to extract (2).
    val diskNumber = readIntLe()
    val diskWithCentralDirStart = readIntLe()
    val entryCount = readLongLe()
    val totalEntryCount = readLongLe()
    if (entryCount != totalEntryCount || diskNumber != 0 || diskWithCentralDirStart != 0) {
        throw IOException("unsupported zip: spanned")
    }
    skip(8) // central directory size.
    val centralDirectoryOffset = readLongLe()

    return EocdRecord(
        entryCount = entryCount,
        centralDirectoryOffset = centralDirectoryOffset,
        commentByteCount = regularRecord.commentByteCount,
    )
}

private data class EocdRecord(
    val entryCount: Long,
    val centralDirectoryOffset: Long,
    val commentByteCount: Int,
)

/** When this returns, [this] will be positioned at the start of the next entry. */
internal suspend fun AsyncSource.readCentralDirectoryZipEntry(): ZipEntry {
    val signature = readIntLe()
    if (signature != CENTRAL_FILE_HEADER_SIGNATURE) {
        throw IOException(
            "bad zip: expected ${CENTRAL_FILE_HEADER_SIGNATURE.hex} but was ${signature.hex}",
        )
    }

    skip(4) // version made by (2) + version to extract (2).
    val bitFlag = readShortLe().toInt() and 0xffff
    if (bitFlag and BIT_FLAG_UNSUPPORTED_MASK != 0) {
        throw IOException("unsupported zip: general purpose bit flag=${bitFlag.hex}")
    }

    val compressionMethod = readShortLe().toInt() and 0xffff
    val dosLastModifiedTime = readShortLe().toInt() and 0xffff
    val dosLastModifiedDate = readShortLe().toInt() and 0xffff

    // These are 32-bit values in the file, but 64-bit fields in this object.
    val crc = readIntLe().toLong() and 0xffffffffL
    var compressedSize = readIntLe().toLong() and 0xffffffffL
    var size = readIntLe().toLong() and 0xffffffffL
    val nameSize = readShortLe().toInt() and 0xffff
    val extraSize = readShortLe().toInt() and 0xffff
    val commentByteCount = readShortLe().toInt() and 0xffff

    skip(8) // disk number start (2) + internal file attributes (2) + external file attributes (4).
    var offset = readIntLe().toLong() and 0xffffffffL
    val name = readString(nameSize.toLong())
    if ('\u0000' in name) throw IOException("bad zip: filename contains 0x00")

    val requiredZip64ExtraSize = run {
        var result = 0L
        if (size == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) result += 8
        if (compressedSize == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) result += 8
        if (offset == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) result += 8
        return@run result
    }

    var ntfsLastModifiedAtFiletime: Long? = null
    var ntfsLastAccessedAtFiletime: Long? = null
    var ntfsCreatedAtFiletime: Long? = null

    var hasZip64Extra = false
    readExtra(extraSize) { headerId, dataSize ->
        when (headerId) {
            HEADER_ID_ZIP64_EXTENDED_INFO -> {
                if (hasZip64Extra) {
                    throw IOException("bad zip: zip64 extra repeated")
                }
                hasZip64Extra = true

                if (dataSize < requiredZip64ExtraSize) {
                    throw IOException("bad zip: zip64 extra too short")
                }

                // Read each field if it has a sentinel value in the regular header.
                size = if (size == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) readLongLe() else size
                compressedSize = if (compressedSize == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) readLongLe() else 0L
                offset = if (offset == MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) readLongLe() else 0L
            }

            HEADER_ID_NTFS_EXTRA -> {
                if (dataSize < 4L) {
                    throw IOException("bad zip: NTFS extra too short")
                }
                skip(4L)

                // Reads the NTFS extra metadata. This metadata recursively does a tag and length scheme
                // inside of ZIP extras' own tag and length scheme. So we do readExtra() again.
                readExtra((dataSize - 4L).toInt()) { attributeId, attributeSize ->
                    when (attributeId) {
                        0x1 -> {
                            if (ntfsLastModifiedAtFiletime != null) {
                                throw IOException("bad zip: NTFS extra attribute tag 0x0001 repeated")
                            }

                            if (attributeSize != 24L) {
                                throw IOException("bad zip: NTFS extra attribute tag 0x0001 size != 24")
                            }

                            ntfsLastModifiedAtFiletime = readLongLe()
                            ntfsLastAccessedAtFiletime = readLongLe()
                            ntfsCreatedAtFiletime = readLongLe()
                        }
                    }
                }
            }
        }
    }

    if (requiredZip64ExtraSize > 0L && !hasZip64Extra) {
        throw IOException("bad zip: zip64 extra required but absent")
    }

    val comment = readString(commentByteCount.toLong())
    val canonicalPath = "/${name}"
    val isDirectory = name.endsWith("/")

    return ZipEntry(
        canonicalPath = canonicalPath,
        isDirectory = isDirectory,
        comment = comment,
        crc = crc,
        compressedSize = compressedSize,
        size = size,
        compressionMethod = compressionMethod,
        offset = offset,
        dosLastModifiedAtDate = dosLastModifiedDate,
        dosLastModifiedAtTime = dosLastModifiedTime,
        ntfsLastModifiedAtFiletime = ntfsLastModifiedAtFiletime,
        ntfsLastAccessedAtFiletime = ntfsLastAccessedAtFiletime,
        ntfsCreatedAtFiletime = ntfsCreatedAtFiletime,
    )
}

internal suspend fun AsyncSource.skipLocalHeader() {
    readOrSkipLocalHeader(null)
}

/**
 * If [centralDirectoryZipEntry] is null this will return null. Otherwise, it will return a new
 * entry which unions [centralDirectoryZipEntry] with information from the local header.
 */
private suspend fun AsyncSource.readOrSkipLocalHeader(
    centralDirectoryZipEntry: ZipEntry?,
): ZipEntry? {
    val signature = readIntLe()
    if (signature != LOCAL_FILE_HEADER_SIGNATURE) {
        throw IOException(
            "bad zip: expected ${LOCAL_FILE_HEADER_SIGNATURE.hex} but was ${signature.hex}",
        )
    }
    skip(2) // version to extract.
    val bitFlag = readShortLe().toInt() and 0xffff
    if (bitFlag and BIT_FLAG_UNSUPPORTED_MASK != 0) {
        throw IOException("unsupported zip: general purpose bit flag=${bitFlag.hex}")
    }
    skip(18) // compression method (2) + time+date (4) + crc32 (4) + compressed size (4) + size (4).
    val fileNameLength = readShortLe().toLong() and 0xffff
    val extraSize = readShortLe().toInt() and 0xffff
    skip(fileNameLength)

    if (centralDirectoryZipEntry == null) {
        skip(extraSize.toLong())
        return null
    }

    var extendedLastModifiedAtSeconds: Int? = null
    var extendedLastAccessedAtSeconds: Int? = null
    var extendedCreatedAtSeconds: Int? = null

    readExtra(extraSize) { headerId, dataSize ->
        when (headerId) {
            HEADER_ID_EXTENDED_TIMESTAMP -> {
                if (dataSize < 1) {
                    throw IOException("bad zip: extended timestamp extra too short")
                }
                val flags = readByte().toInt() and 0xff

                val hasLastModifiedAtMillis = (flags and 0x1) == 0x1
                val hasLastAccessedAtMillis = (flags and 0x2) == 0x2
                val hasCreatedAtMillis = (flags and 0x4) == 0x4
                val requiredSize = run {
                    var result = 1L
                    if (hasLastModifiedAtMillis) result += 4L
                    if (hasLastAccessedAtMillis) result += 4L
                    if (hasCreatedAtMillis) result += 4L
                    return@run result
                }
                if (dataSize < requiredSize) {
                    throw IOException("bad zip: extended timestamp extra too short")
                }

                if (hasLastModifiedAtMillis) extendedLastModifiedAtSeconds = readIntLe()
                if (hasLastAccessedAtMillis) extendedLastAccessedAtSeconds = readIntLe()
                if (hasCreatedAtMillis) extendedCreatedAtSeconds = readIntLe()
            }
        }
    }

    return centralDirectoryZipEntry.copy(
        extendedLastModifiedAtSeconds = extendedLastModifiedAtSeconds,
        extendedLastAccessedAtSeconds = extendedLastAccessedAtSeconds,
        extendedCreatedAtSeconds = extendedCreatedAtSeconds,
    )
}

/**
 * Read a sequence of 0 or more extra fields. Each field has this structure:
 *
 *  * 2-byte header ID
 *  * 2-byte data size
 *  * variable-byte data value
 *
 * This reads each extra field and calls [block] for each. The parameters are the header ID and
 * data size. It is an error for [block] to process more bytes than the data size.
 */
private suspend fun AsyncSource.readExtra(extraSize: Int, block: suspend (Int, Long) -> Unit) {
    var remaining = extraSize.toLong()
    while (remaining != 0L) {
        if (remaining < 4) {
            throw IOException("bad zip: truncated header in extra field")
        }
        val headerId = readShortLe().toInt() and 0xffff
        val dataSize = readShortLe().toLong() and 0xffff
        remaining -= 4
        if (remaining < dataSize) {
            throw IOException("bad zip: truncated value in extra field")
        }
        require(dataSize)
        val sizeBefore = buffer.size
        block(headerId, dataSize)
        val fieldRemaining = dataSize + buffer.size - sizeBefore
        when {
            fieldRemaining < 0 -> {
                throw IOException("unsupported zip: too many bytes processed for $headerId")
            }
            fieldRemaining > 0 -> {
                buffer.skip(fieldRemaining)
            }
        }
        remaining -= dataSize
    }
}

internal expect suspend fun SuspendIo.fileSize(fd: FileHandle): Long
internal expect suspend fun SuspendIo.positionalFileSource(initialOffset: Long, fd: FileHandle) : AsyncRawSource

private val Int.hex: String
    get() = "0x${this.toString(16)}"

internal fun AsyncRawSource.fixed(size: Long): FixedSizeSource = FixedSizeSource(this, size)

internal class FixedSizeSource(
    private val upstream: AsyncRawSource,
    private var remaining: Long,
) : AsyncRawSource {
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (remaining == 0L) return -1L

        val toRead = minOf(byteCount, remaining)
        val read = upstream.readAtMostTo(sink, toRead)

        if (read == -1L) {
            throw EOFException("Unexpected EOF while reading request body")
        }

        remaining -= read
        return read
    }

    override suspend fun close() {
        upstream.close()
    }
}

/**
 * Overall .ZIP file format:
 *       [local file header 1]
 *       [encryption header 1]
 *       [file data 1]
 *       [data descriptor 1]
 *       .
 *       .
 *       .
 *       [local file header n]
 *       [encryption header n]
 *       [file data n]
 *       [data descriptor n]
 *       [archive decryption header]
 *       [archive extra data record]
 *       [central directory header 1]
 *       .
 *       .
 *       .
 *       [central directory header n]
 *       [zip64 end of central directory record]
 *       [zip64 end of central directory locator]
 *       [end of central directory record]
 */
internal class WriteZipScopeImpl(private val sink: AsyncSink): WriteZipScope {
    private val entries = mutableListOf<WrittenZipEntry>()
    private var position = 0L
    private var writing = false
    private var failed = false
    var finished = false

    /** Writes one file; calls must be sequential. Paths are relative UTF-8 ZIP paths. */
    override suspend fun file(path: String, source: AsyncSource) {
        check(!finished && !failed && !writing) { "ZIP writer is not available" }
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path) {
            "Invalid ZIP path: $path"
        }
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Expected a relative file path: $path"
        }
        require(entries.none { it.path == path }) { "Duplicate ZIP path: $path" }
        val name = path.encodeToByteArray()
        require(name.size <= 0xffff) { "ZIP file name is too long" }
        check(entries.size < 0xfffe) { "ZIP64 is not supported" }
        writing = true
        try {
            val offset = position
            writeHeader(local = true, name = name)
            val dataOffset = position
            var crc = -1
            var size = 0L
            var discard = false
            val target = object : AsyncRawSink {
                override suspend fun write(source: Buffer, byteCount: Long) {
                    if (discard) {
                        source.skip(byteCount)
                        return
                    }
                    checkZipSize(position + byteCount)
                    sink.write(source, byteCount)
                    position += byteCount
                }
                override suspend fun flush() = Unit
                override suspend fun close() = Unit
            }.buffered()
            val deflater = target.deflateSink()
            try {
                val bytes = ByteArray(CHUNK_SIZE)
                val input = Buffer()
                while (true) {
                    val read = source.readAtMostTo(bytes)
                    if (read == -1) break
                    if (read == 0) continue
                    size += read
                    checkZipSize(size)
                    repeat(read) { index ->
                        crc = (crc ushr 8) xor ZIP_CRC_TABLE[(crc xor bytes[index].toInt()) and 0xff]
                    }
                    input.write(bytes, 0, read)
                    deflater.write(input, read.toLong())
                }
            } catch (error: Throwable) {
                // Release the compressor without appending a partial entry to the archive.
                discard = true
                try {
                    withContext(NonCancellable) { deflater.close() }
                } catch (cleanup: Throwable) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
                throw error
            }
            deflater.close()
            val compressedSize = position - dataOffset
            val checksum = crc.inv().toLong() and 0xffffffffL
            writeInt(0x08074b50)
            writeInt(checksum)
            writeInt(compressedSize)
            writeInt(size)
            entries += WrittenZipEntry(path, name, checksum, compressedSize, size, offset)
        } catch (error: Throwable) {
            failed = true
            throw error
        } finally {
            writing = false
        }
    }

    internal suspend fun finish() {
        check(!finished && !failed && !writing) { "ZIP writer is not available" }
        finished = true
        val directoryOffset = position
        for (entry in entries) {
            writeHeader(false, entry.name, entry.crc, entry.compressedSize, entry.size, entry.offset)
        }
        val directorySize = position - directoryOffset
        writeInt(END_OF_CENTRAL_DIRECTORY_SIGNATURE.toLong())
        writeShort(0) // Disk number.
        writeShort(0) // Central directory disk.
        writeShort(entries.size)
        writeShort(entries.size)
        writeInt(directorySize)
        writeInt(directoryOffset)
        writeShort(0) // Archive comment.
        sink.flush()
    }

    /**
     * Local file header:
     *
     *       local file header signature     4 bytes  (0x04034b50)
     *       version needed to extract       2 bytes
     *       general purpose bit flag        2 bytes
     *       compression method              2 bytes
     *       last mod file time              2 bytes
     *       last mod file date              2 bytes
     *       crc-32                          4 bytes
     *       compressed size                 4 bytes
     *       uncompressed size               4 bytes
     *       file name length                2 bytes
     *       extra field length              2 bytes
     *
     *       file name (variable size)
     *       extra field (variable size)
     *       [central directory header n]
     *       [digital signature]
     *
     * central directory header:
     *       central file header signature   4 bytes  (0x02014b50)
     *       version made by                 2 bytes
     *       version needed to extract       2 bytes
     *       general purpose bit flag        2 bytes
     *       compression method              2 bytes
     *       last mod file time              2 bytes
     *       last mod file date              2 bytes
     *       crc-32                          4 bytes
     *       compressed size                 4 bytes
     *       uncompressed size               4 bytes
     *       file name length                2 bytes
     *       extra field length              2 bytes
     *       file comment length             2 bytes
     *       disk number start               2 bytes
     *       internal file attributes        2 bytes
     *       external file attributes        4 bytes
     *       relative offset of local header 4 bytes
     *
     *       file name (variable size)
     *       extra field (variable size)
     *       file comment (variable size)
     */
    private suspend fun writeHeader(
        local: Boolean,
        name: ByteArray,
        crc: Long = 0,
        compressedSize: Long = 0,
        size: Long = 0,
        offset: Long = 0,
    ) {
        writeInt((if (local) LOCAL_FILE_HEADER_SIGNATURE else CENTRAL_FILE_HEADER_SIGNATURE).toLong())
        if (!local) writeShort(20) // Version made by: ZIP 2.0, MS-DOS.
        writeShort(20) // Version needed: ZIP 2.0.
        writeShort(0x0808) // UTF-8 names and trailing data descriptor.
        writeShort(COMPRESSION_METHOD_DEFLATED)
        writeShort(0) // DOS time: midnight.
        writeShort(0x21) // DOS date: 1980-01-01.
        writeInt(crc)
        writeInt(compressedSize)
        writeInt(size)
        writeShort(name.size)
        writeShort(0) // Extra fields.
        if (!local) {
            writeShort(0) // Entry comment.
            writeShort(0) // Disk number.
            writeShort(0) // Internal attributes.
            writeInt(0) // External attributes.
            writeInt(offset)
        }
        checkZipSize(position + name.size)
        sink.write(name)
        position += name.size
    }

    private suspend fun writeShort(value: Int) {
        checkZipSize(position + 2)
        repeat(2) { sink.writeByte((value ushr (it * 8)).toByte()) }
        position += 2
    }

    private suspend fun writeInt(value: Long) {
        checkZipSize(position + 4)
        repeat(4) { sink.writeByte((value ushr (it * 8)).toByte()) }
        position += 4
    }

    private fun checkZipSize(size: Long) {
        check(size in 0 until MAX_ZIP_ENTRY_AND_ARCHIVE_SIZE) { "ZIP64 is not supported" }
    }
}

private class WrittenZipEntry(
    val path: String,
    val name: ByteArray,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val offset: Long,
)

private val ZIP_CRC_TABLE = IntArray(256) { value ->
    var crc = value
    repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
    crc
}
