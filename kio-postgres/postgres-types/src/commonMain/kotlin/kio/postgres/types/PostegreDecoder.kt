package kio.postgres.types

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readCodePointValue
import kotlinx.io.readDouble
import kotlinx.io.readFloat
import kotlinx.io.readString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule

internal interface PgDecoder : Decoder {
    fun decodeByteArray(): ByteArray
}

internal interface PgCompositeDecoder : CompositeDecoder {
    fun decodeByteArrayElement(descriptor: SerialDescriptor, index: Int): ByteArray
}

@OptIn(ExperimentalSerializationApi::class)
internal class PostgresDecoder(
    val postgres: PostgresFormat,
    private val input: Buffer
) : AbstractDecoder(), Decoder, CompositeDecoder {
    override val serializersModule: SerializersModule = postgres.serializersModule

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return when {
            descriptor.isPostgresBuildInType() -> {
                PostgresPrimitiveDecoder(postgres, input)
            }

            descriptor.isPostgresArrayType() -> {
                PostgresArrayDecoder(postgres, input)
            }

            else -> {
                InternalCompositePostgresDecoder(postgres, input)
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class InternalCompositePostgresDecoder(
    private val postegre: PostgresFormat,
    private val input: Buffer,
) : AbstractDecoder(), Decoder, CompositeDecoder {
    private val elementCount = input.readShort().toInt()

    override val serializersModule: SerializersModule = postegre.serializersModule

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val decoder = when {
            descriptor.isPostgresBuildInType() -> {
                PostgresPrimitiveDecoder(postegre, input)
            }

            descriptor.isPostgresRangeType() -> {
                PostgresRangeDecoder(postegre, input)
            }

            descriptor.isPostgresMultiRangeType() -> {
                PostgresMultiRangeDecoder(postegre, input)
            }

            descriptor.isPostgresArrayType() -> {
                PostgresArrayDecoder(postegre, input)
            }

            descriptor.kind == StructureKind.CLASS -> {
                val buffer = Buffer()
                val isNull = input.readPgElementTo(buffer)
                PostgresCompositeDecoder(postegre, descriptor, buffer)
            }

            else -> error("Not support ${descriptor.serialName}")
        }
        return decoder
    }

    override fun decodeNotNullMark(): Boolean {
        input.require(4)
        val size = input.peek().readInt()
        if (size == -1) {
            input.skip(4)
            return false
        }

        return true
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal class PostgresCompositeDecoder(
    val postegre: PostgresFormat,
    descriptor: SerialDescriptor,
    val source: Buffer,
) : AbstractDecoder(), Decoder, CompositeDecoder {
    override val serializersModule: SerializersModule = postegre.serializersModule

    init {
        check(source.readInt() == descriptor.elementsCount)
    }

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        val elementDescriptor = descriptor.getElementDescriptor(index)
        val oid = source.readInt()
        val decoder = when {
            elementDescriptor.isPostgresBuildInType() -> {
                PostgresPrimitiveDecoder(postegre, source)
            }

            elementDescriptor.kind == StructureKind.CLASS -> {
                val buffer = Buffer()
                source.readPgElementTo(buffer)
                PostgresCompositeDecoder(postegre, elementDescriptor, buffer)
            }

            else -> this
        }

        return decoder.decodeSerializableValue(deserializer, previousValue)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal class PostgresMultiRangeDecoder(
    val postegre: PostgresFormat,
    val source: Buffer,
) : AbstractDecoder(), Decoder, CompositeDecoder {
    private val size = source.readInt()

    override val serializersModule: SerializersModule = postegre.serializersModule

    override fun decodeSequentially(): Boolean = true

    override fun decodeInt(): Int {
        return source.readInt()
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val decoder = when {
            descriptor.isPostgresRangeType() -> {
                PostgresRangeDecoder(postegre, source)
            }

            else -> error("not support decode ${descriptor.serialName} in multi-range decoder")
        }
        return decoder
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal class PostgresRangeDecoder(
    val postegre: PostgresFormat,
    val source: Buffer,
) : AbstractDecoder(), Decoder, CompositeDecoder {
    private val size = source.readInt()

    override val serializersModule: SerializersModule = postegre.serializersModule

    override fun decodeByte(): Byte {
        return source.readByte()
    }

    override fun decodeNotNullMark(): Boolean {
        return !source.exhausted()
    }

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val decoder = when {
            descriptor.isPostgresBuildInType() -> {
                PostgresPrimitiveDecoder(postegre, source)
            }

            else -> error("not support ${descriptor.serialName} in range decoder")
        }

        return decoder
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal class PostgresArrayDecoder(
    val postgres: PostgresFormat,
    val source: Buffer,
) : AbstractDecoder(), Decoder, CompositeDecoder {
    private val size = source.readInt()

    private val remainingBeforeArray = source.size
    private val ndm = source.readInt()
    private val hasNull = source.readInt() != 0
    private val elementOid = source.readInt()

    private val dimensionLengthArray = IntArray(ndm)
    private val lowerBoundArray = IntArray(ndm)

    init {
        repeat(ndm) { i ->
            dimensionLengthArray[i] = source.readInt()
            lowerBoundArray[i] = source.readInt()
        }
    }

    override val serializersModule: SerializersModule = postgres.serializersModule

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        if (ndm == 0) return 0
        return dimensionLengthArray.fold(1) { acc, i -> acc * i }
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        val elementDescriptor = descriptor.getElementDescriptor(index)
        if (hasNull) {
            check(elementDescriptor.isNullable) {
                "source data has null element but $elementDescriptor is not nullable."
            }
        }

        check(elementOid == elementDescriptor.postgresPrimitiveTypeOid()) {
            "required oid is $elementOid. ${elementDescriptor.serialName}"
        }

        val decoder = when {
            elementDescriptor.isPostgresBuildInType() -> {
                PostgresPrimitiveDecoder(postgres, source)
            }

            else -> this
        }

        return decoder.decodeSerializableValue(deserializer, previousValue)
    }

    fun <T> decodePgArray(
        deserializer: KSerializer<T>,
    ): PgArray<T> {
        val values = decodeSerializableValue(
            ListSerializer(deserializer),
        )

        val consumed = remainingBeforeArray - source.size
        check(consumed == size.toLong()) {
            "Array size mismatch: expected $size bytes, consumed $consumed"
        }

        return PgArray(
            values = values,
            lengths = dimensionLengthArray.toList(),
            lowerBounds = lowerBoundArray.toList(),
        )
    }
}

// Return if this element is null.
private fun Source.readPgElementTo(buffer: Buffer): Boolean {
    val columValueLength = readInt()
    if (columValueLength < 0) {
        // null detected, write to buffer.
        buffer.writeInt(-1)
        return true
    } else {
        readTo(buffer, columValueLength.toLong())
        return false
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class PostgresPrimitiveDecoder(
    postegre: PostgresFormat,
    private val buffer: Buffer,
) : AbstractDecoder(), PgDecoder, PgCompositeDecoder {
    override val serializersModule: SerializersModule = postegre.serializersModule

    private val size = buffer.readInt()

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }

    override fun decodeByteArrayElement(descriptor: SerialDescriptor, index: Int): ByteArray =
        decodeByteArray()

    override fun decodeByteArray(): ByteArray {
        return buffer.readByteArray()
    }

    override fun decodeChar(): Char {
        return buffer.readCodePointValue().toChar()
    }

    override fun decodeBoolean(): Boolean {
        return buffer.readByte() == 1.toByte()
    }

    override fun decodeLong(): Long {
        return buffer.readLong()
    }

    override fun decodeByte(): Byte {
        return buffer.readByte()
    }

    override fun decodeInt(): Int {
        return buffer.readInt()
    }

    override fun decodeShort(): Short {
        return buffer.readShort()
    }

    override fun decodeString(): String {
        return buffer.readString(size.toLong())
    }

    override fun decodeFloat(): Float {
        return buffer.readFloat()
    }

    override fun decodeDouble(): Double {
        return buffer.readDouble()
    }

    override fun decodeNotNullMark(): Boolean {
        return size != -1
    }
}