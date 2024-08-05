package pbandk.internal

import pbandk.FieldDescriptor

internal interface FieldIterator<M : Any, MM : Any> : Iterator<FieldDescriptor<M, MM, out Any?>> {
    fun nextValue(): Any?
}

internal inline fun <M : Any, MM : Any> FieldIterator<M, MM>.forEach(operation: (FieldDescriptor<M, MM, out Any?>, Any?) -> Unit) {
    while (hasNext()) {
        val fd = next()
        val value = nextValue()
        operation(fd, value)
    }
}