package pbandk.internal

import pbandk.FieldDescriptor
import pbandk.FieldDescriptorSet
import pbandk.FieldMetadata
import pbandk.FieldMetadataSet
import pbandk.InvalidProtocolBufferException
import pbandk.MessageDescriptor
import pbandk.binary.WireType
import pbandk.binary.WireValue
import pbandk.internal.binary.BinaryDecoderContext
import pbandk.internal.binary.Tag
import pbandk.internal.binary.kotlin.WireReader
import pbandk.internal.types.MessageValueType
import pbandk.types.IntValueType
import pbandk.types.ValueType

internal abstract class ProtoDecoder {
    abstract fun nextField(fields: FieldMetadataSet): FieldMetadata?

    abstract fun <T : Any> decodeValue(fieldMetadata: FieldMetadata, valueType: ValueType<T>): T
    abstract fun decodeIntValue(fieldMetadata: FieldMetadata, valueType: IntValueType): Int
    abstract fun <T : Any> decodeRepeatedValue(fieldMetadata: FieldMetadata, valueType: ValueType<T>, dest: MutableList<T>)

    abstract fun decodeUInt32Value(fieldMetadata: FieldMetadata): UInt
    abstract fun decodeInt32Value(fieldMetadata: FieldMetadata): Int
}

internal class BinaryDecoder(private val wireReader: WireReader): ProtoDecoder() {
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

    override fun nextField(fields: FieldMetadataSet): FieldMetadata? {
        if (wireReader.isAtEnd()) {
            lastTag = Tag.Zero
            return null
        }

        lastTag = readTag()
        val fieldNumber = lastTag.fieldNumber
        val wireType = lastTag.wireType
        if (fieldNumber == 0) {
            // If we actually read zero (or any tag number corresponding to field
            // number zero), that's not a valid tag.
            throw InvalidProtocolBufferException.invalidTag()
        }

        if (wireType == WireType.END_GROUP) {
            return null
        }

        return fields[fieldNumber]


    }

    override fun <T : Any> decodeValue(fieldMetadata: FieldMetadata, valueType: ValueType<T>): T {
        return valueType.decodeValue(fieldMetadata, this)
    }

    override fun decodeIntValue(fieldMetadata: FieldMetadata, valueType: IntValueType): Int {
        return valueType.decodeIntValue(fieldMetadata, this)
    }

    override fun <T : Any> decodeRepeatedValue(
        fieldMetadata: FieldMetadata,
        valueType: ValueType<T>,
        dest: MutableList<T>
    ) {
        // Check if the field is "packed" (multiple values from the repeated list encoded into a single field). Only
        // repeated values that don't use [WireType.LENGTH_DELIMITED] can be packed. If the value uses
        // [WireType.LENGTH_DELIMITED], then this field can only represent a single value from the repeated list.
        if (lastTag.wireType == WireType.LENGTH_DELIMITED && valueType.binaryWireType != WireType.LENGTH_DELIMITED) {
            val size = decodePrefix()
            val oldLimit = context.wireReader.pushLimit(size)
            while (!context.wireReader.isAtEnd()) {
                dest.add(valueType.decodeValue(fieldMetadata, this))
            }
            context.wireReader.popLimit(oldLimit)
        } else {
            dest.add(valueType.decodeValue(fieldMetadata, this))
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

    override fun decodeInt32Value(fieldMetadata: FieldMetadata): Int {
        if (lastTag.wireType != WireType.VARINT) {
            throw InvalidProtocolBufferException("Unexpected wire type for int32 value: ${lastTag.wireType}")
        }
        return decodeVarint().decodeSignedInt
    }

    override fun decodeUInt32Value(fieldMetadata: FieldMetadata): UInt {
        if (lastTag.wireType != WireType.VARINT) {
            throw InvalidProtocolBufferException("Unexpected wire type for uint32 value: ${lastTag.wireType}")
        }
        return decodeVarint().decodeUnsignedInt
    }
}

private fun <M : Any, MM : Any> MessageDescriptor<M, MM>.decodeMessage(decoder: ProtoDecoder): M {
    return builder {
        do {
            val fieldMetadata = decoder.nextField(fields.metadataSet) ?: TODO()
            val fd = fields[fieldMetadata.number] ?: error()
            when (fd) {
                is FieldDescriptor.Optional<M, MM, *> -> {
                    val value = decoder.decodeValue(fd.metadata, fd.fieldType.valueType)
                    if (fd.fieldType.valueType is MessageValueType<*, *>) {
                        val mergedValue = fd.fieldType.mergeValues(fd.metadata, fd.getValue(this), value)
                        if (mergedValue != null) {
                            fd.setValue(this, mergedValue)
                        }
                    } else {
                        fd.setValue(this, value)
                    }
                }
                is FieldDescriptor.Singular -> TODO()
                is FieldDescriptor.Required -> TODO()
                is FieldDescriptor.Repeated<M, MM, *> -> {
                    decoder.decodeRepeatedValue(fd.metadata, fd.fieldType.valueType, fd.getMutableValue(this))
                }
                is FieldDescriptor.Map<M, MM, *, *> -> TODO()
                is FieldDescriptor.Extension -> TODO()
                is FieldDescriptor.RepeatedExtension<*, *, *> -> TODO()
            }
        } while (fieldMetadata.number != 0)
    }
}