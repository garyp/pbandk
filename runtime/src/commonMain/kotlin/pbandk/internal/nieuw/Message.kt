package pbandk.internal.nieuw

import pbandk.EnumDescriptor
import pbandk.ExperimentalProtoReflection
import pbandk.UnknownField
import pbandk.binary.WireValue
import pbandk.internal.binary.kotlin.ByteArrayWireWriter

public interface Message {
    public val unknownFields: Map<Int, UnknownField>
    public val companion: Companion<out Message>
    public val protoSize: Int

    /**
     * Implements [protobuf merge semantics](https://protobuf.dev/programming-guides/encoding/#last-one-wins), similarly
     * to [Java's `mergeFrom` method](https://protobuf.dev/reference/java/api-docs/com/google/protobuf/Message.Builder.html#mergeFrom-com.google.protobuf.Message-).
     *
     * Note that this is _not_ the same as applying the `plus` operator to individual fields within the message.
     * Notably, numeric fields will not be added together and string fields will not be concatenated. Instead, the value
     * of the numeric/string field in [other] will overwrite the value in this message.
     */
    public operator fun plus(other: Message?): Message

    /**
     * Returns the value of the protocol buffer field from this message that is described by [fieldSchema]. If this
     * message does not contain a value for this field, the field's default value will be returned.
     *
     * [fieldSchema] can be a schema for an extension field that was not defined on the original message, but it
     * _MUST_ be a schema for fields in messages of type [M].
     */
    @ExperimentalProtoReflection
    public fun <V> getFieldValue(fieldSchema: FieldSchema<*, *, V>): V

    public abstract class Companion<M : Message> {
        public abstract val schema: MessageSchema<M>
    }

    public interface Enum {
        public val value: Int?
        public val name: String?

        public val descriptor: EnumDescriptor<out Enum>

        public interface Companion<E : Enum> {
            public val descriptor: EnumDescriptor<E>

            /** Returns `E.UNRECOGNIZED` if [value] is not a known value of this enum. */
            public fun fromValue(value: Int): E

            /** Throws [IllegalArgumentException] if [name] is not a valid value of this enum. */
            @Throws(IllegalArgumentException::class)
            public fun fromName(name: String): E
        }
    }

    public interface OneOf<V : Any> {
        public val value: V
    }
}

@DslMarker
public annotation class MessageBuilderMarker

@MessageBuilderMarker
public interface MutableMessage {
    public val unknownFields: MutableMap<Int, UnknownField>
}

public interface ExtendableMessage<M : ExtendableMessage<M>> : Message {
    public val extensionFields: ExtensionFieldSet<M>
}

public interface MutableExtendableMessage<M : ExtendableMessage<M>> : MutableMessage, ExtendableMessage<M> {
    override val extensionFields: MutableExtensionFieldSet<M>
}

internal sealed class ExtensionValue<M : ExtendableMessage<M>> {
    class SingularDecoded<M : ExtendableMessage<M>, V : Any>(
        val schema: FieldSchema.Extension<M, *, V>,
        val value: V,
    ) : ExtensionValue<M>() {
        fun encodeToBinary(): List<WireValue> {
//            val sizeVisitor = BinarySizeFieldVisitor()
//            sizeVisitor.visitField(schema.descriptor, schema.valueType, value)
//            return ByteArrayWireWriter.allocate(sizeVisitor.size).also {
//                BinaryEncoderFieldVisitor(it).visitField(schema.descriptor, schema.valueType, value)
//            }.toByteArray()
            return mutableListOf<WireValue>().also {
                BinaryEncoderFieldVisitor(BinaryWireValueArrayWriter(it))
                    .visitField(schema.descriptor, schema.valueType, value)
            }
        }
    }

    class RepeatedDecoded<M : ExtendableMessage<M>, V : Any>(
        val schema: FieldSchema.RepeatedExtension<M, *, V>,
        val value: List<V>,
    ) : ExtensionValue<M>() {
        fun encodeToBinary(): List<WireValue> {
//            val sizeVisitor = BinarySizeFieldVisitor()
//            sizeVisitor.visitRepeatedField(schema.descriptor, schema.valueType, value)
//            return ByteArrayWireWriter.allocate(sizeVisitor.size).also {
//                BinaryEncoderFieldVisitor(it).visitRepeatedField(schema.descriptor, schema.valueType, value)
//            }.toByteArray()
            return mutableListOf<WireValue>().also {
                BinaryEncoderFieldVisitor(BinaryWireValueArrayWriter(it))
                    .visitRepeatedField(schema.descriptor, schema.valueType, value)
            }
        }
    }

    class Binary<M : ExtendableMessage<M>>(val value: List<WireValue>) : ExtensionValue<M>()

    class Json<M : ExtendableMessage<M>>(
        val key: String,
        val value: pbandk.internal.json.WireValue,
    ) : ExtensionValue<M>()
}

public open class ExtensionFieldSet<M : ExtendableMessage<M>> internal constructor(
    internal open val fields: Map<Int, ExtensionValue<M>> = emptyMap()
) {
    internal fun <V : Any> getValue(fieldSchema: FieldSchema<M, *, V>): V? {
        return fields[fieldSchema.descriptor.number]?.let {
            if (it is ExtensionValue.SingularDecoded<M, *> && it.schema == fieldSchema) {
                @Suppress("UNCHECKED_CAST")
                it.value as V
            } else {
                null
            }
        }
    }

    private inline fun <M : ExtendableMessage<M>, V : Any> visitFieldHelper(
        visitor: ProtoFieldVisitor,
        field: ExtensionValue.SingularDecoded<M, V>,
    ) {
        visitor.visitField(field.schema.descriptor, field.schema.valueType, field.value)
    }

    private inline fun <M : ExtendableMessage<M>, V : Any> visitRepeatedFieldHelper(
        visitor: ProtoFieldVisitor,
        field: ExtensionValue.RepeatedDecoded<M, V>,
    ) {
        visitor.visitRepeatedField(field.schema.descriptor, field.schema.valueType, field.value)
    }

    internal fun visitFields(visitor: ProtoFieldVisitor) {
        for (field in fields.values) {
            when (field) {
                is ExtensionValue.SingularDecoded<M, *> -> visitFieldHelper(visitor, field)
                is ExtensionValue.RepeatedDecoded<M, *> -> visitRepeatedFieldHelper(visitor, field)
                is ExtensionValue.Binary<M> -> visitor.visitUnknownField(field.value)
                is ExtensionValue.Json<M> -> TODO()
            }
        }
    }
}

public class MutableExtensionFieldSet<M : ExtendableMessage<M>> internal constructor() : ExtensionFieldSet<M>() {
    override val fields: MutableMap<Int, ExtensionValue<M>> = mutableMapOf()
}