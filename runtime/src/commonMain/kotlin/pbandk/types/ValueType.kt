package pbandk.types

import pbandk.FieldMetadata
import pbandk.PublicForGeneratedCode
import pbandk.binary.BinaryFieldValueDecoder
import pbandk.binary.BinaryFieldValueEncoder
import pbandk.binary.WireType
import pbandk.internal.ProtoDecoder
import pbandk.internal.ProtoVisitor
import pbandk.json.JsonFieldValueDecoder
import pbandk.json.JsonFieldValueEncoder

@PublicForGeneratedCode
public interface ValueType<KotlinType : Any> {
    /**
     * Returns the default value for this type. Can throw an exception if [KotlinType] is a [pbandk.Message] with
     * `required` fields, since such messages do not have a default value. Prefer [isDefaultValue] over [defaultValue]
     * when you only need to check if another value is the default, as this avoids the possibility of throwing an
     * exception.
     */
    @get:Throws(UnsupportedOperationException::class)
    public val defaultValue: KotlinType
    public fun isDefaultValue(value: KotlinType): Boolean
    public fun mergeValues(currentValue: KotlinType, newValue: KotlinType): KotlinType

    public val binaryWireType: WireType
    public fun binarySize(value: KotlinType): Int
    public fun encodeToBinary(value: KotlinType, encoder: BinaryFieldValueEncoder)
    public fun decodeFromBinary(decoder: BinaryFieldValueDecoder): KotlinType

    public fun encodeToJson(value: KotlinType, encoder: JsonFieldValueEncoder)
    public fun encodeToJsonMapKey(value: KotlinType): String
    public fun decodeFromJson(decoder: JsonFieldValueDecoder): KotlinType
    public fun decodeFromJsonMapKey(decoder: JsonFieldValueDecoder.String): KotlinType

    public fun visitValue(fieldMetadata: FieldMetadata, value: KotlinType, visitor: ProtoVisitor)
    public fun decodeValue(fieldMetadata: FieldMetadata, decoder: ProtoDecoder): KotlinType
}

public interface IntValueType : ValueType<Int> {
    public fun visitIntValue(fieldMetadata: FieldMetadata, value: Int, visitor: ProtoVisitor)
    public fun decodeIntValue(fieldMetadata: FieldMetadata, decoder: ProtoDecoder): Int
}

public interface UIntValueType : ValueType<UInt> {
    public fun visitUIntValue(fieldMetadata: FieldMetadata, value: UInt, visitor: ProtoVisitor)
}

public interface LongValueType : ValueType<Long> {
    public fun visitLongValue(fieldMetadata: FieldMetadata, value: Long, visitor: ProtoVisitor)
}

public interface ULongValueType : ValueType<ULong> {
    public fun visitULongValue(fieldMetadata: FieldMetadata, value: ULong, visitor: ProtoVisitor)
}

public interface BooleanValueType : ValueType<Boolean> {
    public fun visitBooleanValue(fieldMetadata: FieldMetadata, value: Boolean, visitor: ProtoVisitor)
}