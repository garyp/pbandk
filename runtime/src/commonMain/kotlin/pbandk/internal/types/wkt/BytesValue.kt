package pbandk.internal.types.wkt

import pbandk.ByteArr
import pbandk.wkt.BytesValue
import pbandk.wkt.MutableBytesValue

internal object BytesValue : WktWrapperValueType<ByteArr, BytesValue, MutableBytesValue>(
    wrapperFieldDescriptor = BytesValue.FieldDescriptors.value,
    wrappedValueType = pbandk.internal.types.primitive.Bytes,
)