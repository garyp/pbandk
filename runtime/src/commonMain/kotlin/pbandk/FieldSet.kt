package pbandk

import pbandk.internal.AtomicReference
import pbandk.internal.FieldIterator
import pbandk.internal.forEach
import pbandk.internal.types.FieldType

/**
 * A collection of protobuf field values, along with the [FieldDescriptor] for each field. Fields that contain the
 * default value for their type will not be stored in the `FieldSet` and will not be returned during iteration. In
 * other words, there is no way to differentiate between a field that is not present in the `FieldSet` and a field that
 * is set to the field type's default value.
 */
@PublicForGeneratedCode
public sealed class FieldSet<M : Any, MM : Any> {
    internal abstract val size: Int

    internal fun isEmpty() = size == 0

    internal abstract operator fun <V> get(fieldDescriptor: FieldDescriptor<M, MM, V>): V?

    public fun <V> getOrDefault(fieldDescriptor: FieldDescriptor<M, MM, V>): V {
        return get(fieldDescriptor) ?: fieldDescriptor.fieldType.defaultValue
    }

    public abstract operator fun contains(fieldDescriptor: FieldDescriptor<M, MM, *>): Boolean

    internal abstract fun iterator(): FieldIterator<M, MM>

    protected class Iterator<M : Any, MM : Any>(
        private val iterator: kotlin.collections.Iterator<Map.Entry<FieldDescriptor<M, MM, out Any?>, Any>>
    ) : FieldIterator<M, MM> {
        private var lastEntry: Map.Entry<FieldDescriptor<M, *, out Any?>, Any>? = null

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): FieldDescriptor<M, MM, out Any?> = iterator.next().also { lastEntry = it }.key

        override fun nextValue(): Any? = lastEntry?.value
    }

    private data object Empty : FieldSet<Any, Any>() {
        override val size: Int = 0

        override fun <V> get(fieldDescriptor: FieldDescriptor<Any, Any, V>) = null

        override fun contains(fieldDescriptor: FieldDescriptor<Any, Any, *>) = false

        override fun iterator() = Iterator(emptyList<Map.Entry<FieldDescriptor<Any, Any, out Any?>, Any>>().iterator())
    }

    public companion object {
        @Suppress("UNCHECKED_CAST")
        internal fun <M : Any, MM : Any> empty(): FieldSet<M, MM> = Empty as FieldSet<M, MM>
    }
}

/**
 * A collection of protobuf field values, along with the [FieldDescriptor] for each field. Fields that contain the
 * default value for their type will not be stored in the `MutableFieldSet`. In other words, there is no way to
 * differentiate between a field that is not present in the `MutableFieldSet` and a field that is set to the field
 * type's default value.
 */
@PublicForGeneratedCode
public sealed class MutableFieldSet<M : Any, MM : Any> : FieldSet<M, MM>() {
    public abstract fun <V, MV : Any> getOrCreate(fieldDescriptor: FieldDescriptor.MutableValue<M, MM, V, MV>): MV
    public abstract operator fun <V> set(fieldDescriptor: FieldDescriptor<M, MM, V>, value: V)
    public abstract fun remove(fieldDescriptor: FieldDescriptor<M, MM, *>)
    public abstract fun toFieldSet(): FieldSet<M, MM>
}

private fun <M : Any> Map.Entry<FieldDescriptor<M, *, out Any?>, Any>.isDefaultValue(): Boolean {
    @Suppress("UNCHECKED_CAST")
    return (key.fieldType as FieldType<Any?>).isDefaultValue(value)
}

// This function exists to make the type checker happy
private inline fun <V, MV : Any> FieldType.CollectionFieldType<V, MV>.newMutableValueFrom(value: V): MV {
    return newMutableCollection().also { replaceAllItems(it, value) }
}

internal class ExtensionFieldSet<M : Any, MM : Any>(
    /**
     * Assumes that the values in [fieldSet] are already the correct type for each corresponding field descriptor.
     */
    private val fieldSet: FieldSet<M, MM>,
    private val unknownFields: Map<Int, UnknownField>,
) : FieldSet<M, MM>() {
    private val extensionFieldCache: FieldSetCache<M, MM> = FieldSetCache()

    override val size: Int get() = fieldSet.size

    override fun <V> get(fieldDescriptor: FieldDescriptor<M, MM, V>): V? {
        require(fieldDescriptor.metadata.isExtension) { "Provided field descriptor does not describe an extension field" }

        var value: V? = fieldSet[fieldDescriptor]
        if (value != null) {
            // The extension value was provided when this message was constructed
            return value
        }

        value = extensionFieldCache[fieldDescriptor]
        if (value != null) {
            // If we've already decoded the value of the extension field from the unknown fields, then return the
            // cached value
            return value
        }

        return tryDecodeUnknownField(fieldDescriptor)
    }

    override operator fun contains(fieldDescriptor: FieldDescriptor<M, MM, *>): Boolean {
        require(fieldDescriptor.metadata.isExtension) { "Provided field descriptor does not describe an extension field" }
        return fieldDescriptor in fieldSet ||
                fieldDescriptor in extensionFieldCache ||
                tryDecodeUnknownField(fieldDescriptor) != null
    }

    /**
     * Returns a [FieldIterator] over all fields in this set, ordered by increasing field number.
     */
    override fun iterator(): FieldIterator<M, MM> {
        // We need to ensure that map iteration will always iterate over the fields in order of increasing field
        // number. So we store the fields in a `LinkedHashMap` (which preserves insertion order when iterating) and
        // sort them by field number before copying them into the map.
        val entries =
            ArrayList<Pair<FieldDescriptor<M, MM, out Any?>, Any>>(fieldSet.size /*+ extensionFieldCache.size*/).apply {
                fieldSet.iterator().forEach { fd, value ->
                    if (value != null) add(fd to value)
                }
                /*
                extensionFieldCache.iterator().forEach { fd, value ->
                    if (value != null) add(fd to value)
                }
                 */
            }
        val map = LinkedHashMap<FieldDescriptor<M, MM, out Any?>, Any>(entries.size).apply {
            entries.sortedBy { it.first.metadata.number }.toMap(this)
        }
        return Iterator(map.entries.iterator())
    }

    private fun <V> tryDecodeUnknownField(fieldDescriptor: FieldDescriptor<M, MM, V>): V? {
        // Try to find the extension field in the unknown fields and decode it
        val value = unknownFields[fieldDescriptor.number]?.decodeAs(fieldDescriptor)
        if (value != null) {
            // We found the field and were able to decode it. Cache a copy of the decoded value and return it.
            extensionFieldCache[fieldDescriptor] = value
            return value
        }
        return null
    }
}

/**
 * A variant of `MutableExtensionFieldSet` that can be safely used from multiple threads **for caching purposes only**.
 * Specifically, the [FieldSetCache.set] method is lossy and can silently drop updates during concurrent
 * updates. When used as a cache, the only impact of this is that the value will have to be recalculated again the next
 * time it is accessed. However, this makes this class unsuitable for any other purposes.
 */
private class FieldSetCache<M : Any, MM : Any> : FieldSet<M, MM>() {
    private val map: AtomicReference<Map<FieldDescriptor<M, MM, out Any?>, Any>> = AtomicReference(emptyMap())

    override val size: Int get() = map.get().size

    override operator fun <V> get(fieldDescriptor: FieldDescriptor<M, MM, V>): V? {
        @Suppress("UNCHECKED_CAST")
        return map.get()[fieldDescriptor] as V?
    }

    override fun iterator(): FieldIterator<M, MM> {
        return Iterator(map.get().iterator())
    }

    override operator fun contains(fieldDescriptor: FieldDescriptor<M, MM, *>): Boolean =
        map.get().contains(fieldDescriptor)

    operator fun <V> set(fieldDescriptor: FieldDescriptor<M, MM, V>, value: V) {
        if (value == null || fieldDescriptor.fieldType.isDefaultValue(value)) {
            map.set(map.get() - fieldDescriptor)
        } else {
            map.set(map.get() + (fieldDescriptor to value))
        }
    }
}

internal class MutableExtensionFieldSet<M : Any, MM : Any>(
    map: Map<FieldDescriptor<M, MM, out Any?>, Any> = emptyMap(),
) : MutableFieldSet<M, MM>() {
    private val map: MutableMap<FieldDescriptor<M, MM, out Any?>, Any> = map.toMutableMap()

    override val size: Int get() = map.size

    override fun <V> get(fieldDescriptor: FieldDescriptor<M, MM, V>): V? {
        require(fieldDescriptor.metadata.isExtension) { "Provided field descriptor does not describe an extension field" }
        @Suppress("UNCHECKED_CAST")
        return map[fieldDescriptor] as V?
    }

    override fun contains(fieldDescriptor: FieldDescriptor<M, MM, *>): Boolean {
        require(fieldDescriptor.metadata.isExtension) { "Provided field descriptor does not describe an extension field" }
        return fieldDescriptor in map
    }

    override fun iterator(): FieldIterator<M, MM> {
        return Iterator(map.entries.iterator())
    }

    override fun <V, MV : Any> getOrCreate(fieldDescriptor: FieldDescriptor.MutableValue<M, MM, V, MV>): MV {
        require(fieldDescriptor.metadata.isExtension) { "Provided field descriptor does not describe an extension field" }

        val value = map[fieldDescriptor]
        if (value != null) {
            @Suppress("UNCHECKED_CAST")
            return value as MV
        }

        val newValue = fieldDescriptor.fieldType.newMutableCollection()
        map[fieldDescriptor] = newValue
        return newValue
    }

    override operator fun <V> set(fieldDescriptor: FieldDescriptor<M, MM, V>, value: V) {
        require(fieldDescriptor.metadata.isExtension) { "Provided field descriptor does not describe an extension field" }

        if (value == null || fieldDescriptor.fieldType.isDefaultValue(value)) {
            map.remove(fieldDescriptor)
            return
        }

        map[fieldDescriptor] = if (fieldDescriptor is FieldDescriptor.MutableValue<M, MM, V, *>) {
            fieldDescriptor.fieldType.newMutableValueFrom(value)
        } else {
            value
        }
    }

    override fun remove(fieldDescriptor: FieldDescriptor<M, MM, *>) {
        require(fieldDescriptor.metadata.isExtension) { "Provided field descriptor does not describe an extension field" }
        map.remove(fieldDescriptor)
    }

    override fun toFieldSet(): FieldSet<M, MM> {
        // TODO: make MutableMessage.toMessage() more efficient when converting the extension fields
        return if (map.isEmpty()) empty() else MutableExtensionFieldSet(map.filterNot { it.isDefaultValue() })
    }
}