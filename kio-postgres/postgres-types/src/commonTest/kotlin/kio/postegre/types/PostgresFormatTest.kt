package kio.postegre.types

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeDouble
import kotlinx.io.writeFloat
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class PostgresFormatTest {
    @Test
    fun postegreNullableSerializerTest() {
        @Serializable
        data class Foo(val value: PgBool?)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(-1); }.readByteArray(),
            Foo(null)
        )
    }

    @Test
    fun postegreBoolSerializerTest() {
        @Serializable
        data class Foo(val value: PgBool)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(1); writeByte(1); }.readByteArray(),
            Foo(true)
        )
    }

    @Test
    fun postegreCharSerializerTest() {
        @Serializable
        data class Foo(val value: PgChar)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(3); writeString('能'.toString()) }
                .readByteArray(),
            Foo('能')
        )
    }

    @Test
    fun postegreLongSerializerTest() {
        @Serializable
        data class Foo(val value: PgInt8)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(8); writeLong(65565) }.readByteArray(),
            Foo(65565L)
        )
    }

    @Test
    fun postegreShortSerializerTest() {
        @Serializable
        data class Foo(val value: PgInt2)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(2); writeShort(23) }.readByteArray(),
            Foo(23)
        )
    }

    @Test
    fun postegreIntSerializerTest() {
        @Serializable
        data class Foo(val value: PgInt4)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(4); writeInt(23) }.readByteArray(),
            Foo(23)
        )
    }

    @Test
    fun postegreTextSerializerTest() {
        @Serializable
        data class Foo(val value: PgText)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(4); writeString("2333") }.readByteArray(),
            Foo("2333")
        )
    }

    @Test
    fun postegreFloat4SerializerTest() {
        @Serializable
        data class Foo(val value: PgFloat4)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(4); writeFloat(3.14.toFloat()) }
                .readByteArray(),
            Foo(3.14.toFloat())
        )
    }

    @Test
    fun postegreFloat8SerializerTest() {
        @Serializable
        data class Foo(val value: PgFloat8)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(8); writeDouble(3.14) }.readByteArray(),
            Foo(3.14)
        )
    }

    @Test
    fun postegreTimeStampSerializerTest() {
        @Serializable
        data class Foo(val value: PgTimestamp)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(8); writeLong(1000) }.readByteArray(),
            Foo(LocalDateTime.orNull(2000, 1, 1, 0, 0, 1)!!)
        )
    }

    @Test
    fun postegreTimeStampTzSerializerTest() {
        @Serializable
        data class Foo(val value: PgTimestampTz)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(8); writeLong(1000) }.readByteArray(),
            Foo(LocalDateTime.orNull(2000, 1, 1, 0, 0, 1)!!.toInstant(TimeZone.UTC))
        )
    }

    @Test
    fun postegreByteaSerializerTest() {
        @Serializable
        data class Foo(val value: PgBytea) {
            override fun equals(other: Any?): Boolean = true
        }

        val byteArray = Buffer().apply { writeString("Hello world") }.readByteArray()
        val encodeExpect =
            Buffer().apply { writeShort(1); writeInt(byteArray.size); write(byteArray) }
                .readByteArray()
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            encodeExpect,
            Foo(byteArray)
        )
        assertByteArrayEquals(encodeExpect, PostgresFormat.encodeToByteArray(Foo(byteArray)))
    }

    @Test
    fun postegreDateSerializerTest() {
        @Serializable
        data class Foo(val value: PgDate)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(4); writeInt(1) }.readByteArray(),
            Foo(LocalDate.orNull(2000, 1, 2)!!)
        )
    }

    @Test
    fun postegreTimeSerializerTest() {
        @Serializable
        data class Foo(val value: PgTime)
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(4); writeInt(4 * 60 * 60) }.readByteArray(),
            Foo(LocalTime.fromSecondOfDay(4 * 60 * 60))
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun postegreUuidSerializerTest() {
        @Serializable
        data class Foo(val value: PgUuid)

        val source = Uuid.random()
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(16); write(source.toByteArray()) }
                .readByteArray(),
            Foo(source)
        )
    }

    @Test
    fun postegrePointSerializerTest() {
        @Serializable
        data class Foo(val value: PgPoint)

        val source = PgPoint(1.0, 2.0)
        val byteArray = byteArrayOf(
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(16); write(byteArray) }
                .readByteArray(),
            Foo(source)
        )
    }

    @Test
    fun postegreLineSerializerTest() {
        @Serializable
        data class Foo(val value: PgLine)

        val source = PgLine(1.0, -1.0, 0.0)
        val byteArray = byteArrayOf(
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xBF.toByte(), 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(24); write(byteArray) }
                .readByteArray(),
            Foo(source)
        )
    }

    @Test
    fun postegreLsegSerializerTest() {
        @Serializable
        data class Foo(val value: PgLseg)

        val source = PgLseg(0.0, 0.0, 1.0, 1.0)
        val byteArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(byteArray.size); write(byteArray) }
                .readByteArray(),
            Foo(source)
        )
    }

    @Test
    fun postegreBoxSerializerTest() {
        @Serializable
        data class Foo(val value: PgBox)

        val source = PgBox(0.0, 0.0, 1.0, 1.0)
        val byteArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(byteArray.size); write(byteArray) }
                .readByteArray(),
            Foo(source)
        )
    }

    @Test
    fun postegrePathSerializerTest() {
        @Serializable
        data class Foo(val value: PgPath)

        val source = PgPath(true, listOf(PgPoint(0.0, 0.0), PgPoint(1.0, 1.0), PgPoint(2.0, 0.0)))
        val byteArray = byteArrayOf(
            // closed = true
            0x01,

            // point count = 3
            0x00, 0x00, 0x00, 0x03,

            // point1 = (0.0, 0.0)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,

            // point2 = (1.0, 1.0)
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,

            // point3 = (2.0, 0.0)
            0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(byteArray.size); write(byteArray) }
                .readByteArray(),
            Foo(source)
        )
    }

    @Test
    fun postegrePolygonSerializerTest() {
        @Serializable
        data class Foo(val value: PgPolygon)

        val source = PgPolygon(listOf(PgPoint(0.0, 0.0), PgPoint(1.0, 1.0), PgPoint(2.0, 0.0)))
        val byteArray = byteArrayOf(

            // point count = 3
            0x00, 0x00, 0x00, 0x03,

            // point1 = (0.0, 0.0)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,

            // point2 = (1.0, 1.0)
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x3F, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,

            // point3 = (2.0, 0.0)
            0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(byteArray.size); write(byteArray) }
                .readByteArray(),
            Foo(source)
        )
    }

    @Test
    fun postegreCircleSerializerTest() {
        @Serializable
        data class Foo(val value: PgCircle)

        val source = PgCircle(PgPoint(0.0, 1.0), 2.0)
        val byteArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,

            0x3F, 0xF0.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,

            0x40, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(byteArray.size); write(byteArray) }
                .readByteArray(),
            Foo(source)
        )
    }

    @Test
    fun postegreIntArraySerializerTest() {
        @Serializable
        data class Foo(@PgArray val value: List<PgInt4>)

        val intArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, // ndim = 1
            0x00, 0x00, 0x00, 0x00, // has_null = 0
            0x00, 0x00, 0x00, 0x17, // element_oid = 23

            0x00, 0x00, 0x00, 0x03, // dim length = 3
            0x00, 0x00, 0x00, 0x01, // lower bound = 1

            0x00, 0x00, 0x00, 0x04, // element length = 4
            0x00, 0x00, 0x00, 0x01, // value = 1

            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x02,

            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x03,
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(intArray.size); write(intArray) }
                .readByteArray(),
            Foo(listOf(1, 2, 3))
        )
    }

    @Test
    fun postegreNullableIntArraySerializerTest() {
        @Serializable
        data class Foo(@PgArray val value: List<PgInt4?>)

        val intArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, // ndim = 1
            0x00, 0x00, 0x00, 0x01, // has_null = 1
            0x00, 0x00, 0x00, 0x17, // element_oid = 23 / int4

            0x00, 0x00, 0x00, 0x03, // dim length = 3
            0x00, 0x00, 0x00, 0x01, // lower bound = 1

            0x00, 0x00, 0x00, 0x04, // element length = 4
            0x00, 0x00, 0x00, 0x01, // value = 1

            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // element length = -1, NULL

            0x00, 0x00, 0x00, 0x04, // element length = 4
            0x00, 0x00, 0x00, 0x03, // value = 3
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(intArray.size); write(intArray) }
                .readByteArray(),
            Foo(listOf(1, null, 3))
        )
    }

    @Test
    fun postegre2DIntArraySerializerTest() {
        @Serializable
        data class Foo(@PgArray(dimension = 2, lengths = [2, 2]) val value: List<PgInt4?>)

        val intArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x02,   // ndim = 2
            0x00, 0x00, 0x00, 0x00,   // hasNull = 0
            0x00, 0x00, 0x00, 0x17,   // elementOid = 23 (int4)

            0x00, 0x00, 0x00, 0x02,   // dim1 length = 2
            0x00, 0x00, 0x00, 0x01,   // dim1 lowerBound = 1

            0x00, 0x00, 0x00, 0x02,   // dim2 length = 2
            0x00, 0x00, 0x00, 0x01,   // dim2 lowerBound = 1

            0x00, 0x00, 0x00, 0x04,   // element length = 4
            0x00, 0x00, 0x00, 0x01,   // 1
            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x02,   // 2
            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x03,   // 3
            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x04,   // 4
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(intArray.size); write(intArray) }
                .readByteArray(),
            Foo(listOf(1, 2, 3, 4))
        )
    }

    @Test
    fun postegre2DNullableStringArraySerializerTest() {
        @Serializable
        data class Foo(val value: List<PgText?>)

        val intArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,   // ndim = 1
            0x00, 0x00, 0x00, 0x01,   // hasNull = 1
            0x00, 0x00, 0x00, 0x19,   // elementOid = 25 = text

            0x00, 0x00, 0x00, 0x04,   // dim length = 4
            0x00, 0x00, 0x00, 0x01,   // lower bound = 1

            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // element length = -1, NULL

            0x00, 0x00, 0x00, 0x0B,   // element 2 length = 11
            0x31, 0x32, 0x33, 0x31, 0x33, 0x31, 0x32, 0x33, 0x31, 0x32, 0x33, // "12313123123"

            0x00, 0x00, 0x00, 0x01,   // element 3 length = 1
            0x31,                     // "1"

            0x00, 0x00, 0x00, 0x01,   // element 4 length = 1
            0x33,                     // "3"
        )
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            Buffer().apply { writeShort(1); writeInt(intArray.size); write(intArray) }
                .readByteArray(),
            Foo(listOf(null, "12313123123", "1", "3"))
        )
    }

    @Test
    fun postegreCompositeSerializerTest() {
        @Serializable
        data class Person(val id: PgInt4, val name: PgText)

        @Serializable
        data class Worker(val person: Person, val salary: PgInt4)

        @Serializable
        data class Foo(val value: Worker)

        val intArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x02,           // worker field count = 2

            0x00, 0x00, 0x40, 0x06,           // field1 oid = 16390 = person
            0x00, 0x00, 0x00, 0x1B,           // field1 length = 27

            0x00, 0x00, 0x00, 0x02,           // person field count = 2
            0x00, 0x00, 0x00, 0x17,           // id oid = int4
            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x7B,           // 123
            0x00, 0x00, 0x00, 0x19,           // name oid = text
            0x00, 0x00, 0x00, 0x03,
            0x54, 0x6F, 0x6D,                 // Tom

            0x00, 0x00, 0x00, 0x17,           // field2 oid = int4
            0x00, 0x00, 0x00, 0x04,

            0x00, 0x00, 0x00, 0x01,           // $1 = 1
        )
        val worker = PostgresFormat.decodeFromByteArray<Foo>(Buffer().apply {
            writeShort(1); writeInt(intArray.size); write(intArray)
        }
            .readByteArray())
        assertEquals(
            Foo(Worker(Person(123, "Tom"), 1)),
            worker
        )
    }

    @Test
    fun postegreInt4RangeSerializerTest() {
        @Serializable
        data class Foo(val value: PgInt4Range)

        val intArray = byteArrayOf(
            0x02,                   // RANGE_LB_INC

            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x01,

            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x06,
        )
        val source = Buffer().apply { writeShort(1); writeInt(intArray.size); write(intArray) }
            .readByteArray()
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            source,
            Foo(
                PgInt4Range(
                    lower = 1,
                    upper = 6,
                    lowerType = PgRange.BoundType.Inclusive,
                    upperType = PgRange.BoundType.Exclusive
                )
            )
        )
    }

    @Test
    fun postegreUnboundedInt4RangeSerializerTest() {
        @Serializable
        data class Foo(val value: PgInt4Range)

        val intArray = byteArrayOf(
            0x08,

            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x05,
        )
        val source = Buffer().apply { writeShort(1); writeInt(intArray.size); write(intArray) }
            .readByteArray()
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            source,
            Foo(
                PgInt4Range(
                    lower = null,
                    upper = 5,
                    lowerType = PgRange.BoundType.Unbounded,
                    upperType = PgRange.BoundType.Exclusive
                )
            )
        )
    }

    @Test
    fun postegreEmptyInt4RangeSerializerTest() {
        @Serializable
        data class Foo(val value: PgInt4Range)

        val intArray = byteArrayOf(
            0x01,
        )
        val source = Buffer().apply { writeShort(1); writeInt(intArray.size); write(intArray) }
            .readByteArray()
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            source,
            Foo(
                PgInt4Range(
                    lower = null,
                    upper = null,
                    lowerType = PgRange.BoundType.Empty,
                    upperType = PgRange.BoundType.Empty
                )
            )
        )
    }

    @Test
    fun postegreUnboundedInt8RangeSerializerTest() {
        @Serializable
        data class Foo(val value: PgInt8Range)

        val intArray = byteArrayOf(
            0x08,

            0x00, 0x00, 0x00, 0x08,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05,
        )
        val source = Buffer().apply { writeShort(1); writeInt(intArray.size); write(intArray) }
            .readByteArray()
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            source,
            Foo(
                PgInt8Range(
                    lower = null,
                    upper = 5,
                    lowerType = PgRange.BoundType.Unbounded,
                    upperType = PgRange.BoundType.Exclusive
                )
            )
        )
    }

    @Test
    fun postegreInt4MultiRangeSerializerTest() {
        @Serializable
        data class Foo(val value: PgInt4MultiRange)

        val intArray = byteArrayOf(
            // range count = 2
            0x00, 0x00, 0x00, 0x02,

            // range 1 length = 17
            0x00, 0x00, 0x00, 0x11,
            0x02, // lower inclusive

            // lower = 1
            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x01,

            // upper = 5
            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x05,

            // range 2 length = 17
            0x00, 0x00, 0x00, 0x11,
            0x02, // lower inclusive

            // lower = 10
            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x0A,

            // upper = 20
            0x00, 0x00, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x14,
        )
        val source = Buffer().apply { writeShort(1); writeInt(intArray.size); write(intArray) }
            .readByteArray()
        assertSerializerEncodeAndDecode(
            Foo.serializer(),
            source,
            Foo(
                PgInt4MultiRange(
                    listOf(
                        PgInt4Range(
                            lower = 1,
                            upper = 5,
                            lowerType = PgRange.BoundType.Inclusive,
                            upperType = PgRange.BoundType.Exclusive
                        ),
                        PgInt4Range(
                            lower = 10,
                            upper = 20,
                            lowerType = PgRange.BoundType.Inclusive,
                            upperType = PgRange.BoundType.Exclusive
                        )
                    )
                )
            )
        )
    }
}

private fun <T : Any> assertSerializerEncodeAndDecode(
    serializer: KSerializer<*>,
    expected: ByteArray,
    source: T
) {
    val encode = PostgresFormat.encodeToByteArray(serializer as KSerializer<T>, source)
    assertByteArrayEquals(expected, encode)
    assertEquals(source, PostgresFormat.decodeFromByteArray(serializer, encode))
}

private fun assertByteArrayEquals(expected: ByteArray, actual: ByteArray) {
    expected.forEachIndexed { index, b ->
        assertEquals(
            b,
            actual[index],
            "expect: ${expected.contentToString()}\nactual: ${actual.contentToString()}"
        )
    }
}
