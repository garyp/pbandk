package pbandk

import pbandk.internal.binary.BinaryMessageDecoder
import pbandk.internal.binary.BinaryMessageEncoder
import pbandk.internal.binary.allocate
import pbandk.internal.binary.fromByteArray
import pbandk.internal.types.MessageValueType

public interface Message {
    public val unknownFields: Map<Int, UnknownField>

    @Deprecated(
        message = "Use companion.valueType.descriptor instead",
        replaceWith = ReplaceWith("companion.valueType.descriptor"),
    )
    public val descriptor: MessageDescriptor<out Message, *> get() = companion.valueType.descriptor

    public val companion: Companion<out Message, *>

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
     * Returns the value of the protocol buffer field from this message that is described by [fieldDescriptor]. If this
     * message does not contain a value for this field, the field's default value will be returned.
     *
     * [fieldDescriptor] can be a descriptor for an extension field that was not defined on the original message, but it
     * _MUST_ be a descriptor for fields in messages of type [M].
     */
    @ExperimentalProtoReflection
    public fun <V> getFieldValue(fieldDescriptor: FieldDescriptor<*, *, V>): V

    public abstract class Companion<M : Message, MM : MutableMessage<M>> {
        /**
         * Returns the default value for this message type. Can throw an exception if [M] is a message with `required`
         * fields, since such messages do not have a default value.
         */
        @Deprecated(
            message = "Use valueType.descriptor.defaultInstance instead",
            replaceWith = ReplaceWith("valueType.descriptor.defaultInstance"),
        )
        @get:Throws(UnsupportedOperationException::class)
        public val defaultInstance: M get() = valueType.descriptor.defaultInstance

        public abstract val valueType: MessageValueType<M, M>
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

@Suppress("UNCHECKED_CAST")
private inline val <M : Message> M.messageCompanion get() = companion as Message.Companion<M, *>

/**
 * Encode this message to a ByteArray using the protocol buffer binary encoding.
 */
@Export
public fun <M : Message> M.encodeToByteArray(): ByteArray = messageCompanion.valueType.encodeToByteArray(this)

@Export
public fun <M : Any> MessageValueType<M, *>.encodeToByteArray(message: M): ByteArray =
    BinaryMessageEncoder.allocate(binarySize(message)).also {
        it.writeMessage(message, this)
    }.toByteArray()

/**
 * Decode a binary protocol buffer message from [arr].
 */
@Export
@Throws(InvalidProtocolBufferException::class)
public fun <M : Message> Message.Companion<M, *>.decodeFromByteArray(arr: ByteArray): M =
    valueType.decodeFromByteArray(arr)

@Export
@Throws(InvalidProtocolBufferException::class)
public fun <M : Any> MessageValueType<M, *>.decodeFromByteArray(arr: ByteArray): M =
    BinaryMessageDecoder.fromByteArray(arr).readMessage(this)

@Suppress("UNCHECKED_CAST")
@Export
public operator fun <M : Message> M?.plus(other: M?): M? = this?.plus(other) as M? ?: other

@Export
public fun <M : Any> MessageValueType<M, *>.merge(m: M?, other: M?): M? = if (m != null && other != null) {
    mergeValues(m, other)
} else {
    m ?: other
}

/**
 * Returns the value of the protocol buffer field from this message that is described by [fieldDescriptor]. If this
 * message does not contain a value for this field, the field's default value will be returned.
 *
 * [fieldDescriptor] can be a descriptor for an extension field that was not defined on the original message, but it
 * _MUST_ be a descriptor for fields in messages of type [M].
 */
//@ExperimentalProtoReflection
//public fun <M : Message, V : Any> M.getFieldValue(fieldDescriptor: FieldDescriptor<M, out V>): V? {
//    return fieldDescriptor.getValue(this)
//}