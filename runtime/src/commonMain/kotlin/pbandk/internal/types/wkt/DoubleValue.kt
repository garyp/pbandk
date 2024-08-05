package pbandk.internal.types.wkt

import pbandk.wkt.DoubleValue
import pbandk.wkt.MutableDoubleValue

internal object DoubleValue : WktWrapperValueType<Double, DoubleValue, MutableDoubleValue>(
    wrapperFieldDescriptor = DoubleValue.FieldDescriptors.value,
    wrappedValueType = pbandk.internal.types.primitive.Double,
)