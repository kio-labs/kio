package kio.postegre.types

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.modules.SerializersModule

object PostgresFormat : BinaryFormat {
    override val serializersModule: SerializersModule = SerializersModule {
        contextual(PgBool::class, PostegreBoolSerializer)
    }

    override fun <T> encodeToByteArray(
        serializer: SerializationStrategy<T>,
        value: T
    ): ByteArray {
        val output = Buffer().apply { writeShort(serializer.descriptor.elementsCount.toShort()) }
        val encoder = PostgresEncoder(this, output)
        encoder.encodeSerializableValue(serializer, value)
        val ret =  output.readByteArray()
        return ret
    }

    override fun <T> decodeFromByteArray(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray
    ): T {
        check(deserializer.descriptor.kind == StructureKind.CLASS) {
            "only support class decode"
        }
        val input = Buffer().apply { write(bytes) }
        val elementCount = input.readShort().toInt()
        val reader = PostegreDecoder(this, input)
        return reader.decodeSerializableValue(deserializer)
    }
}
