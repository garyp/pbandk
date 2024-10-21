package pbandk.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pbandk.ByteArr
import pbandk.EnumDescriptor
import pbandk.FieldDescriptor
import pbandk.FieldMetadata
import pbandk.InvalidProtocolBufferException
import pbandk.Message
import pbandk.MessageDescriptor
import pbandk.MessageEncoding
import pbandk.UnknownField
import pbandk.binary.BinaryFieldValueEncoder.Companion.encodeToBuffer
import pbandk.binary.MAX_VARINT_SIZE
import pbandk.binary.WireType
import pbandk.binary.WireValue
import pbandk.gen.ListField
import pbandk.getTypeUrl
import pbandk.internal.binary.Tag
import pbandk.internal.binary.kotlin.ByteArrayWireWriter
import pbandk.internal.binary.kotlin.WireWriter
import pbandk.internal.types.MessageValueType
import pbandk.json.JsonConfig
import pbandk.types.IntValueType
import pbandk.types.LongValueType
import pbandk.types.UIntValueType
import pbandk.types.ULongValueType
import pbandk.types.ValueType
import pbandk.unpack
import pbandk.wkt.Duration
import pbandk.wkt.FeatureSet
import pbandk.wkt.NullValue

internal abstract class ProtoVisitor {
    abstract fun <T : Any> visitField(fieldMetadata: FieldMetadata, valueType: ValueType<T>, value: T)
    abstract fun visitIntField(fieldMetadata: FieldMetadata, valueType: IntValueType, value: Int)
    abstract fun visitUIntField(fieldMetadata: FieldMetadata, valueType: UIntValueType, value: UInt)
    abstract fun visitLongField(fieldMetadata: FieldMetadata, valueType: LongValueType, value: Long)
    abstract fun visitULongField(fieldMetadata: FieldMetadata, valueType: ULongValueType, value: ULong)
    abstract fun <T : Any> visitRepeatedField(fieldMetadata: FieldMetadata, valueType: ValueType<T>, value: List<T>)
    abstract fun visitUnknownField(value: UnknownField)

    abstract fun visitUInt32Value(fieldMetadata: FieldMetadata, value: UInt)
    abstract fun visitUInt64Value(fieldMetadata: FieldMetadata, value: ULong)
    abstract fun visitInt32Value(fieldMetadata: FieldMetadata, value: Int)
    abstract fun visitInt64Value(fieldMetadata: FieldMetadata, value: Long)
    abstract fun visitStringValue(fieldMetadata: FieldMetadata, value: String)
    abstract fun visitBytesValue(fieldMetadata: FieldMetadata, value: ByteArr)
    abstract fun <E : Message.Enum> visitEnumValue(fieldMetadata: FieldMetadata, value: E, schema: EnumDescriptor<E>)
    abstract fun <M : Any, MM : Any> visitMessageValue(fieldMetadata: FieldMetadata, value: M, schema: MessageDescriptor<M, MM>)
}

internal class BinaryEncoderVisitor(private val wireWriter: WireWriter) : ProtoVisitor() {
    private val byteArrayBuffer = ByteArray(MAX_VARINT_SIZE)

    private fun encodeVarint(value: WireValue.Varint) {
        val position = value.encodeToBuffer(byteArrayBuffer)
        wireWriter.write(byteArrayBuffer, 0, position)
    }

    private fun encodeTag(fieldNumber: Int, wireType: WireType) {
        encodeVarint(WireValue.Varint.encodeUnsignedInt(Tag(fieldNumber, wireType).value))
    }

    private inline fun encodeLenPrefix(length: UInt) {
        encodeVarint(WireValue.Varint.encodeUnsignedInt(length))
    }

    private fun encodeLen(value: WireValue.Len) {
        encodeLenPrefix(value.value.size.toUInt())
        wireWriter.write(value.value, 0, value.value.size)
    }

    override fun <T : Any> visitField(fieldMetadata: FieldMetadata, valueType: ValueType<T>, value: T) {
        if (value is Message.Enum && value.value == null) return

        val isDelimitedMessage =
            valueType is MessageValueType<*, *> && fieldMetadata.messageEncoding == MessageEncoding.DELIMITED
        encodeTag(fieldMetadata.number, if (isDelimitedMessage) WireType.START_GROUP else valueType.binaryWireType)
        valueType.visitValue(fieldMetadata, value, this)
        if (isDelimitedMessage) {
            encodeTag(fieldMetadata.number, WireType.END_GROUP)
        }
    }

    override fun visitIntField(fieldMetadata: FieldMetadata, valueType: IntValueType, value: Int) {
        encodeTag(fieldMetadata.number, valueType.binaryWireType)
        valueType.visitIntValue(fieldMetadata, value, this)
    }

    override fun <T : Any> visitRepeatedField(fieldMetadata: FieldMetadata, valueType: ValueType<T>, value: List<T>) {
        if (value.isEmpty()) return

        if (fieldMetadata.options.features?.repeatedFieldEncoding == FeatureSet.RepeatedFieldEncoding.PACKED) {
            // TODO: check if skipping enums in the list with unrecognized string values is the correct behavior
            encodeTag(fieldMetadata.number, WireType.LENGTH_DELIMITED)
            encodeLenPrefix(((value as? ListField<T>)?.protoSize ?: value.sumOf(valueType::binarySize)).toUInt())
            value.forEach { valueType.visitValue(fieldMetadata, it, this) }
        } else {
            value.forEach {
                // TODO: check if skipping enums in the list with unrecognized string values is the correct behavior
                visitField(fieldMetadata, valueType, it)
            }
        }
    }

    override fun visitUInt64Value(fieldMetadata: FieldMetadata, value: ULong) {
        encodeVarint(WireValue.Varint.encodeUnsignedLong(value))
    }

    override fun visitUInt32Value(fieldMetadata: FieldMetadata, value: UInt) {
        encodeVarint(WireValue.Varint.encodeUnsignedInt(value))
    }

    override fun visitInt32Value(fieldMetadata: FieldMetadata, value: Int) {
        encodeVarint(WireValue.Varint.encodeSignedInt(value))
    }

    override fun visitInt64Value(fieldMetadata: FieldMetadata, value: Long) {
        encodeVarint(WireValue.Varint.encodeSignedLong(value))
    }

    override fun visitBytesValue(fieldMetadata: FieldMetadata, value: ByteArr) {
        encodeLen(WireValue.Len.encodeByteArray(value.array))
    }

    override fun visitStringValue(fieldMetadata: FieldMetadata, value: String) {
        encodeLen(WireValue.Len.encodeString(value))
    }

    override fun <E : Message.Enum> visitEnumValue(fieldMetadata: FieldMetadata, value: E, schema: EnumDescriptor<E>) {
        value.value?.let { encodeVarint(WireValue.Varint.encodeSignedInt(it)) }
    }

    override fun <M : Any, MM : Any> visitMessageValue(fieldMetadata: FieldMetadata, value: M, schema: MessageDescriptor<M, MM>) {
        if (fieldMetadata.messageEncoding == MessageEncoding.LENGTH_PREFIXED) {
            encodeLenPrefix(schema.protoSize(value).toUInt())
        }
        schema.visitFields(value, this)
    }

    override fun visitUnknownField(value: UnknownField) {
        value.values.forEach {
            encodeTag(value.fieldNum, it.wireValue.wireType)
            when (it.wireValue) {
                is WireValue.Varint -> encodeVarint(it.wireValue)
                is WireValue.I32 -> encodeI32(it.wireValue)
                is WireValue.I64 -> encodeI64(it.wireValue)
                is WireValue.Len -> encodeLen(it.wireValue)
                is WireValue.Group -> encodeGroup(fieldNum, it.wireValue)
                is WireValue.EndGroup -> {}
            }
        }
    }
}

internal class JsonEncoderVisitor(private val jsonConfig: JsonConfig) : ProtoVisitor() {
    private var jsonContent: MutableMap<String, JsonElement> = linkedMapOf()

    private var currentElement: JsonElement = JsonObject(jsonContent)

    fun toJson(): JsonElement = currentElement

    private fun encodeString(value: String): JsonElement {
        return JsonPrimitive(try {
            value.checkSurrogatePairs()
        } catch (e: Exception) {
            throw InvalidProtocolBufferException("Attempted to encode an invalid string", e)
        })
    }

    private fun encodeNumberUnsignedInt(value: UInt): JsonElement {
        // XXX: [JsonPrimitive] does not support unsigned number types currently (they do not inherit from [Number]
        // because of limitations with Kotlin inline classes). To work around this, output unsigned integers that are
        // outside of the range of signed integers as strings rather than numeric literals. While the Proto3 JSON spec
        // does say that these should be output as numeric literals, it also requires conforming implementations to
        // accept numeric strings when parsing the JSON.
        val intValue = value.toInt()
        return if (intValue < 0) {
            encodeString(value.toString())
        } else {
            JsonPrimitive(intValue)
        }
    }

    private fun encodeNumberUnsignedLong(value: ULong): JsonElement {
        return encodeString(value.toString())
    }

    private fun encodeNumberSignedInt(value: Int): JsonElement {
        return JsonPrimitive(value)
    }

    private fun encodeNumberSignedLong(value: Long): JsonElement {
        return encodeString(value.toString())
    }

    override fun <T : Any> visitField(fieldMetadata: FieldMetadata, valueType: ValueType<T>, value: T) {
        valueType.visitValue(fieldMetadata, value, this)
        jsonContent[fieldMetadata.jsonName] = currentElement
    }

    override fun visitUInt32Value(fieldMetadata: FieldMetadata, value: UInt) {
        currentElement = encodeNumberUnsignedInt(value)
    }

    override fun visitInt32Value(fieldMetadata: FieldMetadata, value: Int) {
        currentElement = encodeNumberSignedInt(value)
    }

    override fun visitInt64Value(fieldMetadata: FieldMetadata, value: Long) {
        currentElement = encodeNumberSignedLong(value)
    }

    override fun visitUInt64Value(fieldMetadata: FieldMetadata, value: ULong) {
        currentElement = encodeNumberUnsignedLong(value)
    }

    override fun visitBytesValue(fieldMetadata: FieldMetadata, value: ByteArr) {
        currentElement = encodeString(PlatformUtil.bytesToBase64(value.array))
    }

    override fun visitStringValue(fieldMetadata: FieldMetadata, value: String) {
        currentElement = encodeString(value)
    }

    override fun <E : Message.Enum> visitEnumValue(fieldMetadata: FieldMetadata, value: E, schema: EnumDescriptor<E>) {
        currentElement = if (schema.enumCompanion is NullValue) {
            JsonNull
        } else {
            // Unrecognized enum values must be serialized as their numeric value
            value.name?.let { name ->
                encodeString(name)
            } ?: value.value?.let { numericValue ->
                encodeNumberSignedInt(numericValue)
            } ?: throw IllegalStateException("Enums should always contain at least a `name` or a `value`")
        }
    }

    private fun <M : Any, MM : Any> visitWkt(
        value: M,
        schema: MessageDescriptor<M, MM>,
    ): JsonElement? {
        return when (value) {
            // TODO: handle custom types that are mapped to a WKT (e.g. kotlin.time.Duration to pbandk.wkt.Duration)
            is Duration -> encodeString(PlatformUtil.durationToString(value))

            is pbandk.wkt.Any -> {
                val childObject = linkedMapOf<String, JsonElement>()
                childObject["@type"] = encodeString(value.typeUrl)

                val valueType = jsonConfig.typeRegistry.getTypeUrl(value.typeUrl)
                    ?: throw InvalidProtocolBufferException("Type URL not found in type registry: ${value.typeUrl}")
                val unpackedValue = value.unpack(valueType)

                val wktElement = visitWkt(unpackedValue, valueType.descriptor)
                if (wktElement != null) {
                    childObject["value"] = wktElement
                } else {
                    val parentObject = jsonContent
                    jsonContent = childObject
                    schema.visitFields(value, this)
                    jsonContent = parentObject
                }

                JsonObject(childObject)
            }

            else -> null
        }
    }

    override fun <M : Any, MM : Any> visitMessageValue(
        fieldMetadata: FieldMetadata,
        value: M,
        schema: MessageDescriptor<M, MM>,
    ) {
        currentElement = visitWkt(value, schema) ?: run {
            val childObject = linkedMapOf<String, JsonElement>()
            val parentObject = jsonContent
            jsonContent = childObject
            schema.visitFields(value, this)
            jsonContent = parentObject
            JsonObject(childObject)
        }
    }

    override fun visitUnknownField(value: UnknownField) {
        // The protobuf JSON encoding doesn't support unknown fields
    }
}

private fun <M : Any, MM : Any> MessageDescriptor<M, MM>.encodeToByteArray(message: M): ByteArray {
    val writer = ByteArrayWireWriter.allocate(protoSize(message))
    val visitor = BinaryEncoderVisitor(writer)
    visitFields(message, visitor)
    return writer.toByteArray()
}

private fun <M : Any, MM : Any> MessageDescriptor<M, MM>.encodeToJsonString(
    message: M,
    jsonConfig: JsonConfig,
): String {
    val visitor = JsonEncoderVisitor(jsonConfig)
    visitFields(message, visitor)
    val json = Json {
        prettyPrint = !jsonConfig.compactOutput
    }
    return json.encodeToString(JsonElement.serializer(), visitor.toJson())
}

private fun <M : Any, MM : Any> MessageDescriptor<M, MM>.visitFields(message: M, visitor: ProtoVisitor) {
    fields.forEach { fd ->
        when (fd) {
            is FieldDescriptor.Optional<M, MM, *> -> {
                val value = fd.getValue(message)
                if (value != null) {
                    visitor.visitField(fd.metadata, fd.fieldType.valueType, value)
                }
            }
            is FieldDescriptor.Singular<M, MM, *> -> {
                val value = fd.getValue(message)
                if (!fd.fieldType.valueType.isDefaultValue(value)) {
                    visitor.visitField(fd.metadata, fd.fieldType.valueType, value)
                }
            }
            is FieldDescriptor.Required<M, MM, *> -> TODO()
            is FieldDescriptor.Repeated<M, MM, *> -> {
                val value = fd.getValue(message)
                visitor.visitRepeatedField(fd.metadata, fd.fieldType.valueType, value)
            }
            is FieldDescriptor.Map<M, MM, *, *> -> TODO()
            is FieldDescriptor.Extension -> TODO()
            is FieldDescriptor.RepeatedExtension<*, *, *> -> TODO()
        }
    }
    unknownFields(message).values.forEach {
        visitor.visitUnknownField(it)
    }
}