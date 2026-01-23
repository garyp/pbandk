package pbandk.internal.nieuw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pbandk.InvalidProtocolBufferException
import pbandk.binary.MAX_VARINT_SIZE
import pbandk.binary.WireType
import pbandk.binary.WireValue
import pbandk.gen.UnrecognizedEnumValue
import pbandk.internal.binary.BinaryDecoderContext
import pbandk.internal.binary.Tag
import pbandk.internal.binary.kotlin.ByteArrayWireReader
import pbandk.internal.binary.kotlin.WireReader
import pbandk.internal.types.MessageValueType
import pbandk.internal.types.primitive.Enum
import pbandk.internal.types.shouldTreatAsUnknownField
import pbandk.json.JsonConfig
import pbandk.json.decodeAsIntegerType
import pbandk.wkt.NullValue

public abstract class ProtoDecoder {
    public abstract fun nextField(fields: FieldDescriptorSet): FieldDescriptor?
    public abstract fun skipValue()

    public abstract fun <T : Any> decodeValue(fieldDescriptor: FieldDescriptor, valueType: ValueType<T>): T
    public abstract fun decodeIntValue(fieldDescriptor: FieldDescriptor, valueType: IntValueType): Int
    public abstract fun <T : Any> decodeRepeatedValue(
        fieldDescriptor: FieldDescriptor,
        valueType: ValueType<T>,
        dest: MutableList<T>,
    )

    public abstract fun decodeUInt32Value(fieldDescriptor: FieldDescriptor): UInt
    public abstract fun decodeInt32Value(fieldDescriptor: FieldDescriptor): Int
}

internal class BinaryDecoder(
    private val wireReader: WireReader,
    private val binaryConfig: BinaryConfig,
) : ProtoDecoder() {
    private var lastTag: Tag = Tag.Zero
    private val context = BinaryDecoderContext(wireReader)

    private fun readTag() = Tag(context.varintValueDecoder.decodeValue().decodeUnsignedInt)

    private inline fun decodePrefix(): Int {
        val length = context.varintValueDecoder.decodeValue().decodeSignedInt
        if (length < 0) {
            throw InvalidProtocolBufferException.negativeSize()
        }
        return length
    }

    override fun nextField(fields: FieldDescriptorSet): FieldDescriptor? {
        if (wireReader.isAtEnd()) {
            lastTag = Tag.Zero
            return null
        }

        val tag = readTag()
        lastTag = tag
        val fieldNumber = tag.fieldNumber
        val wireType = tag.wireType
        if (fieldNumber == 0) {
            // If we actually read zero (or any tag number corresponding to field
            // number zero), that's not a valid tag.
            throw InvalidProtocolBufferException.invalidTag()
        }

        if (wireType == WireType.END_GROUP) {
            return null
        }

        fields[fieldNumber]?.let { return it }


    }

    override fun skipValue() {
        when (lastTag.wireType) {
            WireType.VARINT -> {
                repeat(MAX_VARINT_SIZE) {
                    if (wireReader.readByte() >= 0)
                        return
                }
                throw InvalidProtocolBufferException.malformedVarint()
            }

            WireType.FIXED32 -> wireReader.skipRawBytes(4)
            WireType.FIXED64 -> wireReader.skipRawBytes(8)
            WireType.LENGTH_DELIMITED -> {
                val size = decodePrefix()
                wireReader.skipRawBytes(size)
            }

            WireType.START_GROUP -> TODO()
            WireType.END_GROUP -> {
                // do nothing since END_GROUP is a tag with no value following it
            }
        }
    }

    override fun <T : Any> decodeValue(fieldDescriptor: FieldDescriptor, valueType: ValueType<T>): T {
        return valueType.decodeValue(fieldDescriptor, this)
    }

    override fun decodeIntValue(fieldDescriptor: FieldDescriptor, valueType: IntValueType): Int {
        return valueType.decodeIntValue(fieldDescriptor, this)
    }

    override fun <T : Any> decodeRepeatedValue(
        fieldDescriptor: FieldDescriptor,
        valueType: ValueType<T>,
        dest: MutableList<T>
    ) {
        // Check if the field is "packed" (multiple values from the repeated list encoded into a single field). Only
        // repeated values that don't use [WireType.LENGTH_DELIMITED] can be packed. If the value uses
        // [WireType.LENGTH_DELIMITED], then this field can only represent a single value from the repeated list.
        if (lastTag.wireType == WireType.LENGTH_DELIMITED &&
            valueType is PrimitiveValueType &&
            valueType.binaryWireType != WireType.LENGTH_DELIMITED
        ) {
            val size = decodePrefix()
            val oldLimit = context.wireReader.pushLimit(size)
            while (!context.wireReader.isAtEnd()) {
                dest.add(valueType.decodeValue(fieldDescriptor, this))
            }
            context.wireReader.popLimit(oldLimit)
        } else {
            dest.add(valueType.decodeValue(fieldDescriptor, this))
        }
    }

    private fun decodeVarint(): WireValue.Varint {
        var result = 0UL
        for (shift in 0..<64 step 7) {
            val b = wireReader.readByte()
            result = result or ((b.toInt() and 0x7F).toULong() shl shift)
            if (b.toInt() and 0x80 == 0) {
                return WireValue.Varint(result)
            }
        }
        throw InvalidProtocolBufferException.malformedVarint()
    }

    override fun decodeInt32Value(fieldDescriptor: FieldDescriptor): Int {
        if (lastTag.wireType != WireType.VARINT) {
            throw InvalidProtocolBufferException("Unexpected wire type for int32 value: ${lastTag.wireType}")
        }
        return decodeVarint().decodeSignedInt
    }

    override fun decodeUInt32Value(fieldDescriptor: FieldDescriptor): UInt {
        if (lastTag.wireType != WireType.VARINT) {
            throw InvalidProtocolBufferException("Unexpected wire type for uint32 value: ${lastTag.wireType}")
        }
        return decodeVarint().decodeUnsignedInt
    }
}

internal class JsonDecoder(
    content: JsonObject,
    private val jsonConfig: JsonConfig,
) : ProtoDecoder() {
    private val iterator = content.iterator()
    private var nextValue: JsonElement? = null

    private fun FieldDescriptorSet.findByJsonName(key: String): FieldDescriptor? {
//        try {
//            key.checkSurrogatePairs()
//        } catch (e: Exception) {
//            throw InvalidProtocolBufferException("JSON value did not contain a valid string", e)
//        }

        val field = this.firstOrNull { key in listOf(it.jsonName, it.name) }
        if (field == null && !jsonConfig.ignoreUnknownFieldsInInput) {
            throw InvalidProtocolBufferException("Unknown field name and ignoreUnknownFieldsInInput=false: $key")
        }
        return field
    }

    override fun nextField(fields: FieldDescriptorSet): FieldDescriptor? {
        var entry: Map.Entry<String, JsonElement>
        // Skip any JSON fields that have `null` values. According to the ProtoJSON spec, a field with a `null` value
        // uses the field's default value (i.e. it behaves the same as if that field wasn't provided in the input at
        // all).
        do {
            entry = if (iterator.hasNext()) iterator.next() else return null
        } while (entry.value is JsonNull)

        nextValue = entry.value
        return fields.findByJsonName(entry.key)
    }

    override fun skipValue() {
        // Nothing to do for the JsonDecoder since nextField() will automatically skip the previous field's value
    }

    override fun <T : Any> decodeValue(fieldDescriptor: FieldDescriptor, valueType: ValueType<T>): T {
        valueType.decodeValue(fieldDescriptor, this)
    }

    override fun decodeIntValue(fieldDescriptor: FieldDescriptor, valueType: IntValueType): Int {
        return valueType.decodeIntValue(fieldDescriptor, this)
    }

    override fun <T : Any> decodeRepeatedValue(
        fieldDescriptor: FieldDescriptor,
        valueType: ValueType<T>,
        dest: MutableList<T>
    ) {
        when (val jsonValue = nextValue!!) {
            is JsonNull -> {}
            is JsonArray -> {
                jsonValue.forEach { arrayElement ->
                    if (arrayElement is JsonNull &&
                        (valueType as? MessageValueType<*, *>)?.descriptor != pbandk.wkt.Value.descriptor &&
                        (valueType as? Enum)?.enumCompanion != NullValue
                    ) {
                        throw InvalidProtocolBufferException("JSON repeated values must not contain nulls")
                    }

                    nextValue = arrayElement
                    val value = valueType.decodeValue(fieldDescriptor, this)
                    if (value is UnrecognizedEnumValue<*> &&
                        value.shouldTreatAsUnknownField(jsonConfig)
                    ) {
                        if (jsonConfig.ignoreUnknownFieldsInInput) {
                            // According to the `IgnoreUnknownEnumStringValue` JSON conformance test, when an unknown
                            // enum value is encountered in a repeated enum, that value should be skipped. This means
                            // that round-tripping the repeated enum will cause the list to shorten.
                            return@forEach
                        } else {
                            throw InvalidProtocolBufferException.unrecognizedEnumValue(fieldDescriptor.name)
                        }
                    }

                    dest.add(value)
                }
            }

            else -> throw InvalidProtocolBufferException("Unexpected JSON type for repeated field: ${jsonValue::class.simpleName}")
        }

    }

    private fun decodeStringAsSignedInt(string: String): Int {
        return string.decodeAsIntegerType(String::toInt)
    }

    private fun decodeStringAsUnsignedInt(string: String): UInt {
        return string.decodeAsIntegerType(String::toUInt)
    }

    override fun decodeUInt32Value(fieldDescriptor: FieldDescriptor): UInt {
        return when (val jsonValue = nextValue!!) {
            is JsonPrimitive -> decodeStringAsUnsignedInt(jsonValue.content)
            else -> throw InvalidProtocolBufferException("Unexpected JSON type for uint32 value: ${jsonValue::class.simpleName}")
        }
    }

    override fun decodeInt32Value(fieldDescriptor: FieldDescriptor): Int {
        return when (val jsonValue = nextValue!!) {
            is JsonPrimitive -> decodeStringAsSignedInt(jsonValue.content)
            else -> throw InvalidProtocolBufferException("Unexpected JSON type for int32 value: ${jsonValue::class.simpleName}")
        }
    }
}

internal fun <M : Any> MessageSchema<M>.decodeFromByteArray(
    array: ByteArray,
    binaryConfig: BinaryConfig = BinaryConfig.DEFAULT,
): M {
    val decoder = BinaryDecoder(ByteArrayWireReader(array), binaryConfig)
    return decodeMessage(decoder)
}

internal fun <M : Any> MessageSchema<M>.decodeFromJsonString(
    string: String,
    jsonConfig: JsonConfig = JsonConfig.DEFAULT,
): M {
    when (val content = Json.decodeFromString(JsonElement.serializer(), string)) {
        is JsonNull -> return defaultValue

        is JsonObject -> {
            val decoder = JsonDecoder(content, jsonConfig)
            return decodeMessage(decoder)
        }

        else -> error()
    }
}