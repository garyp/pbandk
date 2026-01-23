package pbandk.internal.nieuw

import pbandk.Export
import pbandk.OneofDescriptor

@Export
public interface MessageSchema<M : Any> : ValueType<M> {
    public val descriptor: MessageDescriptor

    /**
     * Returns the default value for this message type. Can throw an exception if [M] is a message with `required`
     * fields, since such messages do not have a default value.
     */
    @get:Throws(UnsupportedOperationException::class)
    override val defaultValue: M

    override fun isDefaultValue(value: M): Boolean {
        return value == defaultValue
    }

    /*
    internal fun fieldSchemas(message: M, ordered: Boolean = false): Collection<FieldSchema<M, out Any?>> =
        if (message !is ExtendableMessage<*> || message.extensionFields.isEmpty()) {
            fields
        } else {
            ExtendableMessageFieldDescriptors(ordered, fields, message.extensionFields as FieldSet<M, *>)
        }

    internal fun computeBinarySize(message: M): Int {
        var size = 0

        fieldSchemas(message).forEach { fd ->
            size += fd.binarySize(message)
        }

        size += unknownFields(message).values.sumOf { it.size }
        return size
    }

    internal open fun protoSize(message: M): Int {
        return computeBinarySize(message)
    }
     */

    public fun visitFields(message: M, visitor: ProtoFieldVisitor)

    override fun visitValue(fieldDescriptor: FieldDescriptor, value: M, visitor: ProtoValueVisitor) {
        visitor.visitMessageValue(fieldDescriptor, value, this)
    }

    public fun decodeMessage(decoder: ProtoDecoder): M

    override fun decodeValue(fieldDescriptor: FieldDescriptor, decoder: ProtoDecoder): M {
        return decodeMessage(decoder) // TODO
    }

    public fun merge(message: M, other: M): M
}

internal open class GeneratedMessageSchema<M : GeneratedMessage<M>, MM : MutableMessage> internal constructor(
    override val descriptor: MessageDescriptor,
    private val builder: (MM.() -> Unit) -> M,
    fields: Collection<FieldSchema<M, MM, *>>,
    private val oneofs: Collection<OneofDescriptor<M, *, *>>,
) : MessageSchema<M>, BinarySizeFieldVisitor.CustomMessageSize<M> {
    override val defaultValue: M by lazy(LazyThreadSafetyMode.PUBLICATION) { builder {} }

    internal val fields = FieldSchemaSet(
        buildList<FieldSchema<M, MM, *>>(fields.size + oneofs.flatMap { it.fields }.size) {
            addAll(fields)
            addAll(oneofs.flatMap { it.fields })
            // Keep fields sorted by number so that message encoding outputs fields in order by number. This is not
            // required by the protobuf encoding spec, but is recommended (and is implemented by the official C++, Java,
            // and Python implementations).
            sortBy { it.descriptor.number }
        }
    )

    // Delegate to the message so that we can use the cached protoSize value
//    override fun protoSize(message: M) = message.protoSize

    override fun visitFields(message: M, visitor: ProtoFieldVisitor) {
        fields.forEach { fieldSchema ->
            fieldSchema.visitField(message, visitor)
        }
        message.unknownFields.values.forEach {
            visitor.visitUnknownField(it)
        }
    }

    override fun decodeMessage(decoder: ProtoDecoder): M {
        return builder {
            do {
                val fieldDescriptor = decoder.nextField(fields.descriptorSet) ?: TODO("deal with unknown field")

                val fieldSchema = fields[fieldDescriptor.number] ?: error()
                fieldSchema.decodeField(this, decoder)
            } while (fieldDescriptor.number != 0)
        }
    }

    override fun binarySize(value: M): Int {
        return value.protoSize
    }

    protected open fun merge(destinationMessage: MM, message: M, other: M) {
        for (fieldSchema in fields) {
            if (fieldSchema.descriptor.isOneofMember) continue
            fieldSchema.merge(message, other, destinationMessage)
        }
        for (oneofSchema in oneofs) {
            TODO()
        }
        with(destinationMessage.unknownFields) {
            putAll(message.unknownFields)
            for (field in other.unknownFields.values) {
                this[field.fieldNum] = this[field.fieldNum]?.let { existingField ->
                    existingField.copy(values = existingField.values + field.values)
                } ?: field
            }
        }
    }

    override fun merge(message: M, other: M): M {
        return builder {
            merge(this, message, other)
        }
    }
}

private class ExtendableMessageSchema<M : GeneratedExtendableMessage<M>, MM : MutableExtendableMessage<M>>(
    descriptor: MessageDescriptor,
    builder: (MM.() -> Unit) -> M,
    fields: Collection<FieldSchema<M, MM, *>>,
    oneofs: Collection<OneofDescriptor<M, *, *>>,
    private val extensionRanges: List<IntRange>,
) : GeneratedMessageSchema<M, MM>(descriptor, builder, fields, oneofs) {
    override fun visitFields(message: M, visitor: ProtoFieldVisitor) {
        super.visitFields(message, visitor)
        message.extensionFields.visitFields(visitor)
    }

    override fun decodeMessage(decoder: ProtoDecoder): M {
        TODO()
    }

    private fun <V : Any> mergeMessageField(
        schema1: FieldSchema.Extension<M, MM, V>,
        schema2: FieldSchema.Extension<M, MM, V>,
        value1: V,
        value2: V
    ) = ExtensionValue.SingularDecoded(
        schema1,
        if (schema1.valueType is MessageSchema<V>) {
            schema1.valueType.merge(value1, value2)
        } else {
            value2
        }
    )

    private fun binaryMerge(value1: ExtensionValue<M>, value2: ExtensionValue<M>): ExtensionValue.Binary<M> {
        val value1Binary = when (value1) {
            is ExtensionValue.Binary<M> -> value1.value
            is ExtensionValue.SingularDecoded<M, *> -> value1.encodeToBinary()
            is ExtensionValue.RepeatedDecoded<M, *> -> value1.encodeToBinary()
            is ExtensionValue.Json<M> -> TODO()
        }
        val value2Binary = when (value2) {
            is ExtensionValue.Binary<M> -> value2.value
            is ExtensionValue.SingularDecoded<M, *> -> value2.encodeToBinary()
            is ExtensionValue.RepeatedDecoded<M, *> -> value2.encodeToBinary()
            is ExtensionValue.Json<M> -> TODO()
        }
        return ExtensionValue.Binary(value1Binary + value2Binary)
    }

    override fun merge(destinationMessage: MM, message: M, other: M) {
        super.merge(destinationMessage, message, other)
        with(destinationMessage.extensionFields) {
            fields.putAll(message.extensionFields.fields)
            for ((otherKey, otherField) in other.extensionFields.fields) {
                fields[otherKey] = fields[otherKey]?.let { messageField ->
                    when (messageField) {
                        is ExtensionValue.SingularDecoded<M, *> -> {
                            when (otherField) {
                                is ExtensionValue.SingularDecoded<M, *> -> {
                                    if (messageField.schema.valueType == otherField.schema.valueType) {
                                        mergeMessageField(
                                            messageField.schema,
                                            otherField.schema,
                                            messageField.value,
                                            otherField.value
                                        )
                                    } else {
                                        binaryMerge(messageField, otherField)
                                    }
                                }

                                is ExtensionValue.RepeatedDecoded<M, *> -> binaryMerge(messageField, otherField)
                                is ExtensionValue.Binary<M> -> binaryMerge(messageField, otherField)

                                is ExtensionValue.Json<M> -> TODO()
                            }
                        }

                        is ExtensionValue.RepeatedDecoded<M, *> -> {
                            when (otherField) {
                                is ExtensionValue.RepeatedDecoded<M, *> -> {
                                    if (messageField.schema.valueType == otherField.schema.valueType) {
                                        ExtensionValue.RepeatedDecoded(
                                            messageField.schema,
                                            messageField.value + otherField.value
                                        )
                                    } else {
                                        binaryMerge(messageField, otherField)
                                    }
                                }

                                is ExtensionValue.SingularDecoded<M, *> -> binaryMerge(messageField, otherField)
                                is ExtensionValue.Binary<M> -> binaryMerge(messageField, otherField)
                                is ExtensionValue.Json<M> -> TODO()
                            }
                        }

                        is ExtensionValue.Binary<M> -> binaryMerge(messageField, otherField)

                        is ExtensionValue.Json<M> -> {
                            when (otherField) {
                                is ExtensionValue.SingularDecoded<M, *> -> TODO()
                                is ExtensionValue.RepeatedDecoded<M, *> -> TODO()
                                is ExtensionValue.Binary<M> -> TODO()
                                is ExtensionValue.Json<M> -> TODO()
                            }
                        }
                    }
                } ?: otherField
            }
        }
    }
}