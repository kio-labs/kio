package kio.postgres.types

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.modules.SerializersModule

object PostgresFormat : BinaryFormat {
    override val serializersModule: SerializersModule = SerializersModule {
        contextual(PgBool::class, PostgresBoolSerializer)
    }

    override fun <T> encodeToByteArray(
        serializer: SerializationStrategy<T>,
        value: T
    ): ByteArray {
        val output = Buffer()
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
        val reader = PostgresDecoder(this, input)
        return reader.decodeSerializableValue(deserializer)
    }
}
