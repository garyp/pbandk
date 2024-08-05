package pbandk.internal.types.wkt

import pbandk.wkt.Int32Value
import pbandk.wkt.MutableInt32Value

internal object Int32Value : WktWrapperValueType<Int, Int32Value, MutableInt32Value>(
    wrapperFieldDescriptor = Int32Value.FieldDescriptors.value,
    wrappedValueType = pbandk.internal.types.primitive.Int32,
)