package pbandk.internal.nieuw

import pbandk.PublicForGeneratedCode
import pbandk.binary.WireType
import pbandk.wkt.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

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

    public fun visitValue(fieldDescriptor: FieldDescriptor, value: KotlinType, visitor: ProtoValueVisitor)
    public fun decodeValue(fieldDescriptor: FieldDescriptor, decoder: ProtoDecoder): KotlinType
}

public interface PrimitiveValueType<KotlinType : Any> : ValueType<KotlinType> {
    public val binaryWireType: WireType
}

public interface IntValueType : PrimitiveValueType<Int> {
    public fun visitIntValue(fieldDescriptor: FieldDescriptor, value: Int, visitor: ProtoValueVisitor)
    public fun decodeIntValue(fieldDescriptor: FieldDescriptor, decoder: ProtoDecoder): Int
}

public interface UIntValueType : PrimitiveValueType<UInt> {
    public fun visitUIntValue(fieldDescriptor: FieldDescriptor, value: UInt, visitor: ProtoValueVisitor)
}

public interface LongValueType : PrimitiveValueType<Long> {
    public fun visitLongValue(fieldDescriptor: FieldDescriptor, value: Long, visitor: ProtoValueVisitor)
}

public interface ULongValueType : PrimitiveValueType<ULong> {
    public fun visitULongValue(fieldDescriptor: FieldDescriptor, value: ULong, visitor: ProtoValueVisitor)
}

public interface BooleanValueType : PrimitiveValueType<Boolean> {
    public fun visitBooleanValue(fieldDescriptor: FieldDescriptor, value: Boolean, visitor: ProtoValueVisitor)
}

internal object Int32Value : IntValueType {
    override fun visitIntValue(fieldDescriptor: FieldDescriptor, value: Int, visitor: ProtoValueVisitor) {
        visitor.visitInt32Value(fieldDescriptor, value)
    }

    override fun decodeIntValue(fieldDescriptor: FieldDescriptor, decoder: ProtoDecoder): Int {
        return decoder.decodeInt32Value(fieldDescriptor)
    }

    override val binaryWireType: WireType = WireType.VARINT
    override val defaultValue: Int = 0

    override fun isDefaultValue(value: Int): Boolean = value == 0

    override fun visitValue(fieldDescriptor: FieldDescriptor, value: Int, visitor: ProtoValueVisitor) {
        visitIntValue(fieldDescriptor, value, visitor)
    }

    override fun decodeValue(fieldDescriptor: FieldDescriptor, decoder: ProtoDecoder): Int {
        return decodeIntValue(fieldDescriptor, decoder)
    }
}

internal object StringValue : ValueType<String> {
    override val defaultValue: String = ""

    override fun decodeValue(fieldDescriptor: FieldDescriptor, decoder: ProtoDecoder): String {
        TODO("Not yet implemented")
    }

    override fun visitValue(fieldDescriptor: FieldDescriptor, value: String, visitor: ProtoValueVisitor) {
        visitor.visitStringValue(fieldDescriptor, value)
    }

    override fun isDefaultValue(value: String): Boolean {
        return value.isEmpty()
    }
}

public abstract class TranslatingValueType<T : Any, ProtobufType : Any>(
    private val delegate: ValueType<ProtobufType>,
) : ValueType<T> {
    override val binaryWireType: WireType by delegate::binaryWireType

    protected abstract fun toProtobufType(value: T): ProtobufType
    protected abstract fun fromProtobufType(value: ProtobufType): T

    override fun visitValue(fieldDescriptor: FieldDescriptor, value: T, visitor: ProtoValueVisitor) {
        delegate.visitValue(fieldDescriptor, toProtobufType(value), visitor)
    }

    override fun decodeValue(fieldDescriptor: FieldDescriptor, decoder: ProtoDecoder): T {
        return fromProtobufType(delegate.decodeValue(fieldDescriptor, decoder))
    }
}

private object WktDurationToKotlinDuration : TranslatingValueType<kotlin.time.Duration, pbandk.wkt.Duration>(
    pbandk.wkt.Duration.valueType
) {
    override fun toProtobufType(value: kotlin.time.Duration): pbandk.wkt.Duration {
        return value.toComponents { seconds, nanoseconds ->
            Duration {
                this.seconds = seconds
                this.nanos = nanoseconds
            }
        }
    }

    override fun fromProtobufType(value: pbandk.wkt.Duration): kotlin.time.Duration {
        return value.seconds.seconds + value.nanos.nanoseconds
    }

    override val defaultValue: kotlin.time.Duration = kotlin.time.Duration.ZERO

    override fun isDefaultValue(value: kotlin.time.Duration): Boolean {
        return value == kotlin.time.Duration.ZERO
    }

    override fun visitValue(fieldDescriptor: FieldDescriptor, value: kotlin.time.Duration, visitor: ProtoValueVisitor) {
        value.toComponents { seconds, nanoseconds ->
            if (seconds != 0L) visitor.visitLongField(
                pbandk.wkt.Duration.FieldDescriptors.seconds.metadata,
                pbandk.wkt.Duration.FieldDescriptors.seconds.valueType,
                seconds,
            )
            ...
        }
    }

    override fun decodeValue(fieldDescriptor: FieldDescriptor, decoder: ProtoDecoder): kotlin.time.Duration {
        TODO()
    }
}

private object StringToKotlinDuration : TranslatingValueType<kotlin.time.Duration, String>(
    pbandk.internal.types.primitive.String
) {
    override fun toProtobufType(value: kotlin.time.Duration): String {
        return value.toIsoString()
    }

    override fun fromProtobufType(value: String): kotlin.time.Duration {
        return kotlin.time.Duration.parseIsoString(value)
    }
}