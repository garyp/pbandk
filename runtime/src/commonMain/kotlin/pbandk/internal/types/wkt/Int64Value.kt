package pbandk.internal.types.wkt

import pbandk.wkt.Int64Value
import pbandk.wkt.MutableInt64Value

internal object Int64Value : WktWrapperValueType<Long, Int64Value, MutableInt64Value>(
    wrapperFieldDescriptor = Int64Value.FieldDescriptors.value,
    wrappedValueType = pbandk.internal.types.primitive.Int64,
)