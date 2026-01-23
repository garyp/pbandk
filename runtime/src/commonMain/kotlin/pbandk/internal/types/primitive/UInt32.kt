package pbandk.internal.types.primitive

import pbandk.FieldMetadata
import pbandk.InvalidProtocolBufferException
import pbandk.binary.BinaryFieldValueDecoder
import pbandk.binary.BinaryFieldValueEncoder
import pbandk.binary.WireType
import pbandk.binary.WireValue
import pbandk.internal.nieuw.ProtoFieldVisitor
import pbandk.json.JsonFieldValueDecoder
import pbandk.json.JsonFieldValueEncoder
import pbandk.types.IntValueType

internal object UInt32 : PrimitiveValueType<Int>(), IntValueType {
    override fun visitIntValue(fieldMetadata: FieldMetadata, value: Int, visitor: ProtoFieldVisitor) {
        visitor.visitUInt32Value(fieldMetadata, value.toUInt())
    }

    override fun visitValue(fieldMetadata: FieldMetadata, value: Int, visitor: ProtoFieldVisitor) {
        visitIntValue(fieldMetadata, value, visitor)
    }

    override val defaultValue: Int = 0

    override fun isDefaultValue(value: Int) = value == 0

    override val binaryWireType = WireType.VARINT

    override fun binarySize(value: Int) = WireValue.Varint.encodeUnsignedInt(value.toUInt()).size

    override fun encodeToBinary(value: Int, encoder: BinaryFieldValueEncoder) {
        encoder.encodeVarint(WireValue.Varint.encodeUnsignedInt(value.toUInt()))
    }

    override fun decodeFromBinary(decoder: BinaryFieldValueDecoder): Int {
        if (decoder !is BinaryFieldValueDecoder.Varint) {
            throw InvalidProtocolBufferException("Unexpected wire type for uint32 value: ${decoder.wireType}")
        }
        return decoder.decodeValue().decodeUnsignedInt.toInt()
    }

    override fun encodeToJson(value: Int, encoder: JsonFieldValueEncoder) {
        encoder.encodeNumberUnsignedInt(value)
    }

    override fun encodeToJsonMapKey(value: Int) = value.toUInt().toString()

    override fun decodeFromJson(decoder: JsonFieldValueDecoder): Int = when (decoder) {
        is JsonFieldValueDecoder.Number -> decoder.decodeAsUnsignedInt()
        is JsonFieldValueDecoder.String -> decoder.decodeAsUnsignedInt()
        else -> throw InvalidProtocolBufferException("Unexpected JSON type for uint32 value: ${decoder.wireType.name}")
    }.toInt()

    override fun decodeFromJsonMapKey(decoder: JsonFieldValueDecoder.String) = decoder.decodeAsUnsignedInt().toInt()
}