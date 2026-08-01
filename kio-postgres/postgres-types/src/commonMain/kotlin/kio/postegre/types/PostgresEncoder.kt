package kio.postegre.types

import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.writeDouble
import kotlinx.io.writeFloat
import kotlinx.io.writeString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

@OptIn(ExperimentalSerializationApi::class)
internal class PostgresEncoder(
    private val postgres: PostgresFormat,
    private val sink: Sink,
) : AbstractEncoder(), Encoder, CompositeEncoder {
    override val serializersModule: SerializersModule = postgres.serializersModule

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        return InternalPostgresEncoder(postgres, sink)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class InternalPostgresEncoder(
    private val postgres: PostgresFormat,
    private val sink: Sink,
) : AbstractEncoder(), Encoder, CompositeEncoder {
    override val serializersModule: SerializersModule = postgres.serializersModule

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        val elementDescriptor = descriptor.getElementDescriptor(index)
        val encoder = when {
            elementDescriptor.isPostegreBuildInType() -> {
                PostgresPrimitiveEncoder(postgres, sink)
            }

            elementDescriptor.isPostegreMultiRangeType() -> {
                PostgresMultiRangeEncoder(sink, postgres)
            }

            elementDescriptor.kind == StructureKind.LIST -> {
                val pgArray = descriptor.getElementAnnotations(index).filterIsInstance<PgArray>()
                    .singleOrNull()
                PostegreArrayEncoder(sink, pgArray, postgres)
            }

            elementDescriptor.isPostegreRangeType() -> {
                PostgresRangeEncoder(sink, postgres)
            }

            else -> this
        }
        encoder.encodeSerializableValue(serializer, value)
    }

    @ExperimentalSerializationApi
    override fun encodeNull() {
        sink.writeInt(-1)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class PostgresMultiRangeEncoder(
    private val sink: Sink,
    private val postgres: PostgresFormat,
) : AbstractEncoder(), Encoder, CompositeEncoder {
    override val serializersModule: SerializersModule = postgres.serializersModule

    val tempBuffer = Buffer()

    override fun encodeInt(value: Int) {
        tempBuffer.writeInt(value)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        val elementDescriptor = serializer.descriptor
        val encoder = when {
            elementDescriptor.isPostegreRangeType() -> {
                PostgresRangeEncoder(tempBuffer, postgres)
            }

            else -> error("not support encode ${elementDescriptor.serialName} in multi-range encoder.")
        }
        encoder.encodeSerializableValue(serializer, value)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        sink.writeInt(tempBuffer.size.toInt())
        sink.write(tempBuffer, tempBuffer.size)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class PostgresRangeEncoder(
    private val sink: Sink,
    private val postgres: PostgresFormat,
) : AbstractEncoder(), Encoder, CompositeEncoder {
    override val serializersModule: SerializersModule = postgres.serializersModule

    val tempBuffer = Buffer()

    override fun encodeByte(value: Byte) {
        tempBuffer.writeByte(value)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        val elementDescriptor = descriptor.getElementDescriptor(index)
        val encoder = when {
            elementDescriptor.isPostegreBuildInType() -> {
                PostgresPrimitiveEncoder(postgres, tempBuffer)
            }
            else -> error("not support type for ${elementDescriptor.serialName} in range encoder")
        }
        encoder.encodeSerializableValue(serializer, value)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        sink.writeInt(tempBuffer.size.toInt())
        sink.write(tempBuffer, tempBuffer.size)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class PostegreArrayEncoder(
    private val sink: Sink,
    private val pgArray: PgArray? = null,
    private val postegre: PostgresFormat,
) : AbstractEncoder(), Encoder, CompositeEncoder {
    override val serializersModule: SerializersModule = postegre.serializersModule

    var hasNull = false
    var collectionSize: Int? = null
    val tempBuffer = Buffer()
    var elementOid : Int? = null

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        val elementDescriptor = descriptor.getElementDescriptor(index)
        when {
            elementDescriptor.isPostegreBuildInType() -> {
                if (elementOid == null) elementOid = elementDescriptor.postegrePrimitiveTypeOid()

                val encoder = PostgresPrimitiveEncoder(postegre, tempBuffer)
                encoder.encodeSerializableValue(serializer, value)
                if (encoder.isMarkedNull) hasNull = true
            }

            else -> encodeSerializableValue(serializer, value)
        }
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        this.collectionSize = collectionSize
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val ndim = pgArray?.dimension ?: 1

        // write size header
        val headerSize = (3 + 2 * ndim) * 4
        val elementSize = tempBuffer.size
        sink.writeInt(elementSize.toInt() + headerSize)

        // write array header
        sink.writeInt(ndim)
        sink.writeInt(if (hasNull) 1 else 0)
        sink.writeInt(elementOid!!)

        if (ndim == 1) {
            val dimLength = pgArray?.lengths?.getOrNull(0)
                ?: collectionSize!!.div(ndim)
            sink.writeInt(dimLength)
            val lowerBound = pgArray?.lowerBounds?.getOrNull(0) ?: 1
            sink.writeInt(lowerBound)
        } else {
            repeat(ndim) { i ->
                val dimLength = pgArray?.lengths?.getOrNull(i)
                    ?: error("dimension length is required when setting array for $ndim dimension.")
                sink.writeInt(dimLength)
                val lowerBound = pgArray.lowerBounds.getOrNull(i) ?: 1
                sink.writeInt(lowerBound)
            }
        }

        // write array elements
        sink.write(tempBuffer, elementSize)
    }
}

internal interface PgEncoder : Encoder {
    fun encodeByteArray(value: ByteArray)
}

internal interface PgCompositeEncoder : CompositeEncoder {
    fun encodeByteArrayElement(descriptor: SerialDescriptor, index: Int, value: ByteArray)
}

@OptIn(ExperimentalSerializationApi::class)
private class PostgresPrimitiveEncoder(
    postgres: PostgresFormat,
    private val sink: Sink,
) : AbstractEncoder(), PgEncoder, PgCompositeEncoder {
    override val serializersModule: SerializersModule = postgres.serializersModule

    private val tempBuffer = Buffer()
    var isMarkedNull = false

    override fun encodeByteArrayElement(descriptor: SerialDescriptor, index: Int, value: ByteArray) {
        if (encodeElement(descriptor, index)) encodeByteArray(value)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        if (isMarkedNull) {
            // do nothing, because null is already encoded.
        } else {
            sink.writeInt(tempBuffer.size.toInt())
            sink.write(tempBuffer, tempBuffer.size)
        }
    }

    override fun encodeByteArray(value: ByteArray) {
        tempBuffer.write(value)
    }

    override fun encodeBoolean(value: Boolean) {
        val code = if (value) 1 else 0
        tempBuffer.writeByte(code.toByte())
    }

    override fun encodeChar(value: Char) {
        tempBuffer.writeString(value.toString())
    }

    override fun encodeLong(value: Long) {
        tempBuffer.writeLong(value)
    }

    override fun encodeInt(value: Int) {
        tempBuffer.writeInt(value)
    }

    override fun encodeByte(value: Byte) {
        tempBuffer.writeByte(value)
    }

    override fun encodeShort(value: Short) {
        tempBuffer.writeShort(value)
    }

    override fun encodeString(value: String) {
        tempBuffer.writeString(value)
    }

    override fun encodeFloat(value: Float) {
        tempBuffer.writeFloat(value)
    }

    override fun encodeDouble(value: Double) {
        tempBuffer.writeDouble(value)
    }

    @ExperimentalSerializationApi
    override fun encodeNull() {
        isMarkedNull = true
        sink.writeInt(-1)
    }
}
