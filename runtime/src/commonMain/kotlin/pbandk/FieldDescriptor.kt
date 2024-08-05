package pbandk

import pbandk.binary.BinaryFieldValueDecoder
import pbandk.binary.WireType
import pbandk.internal.binary.BinaryFieldEncoder
import pbandk.internal.json.JsonFieldEncoder
import pbandk.internal.types.FieldType
import pbandk.json.JsonFieldValueDecoder
import pbandk.types.ValueType
import pbandk.wkt.FeatureSet
import pbandk.wkt.FieldOptions
import pbandk.wkt.Syntax
import pbandk.wkt.orDefault
import kotlin.js.JsExport
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

public sealed class FieldMetadata(
    /** The field's unqualified name. */
    @ExperimentalProtoReflection
    public val name: String,
    internal val number: Int,
    internal val jsonName: String,
    internal val isOneofMember: Boolean,
    internal val isExtension: Boolean,
    private val _options: FieldOptions? = null,
) {
    // This has to be indirect to avoid a circular dependency when initializing the [FieldOptions]
    // class, which has its own [FieldDescriptor]s that it tries to initialize.
    @ExperimentalProtoReflection
    public val options: FieldOptions get() = _options.orDefault()

    internal val messageEncoding: MessageEncoding = when (val encoding = _options?.features?.messageEncoding) {
        null, FeatureSet.MessageEncoding.LENGTH_PREFIXED -> MessageEncoding.LENGTH_PREFIXED
        FeatureSet.MessageEncoding.DELIMITED -> MessageEncoding.DELIMITED
        else -> throw IllegalStateException("Unexpected value for features.message_encoding=$encoding on message '$name'")
    }

    /**
     * The field's fully-qualified name.
     *
     * For a regular field, this will be the fully-qualified name of the field's enclosing message combined with the
     * field's [name].
     *
     * For an extension field, the fully-qualified name describes where the extension field is defined, _not_ which
     * message it extends. In other words, the `fullName` of these two extension fields:
     *
     * ```proto
     * package foo.bar;
     * extend Baz {
     *   optional int32 usage_count = 200;
     * }
     * message Fizz {
     *   extend Baz {
     *     optional string fizz_label = 300;
     *   }
     * }
     * ```
     *
     * is `foo.bar.usage_count` and `foo.bar.Fizz.fizz_label`, respectively. `Baz`, the message being extended, is not
     * part of the extension field's name.
     */
    @ExperimentalProtoReflection
    public abstract val fullName: String

    internal class Standard(
        private val messageMetadata: MessageMetadata,
        name: String,
        number: Int,
        jsonName: String,
        isOneofMember: Boolean,
        options: FieldOptions? = null,
    ) : FieldMetadata(
        name = name,
        number = number,
        jsonName = jsonName,
        isOneofMember = isOneofMember,
        isExtension = false,
        _options = options,
    ) {
        override val fullName get() = "${messageMetadata.fullName}.${name}"
    }

    internal class Extension(
        extensionName: String,
        number: Int,
        jsonName: String,
        options: FieldOptions? = null,
    ) : FieldMetadata(
        name = extensionName.substringAfterLast('.'),
        number = number,
        jsonName = jsonName,
        isOneofMember = false,
        isExtension = true,
        _options = options,
    ) {
        override val fullName = extensionName
    }
}

// Proto2 encodes repeated fields unpacked by default, whereas proto3 encodes them packed by default as
// long as the valueType supports it. Both versions allow fields to explicitly override the default.
private fun isFieldPacked(
    fieldOptions: FieldOptions?,
    messageMetadata: MessageMetadata,
    valueType: ValueType<*>
): Boolean = when (fieldOptions?.packed) {
    true -> {
        require(valueType.binaryWireType != WireType.LENGTH_DELIMITED && valueType.binaryWireType != WireType.START_GROUP) {
            "Values with LEN and GROUP wire type cannot use the packed encoding"
        }
        true
    }

    false -> false

    null -> if (messageMetadata.syntax == Syntax.PROTO2) {
        false
    } else {
        valueType.binaryWireType != WireType.LENGTH_DELIMITED && valueType.binaryWireType != WireType.START_GROUP
    }
}

@Export
public sealed class FieldDescriptor<M : Any, MM : Any, V> private constructor(
    internal val messageDescriptor: MessageDescriptor<M, MM>,
    internal val metadata: FieldMetadata,
) : Comparable<FieldDescriptor<M, MM, *>> {
    internal abstract val fieldType: FieldType<V>

    /** The field's unqualified name. */
    @ExperimentalProtoReflection
    public val name: String get() = metadata.name

    internal val number: Int get() = metadata.number
    internal val jsonName: String get() = metadata.jsonName

    @ExperimentalProtoReflection
    public val options: FieldOptions get() = metadata.options

    // At the time that the [FieldDescriptor] constructor is called, the parent [MessageDescriptor] has not been
    // constructed yet. This is because this [FieldDescriptor] is one of the parameters that will be passed to the
    // [MessageDescriptor] constructor. To avoid the circular dependency, this property is declared lazy.
//    internal val messageDescriptor: MessageDescriptor<M, MM> by lazy(LazyThreadSafetyMode.PUBLICATION) { getMessageDescriptor() }

    /**
     * The field's fully-qualified name.
     *
     * For a regular field, this will be the fully-qualified name of the field's enclosing message combined with the
     * field's [name].
     *
     * For an extension field, the fully-qualified name describes where the extension field is defined, _not_ which
     * message it extends. In other words, the `fullName` of these two extension fields:
     *
     * ```proto
     * package foo.bar;
     * extend Baz {
     *   optional int32 usage_count = 200;
     * }
     * message Fizz {
     *   extend Baz {
     *     optional string fizz_label = 300;
     *   }
     * }
     * ```
     *
     * is `foo.bar.usage_count` and `foo.bar.Fizz.fizz_label`, respectively. `Baz`, the message being extended, is not
     * part of the extension field's name.
     */
    @ExperimentalProtoReflection
    public val fullName: String get() = metadata.fullName

    /**
     * FieldDescriptors are sorted by their field number.
     */
    override fun compareTo(other: FieldDescriptor<M, MM, *>): Int {
        require(messageDescriptor == other.messageDescriptor) {
            "Only FieldDescriptors of the same message can be compared"
        }
        return number - other.number
    }

    @JsExport.Ignore
    @PublicForGeneratedCode
    public abstract fun getValue(message: M): V

    @JsExport.Ignore
    @PublicForGeneratedCode
    public abstract fun setValue(message: MM, value: V)

    internal open fun mergeValues(message: M, otherMessage: M, destination: MM) {
        val value = getValue(message)
        val otherValue = getValue(otherMessage)
        val newValue = fieldType.mergeValues(metadata, value, otherValue)
        if (newValue !== value) {
            setValue(destination, newValue)
        }
    }

    internal fun binarySize(message: M): Int {
        val value = getValue(message)
        return fieldType.binarySize(metadata, value)
    }

    internal fun encodeToBinary(encoder: BinaryFieldEncoder, message: M) {
        val value = getValue(message)
        fieldType.encodeToBinary(metadata, value, encoder)
    }

    internal open fun decodeFromBinary(decoder: BinaryFieldValueDecoder, message: MM) {
        @Suppress("UNCHECKED_CAST")
        // TODO: can't assume that message is M now that we've changed the M and MM supertype from Message to Any
        val currentValue = getValue(message as M)
        val decodedValue = fieldType.decodeFromBinary(metadata, decoder)
        val newValue = fieldType.mergeValues(metadata, currentValue, decodedValue)
        if (newValue !== currentValue) {
            setValue(message, newValue)
        }
    }

    internal fun encodeToJson(encoder: JsonFieldEncoder, message: M) {
        val value = getValue(message)
        fieldType.encodeToJson(metadata, value, encoder)
    }

    internal open fun decodeFromJson(decoder: JsonFieldValueDecoder, message: MM) {
        setValue(message, fieldType.decodeFromJson(metadata, decoder))
    }

    public sealed class MutableValue<M : Any, MM : Any, V, MV : Any>(
        messageDescriptor: MessageDescriptor<M, MM>,
        metadata: FieldMetadata
    ) : FieldDescriptor<M, MM, V>(messageDescriptor, metadata) {
        abstract override val fieldType: FieldType.CollectionFieldType<V, MV>

        @JsExport.Ignore
        @PublicForGeneratedCode
        public abstract fun getMutableValue(message: MM): MV

        override fun mergeValues(message: M, otherMessage: M, destination: MM) {
            fieldType.mergeItems(metadata, getMutableValue(destination), getValue(otherMessage))
        }

        override fun setValue(message: MM, value: V) {
            fieldType.replaceAllItems(getMutableValue(message), value)
        }

        override fun decodeFromBinary(decoder: BinaryFieldValueDecoder, message: MM) {
            fieldType.decodeFromBinary(metadata, decoder, getMutableValue(message))
        }
    }

    internal class Required<M : Any, MM : Any, V : Any> internal constructor(
        messageDescriptor: MessageDescriptor<M, MM>,
        name: String,
        number: Int,
        jsonName: String,
        valueType: ValueType<V>,
        private val property: KProperty1<M, V>,
        private val mutableProperty: KMutableProperty1<MM, V>,
        options: FieldOptions? = null,
    ) : FieldDescriptor<M, MM, V>(
        messageDescriptor = messageDescriptor,
        metadata = FieldMetadata.Standard(
            messageMetadata = messageDescriptor.metadata,
            name = name,
            number = number,
            jsonName = jsonName,
            isOneofMember = false,
            options = options,
        )
    ) {
        override val fieldType = FieldType.Required(valueType)

        override fun getValue(message: M): V = property.get(message)

        override fun setValue(message: MM, value: V) {
            mutableProperty.set(message, value)
        }

        override fun decodeFromBinary(decoder: BinaryFieldValueDecoder, message: MM) {
            val decodedValue = fieldType.decodeFromBinary(metadata, decoder)

            // The `getValue()` might throw an exception if the field is required and this is the first instance of it
            // seen by the decoder.
            val currentValue = try {
                @Suppress("UNCHECKED_CAST")
                getValue(message as M)
            } catch (e: Exception) {
                null
            }

            if (currentValue != null) {
                val newValue = fieldType.mergeValues(metadata, currentValue, decodedValue)
                if (newValue !== currentValue) {
                    setValue(message, newValue)
                }
            } else {
                setValue(message, decodedValue)
            }
        }
    }

    internal class Optional<M : Any, MM : Any, V : Any>(
        messageDescriptor: MessageDescriptor<M, MM>,
        name: String,
        number: Int,
        jsonName: String,
        valueType: ValueType<V>,
        private val property: (M) -> V?,
        private val mutableProperty: (MM, V?) -> Unit,
        options: FieldOptions? = null,
        isOneofMember: Boolean = false,
    ) : FieldDescriptor<M, MM, V?>(
        messageDescriptor = messageDescriptor,
        metadata = FieldMetadata.Standard(
            messageMetadata = messageDescriptor.metadata,
            name = name,
            number = number,
            jsonName = jsonName,
            isOneofMember = isOneofMember,
            options = options,
        ),
    ) {
        override val fieldType = FieldType.Optional(valueType)

        override fun getValue(message: M): V? = property(message)

        override fun setValue(message: MM, value: V?) {
            mutableProperty(message, value)
        }
    }

    internal class Singular<M : Any, MM : Any, V : Any>(
        messageDescriptor: MessageDescriptor<M, MM>,
        name: String,
        number: Int,
        jsonName: String,
        private val valueType: ValueType<V>,
        private val property: (M) -> V,
        private val mutableProperty: (MM, V) -> Unit,
        options: FieldOptions? = null,
    ) : FieldDescriptor<M, MM, V>(
        messageDescriptor = messageDescriptor,
        metadata = FieldMetadata.Standard(
            messageMetadata = messageDescriptor.metadata,
            name = name,
            number = number,
            jsonName = jsonName,
            isOneofMember = false,
            options = options,
        ),
    ) {
        override val fieldType = FieldType.Singular(valueType)

        override fun getValue(message: M): V {
            return property(message)
        }

        override fun setValue(message: MM, value: V) {
            mutableProperty(message, value)
        }
    }

    internal class Repeated<M : Any, MM : Any, V : Any>(
        messageDescriptor: MessageDescriptor<M, MM>,
        name: String,
        number: Int,
        jsonName: String,
        valueType: ValueType<V>,
        private val property: KProperty1<M, List<V>>,
        private val mutableProperty: KProperty1<MM, MutableList<V>>,
        options: FieldOptions? = null,
    ) : MutableValue<M, MM, List<V>, MutableList<V>>(
        messageDescriptor = messageDescriptor,
        metadata = FieldMetadata.Standard(
            messageMetadata = messageDescriptor.metadata,
            name = name,
            number = number,
            jsonName = jsonName,
            isOneofMember = false,
            options = options,
        ),
    ) {
        override val fieldType =
            FieldType.Repeated(valueType, isFieldPacked(options, messageDescriptor.metadata, valueType))

        override fun getValue(message: M): List<V> = property.get(message)

        override fun getMutableValue(message: MM): MutableList<V> = mutableProperty.get(message)
    }

    internal class Map<M : Any, MM : Any, K : Any, V : Any>(
        messageDescriptor: MessageDescriptor<M, MM>,
        name: String,
        number: Int,
        jsonName: String,
        keyType: ValueType<K>,
        valueType: ValueType<V>,
        mapEntryMessageMetadata: MessageMetadata,
        private val property: KProperty1<M, kotlin.collections.Map<K, V>>,
        private val mutableProperty: KProperty1<MM, MutableMap<K, V>>,
        options: FieldOptions? = null,
    ) : MutableValue<M, MM, kotlin.collections.Map<K, V>, MutableMap<K, V>>(
        messageDescriptor = messageDescriptor,
        metadata = FieldMetadata.Standard(
            messageMetadata = messageDescriptor.metadata,
            name = name,
            number = number,
            jsonName = jsonName,
            isOneofMember = false,
            options = options,
        ),
    ) {
        override val fieldType = FieldType.Map(mapEntryMessageMetadata, keyType, valueType)

        override fun getValue(message: M): kotlin.collections.Map<K, V> = property.get(message)

        override fun getMutableValue(message: MM): MutableMap<K, V> = mutableProperty.get(message)
    }

    internal class Extension<M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>, V : Any>(
        messageDescriptor: MessageDescriptor<M, MM>,
        extensionName: String,
        number: Int,
        jsonName: String,
        valueType: ValueType<V>,
        options: FieldOptions? = null,
    ) : FieldDescriptor<M, MM, V?>(
        messageDescriptor = messageDescriptor,
        metadata = FieldMetadata.Extension(
            extensionName = extensionName,
            number = number,
            jsonName = jsonName,
            options = options,
        )
    ) {
        override val fieldType = FieldType.Optional(valueType)

        override fun getValue(message: M): V? = message.extensionFields.getOrDefault(this)

        override fun setValue(message: MM, value: V?) {
            message.extensionFields[this] = value
        }
    }

    internal class RepeatedExtension<M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>, V : Any>(
        messageDescriptor: MessageDescriptor<M, MM>,
        extensionName: String,
        number: Int,
        jsonName: String,
        valueType: ValueType<V>,
        options: FieldOptions? = null,
    ) : MutableValue<M, MM, List<V>, MutableList<V>>(
        messageDescriptor = messageDescriptor,
        metadata = FieldMetadata.Extension(
            extensionName = extensionName,
            number = number,
            jsonName = jsonName,
            options = options,
        )
    ) {
        override val fieldType =
            FieldType.Repeated(valueType, isFieldPacked(options, messageDescriptor.metadata, valueType))

        override fun getValue(message: M): List<V> = message.extensionFields.getOrDefault(this)

        override fun getMutableValue(message: MM): MutableList<V> = message.extensionFields.getOrCreate(this)
    }

    public companion object {
        @JsExport.Ignore
        @PublicForGeneratedCode
        public fun <M : Message, MM : MutableMessage<M>, V : Any> ofRequired(
            messageDescriptor: MessageDescriptor<M, MM>,
            name: String,
            number: Int,
            valueType: ValueType<V>,
            value: KProperty1<M, V>,
            mutableValue: KMutableProperty1<MM, V>,
            jsonName: String,
            options: FieldOptions? = null,
        ): FieldDescriptor<M, MM, V> = Required(
            messageDescriptor = messageDescriptor,
            name = name,
            number = number,
            jsonName = jsonName,
            options = options,
            valueType = valueType,
            property = value,
            mutableProperty = mutableValue,
        )

        @JsExport.Ignore
        @PublicForGeneratedCode
        public fun <M : Any, MM : Any, V : Any> ofOptional(
            messageDescriptor: MessageDescriptor<M, MM>,
            name: String,
            number: Int,
            valueType: ValueType<V>,
            value: (M) -> V?,
            mutableValue: (MM, V?) -> Unit,
            jsonName: String,
            options: FieldOptions? = null,
        ): FieldDescriptor<M, MM, V?> = Optional(
            messageDescriptor = messageDescriptor,
            name = name,
            number = number,
            jsonName = jsonName,
            options = options,
            valueType = valueType,
            property = value,
            mutableProperty = mutableValue,
        )

        @JsExport.Ignore
        @PublicForGeneratedCode
        public fun <M : Any, MM : Any, V : Any> ofSingular(
            messageDescriptor: MessageDescriptor<M, MM>,
            name: String,
            number: Int,
            valueType: ValueType<V>,
            value: (M) -> V,
            mutableValue: (MM, V) -> Unit,
            jsonName: String,
            options: FieldOptions? = null,
        ): FieldDescriptor<M, MM, V> = Singular(
            messageDescriptor = messageDescriptor,
            name = name,
            number = number,
            jsonName = jsonName,
            options = options,
            valueType = valueType,
            property = value,
            mutableProperty = mutableValue,
        )

        @JsExport.Ignore
        @PublicForGeneratedCode
        public fun <M : Message, MM : MutableMessage<M>, V : Any> ofOneof(
            messageDescriptor: MessageDescriptor<M, MM>,
            name: String,
            number: Int,
            valueType: ValueType<V>,
            value: KProperty1<M, V?>,
            mutableValue: KMutableProperty1<MM, V?>,
            jsonName: String,
            options: FieldOptions? = null,
        ): FieldDescriptor<M, MM, V?> = Optional(
            messageDescriptor = messageDescriptor,
            name = name,
            number = number,
            jsonName = jsonName,
            options = options,
            valueType = valueType,
            property = value::get,
            mutableProperty = mutableValue::set,
            isOneofMember = true,
        )

        @JsExport.Ignore
        @PublicForGeneratedCode
        public fun <M : Message, MM : MutableMessage<M>, T : Any> ofRepeated(
            messageDescriptor: MessageDescriptor<M, MM>,
            name: String,
            number: Int,
            valueType: ValueType<T>,
            value: KProperty1<M, List<T>>,
            mutableValue: KProperty1<MM, MutableList<T>>,
            jsonName: String,
            options: FieldOptions? = null,
        ): MutableValue<M, MM, List<T>, MutableList<T>> = Repeated(
            messageDescriptor = messageDescriptor,
            name = name,
            number = number,
            jsonName = jsonName,
            options = options,
            valueType = valueType,
            property = value,
            mutableProperty = mutableValue,
        )

        @JsExport.Ignore
        @PublicForGeneratedCode
        public fun <M : Message, MM : MutableMessage<M>, K : Any, V : Any> ofMap(
            messageDescriptor: MessageDescriptor<M, MM>,
            name: String,
            number: Int,
            mapEntryMessageMetadata: MessageMetadata,
            keyType: ValueType<K>,
            valueType: ValueType<V>,
            value: KProperty1<M, kotlin.collections.Map<K, V>>,
            mutableValue: KProperty1<MM, MutableMap<K, V>>,
            jsonName: String,
            options: FieldOptions? = null,
        ): MutableValue<M, MM, kotlin.collections.Map<K, V>, MutableMap<K, V>> = Map(
            messageDescriptor = messageDescriptor,
            name = name,
            number = number,
            jsonName = jsonName,
            options = options,
            mapEntryMessageMetadata = mapEntryMessageMetadata,
            keyType = keyType,
            valueType = valueType,
            property = value,
            mutableProperty = mutableValue,
        )

        @JsExport.Ignore
        @PublicForGeneratedCode
        public fun <M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>, V : Any> ofExtension(
            messageDescriptor: MessageDescriptor<M, MM>,
            fullName: String,
            number: Int,
            valueType: ValueType<V>,
            jsonName: String,
            options: FieldOptions? = null,
        ): FieldDescriptor<M, MM, V?> = Extension(
            messageDescriptor = messageDescriptor,
            extensionName = fullName,
            number = number,
            jsonName = jsonName,
            options = options,
            valueType = valueType,
        )

        @JsExport.Ignore
        @PublicForGeneratedCode
        public fun <M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>, T : Any> ofRepeatedExtension(
            messageDescriptor: MessageDescriptor<M, MM>,
            fullName: String,
            number: Int,
            valueType: ValueType<T>,
            jsonName: String,
            options: FieldOptions? = null,
        ): MutableValue<M, MM, List<T>, MutableList<T>> = RepeatedExtension(
            messageDescriptor = messageDescriptor,
            extensionName = fullName,
            number = number,
            jsonName = jsonName,
            options = options,
            valueType = valueType,
        )
    }
}