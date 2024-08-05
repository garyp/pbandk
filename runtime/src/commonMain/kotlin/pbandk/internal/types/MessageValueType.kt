package pbandk.internal.types

import pbandk.FieldDescriptor
import pbandk.FieldDescriptorSet
import pbandk.InvalidProtocolBufferException
import pbandk.json.JsonFieldValueEncoder
import pbandk.MessageDescriptor
import pbandk.MessageEncoding
import pbandk.PublicForGeneratedCode
import pbandk.UnknownField
import pbandk.binary.BinaryFieldValueDecoder
import pbandk.binary.BinaryFieldValueEncoder
import pbandk.binary.WireType
import pbandk.binary.WireValue
import pbandk.internal.binary.BinaryFieldDecoder
import pbandk.internal.binary.BinaryFieldEncoder
import pbandk.internal.json.JsonFieldDecoder
import pbandk.internal.json.JsonFieldEncoder
import pbandk.internal.types.wkt.BoolValue
import pbandk.internal.types.wkt.BytesValue
import pbandk.internal.types.wkt.DoubleValue
import pbandk.internal.types.wkt.Duration
import pbandk.internal.types.wkt.FloatValue
import pbandk.internal.types.wkt.Int32Value
import pbandk.internal.types.wkt.Int64Value
import pbandk.internal.types.wkt.ListValue
import pbandk.internal.types.wkt.StringValue
import pbandk.internal.types.wkt.Struct
import pbandk.internal.types.wkt.Timestamp
import pbandk.internal.types.wkt.UInt32Value
import pbandk.internal.types.wkt.UInt64Value
import pbandk.internal.types.wkt.Value
import pbandk.json.JsonFieldValueDecoder
import pbandk.types.ValueType
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

public abstract class MessageValueType<KotlinType : Any, ProtobufMessageType : Any> : ValueType<KotlinType> {
    public abstract val descriptor: MessageDescriptor<ProtobufMessageType, *>

    /** Used primarily when this message is stored inside a `google.protobuf.Any`. */
    internal abstract fun encodeMessageToJson(message: ProtobufMessageType, encoder: JsonFieldValueEncoder)

    /** Used primarily when this message is stored inside a `google.protobuf.Any`. */
    internal abstract fun decodeMessageFromJson(decoder: JsonFieldValueDecoder): ProtobufMessageType

    internal abstract fun encodeFieldsToBinary(value: KotlinType, fieldEncoder: BinaryFieldEncoder)

    internal abstract fun decodeFieldsFromBinary(fieldDecoder: BinaryFieldDecoder): KotlinType

    internal abstract fun encodeFieldsToJson(value: KotlinType, fieldEncoder: JsonFieldEncoder)

    internal abstract fun decodeFieldsFromJson(fieldDecoder: JsonFieldDecoder): KotlinType
}

public abstract class TranslatingMessageValueType<T : Any, M : Any>(
    private val delegate: MessageValueType<M, M>,
) : MessageValueType<T, M>() {
    protected abstract fun toProtobufType(t: T): M
    protected abstract fun fromProtobufType(m: M): T

    override val descriptor: MessageDescriptor<M, *> by delegate::descriptor

    override val binaryWireType: WireType by delegate::binaryWireType

    override val defaultValue: T by lazy(LazyThreadSafetyMode.PUBLICATION) { fromProtobufType(delegate.defaultValue) }

    override fun isDefaultValue(value: T): Boolean = delegate.isDefaultValue(toProtobufType(value))

    override fun mergeValues(currentValue: T, newValue: T): T =
        fromProtobufType(delegate.mergeValues(toProtobufType(currentValue), toProtobufType(newValue)))

    override fun binarySize(value: T): Int = delegate.binarySize(toProtobufType(value))

    override fun encodeFieldsToBinary(value: T, fieldEncoder: BinaryFieldEncoder) =
        delegate.encodeFieldsToBinary(toProtobufType(value), fieldEncoder)

    override fun encodeToBinary(value: T, encoder: BinaryFieldValueEncoder): Unit =
        delegate.encodeToBinary(toProtobufType(value), encoder)

    override fun decodeFieldsFromBinary(fieldDecoder: BinaryFieldDecoder): T =
        fromProtobufType(delegate.decodeFieldsFromBinary(fieldDecoder))

    override fun decodeFromBinary(decoder: BinaryFieldValueDecoder): T =
        fromProtobufType(delegate.decodeFromBinary(decoder))

    override fun encodeFieldsToJson(value: T, fieldEncoder: JsonFieldEncoder) =
        delegate.encodeFieldsToJson(toProtobufType(value), fieldEncoder)

    override fun encodeToJson(value: T, encoder: JsonFieldValueEncoder): Unit =
        delegate.encodeToJson(toProtobufType(value), encoder)

    override fun encodeMessageToJson(message: M, encoder: JsonFieldValueEncoder) =
        delegate.encodeMessageToJson(message, encoder)

    override fun decodeFieldsFromJson(fieldDecoder: JsonFieldDecoder): T =
        fromProtobufType(delegate.decodeFieldsFromJson(fieldDecoder))

    override fun decodeFromJson(decoder: JsonFieldValueDecoder): T = fromProtobufType(delegate.decodeFromJson(decoder))

    override fun decodeMessageFromJson(decoder: JsonFieldValueDecoder): M = delegate.decodeMessageFromJson(decoder)

    override fun decodeFromJsonMapKey(decoder: JsonFieldValueDecoder.String): T =
        fromProtobufType(delegate.decodeFromJsonMapKey(decoder))

    override fun encodeToJsonMapKey(value: T): String = delegate.encodeToJsonMapKey(toProtobufType(value))
}

private object DurationValueType :
    TranslatingMessageValueType<kotlin.time.Duration, pbandk.wkt.Duration>(pbandk.wkt.Duration.valueType) {
    override fun toProtobufType(t: kotlin.time.Duration): pbandk.wkt.Duration = t.toComponents { seconds, nanoseconds ->
        pbandk.wkt.Duration {
            this.seconds = seconds
            this.nanos = nanoseconds
        }
    }

    override fun fromProtobufType(m: pbandk.wkt.Duration): kotlin.time.Duration =
        m.seconds.seconds + m.nanos.nanoseconds
}

@PublicForGeneratedCode
public open class PbandkMessageValueType<M : Any, MM : Any>(
    override val descriptor: MessageDescriptor<M, MM>,
    internal val encoding: MessageEncoding = MessageEncoding.LENGTH_PREFIXED,
) : MessageValueType<M, M>() {
    override val defaultValue: M get() = descriptor.defaultInstance

    override fun isDefaultValue(value: M): Boolean = false

    internal fun copy(message: M, builderAction: MM.() -> Unit): M {
        val newMessage = descriptor.builder {
            descriptor.fields.forEach { fieldDescriptor ->
                fieldDescriptor.copyValue(message, this)
            }
            descriptor.unknownFields(this) += descriptor.unknownFields(message)
            builderAction()
        }
        return newMessage
    }

    override fun mergeValues(currentValue: M, newValue: M): M = copy(currentValue) {
        descriptor.fields.forEach { field ->
            if (field.metadata.isOneofMember) return@forEach
            field.mergeValues(currentValue, newValue, this)
        }

        for (oneof in descriptor.oneofs) {
            oneof.mergeValues(currentValue, newValue, this)
        }

        val unknownFields = descriptor.unknownFields(this)

        descriptor.unknownFields(newValue).forEach { (fieldNum, unknownField) ->
            unknownFields[fieldNum] = unknownFields[fieldNum]?.let { prevValue ->
                prevValue.copy(values = prevValue.values + unknownField.values)
            } ?: unknownField
        }
    }

    override val binaryWireType: WireType = when (encoding) {
        MessageEncoding.LENGTH_PREFIXED -> WireType.LENGTH_DELIMITED
        MessageEncoding.DELIMITED -> WireType.START_GROUP
    }

    override fun binarySize(value: M): Int = when (encoding) {
        MessageEncoding.LENGTH_PREFIXED -> WireValue.Len.sizeWithLenPrefix(descriptor.protoSize(value))
        MessageEncoding.DELIMITED -> descriptor.protoSize(value)
    }

    override fun encodeFieldsToBinary(value: M, fieldEncoder: BinaryFieldEncoder) {
        descriptor.fieldDescriptors(value, ordered = true).forEach { fd ->
            fd.encodeToBinary(fieldEncoder, value)
        }
        for (field in descriptor.unknownFields(value).values) {
            field.values.forEach {
                fieldEncoder.encodeField(field.fieldNum, it.wireValue.wireType) { valueEncoder ->
                    valueEncoder.encodeUnknownField(field.fieldNum, it.wireValue)
                }
            }
        }
    }

    override fun encodeToBinary(value: M, encoder: BinaryFieldValueEncoder) {
        when (encoding) {
            MessageEncoding.LENGTH_PREFIXED -> encoder.encodeLenFields(descriptor.protoSize(value)) { fieldEncoder ->
                encodeFieldsToBinary(value, fieldEncoder)
            }

            MessageEncoding.DELIMITED -> encoder.encodeGroupFields { fieldEncoder ->
                encodeFieldsToBinary(value, fieldEncoder)
            }
        }
    }

    override fun decodeFieldsFromBinary(fieldDecoder: BinaryFieldDecoder): M {
        return descriptor.builder {
            val fieldDescriptors = descriptor.fields
            // Keep a `MutableList` of each unknown field while decoding the message. `UnknownField.values` is an
            // _immutable_ `List`, so modifying `unknownFields` directly would require extra object allocation to add a
            // new value to the immutable list.
            val unknownFieldValues = mutableMapOf<Int, MutableList<UnknownField.Value>>()

            fieldDecoder.forEachField { fieldNum, valueDecoder ->
                val fd = fieldDescriptors[fieldNum]
                if (fd != null && fd.fieldType.allowsBinaryWireType(valueDecoder.wireType)) {
                    fd.decodeFromBinary(valueDecoder, this)
                } else {
                    // TODO: support a `discardUnknownFields` option and call skipValue() in that case
                    val unknownFieldValue = UnknownField.Value(valueDecoder)
                    unknownFieldValues.getOrPut(fieldNum) { mutableListOf() }.add(unknownFieldValue)
                }
            }

            unknownFieldValues.forEach { descriptor.unknownFields(this)[it.key] = UnknownField(it.key, it.value) }
        }
    }

    override fun decodeFromBinary(decoder: BinaryFieldValueDecoder): M {
        return when (decoder) {
            is BinaryFieldValueDecoder.Len -> decoder.decodeFields { fieldDecoder ->
                decodeFieldsFromBinary(fieldDecoder)
            }

            is BinaryFieldValueDecoder.Group -> decoder.decodeFields { fieldDecoder ->
                decodeFieldsFromBinary(fieldDecoder)
            }

            else -> throw InvalidProtocolBufferException("Unexpected wire type for message value: ${decoder.wireType}")
        }
    }

    override fun decodeFieldsFromJson(fieldDecoder: JsonFieldDecoder): M {
        return descriptor.builder {
            val fieldDescriptors = descriptor.fields
            fieldDecoder.forEachField { keyDecoder, valueDecoder ->
                val fd = fieldDescriptors.findByJsonName(keyDecoder)
                if (fd != null) {
                    fd.decodeFromJson(valueDecoder, this)
                } else {
                    valueDecoder.skipValue()
                }
            }
        }
    }

    override fun decodeFromJson(decoder: JsonFieldValueDecoder): M {
        if (decoder !is JsonFieldValueDecoder.Object) {
            throw InvalidProtocolBufferException("Unexpected JSON type for message value: ${decoder.wireType.name}")
        }

        return decoder.decodeFields { decodeFieldsFromJson(it) }
    }

    override fun decodeMessageFromJson(decoder: JsonFieldValueDecoder) = decodeFromJson(decoder)

    override fun encodeFieldsToJson(value: M, fieldEncoder: JsonFieldEncoder) {
        descriptor.fieldDescriptors(value, ordered = true).forEach { fd ->
            fd.encodeToJson(fieldEncoder, value)
        }
    }

    override fun encodeToJson(value: M, encoder: JsonFieldValueEncoder) {
        val customValueType = customJsonMappings[descriptor]

        if (customValueType != null) {
            @Suppress("UNCHECKED_CAST")
            customValueType as MessageValueType<*, M>

            customValueType.encodeMessageToJson(value, encoder)
        } else {
            encoder.encodeObject { fieldEncoder ->
                encodeFieldsToJson(value, fieldEncoder)
            }
        }
    }

    override fun encodeMessageToJson(message: M, encoder: JsonFieldValueEncoder) {
        encodeToJson(message, encoder)
    }

    override fun encodeToJsonMapKey(value: M): String =
        throw UnsupportedOperationException("messages cannot be used as map keys")

    override fun decodeFromJsonMapKey(decoder: JsonFieldValueDecoder.String): M =
        throw UnsupportedOperationException("messages cannot be used as map keys")

}

private fun <M : Any, MM : Any> FieldDescriptorSet<M, MM>.findByJsonName(keyDecoder: JsonFieldValueDecoder.String): FieldDescriptor<M, MM, *>? {
    val key = keyDecoder.decodeAsString()
    val fd = this.firstOrNull { key in listOf(it.jsonName, it.name) }
    if (fd == null && !keyDecoder.jsonConfig.ignoreUnknownFieldsInInput) {
        throw InvalidProtocolBufferException("Unknown field name and ignoreUnknownFieldsInInput=false: $key")
    }
    return fd
}

private inline fun <M : Any, MM : Any, T> FieldDescriptor<M, MM, T>.copyValue(
    fromMessage: M,
    toMessage: MM,
) = setValue(toMessage, getValue(fromMessage))

internal val customJsonMappings: Map<MessageDescriptor<*, *>, MessageValueType<*, *>> = mapOf(
    pbandk.wkt.Any.descriptor to pbandk.internal.types.wkt.Any,
    pbandk.wkt.BoolValue.descriptor to BoolValue,
    pbandk.wkt.BytesValue.descriptor to BytesValue,
    pbandk.wkt.DoubleValue.descriptor to DoubleValue,
    pbandk.wkt.Duration.descriptor to Duration,
    pbandk.wkt.FloatValue.descriptor to FloatValue,
    pbandk.wkt.Int32Value.descriptor to Int32Value,
    pbandk.wkt.Int64Value.descriptor to Int64Value,
    pbandk.wkt.ListValue.descriptor to ListValue,
    pbandk.wkt.StringValue.descriptor to StringValue,
    pbandk.wkt.Struct.descriptor to Struct,
    pbandk.wkt.Timestamp.descriptor to Timestamp,
    pbandk.wkt.UInt32Value.descriptor to UInt32Value,
    pbandk.wkt.UInt64Value.descriptor to UInt64Value,
    pbandk.wkt.Value.descriptor to Value,
)