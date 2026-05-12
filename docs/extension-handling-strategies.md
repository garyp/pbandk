# Extension Handling with Visitor Pattern

## The Core Problem

The visitor pattern works great for **known** fields — the schema tells you the type, the visitor encodes/decodes, done. Extensions break this because:

1. During decoding, the wire gives you a field number, but you don't know if it's a regular field or an extension until you look it up
2. Even when you find the extension schema, **repeated extensions** arrive as *multiple separate wire values* that must be accumulated into one list
3. The existing code has a whole lazy-decode-from-unknown-fields machinery that the nieuw approach doesn't have

## Three Strategies

### Strategy A: Eager Decode Everything (Recommended)

Decode all known extensions during the decode loop. Unknown field numbers in extension ranges become `Binary`.

**Pros:** Simple decode loop, consistent with existing behavior, no lazy-decode caching complexity
**Cons:** Need to handle repeated extension accumulation during the loop

### Strategy B: Lazy Decode on First Access

Store all extension data as `Binary`. Decode only when `getExtension()` is called.

**Pros:** Minimal changes to decode loop, easy to implement incrementally
**Cons:** Repeated extensions require pre-allocating mutable lists, merge must decode binary first, subtle caching bugs (the existing code has a `FieldSetCache` for this exact reason)

### Strategy C: Hybrid — Eager Singular, Lazy Repeated

Decode singular extensions eagerly (we have the schema, decode once). Store repeated extensions as raw values, decode to list on first access.

**Pros:** Simplest decode loop for repeated fields
**Cons:** Inconsistent behavior between singular/repeated, merge logic gets more complex

---

## Recommended: Strategy A with Details

### 1. Schema Changes

`FieldSchema.RepeatedExtension` needs accessors (currently it has none — it's a bare schema with no way to read/write the message):

```kotlin
internal class RepeatedExtension<M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>, V : Any>(
    override val descriptor: FieldDescriptor.Extension,
    internal val valueType: ValueType<V>,
    private val mutableValueFn: (MM) -> MutableList<V>,  // NEW
) : FieldSchema<M, MM, List<V>>() {
    // ... getValue, setValue, visitField ...

    override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
        decoder.decodeRepeatedValue(descriptor, valueType, mutableValueFn(destinationMessage))
    }

    override fun merge(message: M, other: M, destinationMessage: MM) {
        val list = mutableValueFn(destinationMessage)
        list.addAll(getValue(message))
        list.addAll(getValue(other))
    }
}
```

The schema needs a unified lookup + pre-allocation:

```kotlin
internal class GeneratedMessageSchema<M : GeneratedExtendableMessage<M>, MM : MutableExtendableMessage<M>>(
    // ...
    private val extensionRanges: List<IntRange>,
    // Build a lookup that covers BOTH regular fields AND extensions
    private val fieldByNumber: Map<Int, FieldSchema<M, MM, *>>,
) {
    // Pre-allocate mutable lists for all repeated extensions
    internal fun preAllocateRepeatedExtensions(message: MM) {
        for (field in fields.values) {
            if (field is FieldSchema.RepeatedExtension<*>) {
                field.getOrCreateMutableList(message)
            }
        }
    }
}
```

### 2. Decode Loop

```kotlin
override fun decodeMessage(decoder: ProtoDecoder): M {
    return builder {
        // Step 1: pre-allocate repeated extension lists
        preAllocateRepeatedExtensions(this)

        do {
            val fieldSchema = companion.schema.lookupFieldOrExtension(decoder.fieldNumber)
                ?: handleUnknownField(decoder.fieldNumber, decoder.wireType)

            when (fieldSchema) {
                is FieldSchema.Extension<*> -> fieldSchema.decodeField(this, decoder)
                is FieldSchema.RepeatedExtension<*> -> fieldSchema.decodeField(this, decoder)
                else -> fieldSchema.decodeField(this, decoder)
            }
        } while (decoder.fieldNumber != 0)
    }
}
```

### 3. Repeated Extension Accumulation

The key trick — when the decoder encounters a repeated extension field number, it needs to append to the pre-allocated list, not create a new one:

```kotlin
// In decode loop, for repeated extensions:
is FieldSchema.RepeatedExtension<*> -> {
    val value = decoder.decodeValue(fieldSchema.descriptor, fieldSchema.valueType)
    fieldSchema.mutableValueFn(destinationMessage).add(value)
}
```

This works because `mutableValueFn` was called during pre-allocation, so the list already exists and is stored inside the message instance.

### 4. Unknown Field Routing

```kotlin
internal fun MessageSchema<*>.lookupFieldOrExtension(fieldNumber: Int): FieldSchema<*, *, *>? {
    // Check known fields first
    fieldByNumber[fieldNumber]?.let { return it }
    // Check if it's in an extension range
    if (this is ExtendableMessageSchema && extensionRanges.any { fieldNumber in it }) {
        return null  // Will be stored as Binary
    }
    return null  // Truly unknown
}
```

Both `null` cases go to unknown fields, but `ExtendableMessageSchema` distinguishes them:
- Extension range → store as `ExtensionValue.Binary`
- Not extension range → store as `UnknownField`

### 5. Merge for Extensions

The merge logic in `ExtendableMessageSchema.merge()` is mostly there but needs the missing pieces:

```kotlin
// For singular extensions with matching schemas:
if (msgSchema.valueType == otherSchema.valueType) {
    when {
        msgSchema.valueType is MessageSchema<*> ->
            ExtensionValue.SingularDecoded(msgSchema,
                msgSchema.valueType.merge(msgValue, otherValue))
        else ->
            ExtensionValue.SingularDecoded(msgSchema, otherValue)  // overwrite
    }
} else {
    // Different schemas → binary merge
    binaryMerge(msgField, otherField)
}

// For repeated extensions:
ExtensionValue.RepeatedDecoded(msgSchema, msgValue + otherValue)
```

The `Binary` → `SingularDecoded` conversion happens on-demand during merge:

```kotlin
private fun decodeBinaryIf_needed(field: ExtensionValue, schema: FieldSchema.Extension<*, *, *>): ExtensionValue.SingularDecoded<*, *> {
    return when (field) {
        is ExtensionValue.SingularDecoded -> field
        is ExtensionValue.Binary -> {
            // Decode from raw WireValue list using the schema's ValueType
            // This is the tricky part — need to reconstruct from binary
            TODO("decode from WireValue list")
        }
        else -> error("Cannot merge ${field::class}")
    }
}
```

### 6. The Hard Part: Decoding Binary Back to Values

When merging, you might have `ExtensionValue.Binary` (decoded from unknown fields) that needs to be merged with a known `SingularDecoded`. You need to decode the binary back:

```kotlin
// This needs to reconstruct from WireValue list using the schema's ValueType
// For singular: decode one value
// For repeated: decode multiple values into a list
```

The existing code does this via `UnknownField.decodeAs()` which uses `FieldType.decodeFromBinary()` + `BinaryFieldValueDecoder.forWireValue()`. Nieuw needs the equivalent:

```kotlin
internal fun decodeFromWireValues(
    valueType: ValueType<*>,
    values: List<WireValue>,
    isRepeated: Boolean,
    dest: MutableList<*>? = null
): Any {
    return if (isRepeated) {
        val list = dest ?: mutableListOf<Any>()
        values.forEach { wv ->
            list.add(decodeSingle(valueType, wv))
        }
        list
    } else {
        decodeSingle(valueType, values.first())
    }
}
```

---

## Comparison Table

| Aspect | Strategy A (Eager) | Strategy B (Lazy) | Strategy C (Hybrid) |
|---|---|---|---|
| Decode loop complexity | Medium | Low | Low |
| Repeated extension handling | Pre-allocate + append | Pre-allocate + lazy decode | Pre-allocate + lazy decode |
| Merge complexity | Medium (decode Binary on demand) | High (decode everything on demand) | Medium |
| `getFieldValue()` complexity | Medium (decode Binary on demand) | Low (already decoded) | Medium |
| Cache invalidation bugs | None | Likely | Likely |
| Performance | Best (decode once) | Worst (decode N times) | Mixed |
| Implementation effort | Medium | Low | Medium |

---

## What I'd Actually Do

**Start with Strategy A** — eager decode everything. The pre-allocation for repeated extensions is a one-time cost, and having all extensions decoded upfront makes merge, `getFieldValue()`, and serialization all straightforward.

The one area where lazy might be worth considering is if you're decoding messages with **thousands of unknown extension fields** — but that's an unusual case, and the `Binary` fallback already handles it.

The biggest single piece of work is the `decodeFromWireValues()` function — converting a `List<WireValue>` back into a Kotlin value using a `ValueType`. This is essentially re-implementing `UnknownField.decodeAs()` in nieuw's terms. Once that exists, the rest of extension handling falls into place fairly mechanically.
