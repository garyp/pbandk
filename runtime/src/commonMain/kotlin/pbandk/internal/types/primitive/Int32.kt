package pbandk.internal.types.primitive

import pbandk.FieldMetadata
import pbandk.InvalidProtocolBufferException
import pbandk.binary.BinaryFieldValueDecoder
import pbandk.binary.BinaryFieldValueEncoder
import pbandk.binary.WireType
import pbandk.binary.WireValue
import pbandk.internal.ProtoDecoder
import pbandk.internal.ProtoVisitor
import pbandk.json.JsonFieldValueDecoder
import pbandk.json.JsonFieldValueEncoder
import pbandk.types.IntValueType

internal object Int32 : PrimitiveValueType<Int>(), IntValueType {
    override fun visitIntValue(fieldMetadata: FieldMetadata, value: Int, visitor: ProtoVisitor) =
        visitor.visitInt32Value(fieldMetadata, value)

    override fun visitValue(fieldMetadata: FieldMetadata, value: Int, visitor: ProtoVisitor) =
        visitIntValue(fieldMetadata, value, visitor)

    override fun decodeIntValue(fieldMetadata: FieldMetadata, decoder: ProtoDecoder) =
        decoder.decodeInt32Value(fieldMetadata)

    override fun decodeValue(fieldMetadata: FieldMetadata, decoder: ProtoDecoder) =
        decodeIntValue(fieldMetadata, decoder)

    override val defaultValue: Int = 0

    override fun isDefaultValue(value: Int) = value == 0

    override val binaryWireType = WireType.VARINT

    override fun binarySize(value: Int) = WireValue.Varint.encodeSignedInt(value).size

    override fun encodeToBinary(value: Int, encoder: BinaryFieldValueEncoder) {
        encoder.encodeVarint(WireValue.Varint.encodeSignedInt(value))
    }

    override fun decodeFromBinary(decoder: BinaryFieldValueDecoder): Int {
        if (decoder !is BinaryFieldValueDecoder.Varint) {
            throw InvalidProtocolBufferException("Unexpected wire type for int32 value: ${decoder.wireType}")
        }
        return decoder.decodeValue().decodeSignedInt
    }

    override fun encodeToJson(value: Int, encoder: JsonFieldValueEncoder) {
        encoder.encodeNumberSignedInt(value)
    }

    override fun encodeToJsonMapKey(value: Int) = value.toString()

    override fun decodeFromJson(decoder: JsonFieldValueDecoder): Int = when (decoder) {
        is JsonFieldValueDecoder.Number -> decoder.decodeAsSignedInt()
        is JsonFieldValueDecoder.String -> decoder.decodeAsSignedInt()
        else -> throw InvalidProtocolBufferException("Unexpected JSON type for int32 value: ${decoder.wireType.name}")
    }

    override fun decodeFromJsonMapKey(decoder: JsonFieldValueDecoder.String) = decoder.decodeAsSignedInt()
}