package pbandk

import pbandk.gen.GeneratedOneOf
import pbandk.wkt.OneofOptions
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1

public class OneofDescriptor<M : Any, MM : Any, O : Message.OneOf<*>> private constructor(
    getMessageDescriptor: () -> MessageDescriptor<M, MM>,
    @ExperimentalProtoReflection
    public val name: String,
    internal val getValue: (M) -> O?,
    internal val setValue: (MM, O?) -> Unit,
    @ExperimentalProtoReflection
    public val fields: FieldDescriptorSet<M, MM>,
    // TODO: make this public once we're actually populating it correctly in the CodeGenerator
    internal val options: OneofOptions = OneofOptions.defaultInstance
) {
    // At the time that the [OneofDescriptor] constructor is called, the parent [MessageDescriptor] has not been
    // constructed yet. This is because this [OneofDescriptor] is one of the parameters that will be passed to the
    // [MessageDescriptor] constructor. To avoid the circular dependency, this property is declared lazy.
    internal val messageDescriptor: MessageDescriptor<M, MM> by lazy(LazyThreadSafetyMode.PUBLICATION) { getMessageDescriptor() }

    internal fun mergeValues(message: M, otherMessage: M, destination: MM) {
        val otherValue = getValue(otherMessage) ?: return
        val value = getValue(message)

        if (value == null || value::class != otherValue::class) {
            setValue(destination, otherValue)
        } else {
            @Suppress("UNCHECKED_CAST")
            val fd = (value as GeneratedOneOf<M, *>).fieldDescriptor
            fd.mergeValues(message, otherMessage, destination)
        }
    }

    public companion object {
        @PublicForGeneratedCode
        @Suppress("UNCHECKED_CAST")
        public fun <M : Message, MM : MutableMessage<M>, O : Message.OneOf<*>> of(
            messageDescriptor: KProperty0<MessageDescriptor<M, MM>>,
            name: String,
            value: KProperty1<M, O?>,
            mutableValue: KMutableProperty1<MM, O?>,
            fields: Collection<FieldDescriptor<M, MM, *>>,
            options: OneofOptions = OneofOptions.defaultInstance
        ): OneofDescriptor<M, MM, O> = OneofDescriptor(
            getMessageDescriptor = messageDescriptor::get,
            name = name,
            getValue = value::get,
            setValue = mutableValue::set as (MutableMessage<M>, O?) -> Unit,
            fields = FieldDescriptorSet(fields),
            options = options
        )
    }
}