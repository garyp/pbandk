package pbandk.internal.types.wkt

import pbandk.wkt.FloatValue
import pbandk.wkt.MutableFloatValue

internal object FloatValue : WktWrapperValueType<Float, FloatValue, MutableFloatValue>(
    wrapperFieldDescriptor = FloatValue.FieldDescriptors.value,
    wrappedValueType = pbandk.internal.types.primitive.Float,
)