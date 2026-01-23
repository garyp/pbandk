package pbandk.internal.nieuw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pbandk.ByteArr
import pbandk.EnumDescriptor
import pbandk.InvalidProtocolBufferException
import pbandk.Message
import pbandk.MessageEncoding
import pbandk.UnknownField
import pbandk.binary.MAX_VARINT_SIZE
import pbandk.binary.WireType
import pbandk.binary.WireValue
import pbandk.gen.ListField
import pbandk.getTypeUrl
import pbandk.internal.PlatformUtil
import pbandk.internal.binary.Tag
import pbandk.internal.binary.kotlin.ByteArrayWireWriter
import pbandk.internal.binary.kotlin.WireWriter
import pbandk.internal.checkSurrogatePairs
import pbandk.json.JsonConfig
import pbandk.unpack
import pbandk.wkt.Duration
import pbandk.wkt.FeatureSet
import pbandk.wkt.NullValue

public abstract class ProtoFieldVisitor {
    public abstract fun <T : Any> visitField(fieldDescriptor: FieldDescriptor, valueType: ValueType<T>, value: T)
    public abstract fun visitIntField(fieldDescriptor: FieldDescriptor, valueType: IntValueType, value: Int)
    public abstract fun visitUIntField(fieldDescriptor: FieldDescriptor, valueType: UIntValueType, value: UInt)
    public abstract fun visitLongField(fieldDescriptor: FieldDescriptor, valueType: LongValueType, value: Long)
    public abstract fun visitULongField(fieldDescriptor: FieldDescriptor, valueType: ULongValueType, value: ULong)
    public abstract fun <T : Any> visitRepeatedField(
        fieldDescriptor: FieldDescriptor,
        valueType: ValueType<T>,
        value: List<T>
    )

    public abstract fun visitUnknownField(value: UnknownField)
}

public abstract class ProtoValueVisitor {
    public abstract fun visitUInt32Value(fieldDescriptor: FieldDescriptor, value: UInt)
    public abstract fun visitUInt64Value(fieldDescriptor: FieldDescriptor, value: ULong)
    public abstract fun visitInt32Value(fieldDescriptor: FieldDescriptor, value: Int)
    public abstract fun visitInt64Value(fieldDescriptor: FieldDescriptor, value: Long)
    public abstract fun visitStringValue(fieldDescriptor: FieldDescriptor, value: String)
    public abstract fun visitBytesValue(fieldDescriptor: FieldDescriptor, value: ByteArr)
    public abstract fun <E : Message.Enum> visitEnumValue(
        fieldDescriptor: FieldDescriptor,
        value: E,
        schema: EnumDescriptor<E>
    )

    public abstract fun <M : Any> visitMessageValue(
        fieldDescriptor: FieldDescriptor,
        value: M,
        schema: MessageSchema<M>
    )
}

internal interface BinaryWireValueWriter {
    fun writeVarint(value: WireValue.Varint)
    fun writeI32(value: WireValue.I32)
    fun writeI64(value: WireValue.I64)
    fun writeLen(value: WireValue.Len)
    fun writeGroup(fieldNum: Int, value: WireValue.Group)
}

private fun WireValue.Varint.encodeToBuffer(buffer: ByteArray, offset: Int = 0): Int {
    var position = offset
    var valueCur = value
    while (position < MAX_VARINT_SIZE) {
        if ((valueCur and 0x7FUL.inv()) == 0UL) {
            buffer[position++] = valueCur.toByte()
            break
        } else {
            buffer[position++] = ((valueCur and 0x7FU) or 0x80U).toByte()
            valueCur = valueCur shr 7
        }
    }
    return position
}

private fun WireValue.I32.encodeToBuffer(buffer: ByteArray, offset: Int = 0): Int {
    for (i in offset..<offset + 4) {
        buffer[i] = (value shr (8 * i)).toByte()
    }
    return 4
}

private fun WireValue.I64.encodeToBuffer(buffer: ByteArray, offset: Int = 0): Int {
    for (i in offset..<offset + 8) {
        buffer[i] = (value shr (8 * i)).toByte()
    }
    return 8
}

internal class BinaryWireValueWireWriter(private val wireWriter: WireWriter) : BinaryWireValueWriter {
    override fun writeVarint(value: WireValue.Varint) {
        wireWriter.write(MAX_VARINT_SIZE, value::encodeToBuffer)
    }

    override fun writeI32(value: WireValue.I32) {
        wireWriter.write(4, value::encodeToBuffer)
    }

    override fun writeI64(value: WireValue.I64) {
        wireWriter.write(8, value::encodeToBuffer)
    }

    override fun writeLen(value: WireValue.Len) {
        writeLenPrefix(value.value.size.toUInt())
        wireWriter.write(value.value, 0, value.value.size)
    }

    override fun writeGroup(fieldNum: Int, value: WireValue.Group) {
        value.value.forEach { field ->
            field.values.forEach {
                writeTag(field.fieldNum, it.wireValue.wireType)
                writeWireValue(field.fieldNum, it.wireValue)
            }
        }
        writeTag(fieldNum, WireType.END_GROUP)
    }
}

internal class BinaryWireValueArrayWriter(private val array: MutableList<WireValue>) : BinaryWireValueWriter {
    override fun writeVarint(value: WireValue.Varint) {
        array.add(value)
    }

    override fun writeI32(value: WireValue.I32) {
        array.add(value)
    }

    override fun writeI64(value: WireValue.I64) {
        array.add(value)
    }

    override fun writeLen(value: WireValue.Len) {
        array.add(value)
    }

    override fun writeGroup(fieldNum: Int, value: WireValue.Group) {
        array.add(value)
    }
}

private inline fun BinaryWireValueWriter.writeLenPrefix(length: UInt) {
    writeVarint(WireValue.Varint.encodeUnsignedInt(length))
}

private inline fun BinaryWireValueWriter.writeTag(fieldNumber: Int, wireType: WireType) {
    writeVarint(WireValue.Varint.encodeUnsignedInt(Tag(fieldNumber, wireType).value))
}

private fun BinaryWireValueWriter.writeWireValue(fieldNumber: Int, wireValue: WireValue) {
    when (wireValue) {
        is WireValue.Varint -> writeVarint(wireValue)
        is WireValue.I32 -> writeI32(wireValue)
        is WireValue.I64 -> writeI64(wireValue)
        is WireValue.Len -> writeLen(wireValue)
        is WireValue.Group -> writeGroup(fieldNumber, wireValue)
        is WireValue.EndGroup -> {}
    }
}

internal class BinaryEncoderFieldVisitor(private val writer: BinaryWireValueWriter) : ProtoFieldVisitor() {
    private val valueVisitor = BinaryEncoderValueVisitor()


    override fun <T : Any> visitField(fieldDescriptor: FieldDescriptor, valueType: ValueType<T>, value: T) {
        if (value is Message.Enum && value.value == null) return

        val wireType = when (valueType) {
            is PrimitiveValueType -> valueType.binaryWireType
            is MessageSchema -> if (fieldDescriptor.messageEncoding == MessageEncoding.DELIMITED) {
                WireType.START_GROUP
            } else {
                WireType.LENGTH_DELIMITED
            }

            else -> TODO()
        }
        writer.writeTag(fieldDescriptor.number, wireType)
        valueType.visitValue(fieldDescriptor, value, valueVisitor)
        if (wireType == WireType.START_GROUP) {
            writer.writeTag(fieldDescriptor.number, WireType.END_GROUP)
        }
    }

    override fun visitIntField(fieldDescriptor: FieldDescriptor, valueType: IntValueType, value: Int) {
        writer.writeTag(fieldDescriptor.number, valueType.binaryWireType)
        valueType.visitIntValue(fieldDescriptor, value, valueVisitor)
    }

    override fun <T : Any> visitRepeatedField(
        fieldDescriptor: FieldDescriptor,
        valueType: ValueType<T>,
        value: List<T>
    ) {
        if (value.isEmpty()) return

        if (fieldDescriptor.options.features?.repeatedFieldEncoding == FeatureSet.RepeatedFieldEncoding.PACKED) {
            // TODO: check if skipping enums in the list with unrecognized string values is the correct behavior
            writer.writeTag(fieldDescriptor.number, WireType.LENGTH_DELIMITED)
            writer.writeLenPrefix(
                ((value as? ListField<T>)?.protoSize ?: value.sumOf(valueType::binarySize)).toUInt()
            )
            value.forEach { valueType.visitValue(fieldDescriptor, it, valueVisitor) }
        } else {
            value.forEach {
                // TODO: check if skipping enums in the list with unrecognized string values is the correct behavior
                visitField(fieldDescriptor, valueType, it)
            }
        }
    }

    override fun visitUnknownField(value: UnknownField) {
        value.values.forEach {
            writer.writeTag(value.fieldNum, it.wireValue.wireType)
            writer.writeWireValue(value.fieldNum, it.wireValue)
        }
    }

    private inner class BinaryEncoderValueVisitor : ProtoValueVisitor() {
        override fun visitUInt64Value(fieldDescriptor: FieldDescriptor, value: ULong) {
            writer.writeVarint(WireValue.Varint.encodeUnsignedLong(value))
        }

        override fun visitUInt32Value(fieldDescriptor: FieldDescriptor, value: UInt) {
            writer.writeVarint(WireValue.Varint.encodeUnsignedInt(value))
        }

        override fun visitInt32Value(fieldDescriptor: FieldDescriptor, value: Int) {
            writer.writeVarint(WireValue.Varint.encodeSignedInt(value))
        }

        override fun visitInt64Value(fieldDescriptor: FieldDescriptor, value: Long) {
            writer.writeVarint(WireValue.Varint.encodeSignedLong(value))
        }

        override fun visitBytesValue(fieldDescriptor: FieldDescriptor, value: ByteArr) {
            writer.writeLen(WireValue.Len.encodeByteArray(value.array))
        }

        override fun visitStringValue(fieldDescriptor: FieldDescriptor, value: String) {
            writer.writeLen(WireValue.Len.encodeString(value))
        }

        override fun <E : Message.Enum> visitEnumValue(
            fieldDescriptor: FieldDescriptor,
            value: E,
            schema: EnumDescriptor<E>
        ) {
            value.value?.let { writer.writeVarint(WireValue.Varint.encodeSignedInt(it)) }
        }

        override fun <M : Any> visitMessageValue(
            fieldDescriptor: FieldDescriptor,
            value: M,
            schema: MessageSchema<M>
        ) {
            if (fieldDescriptor.messageEncoding == MessageEncoding.LENGTH_PREFIXED) {
                writer.writeLenPrefix(binarySize(schema, value).toUInt())
            }
            schema.visitFields(value, this@BinaryEncoderFieldVisitor)
        }
    }
}


internal class BinarySizeFieldVisitor : ProtoFieldVisitor() {
    internal interface CustomMessageSize<M : Any> {
        fun binarySize(value: M): Int
    }

    var size = 0
        private set

    private val valueVisitor = BinarySizeValueVisitor()

    private fun tagSize(fieldNumber: Int, wireType: WireType): Int {
        return WireValue.Varint.encodeUnsignedInt(Tag(fieldNumber, wireType).value).size
    }

    private fun lenSize(length: Int): Int {
        return WireValue.Varint.encodeUnsignedInt(length.toUInt()).size
    }

    private inline fun sizeWithLenPrefix(block: () -> Unit) {
        val previousSize = size
        block()
        size += lenSize(size - previousSize)
    }

    override fun <T : Any> visitField(fieldDescriptor: FieldDescriptor, valueType: ValueType<T>, value: T) {
        if (value is Message.Enum && value.value == null) return

        val wireType = when (valueType) {
            is PrimitiveValueType -> valueType.binaryWireType
            is MessageSchema -> if (fieldDescriptor.messageEncoding == MessageEncoding.DELIMITED) {
                WireType.START_GROUP
            } else {
                WireType.LENGTH_DELIMITED
            }

            else -> TODO()
        }
        size += tagSize(fieldDescriptor.number, wireType)
        valueType.visitValue(fieldDescriptor, value, valueVisitor)
        if (wireType == WireType.START_GROUP) {
            size += tagSize(fieldDescriptor.number, WireType.END_GROUP)
        }
    }

    override fun visitIntField(fieldDescriptor: FieldDescriptor, valueType: IntValueType, value: Int) {
        size += tagSize(fieldDescriptor.number, valueType.binaryWireType)
        valueType.visitIntValue(fieldDescriptor, value, valueVisitor)
    }

    override fun <T : Any> visitRepeatedField(
        fieldDescriptor: FieldDescriptor,
        valueType: ValueType<T>,
        value: List<T>
    ) {
        if (value.isEmpty()) return

        if (valueType is PrimitiveValueType &&
            fieldDescriptor.options.features?.repeatedFieldEncoding == FeatureSet.RepeatedFieldEncoding.PACKED
        ) {
            // TODO: check if skipping enums in the list with unrecognized string values is the correct behavior
            size += tagSize(fieldDescriptor.number, WireType.LENGTH_DELIMITED)
            sizeWithLenPrefix {
                value.forEach { valueType.visitValue(fieldDescriptor, it, valueVisitor) }
            }
        } else {
            value.forEach {
                // TODO: check if skipping enums in the list with unrecognized string values is the correct behavior
                visitField(fieldDescriptor, valueType, it)
            }
        }
    }

    override fun visitUnknownField(value: UnknownField) {
        value.values.forEach {
            size += tagSize(value.fieldNum, it.wireValue.wireType) + it.wireValue.size
        }
    }

    private inner class BinarySizeValueVisitor : ProtoValueVisitor() {
        override fun visitUInt64Value(fieldDescriptor: FieldDescriptor, value: ULong) {
            size += WireValue.Varint.encodeUnsignedLong(value).size
        }

        override fun visitUInt32Value(fieldDescriptor: FieldDescriptor, value: UInt) {
            size += WireValue.Varint.encodeUnsignedInt(value).size
        }

        override fun visitInt32Value(fieldDescriptor: FieldDescriptor, value: Int) {
            size += WireValue.Varint.encodeSignedInt(value).size
        }

        override fun visitInt64Value(fieldDescriptor: FieldDescriptor, value: Long) {
            size += WireValue.Varint.encodeSignedLong(value).size
        }

        override fun visitBytesValue(fieldDescriptor: FieldDescriptor, value: ByteArr) {
            size += WireValue.Len.encodeByteArray(value.array).size
        }

        override fun visitStringValue(fieldDescriptor: FieldDescriptor, value: String) {
            size += WireValue.Len.encodeString(value).size
        }

        override fun <E : Message.Enum> visitEnumValue(
            fieldDescriptor: FieldDescriptor,
            value: E,
            schema: EnumDescriptor<E>
        ) {
            value.value?.let { size += WireValue.Varint.encodeSignedInt(it).size }
        }

        override fun <M : Any> visitMessageValue(
            fieldDescriptor: FieldDescriptor,
            value: M,
            schema: MessageSchema<M>
        ) {
            val valueSize = binarySize(schema, value)
            if (fieldDescriptor.messageEncoding == MessageEncoding.LENGTH_PREFIXED) {
                size += lenSize(valueSize)
            }
            size += valueSize
        }
    }
}

internal class JsonEncoderFieldVisitor(private val jsonConfig: JsonConfig) : ProtoFieldVisitor() {
    private val valueVisitor = JsonEncoderValueVisitor()

    private var jsonContent: MutableMap<String, JsonElement> = linkedMapOf()

    private var currentElement: JsonElement = JsonObject(jsonContent)

    fun toJson(): JsonElement = currentElement

    private fun encodeString(value: String): JsonElement {
        return JsonPrimitive(
            try {
                value.checkSurrogatePairs()
            } catch (e: Exception) {
                throw InvalidProtocolBufferException("Attempted to encode an invalid string", e)
            }
        )
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

    override fun <T : Any> visitField(fieldDescriptor: FieldDescriptor, valueType: ValueType<T>, value: T) {
        valueType.visitValue(fieldDescriptor, value, valueVisitor)
        jsonContent[fieldDescriptor.jsonName] = currentElement
    }

    override fun visitUnknownField(value: UnknownField) {
        // The protobuf JSON encoding doesn't support unknown fields
    }

    private inner class JsonEncoderValueVisitor : ProtoValueVisitor() {

        override fun visitUInt32Value(fieldDescriptor: FieldDescriptor, value: UInt) {
            currentElement = encodeNumberUnsignedInt(value)
        }

        override fun visitInt32Value(fieldDescriptor: FieldDescriptor, value: Int) {
            currentElement = encodeNumberSignedInt(value)
        }

        override fun visitInt64Value(fieldDescriptor: FieldDescriptor, value: Long) {
            currentElement = encodeNumberSignedLong(value)
        }

        override fun visitUInt64Value(fieldDescriptor: FieldDescriptor, value: ULong) {
            currentElement = encodeNumberUnsignedLong(value)
        }

        override fun visitBytesValue(fieldDescriptor: FieldDescriptor, value: ByteArr) {
            currentElement = encodeString(PlatformUtil.bytesToBase64(value.array))
        }

        override fun visitStringValue(fieldDescriptor: FieldDescriptor, value: String) {
            currentElement = encodeString(value)
        }

        override fun <E : Message.Enum> visitEnumValue(
            fieldDescriptor: FieldDescriptor,
            value: E,
            schema: EnumDescriptor<E>
        ) {
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

        private fun <M : Any> visitWkt(
            value: M,
            schema: MessageSchema<M>,
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
                        schema.visitFields(value, this@JsonEncoderFieldVisitor)
                        jsonContent = parentObject
                    }

                    JsonObject(childObject)
                }

                else -> null
            }
        }

        override fun <M : Any> visitMessageValue(
            fieldDescriptor: FieldDescriptor,
            value: M,
            schema: MessageSchema<M>,
        ) {
            currentElement = visitWkt(value, schema) ?: run {
                val childObject = linkedMapOf<String, JsonElement>()
                val parentObject = jsonContent
                jsonContent = childObject
                schema.visitFields(value, this@JsonEncoderFieldVisitor)
                jsonContent = parentObject
                JsonObject(childObject)
            }
        }
    }
}

internal fun <M : Any> MessageSchema<M>.encodeToByteArray(message: M): ByteArray {
    val writer = ByteArrayWireWriter.allocate(binarySize(this, message))
    val visitor = BinaryEncoderFieldVisitor(BinaryWireValueWireWriter(writer))
    visitFields(message, visitor)
    return writer.toByteArray()
}

internal fun <M : Any> MessageSchema<M>.encodeToJsonString(
    message: M,
    jsonConfig: JsonConfig,
): String {
    val visitor = JsonEncoderFieldVisitor(jsonConfig)
    visitFields(message, visitor)
    val json = Json {
        prettyPrint = !jsonConfig.compactOutput
    }
    return json.encodeToString(JsonElement.serializer(), visitor.toJson())
}

internal fun <M : Any> computeBinarySize(schema: MessageSchema<M>, message: M): Int {
    val visitor = BinarySizeFieldVisitor()
    schema.visitFields(message, visitor)
    return visitor.size
}

private fun <M : Any> binarySize(schema: MessageSchema<M>, message: M): Int {
    return if (schema is BinarySizeFieldVisitor.CustomMessageSize<*>) {
        @Suppress("UNCHECKED_CAST")
        (schema as BinarySizeFieldVisitor.CustomMessageSize<M>).binarySize(message)
    } else {
        computeBinarySize(schema, message)
    }
}