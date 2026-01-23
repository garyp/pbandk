package pbandk.internal.nieuw

public sealed class FieldSchema<M : Any, MM : Any, V> protected constructor(
//    internal val messageSchema: GeneratedMessageSchema<M, MM>,
) : Comparable<FieldSchema<M, MM, *>> {
    internal abstract val descriptor: FieldDescriptor
    //    internal abstract val fieldType: FieldType<V>

    /**
     * FieldDescriptors are sorted by their field number.
     */
    override fun compareTo(other: FieldSchema<M, MM, *>): Int {
//        require(messageSchema == other.messageSchema) {
//            "Only FieldSchemas of the same message can be compared"
//        }
        return descriptor.number - other.descriptor.number
    }

    internal abstract fun getValue(message: M): V

    internal abstract fun setValue(message: MM, value: V)

    internal abstract fun visitField(message: M, visitor: ProtoFieldVisitor)

    internal abstract fun decodeField(destinationMessage: MM, decoder: ProtoDecoder)

    internal abstract fun merge(message: M, other: M, destinationMessage: MM)

    internal sealed class SingleValue<M : Any, MM : Any, V>(
//        messageSchema: GeneratedMessageSchema<M, MM>,
    ) : FieldSchema<M, MM, V>() {
        abstract val valueType: ValueType<V & Any>
    }

    internal open class ExplicitPresence<M : Any, MM : Any, V : Any>(
//        messageSchema: MessageSchema<M>,
        override val descriptor: FieldDescriptor.Standard,
        override val valueType: ValueType<V>,
        private val valueFn: (M) -> V?,
        private val setValueFn: (MM, V?) -> Unit,
    ) : SingleValue<M, MM, V?>() {
        override fun getValue(message: M): V? {
            return valueFn(message)
        }

        override fun setValue(message: MM, value: V?) {
            setValueFn(message, value)
        }

        override fun visitField(message: M, visitor: ProtoFieldVisitor) {
            val value = getValue(message)
            if (value != null) {
                visitor.visitField(descriptor, valueType, value)
            }
        }

        override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
            val value = decoder.decodeValue(descriptor, valueType)
            setValue(destinationMessage, value)
        }

        override fun merge(message: M, other: M, destinationMessage: MM) {
            val value = getValue(other) ?: getValue(message)
            if (value != null) {
                setValue(destinationMessage, value)
            }
        }
    }

    internal class Message<M : Any, MM : Any, V : Any>(
        descriptor: FieldDescriptor.Standard,
        private val schema: MessageSchema<V>,
        valueFn: (M) -> V?,
        setValueFn: (MM, V?) -> Unit,
        private val mutableValueFn: (MM) -> V?,
    ) : ExplicitPresence<M, MM, V>(descriptor, schema, valueFn, setValueFn) {
        override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
            val value = decoder.decodeValue(descriptor, valueType)
            val originalValue = mutableValueFn(destinationMessage)
            if (originalValue == null) {
                setValue(destinationMessage, value)
            } else {
                setValue(destinationMessage, schema.merge(originalValue, value))
            }
        }

        override fun merge(message: M, other: M, destinationMessage: MM) {
            val messageValue = getValue(message)
            val otherValue = getValue(other)
            val newValue = if (messageValue != null && otherValue != null) {
                schema.merge(messageValue, otherValue)
            } else {
                otherValue ?: messageValue
            }
            if (newValue != null) {
                setValue(destinationMessage, newValue)
            }
        }
    }

    internal class ImplicitPresence<M : Any, MM : Any, V : Any>(
//        messageSchema: GeneratedMessageSchema<M, MM>,
        override val descriptor: FieldDescriptor.Standard,
        override val valueType: ValueType<V>,
        private val valueFn: (M) -> V,
        private val setValueFn: (MM, V) -> Unit,
    ) : SingleValue<M, MM, V>() {
        override fun getValue(message: M): V {
            return valueFn(message)
        }

        override fun setValue(message: MM, value: V) {
            setValueFn(message, value)
        }

        override fun visitField(message: M, visitor: ProtoFieldVisitor) {
            val value = getValue(message)
            if (!valueType.isDefaultValue(value)) {
                visitor.visitField(descriptor, valueType, value)
            }
        }

        override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
            val value = decoder.decodeValue(descriptor, valueType)
            setValue(destinationMessage, value)
        }

        override fun merge(message: M, other: M, destinationMessage: MM) {
            setValue(destinationMessage, getValue(other))
        }
    }

    internal class Repeated<M : Any, MM : Any, V : Any>(
//        messageSchema: MessageSchema<M>,
        override val descriptor: FieldDescriptor.Standard,
        internal val valueType: ValueType<V>,
        internal val valueFn: (M) -> List<V>,
        internal val mutableValueFn: (MM) -> MutableList<V>,
    ) : FieldSchema<M, MM, List<V>>() {
        override fun getValue(message: M): List<V> {
            return valueFn(message)
        }

        override fun setValue(message: MM, value: List<V>) {
            val list = mutableValueFn(message)
            list.clear()
            list.addAll(value)
        }

        override fun visitField(message: M, visitor: ProtoFieldVisitor) {
            val value = getValue(message)
            if (value.isNotEmpty()) {
                visitor.visitRepeatedField(descriptor, valueType, value)
            }
        }

        override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
            decoder.decodeRepeatedValue(descriptor, valueType, mutableValueFn(destinationMessage))
        }

        override fun merge(message: M, other: M, destinationMessage: MM) {
            val list = mutableValueFn(destinationMessage)
            list.addAll(getValue(message))
            list.addAll(getValue(other))
        }
    }

    internal class Map<M : Any, MM : Any, K : Any, V : Any>(
//        messageSchema: MessageSchema<M, MM>,
        override val descriptor: FieldDescriptor.Standard,
        internal val keyValueType: ValueType<K>,
        internal val valueValueType: ValueType<V>,
        private val value: (M) -> kotlin.collections.Map<K, V>,
        private val mutableValueFn: (MM) -> MutableMap<K, V>,
    ) : FieldSchema<M, MM, kotlin.collections.Map<K, V>>() {
        override fun getValue(message: M): kotlin.collections.Map<K, V> {
            return value(message)
        }

        override fun setValue(message: MM, value: kotlin.collections.Map<K, V>) {
            val map = mutableValueFn(message)
            map.clear()
            map.putAll(value)
        }

        override fun visitField(message: M, visitor: ProtoFieldVisitor) {
            val value = getValue(message)
            if (value.isNotEmpty()) {
                visitor.visitMapField(descriptor, keyValueType, valueValueType, value)
            }
        }

        override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
            TODO("Not yet implemented")
        }

        override fun merge(message: M, other: M, destinationMessage: MM) {
            val map = mutableValueFn(destinationMessage)
            map.putAll(getValue(message))
            map.putAll(getValue(other))
        }
    }

    internal class Extension<M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>, V : Any>(
        override val descriptor: FieldDescriptor.Extension,
        override val valueType: ValueType<V>,
    ) : SingleValue<M, MM, V?>() {
        override fun getValue(message: M): V? {
            return message.extensionFields.getOrDefault(this)
        }

        override fun setValue(message: MM, value: V?) {
            message.extensionFields.set(this, value)
        }

        override fun visitField(message: M, visitor: ProtoFieldVisitor) {
            val value = getValue(message)
            if (value != null) {
                visitor.visitField(descriptor, valueType, value)
            }
        }

        override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
            TODO("Not yet implemented")
        }

        override fun merge(message: M, other: M, destinationMessage: MM) {
            TODO("Not yet implemented")
        }
    }

    internal class RepeatedExtension<M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>, V : Any>(
        override val descriptor: FieldDescriptor.Extension,
        internal val valueType: ValueType<V>,
    ) : FieldSchema<M, MM, List<V>>() {
        override fun getValue(message: M): List<V> {
            return message.extensionFields.getOrDefault(this)
        }

        override fun setValue(message: MM, value: List<V>) {
            val list: MutableList<V> = message.extensionFields.getOrCreate(this)
            list.clear()
            list.addAll(value)
        }

        override fun visitField(message: M, visitor: ProtoFieldVisitor) {
            val value = getValue(message)
            if (value.isNotEmpty()) {
                visitor.visitRepeatedField(descriptor, valueType, value)
            }
        }
    }
}