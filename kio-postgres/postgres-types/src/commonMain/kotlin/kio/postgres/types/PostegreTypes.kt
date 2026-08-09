package kio.postgres.types

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// TODO: Support get types from PgType Serializer.
fun KSerializer<*>.typeOids(): List<Int> = descriptor.elementDescriptors.map { descriptor ->
    getTypeOid(descriptor)
}

fun KSerializer<*>.formats() = descriptor.elementDescriptors.map { descriptor ->
    getFormat(descriptor)
}

typealias PgBool = @Serializable(PostgresBoolSerializer::class) Boolean
typealias PgInt8 = @Serializable(PostgresInt8Serializer::class) Long
typealias PgInt4 = @Serializable(PostgresInt4Serializer::class) Int
typealias PgInt2 = @Serializable(PostgresInt2Serializer::class) Short
typealias PgText = @Serializable(PostgresTextSerializer::class) String
typealias PgChar = @Serializable(PostgresCharSerializer::class) Char
typealias PgFloat4 = @Serializable(PostgresFloat4Serializer::class) Float
typealias PgFloat8 = @Serializable(PostgresFloat8Serializer::class) Double
typealias PgTimestamp = @Serializable(PostgresTimestampSerializer::class) LocalDateTime
typealias PgTimestampTz = @Serializable(PostgresTimestampTzSerializer::class) Instant
typealias PgBytea = @Serializable(PostgresByteaSerializer::class) ByteArray
typealias PgDate = @Serializable(PostgresDateSerializer::class) LocalDate
typealias PgTime = @Serializable(PostgresTimeSerializer::class) LocalTime
@OptIn(ExperimentalUuidApi::class)
typealias PgUuid = @Serializable(PostgresUuidSerializer::class) Uuid
typealias PgInt4Range = @Serializable(PostgresInt4RangeSerializer::class) PgRange<PgInt4>
typealias PgInt8Range = @Serializable(PostgresInt8RangeSerializer::class) PgRange<PgInt8>
typealias PgDateRange = @Serializable(PostgresDateRangeSerializer::class) PgRange<PgDate>
typealias PgTsRange = @Serializable(PostgresTimeStampRangeSerializer::class) PgRange<PgTimestamp>
typealias PgTsTzRange = @Serializable(PostgresTimeStampTzRangeSerializer::class) PgRange<PgTimestampTz>
typealias PgInt4MultiRange = @Serializable(PostgresInt4MultiRangeSerializer::class) PgMultiRange<PgInt4Range>
typealias PgInt8MultiRange = @Serializable(PostgresInt8MultiRangeSerializer::class) PgMultiRange<PgInt8Range>
typealias PgTsMultiRange = @Serializable(PostgresTimeStampMultiRangeSerializer::class) PgMultiRange<PgTsRange>
typealias PgDateMultiRange = @Serializable(PostgresDateMultiRangeSerializer::class) PgMultiRange<PgDateRange>
typealias PgTsTzMultiRange = @Serializable(PostgresTimeStampTzMultiRangeSerializer::class) PgMultiRange<PgTsTzRange>
@Serializable(PostgresPointSerializer::class) data class PgPoint(val x: Double, val y: Double)
@Serializable(PostgresLineSerializer::class) data class PgLine(val a: Double, val b: Double, val c: Double)
@Serializable(PostgresLsegSerializer::class) data class PgLseg(val x1: Double, val y1: Double, val x2: Double, val y2: Double)
@Serializable(PostgresBoxSerializer::class) data class PgBox(val x1: Double, val y1: Double, val x2: Double, val y2: Double)
@Serializable(PostgresPathSerializer::class) data class PgPath(val closed: Boolean, val points: List<PgPoint>)
@Serializable(PostgresPolygonSerializer::class) data class PgPolygon(val points: List<PgPoint>)
@Serializable(PostgresCircleSerializer::class) data class PgCircle(val center: PgPoint, val radius: Double)

data class PgRange<T>(
    val lower: T?,
    val upper: T?,
    val lowerType: BoundType,
    val upperType: BoundType,
) {

    enum class BoundType {
        Inclusive,
        Exclusive,
        Unbounded,
        Empty,
    }
}

data class PgMultiRange<T>(val ranges: List<T>)

object PostgresBoolSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_BOOL_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeStructure(descriptor) {
            encodeBooleanElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): Boolean {
        return decoder.decodeStructure(descriptor) {
            decodeBooleanElement(descriptor, 0)
        }
    }
}

object PostgresCharSerializer : KSerializer<Char> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_CHAR_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: Char) {
        encoder.encodeStructure(descriptor) {
            encodeCharElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): Char {
        return decoder.decodeStructure(descriptor) {
            decodeCharElement(descriptor, 0)
        }
    }
}

object PostgresInt8Serializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_INT8_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeStructure(descriptor) {
            encodeLongElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): Long {
        return decoder.decodeStructure(descriptor) {
            decodeLongElement(descriptor, 0)
        }
    }
}

object PostgresInt2Serializer : KSerializer<Short> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_INT2_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: Short) {
        encoder.encodeStructure(descriptor) {
            encodeShortElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): Short {
        return decoder.decodeStructure(descriptor) {
            decodeShortElement(descriptor, 0)
        }
    }
}

private interface AbstractPostgresInt4Serializer : KSerializer<Int> {
    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): Int {
        return decoder.decodeStructure(descriptor) {
            decodeIntElement(descriptor, 0)
        }
    }
}

object PostgresInt4Serializer : AbstractPostgresInt4Serializer {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_INT4_SERIALIZER_BIN) {}
}

object PostgresTextSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_TEXT_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): String {
        return decoder.decodeStructure(descriptor) {
            decodeStringElement(descriptor, 0)
        }
    }
}

object PostgresFloat4Serializer : KSerializer<Float> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_FLOAT4_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: Float) {
        encoder.encodeStructure(descriptor) {
            encodeFloatElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): Float {
        return decoder.decodeStructure(descriptor) {
            decodeFloatElement(descriptor, 0)
        }
    }
}

object PostgresFloat8Serializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_FLOAT8_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: Double) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): Double {
        return decoder.decodeStructure(descriptor) {
            decodeDoubleElement(descriptor, 0)
        }
    }
}

object PostgresTimestampSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_TIMESTAMP_SERIALIZER_BIN) {}

    private val POSTGRES_EPOCH_MICROS =
        LocalDateTime.orNull(2000, 1, 1, 0, 0, 0)!!
            .toInstant(TimeZone.UTC).toEpochMilliseconds()

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        val instant = value.toInstant(TimeZone.UTC)
        val unixMicros = instant.toEpochMilliseconds()
        val pgMicros = unixMicros - POSTGRES_EPOCH_MICROS

        encoder.encodeStructure(descriptor) {
            encodeLongElement(descriptor, 0, pgMicros)
        }
    }

    override fun deserialize(decoder: Decoder): LocalDateTime {
        val pgMicros = decoder.decodeStructure(descriptor) {
            decodeLongElement(descriptor, 0)
        }
        return Instant.fromEpochMilliseconds(pgMicros + POSTGRES_EPOCH_MICROS)
            .toLocalDateTime(TimeZone.UTC)
    }
}

object PostgresTimestampTzSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_TIMESTAMPTZ_SERIALIZER_BIN) {}

    private val POSTGRES_EPOCH_MICROS =
        LocalDateTime.orNull(2000, 1, 1, 0, 0, 0)!!
            .toInstant(TimeZone.UTC).toEpochMilliseconds()

    override fun serialize(encoder: Encoder, value: Instant) {
        val unixMicros = value.toEpochMilliseconds()
        val pgMicros = unixMicros - POSTGRES_EPOCH_MICROS

        encoder.encodeStructure(descriptor) {
            encodeLongElement(descriptor, 0, pgMicros)
        }
    }

    override fun deserialize(decoder: Decoder): Instant {
        val pgMicros = decoder.decodeStructure(descriptor) {
            decodeLongElement(descriptor, 0)
        }
        return Instant.fromEpochMilliseconds(pgMicros + POSTGRES_EPOCH_MICROS)
    }
}

object PostgresByteaSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_BYTEA_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeStructure(descriptor) {
            this as PgCompositeEncoder
            encodeByteArrayElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return decoder.decodeStructure(descriptor) {
            this as PgCompositeDecoder
            decodeByteArrayElement(descriptor, 0)
        }
    }
}

object PostgresDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_DATE_SERIALIZER_BIN) {}

    private val BASE_DATE = LocalDate.orNull(2000, 1, 1)!!

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeStructure(descriptor) {
            val dayCount = BASE_DATE.daysUntil(value)
            encodeIntElement(descriptor, 0, dayCount)
        }
    }

    override fun deserialize(decoder: Decoder): LocalDate {
        val days = decoder.decodeStructure(descriptor) {
            decodeIntElement(descriptor, 0)
        }
        return BASE_DATE.plus(days, DateTimeUnit.DAY)
    }
}

object PostgresTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_TIME_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: LocalTime) {
        encoder.encodeStructure(descriptor) {
            val seconds = value.toSecondOfDay()
            encodeIntElement(descriptor, 0, seconds)
        }
    }

    override fun deserialize(decoder: Decoder): LocalTime {
        val seconds = decoder.decodeStructure(descriptor) {
            decodeIntElement(descriptor, 0)
        }
        return LocalTime.fromSecondOfDay(seconds)
    }
}

@OptIn(ExperimentalUuidApi::class)
object PostgresUuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_UUID_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: Uuid) {
        encoder.encodeStructure(descriptor) {
            this as PgCompositeEncoder
            encodeByteArrayElement(descriptor, 0, value.toByteArray())
        }
    }

    override fun deserialize(decoder: Decoder): Uuid {
        val raw = decoder.decodeStructure(descriptor) {
            this as PgCompositeDecoder
            decodeByteArrayElement(descriptor, 0)
        }
        return Uuid.fromByteArray(raw)
    }
}

object PostgresPointSerializer : KSerializer<PgPoint> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_POINT_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: PgPoint) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.x)
            encodeDoubleElement(descriptor, 0, value.y)
        }
    }

    override fun deserialize(decoder: Decoder): PgPoint {
        var x: Double? = null
        var y: Double? = null
        decoder.decodeStructure(descriptor) {
            x = decodeDoubleElement(descriptor, 0)
            y = decodeDoubleElement(descriptor, 0)
        }
        check(x != null && y != null)
        return PgPoint(x, y)
    }
}

object PostgresLineSerializer : KSerializer<PgLine> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_LINE_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: PgLine) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.a)
            encodeDoubleElement(descriptor, 0, value.b)
            encodeDoubleElement(descriptor, 0, value.c)
        }
    }

    override fun deserialize(decoder: Decoder): PgLine {
        var a: Double? = null
        var b: Double? = null
        var c: Double? = null
        decoder.decodeStructure(descriptor) {
            a = decodeDoubleElement(descriptor, 0)
            b = decodeDoubleElement(descriptor, 0)
            c = decodeDoubleElement(descriptor, 0)
        }
        check(a != null && b != null && c != null)
        return PgLine(a, b, c)
    }
}

object PostgresLsegSerializer : KSerializer<PgLseg> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_LSEG_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: PgLseg) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.x1)
            encodeDoubleElement(descriptor, 0, value.y1)
            encodeDoubleElement(descriptor, 0, value.x2)
            encodeDoubleElement(descriptor, 0, value.y2)
        }
    }

    override fun deserialize(decoder: Decoder): PgLseg {
        var x1: Double? = null
        var y1: Double? = null
        var x2: Double? = null
        var y2: Double? = null
        decoder.decodeStructure(descriptor) {
            x1 = decodeDoubleElement(descriptor, 0)
            y1 = decodeDoubleElement(descriptor, 0)
            x2 = decodeDoubleElement(descriptor, 0)
            y2 = decodeDoubleElement(descriptor, 0)
        }
        check(x1 != null && y1 != null && x2 != null && y2 != null)
        return PgLseg(x1, y1, x2, y2)
    }
}

object PostgresBoxSerializer : KSerializer<PgBox> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_BOX_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: PgBox) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.x1)
            encodeDoubleElement(descriptor, 0, value.y1)
            encodeDoubleElement(descriptor, 0, value.x2)
            encodeDoubleElement(descriptor, 0, value.y2)
        }
    }

    override fun deserialize(decoder: Decoder): PgBox {
        var x1: Double? = null
        var y1: Double? = null
        var x2: Double? = null
        var y2: Double? = null
        decoder.decodeStructure(descriptor) {
            x1 = decodeDoubleElement(descriptor, 0)
            y1 = decodeDoubleElement(descriptor, 0)
            x2 = decodeDoubleElement(descriptor, 0)
            y2 = decodeDoubleElement(descriptor, 0)
        }
        check(x1 != null && y1 != null && x2 != null && y2 != null)
        return PgBox(x1, y1, x2, y2)
    }
}

object PostgresPathSerializer : KSerializer<PgPath> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_PATH_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: PgPath) {
        encoder.encodeStructure(descriptor) {
            encodeByteElement(descriptor, 0, if (value.closed) 1 else 0)
            encodeIntElement(descriptor, 0, value.points.size)

            value.points.forEach { point ->
                encodeDoubleElement(descriptor, 0, point.x)
                encodeDoubleElement(descriptor, 0, point.y)
            }
        }
    }

    override fun deserialize(decoder: Decoder): PgPath {
        return decoder.decodeStructure(descriptor) {
            val closed = decodeByteElement(descriptor, 0).toInt() == 1
            val count = decodeIntElement(descriptor, 0)
            val points = buildList {
                repeat(count) { i ->
                    add(
                        PgPoint(
                            decodeDoubleElement(descriptor, 0),
                            decodeDoubleElement(descriptor, 0),
                        )
                    )
                }
            }
            PgPath(closed, points)
        }
    }
}

object PostgresPolygonSerializer : KSerializer<PgPolygon> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_POLYGON_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: PgPolygon) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.points.size)

            value.points.forEach { point ->
                encodeDoubleElement(descriptor, 0, point.x)
                encodeDoubleElement(descriptor, 0, point.y)
            }
        }
    }

    override fun deserialize(decoder: Decoder): PgPolygon {
        return decoder.decodeStructure(descriptor) {
            val count = decodeIntElement(descriptor, 0)
            val points = buildList {
                repeat(count) { i ->
                    add(
                        PgPoint(
                            decodeDoubleElement(descriptor, 0),
                            decodeDoubleElement(descriptor, 0),
                        )
                    )
                }
            }
            PgPolygon(points)
        }
    }
}

object PostgresCircleSerializer : KSerializer<PgCircle> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_CIRCLE_SERIALIZER_BIN) {}

    override fun serialize(encoder: Encoder, value: PgCircle) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.center.x)
            encodeDoubleElement(descriptor, 0, value.center.y)
            encodeDoubleElement(descriptor, 0, value.radius)
        }
    }

    override fun deserialize(decoder: Decoder): PgCircle {
        return decoder.decodeStructure(descriptor) {
            val centerX = decodeDoubleElement(descriptor, 0)
            val centerY = decodeDoubleElement(descriptor, 0)
            val radius = decodeDoubleElement(descriptor, 0)
            PgCircle(PgPoint(centerX, centerY), radius)
        }
    }
}

private interface PostgresTextFormatSerializer : KSerializer<String> {
    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value)
        }
    }

    override fun deserialize(decoder: Decoder): String {
        return decoder.decodeStructure(descriptor) {
            decodeStringElement(descriptor, 0)
        }
    }
}

class PostgresInt4RangeSerializer(
    override val dataSerializer: KSerializer<PgInt4>
) : PostgresRangeSerializer<PgInt4> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_RANGE_INT4_SERIALIZER_BIN)

    override fun createPgRange(lower: PgInt4?, upper: PgInt4?, lowerBoundType: PgRange.BoundType, upperBoundType: PgRange.BoundType): PgRange<PgInt4> {
        return PgInt4Range(lower, upper, lowerBoundType, upperBoundType)
    }
}

class PostgresInt8RangeSerializer(
    override val dataSerializer: KSerializer<PgInt8>
) : PostgresRangeSerializer<PgInt8> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_RANGE_INT8_SERIALIZER_BIN)

    override fun createPgRange(lower: PgInt8?, upper: PgInt8?, lowerBoundType: PgRange.BoundType, upperBoundType: PgRange.BoundType): PgRange<PgInt8> {
        return PgInt8Range(lower, upper, lowerBoundType, upperBoundType)
    }
}

class PostgresDateRangeSerializer(
    override val dataSerializer: KSerializer<PgDate>
) : PostgresRangeSerializer<PgDate> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_RANGE_DATE_SERIALIZER_BIN)

    override fun createPgRange(lower: PgDate?, upper: PgDate?, lowerBoundType: PgRange.BoundType, upperBoundType: PgRange.BoundType): PgRange<PgDate> {
        return PgDateRange(lower, upper, lowerBoundType, upperBoundType)
    }
}

class PostgresTimeStampRangeSerializer(
    override val dataSerializer: KSerializer<PgTimestamp>
) : PostgresRangeSerializer<PgTimestamp> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_RANGE_TIMESTAMP_SERIALIZER_BIN)

    override fun createPgRange(lower: PgTimestamp?, upper: PgTimestamp?, lowerBoundType: PgRange.BoundType, upperBoundType: PgRange.BoundType): PgRange<PgTimestamp> {
        return PgTsRange(lower, upper, lowerBoundType, upperBoundType)
    }
}

class PostgresTimeStampTzRangeSerializer(
    override val dataSerializer: KSerializer<PgTimestampTz>
) : PostgresRangeSerializer<PgTimestampTz> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_RANGE_TIMESTAMPTZ_SERIALIZER_BIN)

    override fun createPgRange(lower: PgTimestampTz?, upper: PgTimestampTz?, lowerBoundType: PgRange.BoundType, upperBoundType: PgRange.BoundType): PgRange<PgTimestampTz> {
        return PgTsTzRange(lower, upper, lowerBoundType, upperBoundType)
    }
}

private interface PostgresRangeSerializer<T> : KSerializer<PgRange<T>> {
    val dataSerializer: KSerializer<T>

    override fun serialize(encoder: Encoder, value: PgRange<T>) {
        encoder.encodeStructure(descriptor) {
            var rangeType: Byte = 0x00

            when (value.lowerType) {
                PgRange.BoundType.Inclusive -> rangeType = rangeType or LOWER_INCLUSIVE_BIT_MASK
                PgRange.BoundType.Unbounded -> rangeType = rangeType or LOWER_UNBOUNDED_BIT_MASK
                PgRange.BoundType.Empty -> rangeType = rangeType or EMPTY_BIT_MASK
                PgRange.BoundType.Exclusive -> Unit
            }

            when (value.upperType) {
                PgRange.BoundType.Inclusive -> rangeType = rangeType or UPPER_INCLUSIVE_BIT_MASK
                PgRange.BoundType.Unbounded -> rangeType = rangeType or UPPER_UNBOUNDED_BIT_MASK
                PgRange.BoundType.Empty -> rangeType = rangeType or EMPTY_BIT_MASK
                PgRange.BoundType.Exclusive -> Unit
            }

            encodeByteElement(descriptor, 0, rangeType)

            value.lower?.let {
                encodeSerializableElement(descriptor, 0, dataSerializer, it)
            }
            value.upper?.let {
                encodeSerializableElement(descriptor, 1, dataSerializer, it)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): PgRange<T> {
        return decoder.decodeStructure(descriptor) {
            val rangeType = decodeByteElement(descriptor, 0)
            val element1 = decodeNullableSerializableElement(descriptor, 0, dataSerializer)
            val element2 = decodeNullableSerializableElement(descriptor, 1, dataSerializer)

            val lowerBoundType: PgRange.BoundType
            val upperBoundType: PgRange.BoundType

            if (rangeType and EMPTY_BIT_MASK > 0) {
                check(element1 == null && element2 == null)
                lowerBoundType = PgRange.BoundType.Empty
                upperBoundType = PgRange.BoundType.Empty
            } else {
                lowerBoundType = when {
                    rangeType and LOWER_INCLUSIVE_BIT_MASK > 0 -> PgRange.BoundType.Inclusive
                    rangeType and LOWER_UNBOUNDED_BIT_MASK > 0 -> PgRange.BoundType.Unbounded
                    else -> PgRange.BoundType.Exclusive
                }
                upperBoundType = when {
                    rangeType and UPPER_INCLUSIVE_BIT_MASK > 0 -> PgRange.BoundType.Inclusive
                    rangeType and UPPER_UNBOUNDED_BIT_MASK > 0 -> PgRange.BoundType.Unbounded
                    else -> PgRange.BoundType.Exclusive
                }
            }

            var lower: T?
            var upper: T?
            if ((element1 == null) != (element2 == null)) {
                // only one element
                val element = element1 ?: element2 ?: error("never")
                lower = if (lowerBoundType.exist()) element else null
                upper = if (upperBoundType.exist()) element else null
            } else {
                lower = element1
                upper = element2
            }

            createPgRange(lower, upper, lowerBoundType, upperBoundType)
        }
    }

    fun buildClassSerialDescriptor(name: String) = buildClassSerialDescriptor(name) {
        element("lower", dataSerializer.descriptor)
        element("upper", dataSerializer.descriptor)
    }

    fun createPgRange(
        lower: T?,
        upper: T?,
        lowerBoundType: PgRange.BoundType,
        upperBoundType: PgRange.BoundType,
    ): PgRange<T>

    private fun PgRange.BoundType.exist(): Boolean {
        return this == PgRange.BoundType.Exclusive || this == PgRange.BoundType.Inclusive
    }

    companion object {
        private const val EMPTY_BIT_MASK: Byte = 0x01
        private const val LOWER_INCLUSIVE_BIT_MASK: Byte = 0x02
        private const val UPPER_INCLUSIVE_BIT_MASK: Byte = 0x04
        private const val LOWER_UNBOUNDED_BIT_MASK: Byte = 0x08
        private const val UPPER_UNBOUNDED_BIT_MASK: Byte = 0x10
    }
}

class PostgresInt4MultiRangeSerializer(
    override val rangeSerializer: KSerializer<PgInt4Range>
): PostgresMultiRangeSerializer<PgInt4Range> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_MULTI_RANGE_INT4_SERIALIZER_BIN) {}
}

class PostgresInt8MultiRangeSerializer(
    override val rangeSerializer: KSerializer<PgInt8Range>
): PostgresMultiRangeSerializer<PgInt8Range> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_MULTI_RANGE_INT8_SERIALIZER_BIN) {}
}

class PostgresTimeStampMultiRangeSerializer(
    override val rangeSerializer: KSerializer<PgTsRange>
): PostgresMultiRangeSerializer<PgTsRange> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_MULTI_RANGE_TIMESTAMP_SERIALIZER_BIN) {}
}

class PostgresDateMultiRangeSerializer(
    override val rangeSerializer: KSerializer<PgDateRange>
): PostgresMultiRangeSerializer<PgDateRange> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_MULTI_RANGE_DATE_SERIALIZER_BIN) {}
}

class PostgresTimeStampTzMultiRangeSerializer(
    override val rangeSerializer: KSerializer<PgDateRange>
): PostgresMultiRangeSerializer<PgDateRange> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(POSTGRES_MULTI_RANGE_TIMESTAMPTZ_SERIALIZER_BIN) {}
}

private interface PostgresMultiRangeSerializer<T> : KSerializer<PgMultiRange<T>> {
    val rangeSerializer: KSerializer<T>

    override fun deserialize(decoder: Decoder): PgMultiRange<T> {
        return decoder.decodeStructure(descriptor) {
            val size = decodeIntElement(descriptor, 0)
            PgMultiRange(
                List(size) { i ->
                    decodeSerializableElement(descriptor, i, rangeSerializer)
                }
            )
        }
    }

    override fun serialize(encoder: Encoder, value: PgMultiRange<T>) {
        return encoder.encodeStructure(descriptor) {
            val size = value.ranges.size
            encodeIntElement(descriptor, 0, size)

            value.ranges.forEachIndexed { i, range ->
                encodeSerializableElement(descriptor, i, rangeSerializer, range)
            }
        }
    }
}

internal fun SerialDescriptor.postgresPrimitiveTypeOid(): Int {
    val name = if (isNullable) serialName.removeSuffix("?") else serialName
    return when (name) {
        POSTGRES_BOOL_SERIALIZER_BIN -> 16
        POSTGRES_CHAR_SERIALIZER_BIN -> 18
        POSTGRES_INT8_SERIALIZER_BIN -> 20
        POSTGRES_INT2_SERIALIZER_BIN -> 21
        POSTGRES_INT4_SERIALIZER_BIN -> 23
        POSTGRES_TEXT_SERIALIZER_BIN -> 25
        POSTGRES_FLOAT4_SERIALIZER_BIN -> 700
        POSTGRES_FLOAT8_SERIALIZER_BIN -> 701
        POSTGRES_TIMESTAMP_SERIALIZER_BIN -> 1114
        POSTGRES_TIMESTAMPTZ_SERIALIZER_BIN -> 1184
        POSTGRES_BYTEA_SERIALIZER_BIN -> 17
        POSTGRES_DATE_SERIALIZER_BIN -> 1082
        POSTGRES_TIME_SERIALIZER_BIN -> 1083
        POSTGRES_UUID_SERIALIZER_BIN -> 2950
        POSTGRES_POINT_SERIALIZER_BIN -> 600
        POSTGRES_LINE_SERIALIZER_BIN -> 628
        POSTGRES_LSEG_SERIALIZER_BIN -> 601
        POSTGRES_BOX_SERIALIZER_BIN -> 603
        POSTGRES_PATH_SERIALIZER_BIN -> 602
        POSTGRES_POLYGON_SERIALIZER_BIN -> 604
        POSTGRES_CIRCLE_SERIALIZER_BIN -> 718
        else -> error("Never ${this.serialName}")
    }
}

internal fun SerialDescriptor.postgresRangeTypeOid(): Int {
    val name = if (isNullable) serialName.removeSuffix("?") else serialName
    return when (name) {
        POSTGRES_RANGE_INT4_SERIALIZER_BIN -> 3904
        POSTGRES_RANGE_INT8_SERIALIZER_BIN -> 3926
        POSTGRES_RANGE_DATE_SERIALIZER_BIN -> 3912
        POSTGRES_RANGE_TIMESTAMP_SERIALIZER_BIN -> 3908
        POSTGRES_RANGE_TIMESTAMPTZ_SERIALIZER_BIN -> 3910
        else -> error("Never ${this.serialName}")
    }
}

internal fun SerialDescriptor.postgresMultiRangeTypeOid(): Int {
    val name = if (isNullable) serialName.removeSuffix("?") else serialName
    return when (name) {
        POSTGRES_MULTI_RANGE_INT4_SERIALIZER_BIN -> 4451
        POSTGRES_MULTI_RANGE_INT8_SERIALIZER_BIN -> 4536
        POSTGRES_MULTI_RANGE_TIMESTAMP_SERIALIZER_BIN -> 4533
        POSTGRES_MULTI_RANGE_DATE_SERIALIZER_BIN -> 4535
        POSTGRES_MULTI_RANGE_TIMESTAMPTZ_SERIALIZER_BIN -> 4534
        else -> error("Never ${this.serialName}")
    }
}

internal fun SerialDescriptor.postegreArrayTypeOid(): Int {
    val name = if (isNullable) serialName.removeSuffix("?") else serialName
    return when (name) {
        POSTGRES_BOOL_SERIALIZER_BIN -> 1000
        POSTGRES_CHAR_SERIALIZER_BIN -> 1002
        POSTGRES_INT8_SERIALIZER_BIN -> 1016
        POSTGRES_INT2_SERIALIZER_BIN -> 1005
        POSTGRES_INT4_SERIALIZER_BIN -> 1007
        POSTGRES_TEXT_SERIALIZER_BIN -> 1009
        POSTGRES_FLOAT4_SERIALIZER_BIN -> 1021
        POSTGRES_FLOAT8_SERIALIZER_BIN -> 1022
        POSTGRES_TIMESTAMP_SERIALIZER_BIN -> 1115
        POSTGRES_TIMESTAMPTZ_SERIALIZER_BIN -> 1185
        POSTGRES_BYTEA_SERIALIZER_BIN -> 1001
        POSTGRES_DATE_SERIALIZER_BIN -> 1182
        POSTGRES_TIME_SERIALIZER_BIN -> 1183
        POSTGRES_UUID_SERIALIZER_BIN -> 2951
        POSTGRES_POINT_SERIALIZER_BIN -> 1017
        POSTGRES_LINE_SERIALIZER_BIN -> 629
        POSTGRES_LSEG_SERIALIZER_BIN -> 1018
        POSTGRES_BOX_SERIALIZER_BIN -> 1020
        POSTGRES_PATH_SERIALIZER_BIN -> 1019
        POSTGRES_POLYGON_SERIALIZER_BIN -> 1027
        POSTGRES_CIRCLE_SERIALIZER_BIN -> 719
        POSTGRES_RANGE_INT8_SERIALIZER_BIN -> 3927
        POSTGRES_RANGE_DATE_SERIALIZER_BIN -> 3913
        POSTGRES_RANGE_TIMESTAMP_SERIALIZER_BIN -> 3909
        POSTGRES_RANGE_TIMESTAMPTZ_SERIALIZER_BIN -> 3911
        POSTGRES_MULTI_RANGE_INT4_SERIALIZER_BIN -> 6150
        POSTGRES_MULTI_RANGE_INT8_SERIALIZER_BIN -> 6157
        POSTGRES_MULTI_RANGE_TIMESTAMP_SERIALIZER_BIN -> 6152
        POSTGRES_MULTI_RANGE_DATE_SERIALIZER_BIN -> 6155
        POSTGRES_MULTI_RANGE_TIMESTAMPTZ_SERIALIZER_BIN -> 6153
        else -> error("Never ${this.serialName}")
    }
}

fun getFormat(descriptor: SerialDescriptor): Short {
    return when {
        descriptor.kind == StructureKind.LIST -> 1
        descriptor.isPostgresBuildInType() -> if (descriptor.isBinaryFormat()) 1 else 0
        descriptor.isPostgresRangeType() -> if (descriptor.isBinaryFormat()) 1 else 0
        descriptor.isPostgresMultiRangeType() -> if (descriptor.isBinaryFormat()) 1 else 0
        else -> 1
    }
}

fun getTypeOid(descriptor: SerialDescriptor): Int {
    return when {
        descriptor.kind == StructureKind.LIST -> {
            val childDescripter = descriptor.getElementDescriptor(0)
            childDescripter.postegreArrayTypeOid()
        }

        descriptor.isPostgresBuildInType() -> descriptor.postgresPrimitiveTypeOid()
        descriptor.isPostgresRangeType() -> descriptor.postgresRangeTypeOid()
        descriptor.isPostgresMultiRangeType() -> descriptor.postgresMultiRangeTypeOid()
        else -> TODO("unsupported descriper name: ${descriptor.serialName}")
    }
}

private const val PRIMITIVE_TYPE_PREFIX = "postegre.primitive.types"
private const val RANGE_TYPE_PREFIX = "postegre.range.types"
private const val MULTI_RANGE_TYPE_PREFIX = "postegre.multirange.types"
private const val BIN_FORMAT_SUFFIX = ".bin"
private const val TEXT_FORMAT_SUFFIX = ".text"

internal fun SerialDescriptor.isPostgresBuildInType() =
    this.serialName.startsWith(PRIMITIVE_TYPE_PREFIX)

internal fun SerialDescriptor.isPostgresRangeType() =
    this.serialName.startsWith(RANGE_TYPE_PREFIX)

internal fun SerialDescriptor.isPostgresMultiRangeType() =
    this.serialName.startsWith(MULTI_RANGE_TYPE_PREFIX)

internal fun SerialDescriptor.isBinaryFormat() =
    this.serialName.endsWith(BIN_FORMAT_SUFFIX)

private const val POSTGRES_INT8_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.int8${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_INT4_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.int4${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_INT2_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.int2${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_CHAR_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.char${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_BOOL_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.bool${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_TEXT_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.text${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_FLOAT4_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.float4${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_FLOAT8_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.float8${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_TIMESTAMP_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.timestamp${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_TIMESTAMPTZ_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.timestamptz${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_BYTEA_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.bytea${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_DATE_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.date${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_TIME_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.time${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_UUID_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.uuid${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_POINT_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.point${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_LINE_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.line${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_LSEG_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.lseg${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_BOX_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.box${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_PATH_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.path${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_POLYGON_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.polygon${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_CIRCLE_SERIALIZER_BIN = "${PRIMITIVE_TYPE_PREFIX}.circle${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_RANGE_INT4_SERIALIZER_BIN = "${RANGE_TYPE_PREFIX}.int4${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_RANGE_INT8_SERIALIZER_BIN = "${RANGE_TYPE_PREFIX}.int8${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_RANGE_DATE_SERIALIZER_BIN = "${RANGE_TYPE_PREFIX}.date${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_RANGE_TIMESTAMP_SERIALIZER_BIN = "${RANGE_TYPE_PREFIX}.tsrange${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_RANGE_TIMESTAMPTZ_SERIALIZER_BIN = "${RANGE_TYPE_PREFIX}.tstzrange${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_MULTI_RANGE_INT4_SERIALIZER_BIN = "${MULTI_RANGE_TYPE_PREFIX}.int4${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_MULTI_RANGE_INT8_SERIALIZER_BIN = "${MULTI_RANGE_TYPE_PREFIX}.int8${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_MULTI_RANGE_TIMESTAMP_SERIALIZER_BIN = "${MULTI_RANGE_TYPE_PREFIX}.tsmultirange${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_MULTI_RANGE_TIMESTAMPTZ_SERIALIZER_BIN = "${MULTI_RANGE_TYPE_PREFIX}.tstzmultirange${BIN_FORMAT_SUFFIX}"
private const val POSTGRES_MULTI_RANGE_DATE_SERIALIZER_BIN = "${MULTI_RANGE_TYPE_PREFIX}.datemultirange${BIN_FORMAT_SUFFIX}"
