package pbandk.internal.types.primitive

import pbandk.FieldMetadata
import pbandk.InvalidProtocolBufferException
import pbandk.binary.BinaryFieldValueDecoder
import pbandk.binary.BinaryFieldValueEncoder
import pbandk.binary.WireType
import pbandk.binary.WireValue
import pbandk.internal.PlatformUtil
import pbandk.internal.nieuw.ProtoFieldVisitor
import pbandk.json.JsonFieldValueDecoder
import pbandk.json.JsonFieldValueEncoder
import kotlin.String

internal object String : PrimitiveValueType<String>() {
    override fun visitValue(fieldMetadata: FieldMetadata, value: String, visitor: ProtoFieldVisitor) {
        visitor.visitStringValue(fieldMetadata, value)
    }

    override val defaultValue: String = ""

    override fun isDefaultValue(value: String) = value.isEmpty()

    override val binaryWireType = WireType.LENGTH_DELIMITED

    override fun binarySize(value: String) = WireValue.Len.sizeWithLenPrefix(PlatformUtil.utf8len(value))

    override fun encodeToBinary(value: String, encoder: BinaryFieldValueEncoder) {
        encoder.encodeLen(WireValue.Len.encodeString(value))
    }

    override fun decodeFromBinary(decoder: BinaryFieldValueDecoder): String {
        if (decoder !is BinaryFieldValueDecoder.Len) {
            throw InvalidProtocolBufferException("Unexpected wire type for string value: ${decoder.wireType}")
        }
        return decoder.decodeValue().decodeString
    }

    override fun encodeToJson(value: String, encoder: JsonFieldValueEncoder) {
        encoder.encodeString(value)
    }

    override fun encodeToJsonMapKey(value: String) = value

    override fun decodeFromJson(decoder: JsonFieldValueDecoder): String {
        return when (decoder) {
            is JsonFieldValueDecoder.String -> decoder.decodeAsString()
            else -> throw InvalidProtocolBufferException("Unexpected JSON type for string value: ${decoder.wireType.name}")
        }
    }

    override fun decodeFromJsonMapKey(decoder: JsonFieldValueDecoder.String) = decoder.decodeAsString()
}