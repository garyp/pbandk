package pbandk.internal.types.wkt

import pbandk.wkt.BoolValue
import pbandk.wkt.MutableBoolValue

internal object BoolValue : WktWrapperValueType<Boolean, BoolValue, MutableBoolValue>(
    wrapperFieldDescriptor = BoolValue.FieldDescriptors.value,
    wrappedValueType = pbandk.internal.types.primitive.Bool,
)