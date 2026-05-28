package kio.postegre.types

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readCodePointValue
import kotlinx.io.readDouble
import kotlinx.io.readFloat
import kotlinx.io.readString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
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
internal class PostegreDecoder(
    val postegre: PostgresFormat,
    private val input: Buffer
) : AbstractDecoder(), Decoder, CompositeDecoder {
    override val serializersModule: SerializersModule = postegre.serializersModule

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return InternalPostegreDecoder(postegre, input)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class InternalPostegreDecoder(
    private val postegre: PostgresFormat,
    private val input: Buffer,
) : AbstractDecoder(), Decoder, CompositeDecoder {
    override val serializersModule: SerializersModule = postegre.serializersModule

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val buffer = Buffer()
        val isNull = input.readPgElementTo(buffer)
        val decoder = when {
            descriptor.isPostegreBuildInType() -> {
                PostegrePrimitiveDecoder(postegre, buffer, isNull)
            }

            descriptor.isPostegreRangeType() -> {
                PostegreRangeDecoder(postegre, buffer)
            }

            descriptor.isPostegreMultiRangeType() -> {
                PostegreMultiRangeDecoder(postegre, buffer)
            }

            descriptor.kind == StructureKind.LIST -> {
                PostegreArrayDecoder(postegre, buffer)
            }

            descriptor.kind == StructureKind.CLASS -> {
                PostegreCompositeDecoder(postegre, descriptor, buffer)
            }

            else -> error("Not support ${descriptor.serialName}")
        }
        return decoder
    }

    override fun decodeNotNullMark(): Boolean {
        val size = input.readInt()
        val isNotNull = size != -1
        if (isNotNull) {
            input.writeInt(size)
        }
        return isNotNull
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal class PostegreCompositeDecoder(
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
            elementDescriptor.isPostegreBuildInType() -> {
                val buffer = Buffer()
                val isNull = source.readPgElementTo(buffer)
                PostegrePrimitiveDecoder(postegre, buffer, isNull)
            }

            elementDescriptor.kind == StructureKind.CLASS -> {
                val buffer = Buffer()
                source.readPgElementTo(buffer)
                PostegreCompositeDecoder(postegre, elementDescriptor, buffer)
            }

            else -> this
        }

        return decoder.decodeSerializableValue(deserializer, previousValue)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal class PostegreMultiRangeDecoder(
    val postegre: PostgresFormat,
    val source: Buffer,
) : AbstractDecoder(), Decoder, CompositeDecoder {
    override val serializersModule: SerializersModule = postegre.serializersModule

    override fun decodeSequentially(): Boolean = true

    override fun decodeInt(): Int {
        return source.readInt()
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val buffer = Buffer()
        val isNull = source.readPgElementTo(buffer)
        val decoder = when {
            descriptor.isPostegreRangeType() -> {
                PostegreRangeDecoder(postegre, buffer)
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
internal class PostegreRangeDecoder(
    val postegre: PostgresFormat,
    val source: Buffer,
) : AbstractDecoder(), Decoder, CompositeDecoder {
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
        val buffer = Buffer()
        val isNull = source.readPgElementTo(buffer)

        val decoder = when {
            descriptor.isPostegreBuildInType() -> {
                PostegrePrimitiveDecoder(postegre, buffer, isNull)
            }

            else -> error("not support ${descriptor.serialName} in range decoder")
        }

        return decoder
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal class PostegreArrayDecoder(
    val postegre: PostgresFormat,
    val source: Buffer,
) : AbstractDecoder(), Decoder, CompositeDecoder {
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

    override val serializersModule: SerializersModule = postegre.serializersModule

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        TODO("Not yet implemented")
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
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

        check(elementOid == elementDescriptor.postegrePrimitiveTypeOid()) {
            "required oid is $elementOid. ${elementDescriptor.serialName}"
        }

        val decoder = when {
            elementDescriptor.isPostegreBuildInType() -> {
                val buffer = Buffer()
                val isNull = source.readPgElementTo(buffer)
                PostegrePrimitiveDecoder(postegre, buffer, isNull)
            }

            else -> this
        }

        return decoder.decodeSerializableValue(deserializer, previousValue)
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
private class PostegrePrimitiveDecoder(
    postegre: PostgresFormat,
    private val buffer: Buffer,
    private val isNull: Boolean,
) : AbstractDecoder(), PgDecoder, PgCompositeDecoder {
    override val serializersModule: SerializersModule = postegre.serializersModule

    override fun endStructure(descriptor: SerialDescriptor) {
        check(buffer.exhausted())
    }

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
        return buffer.readString()
    }

    override fun decodeFloat(): Float {
        return buffer.readFloat()
    }

    override fun decodeDouble(): Double {
        return buffer.readDouble()
    }

    override fun decodeNotNullMark(): Boolean {
        return !isNull
    }
}