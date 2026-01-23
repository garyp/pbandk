package pbandk.internal.types

import pbandk.FieldMetadata
import pbandk.InvalidProtocolBufferException
import pbandk.Message
import pbandk.MessageMetadata
import pbandk.binary.BinaryFieldValueDecoder
import pbandk.binary.WireType
import pbandk.gen.ListField
import pbandk.gen.MutableListField
import pbandk.gen.UnrecognizedEnumValue
import pbandk.internal.binary.BinaryFieldEncoder
import pbandk.internal.binary.Tag
import pbandk.binary.WireValue
import pbandk.binary.tryDecodeField
import pbandk.internal.nieuw.ProtoFieldVisitor
import pbandk.internal.json.JsonFieldEncoder
import pbandk.internal.types.primitive.Enum
import pbandk.json.JsonConfig
import pbandk.json.JsonConfig.UnrecognizedEnumValueBehavior.*
import pbandk.json.JsonFieldValueDecoder
import pbandk.types.ValueType
import pbandk.wkt.NullValue

internal sealed class FieldType<KotlinType> {
    abstract fun mergeValues(metadata: FieldMetadata, currentValue: KotlinType, newValue: KotlinType): KotlinType
    abstract fun isDefaultValue(value: KotlinType): Boolean

    /**
     * Returns the default value for this field. Can throw an exception if [KotlinType] is a [pbandk.Message] with
     * `required` fields, since such messages do not have a default value. Prefer [isDefaultValue] over [defaultValue]
     * when you only need to check if another value is the default, as this avoids the possibility of throwing an
     * exception.
     */
    @get:Throws(UnsupportedOperationException::class)
    abstract val defaultValue: KotlinType

    abstract fun visitField(metadata: FieldMetadata, value: KotlinType, visitor: ProtoFieldVisitor)

    abstract fun allowsBinaryWireType(wireType: WireType): Boolean
    abstract fun binarySize(metadata: FieldMetadata, value: KotlinType): Int
    abstract fun encodeToBinary(metadata: FieldMetadata, value: KotlinType, encoder: BinaryFieldEncoder)
    abstract fun encodeToJson(metadata: FieldMetadata, value: KotlinType, encoder: JsonFieldEncoder)

    abstract fun decodeFromBinary(metadata: FieldMetadata, decoder: BinaryFieldValueDecoder): KotlinType
    abstract fun decodeFromJson(metadata: FieldMetadata, decoder: JsonFieldValueDecoder): KotlinType

    sealed class CollectionFieldType<CollectionType, MutableCollectionType : Any> : FieldType<CollectionType>() {
        abstract fun newMutableCollection(): MutableCollectionType
        abstract fun replaceAllItems(collection: MutableCollectionType, newItems: CollectionType)
        abstract fun fromMutableCollection(mutableCollection: MutableCollectionType): CollectionType

        abstract fun mergeItems(
            metadata: FieldMetadata,
            collection: MutableCollectionType,
            newItems: CollectionType
        )

        abstract fun decodeFromBinary(
            metadata: FieldMetadata,
            decoder: BinaryFieldValueDecoder,
            mutableCollection: MutableCollectionType,
        )
    }

    class Required<T : Any>(internal val valueType: ValueType<T>) : FieldType<T>() {
        override fun mergeValues(metadata: FieldMetadata, currentValue: T, newValue: T) =
            valueType.mergeValues(currentValue, newValue)

        override fun isDefaultValue(value: T) = valueType.isDefaultValue(value)

        override val defaultValue: T get() = valueType.defaultValue

        override fun visitField(metadata: FieldMetadata, value: T, visitor: ProtoFieldVisitor) {
            TODO("Not yet implemented")
        }

        override fun allowsBinaryWireType(wireType: WireType): Boolean {
            return valueType.binaryWireType == wireType
        }

        override fun binarySize(metadata: FieldMetadata, value: T): Int {
            if (value is Message.Enum && value.value == null) {
                throw InvalidProtocolBufferException.unrecognizedStringInRequiredEnumField()
            }
            return valueType.binarySizeWithTags(value, metadata.number)
        }

        override fun encodeToBinary(metadata: FieldMetadata, value: T, encoder: BinaryFieldEncoder) {
            if (value is Message.Enum && value.value == null) {
                throw InvalidProtocolBufferException.unrecognizedStringInRequiredEnumField()
            }
            encoder.encodeField(metadata.number, valueType.binaryWireType) { valueEncoder ->
                valueType.encodeToBinary(value, valueEncoder)
            }
        }

        override fun decodeFromBinary(metadata: FieldMetadata, decoder: BinaryFieldValueDecoder): T {
            return valueType.decodeFromBinary(decoder)
        }

        override fun encodeToJson(metadata: FieldMetadata, value: T, encoder: JsonFieldEncoder) {
            encoder.encodeField(encoder.jsonConfig.getFieldJsonName(metadata)) { valueEncoder ->
                valueType.encodeToJson(value, valueEncoder)
            }
        }

        override fun decodeFromJson(metadata: FieldMetadata, decoder: JsonFieldValueDecoder): T {
            if (decoder is JsonFieldValueDecoder.Null &&
                (valueType as? MessageValueType<*, *>)?.descriptor != pbandk.wkt.Value.descriptor &&
                (valueType as? Enum)?.enumCompanion != NullValue
            ) {
                throw InvalidProtocolBufferException("A 'required' field cannot be null since it does not have a default value")
            }

            val value = valueType.decodeFromJson(decoder)
            if (value is UnrecognizedEnumValue<*> && value.shouldTreatAsUnknownField(decoder.jsonConfig)) {
                throw InvalidProtocolBufferException.unrecognizedEnumValue(metadata.name)
            }
            return value
        }
    }

    class Optional<T : Any>(internal val valueType: ValueType<T>) : FieldType<T?>() {
        override fun mergeValues(metadata: FieldMetadata, currentValue: T?, newValue: T?) = when {
            currentValue == null -> newValue
            newValue == null -> currentValue
            else -> valueType.mergeValues(currentValue, newValue)
        }

        override fun isDefaultValue(value: T?) = value == null

        override val defaultValue: T? get() = null

        override fun visitField(metadata: FieldMetadata, value: T?, visitor: ProtoFieldVisitor) {
            when {
                value == null -> return
                value is Message.Enum && value.value == null -> return
                else -> {
                    valueType.visitValue(, value, visitor)
                }

                else -> encoder.encodeField(metadata.number, valueType.binaryWireType) { valueEncoder ->
                    valueType.encodeToBinary(value, valueEncoder)
                }
            }
        }

        override fun allowsBinaryWireType(wireType: WireType): Boolean {
            return valueType.binaryWireType == wireType
        }

        override fun binarySize(metadata: FieldMetadata, value: T?) = when {
            value == null -> 0
            value is Message.Enum && value.value == null -> 0
            else -> valueType.binarySizeWithTags(value, metadata.number)
        }

        override fun encodeToBinary(metadata: FieldMetadata, value: T?, encoder: BinaryFieldEncoder) {
            when {
                value == null -> return
                value is Message.Enum && value.value == null -> return
                else -> encoder.encodeField(metadata.number, valueType.binaryWireType) { valueEncoder ->
                    valueType.encodeToBinary(value, valueEncoder)
                }
            }
        }

        override fun decodeFromBinary(metadata: FieldMetadata, decoder: BinaryFieldValueDecoder): T {
            return valueType.decodeFromBinary(decoder)
        }

        override fun encodeToJson(metadata: FieldMetadata, value: T?, encoder: JsonFieldEncoder) {
            if (value == null) return

            encoder.encodeField(encoder.jsonConfig.getFieldJsonName(metadata)) { valueEncoder ->
                valueType.encodeToJson(value, valueEncoder)
            }
        }

        override fun decodeFromJson(metadata: FieldMetadata, decoder: JsonFieldValueDecoder): T? {
            if (decoder is JsonFieldValueDecoder.Null &&
                (valueType as? MessageValueType<*, *>)?.descriptor != pbandk.wkt.Value.descriptor &&
                (valueType as? Enum)?.enumCompanion != NullValue
            ) {
                decoder.consumeNull()
                return null
            }

            val value = valueType.decodeFromJson(decoder)
            if (value is UnrecognizedEnumValue<*> && value.shouldTreatAsUnknownField(decoder.jsonConfig)) {
                if (decoder.jsonConfig.ignoreUnknownFieldsInInput) {
                    return null
                } else {
                    throw InvalidProtocolBufferException.unrecognizedEnumValue(metadata.name)
                }
            }
            return value
        }
    }

    class Singular<T : Any>(internal val valueType: ValueType<T>) : FieldType<T>() {
        override fun mergeValues(metadata: FieldMetadata, currentValue: T, newValue: T) =
            valueType.mergeValues(currentValue, newValue)

        override fun isDefaultValue(value: T) = valueType.isDefaultValue(value)

        override val defaultValue: T get() = valueType.defaultValue

        override fun allowsBinaryWireType(wireType: WireType): Boolean {
            return valueType.binaryWireType == wireType
        }

        override fun binarySize(metadata: FieldMetadata, value: T) = when {
            valueType.isDefaultValue(value) -> 0
            value is Message.Enum && value.value == null -> 0
            else -> valueType.binarySizeWithTags(value, metadata.number)
        }

        override fun encodeToBinary(metadata: FieldMetadata, value: T, encoder: BinaryFieldEncoder) {
            when {
                valueType.isDefaultValue(value) -> return
                value is Message.Enum && value.value == null -> return
                else -> encoder.encodeField(metadata.number, valueType.binaryWireType) { valueEncoder ->
                    valueType.encodeToBinary(value, valueEncoder)
                }
            }

        }

        override fun decodeFromBinary(metadata: FieldMetadata, decoder: BinaryFieldValueDecoder): T {
            return valueType.decodeFromBinary(decoder)
        }

        override fun encodeToJson(metadata: FieldMetadata, value: T, encoder: JsonFieldEncoder) {
            if (!encoder.jsonConfig.outputDefaultValues && valueType.isDefaultValue(value)) return

            encoder.encodeField(encoder.jsonConfig.getFieldJsonName(metadata)) { valueEncoder ->
                @Suppress("DEPRECATION")
                if (encoder.jsonConfig.outputDefaultValues &&
                    encoder.jsonConfig.outputDefaultStringsAsNull &&
                    value is String && value.isEmpty()
                ) {
                    valueEncoder.encodeNull()
                } else {
                    valueType.encodeToJson(value, valueEncoder)
                }
            }
        }

        override fun decodeFromJson(metadata: FieldMetadata, decoder: JsonFieldValueDecoder): T {
            if (decoder is JsonFieldValueDecoder.Null &&
                (valueType as? MessageValueType<*, *>)?.descriptor != pbandk.wkt.Value.descriptor &&
                (valueType as? Enum)?.enumCompanion != NullValue
            ) {
                decoder.consumeNull()
                return valueType.defaultValue
            }

            val value = valueType.decodeFromJson(decoder)
            if (value is UnrecognizedEnumValue<*> && value.shouldTreatAsUnknownField(decoder.jsonConfig)) {
                if (decoder.jsonConfig.ignoreUnknownFieldsInInput) {
                    return valueType.defaultValue
                } else {
                    throw InvalidProtocolBufferException.unrecognizedEnumValue(metadata.name)
                }
            }
            return value
        }
    }

    class Repeated<T : Any>(
        internal val valueType: ValueType<T>,
        private val packed: Boolean,
    ) : CollectionFieldType<List<T>, MutableList<T>>() {
        private val List<T>.protoSize: Int
            get() = (this as? ListField<T>)?.protoSize ?: this.sumOf(valueType::binarySize)

        override fun mergeValues(metadata: FieldMetadata, currentValue: List<T>, newValue: List<T>): List<T> {
            return currentValue + newValue
        }

        override fun mergeItems(metadata: FieldMetadata, collection: MutableList<T>, newItems: List<T>) {
            collection.addAll(newItems)
        }

        override fun isDefaultValue(value: List<T>) = value.isEmpty()

        override val defaultValue: List<T> get() = ListField.empty()

        override fun newMutableCollection(): MutableListField<T> = MutableListField(valueType)

        override fun replaceAllItems(collection: MutableList<T>, newItems: List<T>) {
            collection.clear()
            collection.addAll(newItems)
        }

        override fun fromMutableCollection(mutableCollection: MutableList<T>): List<T> =
            if (mutableCollection is MutableListField<T>) {
                mutableCollection.toListField()
            } else {
                ListField(valueType, mutableCollection)
            }

        override fun allowsBinaryWireType(wireType: WireType): Boolean {
            return (valueType.binaryWireType == wireType
                    // If the value is packable, then the wire type can be LENGTH_DELIMITED
                    || (wireType == WireType.LENGTH_DELIMITED && valueType.binaryWireType != WireType.LENGTH_DELIMITED))
        }

        override fun binarySize(metadata: FieldMetadata, value: List<T>) = when {
            value.isEmpty() -> 0
            packed -> Tag.size(metadata.number) + WireValue.Len.sizeWithLenPrefix(value.protoSize)
            else -> value.protoSize + (
                    Tag.size(metadata.number)
                            * if (valueType.hasEndTag) 2 else 1
                            * value.count { !(it is Message.Enum && it.value == null) }
                    )
        }

        override fun encodeToBinary(
            metadata: FieldMetadata,
            value: List<T>,
            encoder: BinaryFieldEncoder
        ) {
            if (value.isEmpty()) return

            if (packed) {
                // TODO: check if skipping enums in the list with unrecognized string values is the correct behavior
                encoder.encodeField(metadata.number, WireType.LENGTH_DELIMITED) { valueEncoder ->
                    valueEncoder.encodeLenPrefix(value.protoSize.toUInt())
                    value.forEach { valueType.encodeToBinary(it, valueEncoder) }
                }
            } else {
                value.forEach {
                    // TODO: check if skipping enums in the list with unrecognized string values is the correct behavior
                    if (it is Message.Enum && it.value == null) return@forEach
                    encoder.encodeField(metadata.number, valueType.binaryWireType) { valueEncoder ->
                        valueType.encodeToBinary(it, valueEncoder)
                    }
                }
            }
        }

        override fun decodeFromBinary(
            metadata: FieldMetadata,
            decoder: BinaryFieldValueDecoder,
            mutableCollection: MutableList<T>,
        ) {
            // Check if the field is "packed" (multiple values from the repeated list encoded into a single field). Only
            // repeated values that don't use [WireType.LENGTH_DELIMITED] can be packed. If the value uses
            // [WireType.LENGTH_DELIMITED], then this field can only represent a single value from the repeated list.
            if (decoder is BinaryFieldValueDecoder.Len && valueType.binaryWireType != WireType.LENGTH_DELIMITED) {
                decoder.decodePackedValues(valueType.binaryWireType) {
                    mutableCollection.add(valueType.decodeFromBinary(it))
                }
            } else {
                mutableCollection.add(valueType.decodeFromBinary(decoder))
            }
        }

        override fun decodeFromBinary(metadata: FieldMetadata, decoder: BinaryFieldValueDecoder): List<T> {
            return newMutableCollection().apply {
                decodeFromBinary(metadata, decoder, this)
            }.toListField()
        }

        override fun encodeToJson(metadata: FieldMetadata, value: List<T>, encoder: JsonFieldEncoder) {
            if (!encoder.jsonConfig.outputDefaultValues && value.isEmpty()) return

            encoder.encodeField(encoder.jsonConfig.getFieldJsonName(metadata)) { valueEncoder ->
                valueEncoder.encodeArrayWithValues(value) { arrayValue, arrayValueEncoder ->
                    valueType.encodeToJson(arrayValue, arrayValueEncoder)
                }
            }
        }

        override fun decodeFromJson(metadata: FieldMetadata, decoder: JsonFieldValueDecoder): List<T> = when (decoder) {
            is JsonFieldValueDecoder.Null -> {
                decoder.consumeNull()
                emptyList()
            }

            is JsonFieldValueDecoder.Array -> newMutableCollection().apply {
                decoder.forEachValue { arrayValueDecoder ->
                    if (arrayValueDecoder is JsonFieldValueDecoder.Null &&
                        (valueType as? MessageValueType<*, *>)?.descriptor != pbandk.wkt.Value.descriptor &&
                        (valueType as? Enum)?.enumCompanion != NullValue
                    ) {
                        throw InvalidProtocolBufferException("JSON repeated values must not contain nulls")
                    }

                    val value = valueType.decodeFromJson(arrayValueDecoder)
                    if (value is UnrecognizedEnumValue<*> &&
                        value.shouldTreatAsUnknownField(arrayValueDecoder.jsonConfig)
                    ) {
                        if (arrayValueDecoder.jsonConfig.ignoreUnknownFieldsInInput) {
                            // According to the `IgnoreUnknownEnumStringValue` JSON conformance test, when an unknown
                            // enum value is encountered in a repeated enum, that value should be skipped. This means
                            // that round-tripping the repeated enum will cause the list to shorten.
                            return@forEachValue
                        } else {
                            throw InvalidProtocolBufferException.unrecognizedEnumValue(metadata.name)
                        }
                    }
                    add(value)
                }
            }.toListField()

            else -> throw InvalidProtocolBufferException("Unexpected JSON type for repeated field: ${decoder.wireType.name}")
        }
    }

    // Maps don't allow null keys or values according to protobuf semantics. It's not very well-defined in the specs (as
    // of September 2024 at least), but all of the official language implementations enforce this constraint. The usual
    // approach taken by the official implementations is to automatically convert null map keys or values received on
    // the wire (with binary encoding) into the key/value type's "default value". When the map value type is a message,
    // a default instance of the message is used rather than `null` (which is normally the "default value" for a message
    // type).
    //
    // In effect, this means that map keys and values have implicit presence rather than explicit presence. Even when
    // the underlying map entry message uses proto2 semantics and would be expected to have explicit presence for the
    // `key` and `value` fields because they're declared as `optional`. This can lead to some confusing behavior of the
    // `has_presence` property when using reflection on the underlying map entry message (see
    // https://github.com/protocolbuffers/protobuf/issues/16549#issuecomment-2158927904 for gory details).
    //
    // Furthermore, some implementations (e.g. protobuf-java) will throw an error when parsing JSON input that contains
    // a map with null values (and they'll never produce JSON output with null map values). So we're forced to never
    // output null map values in pbandk in order to interoperate with these implementations. See
    // https://github.com/protocolbuffers/protobuf/issues/5113#issuecomment-419532565 for some extra context.
    class Map<K : Any, V : Any>(
        mapEntryMessageMetadata: MessageMetadata,
        internal val keyValueType: ValueType<K>,
        internal val valueValueType: ValueType<V>,
    ) : CollectionFieldType<kotlin.collections.Map<K, V>, MutableMap<K, V>>() {
        private val keyFieldMetadata = FieldMetadata.Standard(
            messageMetadata = mapEntryMessageMetadata,
            name = "key",
            number = 1,
            jsonName = "key",
            isOneofMember = false,
        )
        private val valueFieldMetadata = FieldMetadata.Standard(
            messageMetadata = mapEntryMessageMetadata,
            name = "value",
            number = 2,
            jsonName = "value",
            isOneofMember = false,
        )

        // See explanation above for why this uses `Singular` rather than `Optional`
        private val keyFieldType: FieldType<K> = Singular(keyValueType)
        private val valueFieldType: FieldType<V> = Singular(valueValueType)

        override fun mergeValues(
            metadata: FieldMetadata,
            currentValue: kotlin.collections.Map<K, V>,
            newValue: kotlin.collections.Map<K, V>,
        ): kotlin.collections.Map<K, V> {
            return currentValue + newValue
        }

        override fun mergeItems(
            metadata: FieldMetadata,
            collection: MutableMap<K, V>,
            newItems: kotlin.collections.Map<K, V>
        ) {
            collection.putAll(newItems)
        }

        override fun isDefaultValue(value: kotlin.collections.Map<K, V>) = value.isEmpty()

        override val defaultValue: kotlin.collections.Map<K, V> get() = emptyMap()

        override fun newMutableCollection(): MutableMap<K, V> = mutableMapOf()

        override fun replaceAllItems(collection: MutableMap<K, V>, newItems: kotlin.collections.Map<K, V>) {
            collection.clear()
            collection.putAll(newItems)
        }

        override fun fromMutableCollection(mutableCollection: MutableMap<K, V>): kotlin.collections.Map<K, V> {
            return mutableCollection.toMap()
        }

        override fun allowsBinaryWireType(wireType: WireType): Boolean {
            return wireType == WireType.LENGTH_DELIMITED
        }

        private fun mapEntryBinarySize(key: K, value: V) = WireValue.Len.sizeWithLenPrefix(
            keyFieldType.binarySize(keyFieldMetadata, key)
                    + valueFieldType.binarySize(valueFieldMetadata, value)
        )

        override fun binarySize(metadata: FieldMetadata, value: kotlin.collections.Map<K, V>): Int {
            if (value.isEmpty()) return 0

            val tagSize = Tag.size(metadata.number)

            return value.entries.sumOf { (k, v) ->
                if (v is Message.Enum && v.value == null) {
                    0
                } else {
                    tagSize + mapEntryBinarySize(k, v)
                }
            }
        }

        override fun encodeToBinary(
            metadata: FieldMetadata,
            value: kotlin.collections.Map<K, V>,
            encoder: BinaryFieldEncoder,
        ) {
            if (value.isEmpty()) return

            value.forEach { (k, v) ->
                if (v is Message.Enum && v.value == null) return@forEach

                encoder.encodeField(metadata.number, WireType.LENGTH_DELIMITED) { valueEncoder ->
                    valueEncoder.encodeLenFields(mapEntryBinarySize(k, v)) { entryFieldEncoder ->
                        keyFieldType.encodeToBinary(keyFieldMetadata, k, entryFieldEncoder)
                        valueFieldType.encodeToBinary(valueFieldMetadata, v, entryFieldEncoder)
                    }
                }
            }
        }

        override fun decodeFromBinary(
            metadata: FieldMetadata,
            decoder: BinaryFieldValueDecoder,
            mutableCollection: MutableMap<K, V>,
        ) {
            if (decoder !is BinaryFieldValueDecoder.Len) {
                throw InvalidProtocolBufferException("Unexpected wire type for message value: ${decoder.wireType}")
            }
            decoder.decodeFields { fieldDecoder ->
                var k: K = keyFieldType.defaultValue
                var v: V = valueFieldType.defaultValue

                fieldDecoder.forEachField { fieldNumber, valueDecoder ->
                    when {
                        valueDecoder.tryDecodeField(keyFieldMetadata, keyFieldType, fieldNumber) { k = it } -> {}
                        valueDecoder.tryDecodeField(valueFieldMetadata, valueFieldType, fieldNumber) { v = it } -> {}
                        else -> valueDecoder.skipValue()
                    }
                }
                mutableCollection[k] = v
            }
        }

        override fun decodeFromBinary(
            metadata: FieldMetadata,
            decoder: BinaryFieldValueDecoder,
        ): kotlin.collections.Map<K, V> {
            return newMutableCollection().apply {
                decodeFromBinary(metadata, decoder, this)
            }.toMap()
        }

        override fun encodeToJson(
            metadata: FieldMetadata,
            value: kotlin.collections.Map<K, V>,
            encoder: JsonFieldEncoder,
        ) {
            if (!encoder.jsonConfig.outputDefaultValues && value.isEmpty()) return

            encoder.encodeField(encoder.jsonConfig.getFieldJsonName(metadata)) { valueEncoder ->
                valueEncoder.encodeObject { objectValueEncoder ->
                    value.forEach { (k, v) ->
                        objectValueEncoder.encodeField(keyValueType.encodeToJsonMapKey(k)) {
                            valueValueType.encodeToJson(v, it)
                        }
                    }
                }
            }
        }

        override fun decodeFromJson(
            metadata: FieldMetadata,
            decoder: JsonFieldValueDecoder,
        ): kotlin.collections.Map<K, V> = when (decoder) {
            is JsonFieldValueDecoder.Null -> {
                decoder.consumeNull()
                emptyMap()
            }

            is JsonFieldValueDecoder.Object -> newMutableCollection().apply {
                decoder.decodeFields { fieldDecoder ->
                    fieldDecoder.forEachField { fieldKeyDecoder, fieldValueDecoder ->
                        if (fieldValueDecoder is JsonFieldValueDecoder.Null &&
                            (valueValueType as? MessageValueType<*, *>)?.descriptor != pbandk.wkt.Value.descriptor &&
                            (valueValueType as? Enum)?.enumCompanion != NullValue
                        ) {
                            fieldValueDecoder.consumeNull()
                            throw InvalidProtocolBufferException("JSON map values must not be null")
                        }

                        val mapKey = keyValueType.decodeFromJsonMapKey(fieldKeyDecoder)
                        val mapValue = valueValueType.decodeFromJson(fieldValueDecoder)

                        if (mapValue is UnrecognizedEnumValue<*> &&
                            mapValue.shouldTreatAsUnknownField(fieldValueDecoder.jsonConfig)
                        ) {
                            if (fieldValueDecoder.jsonConfig.ignoreUnknownFieldsInInput) {
                                // According to the `IgnoreUnknownEnumStringValue` JSON conformance test, when an
                                // unknown enum value is encountered in a map that contains enum values, that map entry
                                // should be skipped. This means that round-tripping the map will cause the number of
                                // entries in the map to decrease.
                                return@forEachField
                            } else {
                                throw InvalidProtocolBufferException.unrecognizedEnumValue(metadata.name)
                            }
                        }
                        put(mapKey, mapValue)
                    }
                }
            }.toMap()

            else -> throw InvalidProtocolBufferException("Unexpected JSON type for map field: ${decoder.wireType.name}")
        }
    }

    /*
    class MapOld<K : Any, V : Any>(
        internal val keyType: ValueType<K>,
        internal val valueType: ValueType<V>,
    ) : CollectionFieldType<kotlin.collections.Map<K, V>, MutableMap<K, V>>() {
        internal val entryCompanion = MapFieldEntryCompanion(keyType, valueType)

        override fun mergeValues(
            metadata: FieldMetadata,
            currentValue: kotlin.collections.Map<K, V>,
            newValue: kotlin.collections.Map<K, V>
        ): kotlin.collections.Map<K, V> {
            return currentValue + newValue
        }

        override fun isDefaultValue(value: kotlin.collections.Map<K, V>) = value.isEmpty()

        override val defaultValue: kotlin.collections.Map<K, V> get() = MapField.empty()

        override fun newMutableCollection(): MutableMapField<K, V> = MutableMapField(entryCompanion)

        override fun replaceAllItems(collection: MutableMap<K, V>, newItems: kotlin.collections.Map<K, V>) {
            collection.clear()
            collection.putAll(newItems)
        }

        override fun fromMutableCollection(mutableCollection: MutableMap<K, V>): kotlin.collections.Map<K, V> {
            return if (mutableCollection is MutableMapField<K, V>) {
                mutableCollection.toMapField()
            } else {
                MapField(entryCompanion, mutableCollection)
            }
        }

        override fun allowsBinaryWireType(wireType: WireType): Boolean {
            return wireType == WireType.LENGTH_DELIMITED
        }

        override fun binarySize(metadata: FieldMetadata, value: kotlin.collections.Map<K, V>): Int {
            if (value.isEmpty()) return 0

            val tagSize = Tag.size(metadata.number)

            return value.entries.sumOf { entry ->
                val entryValue = entry.value
                if (entryValue is Message.Enum && entryValue.value == null) return@sumOf 0

                if (entry is MapField.Entry<*, *>) {
                    entry.protoSize
                } else {
                    val keySize = entry.key
                        .takeIf { !keyType.isDefaultValue(it) }
                        ?.let { keyType.binarySizeWithTags(it, 1) }
                        ?: 0
                    val valueSize = entry.value
                        .takeIf { !valueType.isDefaultValue(it) }
                        ?.let { valueType.binarySizeWithTags(it, 2) }
                        ?: 0
                    keySize + valueSize
                }.let { size -> WireValue.Len.sizeWithLenPrefix(size) + tagSize }
            }
        }

        override fun encodeToBinary(
            metadata: FieldMetadata,
            value: kotlin.collections.Map<K, V>,
            encoder: BinaryFieldEncoder
        ) {
            if (value.isEmpty()) return

//            value as MapField<K, V>
//            value.asMessages().forEach {
//                val entryValue = it.value
//                if (entryValue is Message.Enum && entryValue.value == null) return@forEach
//                encoder.writeField(Tag(metadata.number, WireType.LENGTH_DELIMITED)) { valueEncoder ->
//                    ValueTypes.Message.encodeToBinary(it, valueEncoder)
//                }
//            }
            // or
            // val keyTag = Tag(1, keyType.binaryWireType)
            // val valueTag = Tag(2, valueType.binaryWireType)
            value.forEach { entry ->
                val entryValue = entry.value
                if (entryValue is Message.Enum && entryValue.value == null) return@forEach

                encoder.encodeField(metadata.number, WireType.LENGTH_DELIMITED) { valueEncoder ->
                    entryCompanion.valueType.encodeToBinary(
                        entry as? MapField.Entry<K, V>
                            ?: MutableMapFieldEntry(entry.key, entry.value, entryCompanion),
                        valueEncoder
                    )
                    //                        val keySize = entry.key
                    //                            .takeIf { !keyType.isDefaultValue(it) }
                    //                            ?.let { Tag.size(1) + keyType.binarySize(it) }
                    //                            ?: 0
                    //                        val valueSize = entry.value
                    //                            .takeIf { !valueType.isDefaultValue(it) }
                    //                            ?.let { Tag.size(2) + valueType.binarySize(it) }
                    //                            ?: 0
                    //                        valueEncoder.encodeLenFields(keySize + valueSize) { fieldEncoder ->
                    //                            if (!keyType.isDefaultValue(entry.key)) {
                    //                                fieldEncoder.encodeField(keyTag) { keyType.encodeToBinary(entry.key, it) }
                    //                            }
                    //                            if (!valueType.isDefaultValue(entry.value)) {
                    //                                fieldEncoder.encodeField(valueTag) { valueType.encodeToBinary(entry.value, it) }
                    //                            }
                    //                        }
                }
            }
        }

        override fun decodeFromBinary(
            metadata: FieldMetadata,
            decoder: BinaryFieldValueDecoder,
            mutableCollection: MutableMap<K, V>,
        ) {
            val entry = entryCompanion.valueType.decodeFromBinary(decoder)
            mutableCollection[entry.key] = entry.value
        }

        override fun decodeFromBinary(
            metadata: FieldMetadata,
            decoder: BinaryFieldValueDecoder
        ): kotlin.collections.Map<K, V> {
            return newMutableCollection().apply {
                decodeFromBinary(metadata, decoder, this)
            }.toMapField()
        }

        override fun encodeToJson(
            metadata: FieldMetadata,
            value: kotlin.collections.Map<K, V>,
            encoder: JsonFieldEncoder
        ) {
            if (!encoder.jsonConfig.outputDefaultValues && value.isEmpty()) return

            encoder.encodeField(encoder.jsonConfig.getFieldJsonName(metadata)) { valueEncoder ->
                valueEncoder.encodeObject { objectValueEncoder ->
                    value.forEach { (k, v) ->
                        objectValueEncoder.encodeField(keyType.encodeToJsonMapKey(k)) { valueType.encodeToJson(v, it) }
                    }
                }
            }
        }

        override fun decodeFromJson(
            metadata: FieldMetadata,
            decoder: JsonFieldValueDecoder,
        ): kotlin.collections.Map<K, V> = when (decoder) {
            is JsonFieldValueDecoder.Null -> {
                decoder.consumeNull()
                emptyMap()
            }

            is JsonFieldValueDecoder.Object -> newMutableCollection().apply {
                decoder.decodeFields { fieldDecoder ->
                    fieldDecoder.forEachField { fieldKeyDecoder, fieldValueDecoder ->
                        val mapKey = keyType.decodeFromJsonMapKey(fieldKeyDecoder)
                        val mapValue = if (fieldValueDecoder is JsonFieldValueDecoder.Null) {
                            fieldValueDecoder.consumeNull()
                            throw InvalidProtocolBufferException("JSON map values must not be null")
                        } else {
                            valueType.decodeFromJson(fieldValueDecoder)
                        }
                        if (mapValue is UnrecognizedEnumValue<*> &&
                            mapValue.shouldTreatAsUnknownField(fieldValueDecoder.jsonConfig)
                        ) {
                            if (fieldValueDecoder.jsonConfig.ignoreUnknownFieldsInInput) {
                                return@forEachField
                            } else {
                                throw InvalidProtocolBufferException.unrecognizedEnumValue(metadata.name)
                            }
                        }
                        put(mapKey, mapValue)
                    }
                }
            }.toMapField()

            else -> throw InvalidProtocolBufferException("Unexpected JSON type for map field: ${decoder.wireType.name}")
        }
    }
    */
}

private fun UnrecognizedEnumValue<*>.shouldTreatAsUnknownField(jsonConfig: JsonConfig): Boolean =
    when (jsonConfig.unrecognizedEnumValueBehavior) {
        TreatAsUnknownField -> true
        KeepOnlyNumericValues -> this.value == null
        KeepOnlyStringValues -> this.name == null
        Keep -> false
    }

private val ValueType<*>.hasEndTag: Boolean
    get() = this is MessageValueType<*, *> && this.binaryWireType == WireType.START_GROUP

private fun <KotlinType : Any> ValueType<KotlinType>.binarySizeWithTags(value: KotlinType, fieldNumber: Int): Int {
    return (Tag.size(fieldNumber) * if (hasEndTag) 2 else 1) + binarySize(value)
}