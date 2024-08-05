package pbandk.internal.types.wkt

import pbandk.FieldDescriptor
import pbandk.InvalidProtocolBufferException
import pbandk.MessageDescriptor
import pbandk.binary.BinaryFieldValueDecoder
import pbandk.binary.BinaryFieldValueEncoder
import pbandk.binary.WireType
import pbandk.binary.tryDecodeField
import pbandk.binary.WireValue
import pbandk.internal.binary.BinaryFieldDecoder
import pbandk.internal.binary.BinaryFieldEncoder
import pbandk.internal.json.JsonFieldDecoder
import pbandk.internal.json.JsonFieldEncoder
import pbandk.internal.types.MessageValueType
import pbandk.internal.types.primitive.PrimitiveValueType
import pbandk.json.JsonFieldValueDecoder
import pbandk.json.JsonFieldValueEncoder

internal abstract class WktWrapperValueType<
        KotlinType : kotlin.Any,
        ProtobufMessageType : kotlin.Any,
        MutableProtobufMessageType : kotlin.Any>(
    private val wrapperFieldDescriptor: FieldDescriptor<ProtobufMessageType, MutableProtobufMessageType, KotlinType>,
    private val wrappedValueType: PrimitiveValueType<KotlinType>,
) : MessageValueType<KotlinType, ProtobufMessageType>() {
    override val descriptor: MessageDescriptor<ProtobufMessageType, MutableProtobufMessageType> =
        wrapperFieldDescriptor.messageDescriptor

    override val defaultValue: KotlinType = wrappedValueType.defaultValue

    override fun isDefaultValue(value: KotlinType) = false

    override fun mergeValues(currentValue: KotlinType, newValue: KotlinType) = newValue

    override val binaryWireType = WireType.LENGTH_DELIMITED

    override fun binarySize(value: KotlinType) = WireValue.Len.sizeWithLenPrefix(
        wrapperFieldDescriptor.fieldType.binarySize(wrapperFieldDescriptor.metadata, value)
    )

    override fun encodeFieldsToBinary(value: KotlinType, fieldEncoder: BinaryFieldEncoder) {
        wrapperFieldDescriptor.fieldType.encodeToBinary(wrapperFieldDescriptor.metadata, value, fieldEncoder)
    }

    override fun encodeToBinary(value: KotlinType, encoder: BinaryFieldValueEncoder) {
        encoder.encodeLenFields(
            wrapperFieldDescriptor.fieldType.binarySize(wrapperFieldDescriptor.metadata, value)
        ) { fieldEncoder ->
            encodeFieldsToBinary(value, fieldEncoder)
        }
    }

    override fun decodeFieldsFromBinary(fieldDecoder: BinaryFieldDecoder): KotlinType {
        var value: KotlinType = wrappedValueType.defaultValue
        fieldDecoder.forEachField { fieldNumber, valueDecoder ->
            when {
                valueDecoder.tryDecodeField(wrapperFieldDescriptor, fieldNumber) { value = it } -> {}
                else -> valueDecoder.skipValue()
            }
        }
        return value
    }

    override fun decodeFromBinary(decoder: BinaryFieldValueDecoder): KotlinType {
        if (decoder !is BinaryFieldValueDecoder.Len) {
            throw InvalidProtocolBufferException("Unexpected wire type for message value: ${decoder.wireType}")
        }
        return decoder.decodeFields { fieldDecoder ->
            decodeFieldsFromBinary(fieldDecoder)
        }
    }

    override fun encodeFieldsToJson(value: KotlinType, fieldEncoder: JsonFieldEncoder) {
        TODO("Not yet implemented")
    }

    override fun encodeToJson(value: KotlinType, encoder: JsonFieldValueEncoder) {
        wrappedValueType.encodeToJson(value, encoder)
    }

    override fun encodeMessageToJson(message: ProtobufMessageType, encoder: JsonFieldValueEncoder) {
        encodeToJson(wrapperFieldDescriptor.getValue(message), encoder)
    }

    override fun encodeToJsonMapKey(value: KotlinType) = wrappedValueType.encodeToJsonMapKey(value)

    override fun decodeFieldsFromJson(fieldDecoder: JsonFieldDecoder): KotlinType {
        TODO("Not yet implemented")
    }

    override fun decodeFromJson(decoder: JsonFieldValueDecoder): KotlinType {
        return wrappedValueType.decodeFromJson(decoder)
    }

    override fun decodeMessageFromJson(decoder: JsonFieldValueDecoder): ProtobufMessageType {
        return wrapperFieldDescriptor.messageDescriptor.builder {
            wrapperFieldDescriptor.setValue(this, decodeFromJson(decoder))
        }
    }

    override fun decodeFromJsonMapKey(decoder: JsonFieldValueDecoder.String) =
        wrappedValueType.decodeFromJsonMapKey(decoder)
}