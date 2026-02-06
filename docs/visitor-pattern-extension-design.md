# Extension Field Handling in the Visitor Pattern Architecture

**Author**: OpenCode Analysis  
**Date**: February 6, 2026  
**Status**: Design Proposal

## Problem Statement

Protobuf extensions don't fit naturally into the visitor pattern's static schema model because:

1. **Dynamic Discovery**: Extension fields aren't known at message compile time
2. **Lazy Decoding**: Extensions stored as unknown fields until accessed
3. **Type Ambiguity**: Multiple extensions can share the same field number with different types
4. **Optional Schema**: Extensions may be accessed without their schema being known
5. **Merge Complexity**: Must merge decoded and undecoded extensions with type safety

The current `nieuw` implementation has these methods as `TODO()`, blocking progress.

## Current Working Implementation (Non-Visitor)

The existing pbandk implementation solves this elegantly:

```kotlin
// Extensions stored in unknownFields during decode
class ExtensionFieldSet(
    private val fieldSet: FieldSet<M, MM>,        // Known extensions at construct time
    private val unknownFields: Map<Int, UnknownField>,  // Potentially undecoded extensions
) {
    private val extensionFieldCache: FieldSetCache<M, MM>
    
    fun <V> get(fieldDescriptor: FieldDescriptor<M, MM, V>): V? {
        // 1. Check if provided at construction
        fieldSet[fieldDescriptor]?.let { return it }
        
        // 2. Check if previously decoded and cached
        extensionFieldCache[fieldDescriptor]?.let { return it }
        
        // 3. Try to decode from unknown fields
        return tryDecodeUnknownField(fieldDescriptor)
    }
    
    private fun tryDecodeUnknownField(fieldDescriptor: FieldDescriptor<M, MM, V>): V? {
        val value = unknownFields[fieldDescriptor.number]?.decodeAs(fieldDescriptor)
        if (value != null) {
            extensionFieldCache[fieldDescriptor] = value
        }
        return value
    }
}

// UnknownField.decodeAs() attempts to decode using provided field descriptor
internal fun <T> UnknownField.decodeAs(fieldDescriptor: FieldDescriptor<*, *, T>): T {
    return when (val fieldType = fieldDescriptor.fieldType) {
        is FieldType.Optional<*> -> decodeAsPrimitive(fieldDescriptor)
        is FieldType.Repeated<*> -> decodeAsMutableValue(fieldDescriptor)
        // ... handle all field types
    }
}
```

**Key Insight**: Extensions use **lazy decode-on-access** with **schema provided at access time**.

## Proposed Solution: Three-Tier Extension System

Adapt the working implementation to the visitor pattern by introducing a three-tier system:

```
┌─────────────────────────────────────────────────────────────┐
│ Tier 1: Known Extensions (FieldSchema)                     │
│ - Extensions registered via extension registry              │
│ - Can be decoded immediately during message decode          │
│ - Stored as ExtensionValue.Decoded                          │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ Tier 2: Unknown Extensions (Binary/JSON)                   │
│ - Extensions in unknown fields, not in registry             │
│ - Stored as ExtensionValue.Binary or ExtensionValue.Json   │
│ - Decoded lazily when accessed with schema                  │
└─────────────────────────────────────────────────────────────┐
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ Tier 3: True Unknown Fields                                │
│ - Field number outside extension ranges                     │
│ - Stored in message.unknownFields map                       │
│ - Never decoded, preserved for forward compatibility        │
└─────────────────────────────────────────────────────────────┘
```

### Architecture Components

#### 1. Extension Registry

```kotlin
/**
 * Registry of known extension schemas. Extensions registered here will be decoded immediately
 * during message decoding. Extensions not in the registry will be stored as binary and decoded
 * lazily on first access.
 */
public interface ExtensionRegistry {
    /**
     * Register an extension schema. Returns the previous schema for this field number if any.
     */
    public fun <M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>, V : Any> register(
        schema: FieldSchema.Extension<M, MM, V>
    ): FieldSchema.Extension<M, MM, *>?
    
    /**
     * Get the schema for an extension by field number within a specific message type.
     */
    public fun <M : ExtendableMessage<M>> get(
        messageDescriptor: MessageDescriptor,
        fieldNumber: Int
    ): FieldSchema.Extension<M, *, *>?
    
    /**
     * Get all registered extensions for a message type.
     */
    public fun <M : ExtendableMessage<M>> getAll(
        messageDescriptor: MessageDescriptor
    ): Collection<FieldSchema.Extension<M, *, *>>
    
    public companion object {
        /**
         * Empty registry - all extensions decoded lazily.
         */
        public val EMPTY: ExtensionRegistry
        
        /**
         * Default registry with standard protobuf extensions.
         */
        public val DEFAULT: ExtensionRegistry
    }
}

internal class ExtensionRegistryImpl : ExtensionRegistry {
    // Map: message full name -> field number -> schema
    private val schemas = mutableMapOf<String, MutableMap<Int, FieldSchema.Extension<*, *, *>>>()
    
    override fun <M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>, V : Any> register(
        schema: FieldSchema.Extension<M, MM, V>
    ): FieldSchema.Extension<M, MM, *>? {
        val messageSchemas = schemas.getOrPut(schema.descriptor.messageDescriptor.fullName) { mutableMapOf() }
        val previous = messageSchemas.put(schema.descriptor.number, schema)
        @Suppress("UNCHECKED_CAST")
        return previous as? FieldSchema.Extension<M, MM, *>
    }
    
    override fun <M : ExtendableMessage<M>> get(
        messageDescriptor: MessageDescriptor,
        fieldNumber: Int
    ): FieldSchema.Extension<M, *, *>? {
        @Suppress("UNCHECKED_CAST")
        return schemas[messageDescriptor.fullName]?.get(fieldNumber) as? FieldSchema.Extension<M, *, *>
    }
    
    override fun <M : ExtendableMessage<M>> getAll(
        messageDescriptor: MessageDescriptor
    ): Collection<FieldSchema.Extension<M, *, *>> {
        @Suppress("UNCHECKED_CAST")
        return schemas[messageDescriptor.fullName]?.values as? Collection<FieldSchema.Extension<M, *, *>> ?: emptyList()
    }
}
```

#### 2. Enhanced ExtensionFieldSet

```kotlin
/**
 * Storage for extension field values. Supports:
 * - Decoded typed values (ExtensionValue.Decoded)
 * - Undecoded binary values (ExtensionValue.Binary)
 * - Undecoded JSON values (ExtensionValue.Json)
 * 
 * Lazy decoding: When a binary/JSON extension is accessed with a schema, attempts to decode.
 * If decode succeeds, caches the decoded value. If it fails (type mismatch), returns null.
 */
public open class ExtensionFieldSet<M : ExtendableMessage<M>> internal constructor(
    internal open val fields: Map<Int, ExtensionValue<M>> = emptyMap()
) {
    /**
     * Get extension value using provided schema. Will attempt to decode from binary/JSON if needed.
     * Returns null if:
     * - Extension not present
     * - Extension present but type doesn't match schema
     */
    public fun <V : Any> get(schema: FieldSchema.Extension<M, *, V>): V? {
        return when (val stored = fields[schema.descriptor.number]) {
            null -> null
            
            is ExtensionValue.SingularDecoded<M, *> -> {
                // Check if schemas match
                if (stored.schema.descriptor == schema.descriptor &&
                    stored.schema.valueType == schema.valueType) {
                    @Suppress("UNCHECKED_CAST")
                    stored.value as V
                } else {
                    // Schema mismatch - this field exists but with different type
                    null
                }
            }
            
            is ExtensionValue.Binary<M> -> {
                // Attempt lazy decode from binary
                tryDecodeBinary(schema, stored)
            }
            
            is ExtensionValue.Json<M> -> {
                // Attempt lazy decode from JSON
                tryDecodeJson(schema, stored)
            }
        }
    }
    
    public fun <V : Any> getOrDefault(schema: FieldSchema.Extension<M, *, V>): V? {
        return get(schema) ?: schema.valueType.defaultValue
    }
    
    /**
     * Attempt to decode binary extension using provided schema.
     * If successful, caches decoded value in the fields map.
     */
    private fun <V : Any> tryDecodeBinary(
        schema: FieldSchema.Extension<M, *, V>,
        binary: ExtensionValue.Binary<M>
    ): V? {
        return try {
            // Create a mini decoder just for this field's values
            val value = decodeBinaryWireValues(schema, binary.values)
            
            // Cache the decoded value (in mutable field set only)
            if (this is MutableExtensionFieldSet) {
                fields[schema.descriptor.number] = ExtensionValue.SingularDecoded(schema, value)
            }
            
            value
        } catch (e: Exception) {
            // Decode failed - type mismatch or corrupted data
            // Keep as binary, return null
            null
        }
    }
    
    private fun <V : Any> tryDecodeJson(
        schema: FieldSchema.Extension<M, *, V>,
        json: ExtensionValue.Json<M>
    ): V? {
        return try {
            val value = decodeJsonWireValue(schema, json.key, json.value)
            
            if (this is MutableExtensionFieldSet) {
                fields[schema.descriptor.number] = ExtensionValue.SingularDecoded(schema, value)
            }
            
            value
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Visit all decoded extension fields. Binary/JSON extensions are NOT decoded during visiting
     * (too expensive). They're visited as unknown fields instead.
     */
    internal fun visitFields(visitor: ProtoFieldVisitor) {
        for (field in fields.values) {
            when (field) {
                is ExtensionValue.SingularDecoded<M, *> -> {
                    visitSingularDecoded(visitor, field)
                }
                
                is ExtensionValue.RepeatedDecoded<M, *> -> {
                    visitRepeatedDecoded(visitor, field)
                }
                
                is ExtensionValue.Binary<M> -> {
                    // Visit as unknown field - preserves binary representation
                    field.values.forEach { visitor.visitUnknownField(it) }
                }
                
                is ExtensionValue.Json<M> -> {
                    // Can't preserve in binary encoding
                    // This shouldn't normally happen - JSON extensions should be decoded during JSON decode
                }
            }
        }
    }
    
    private fun <V : Any> visitSingularDecoded(
        visitor: ProtoFieldVisitor,
        field: ExtensionValue.SingularDecoded<M, V>
    ) {
        visitor.visitField(field.schema.descriptor, field.schema.valueType, field.value)
    }
    
    private fun <V : Any> visitRepeatedDecoded(
        visitor: ProtoFieldVisitor,
        field: ExtensionValue.RepeatedDecoded<M, V>
    ) {
        visitor.visitRepeatedField(field.schema.descriptor, field.schema.valueType, field.value)
    }
}

public class MutableExtensionFieldSet<M : ExtendableMessage<M>> internal constructor() : ExtensionFieldSet<M>() {
    override val fields: MutableMap<Int, ExtensionValue<M>> = mutableMapOf()
    
    /**
     * Set a singular extension field value.
     */
    public fun <V : Any> set(schema: FieldSchema.Extension<M, *, V>, value: V?) {
        if (value == null) {
            fields.remove(schema.descriptor.number)
        } else {
            fields[schema.descriptor.number] = ExtensionValue.SingularDecoded(schema, value)
        }
    }
    
    /**
     * Get or create a mutable list for a repeated extension field.
     */
    public fun <V : Any> getOrCreate(schema: FieldSchema.RepeatedExtension<M, *, V>): MutableList<V> {
        val existing = fields[schema.descriptor.number]
        
        return when (existing) {
            is ExtensionValue.RepeatedDecoded<M, *> -> {
                if (existing.schema.descriptor == schema.descriptor) {
                    @Suppress("UNCHECKED_CAST")
                    existing.value as MutableList<V>
                } else {
                    // Schema mismatch - replace with new list
                    val newList = mutableListOf<V>()
                    fields[schema.descriptor.number] = ExtensionValue.RepeatedDecoded(schema, newList)
                    newList
                }
            }
            
            is ExtensionValue.Binary<M> -> {
                // Try to decode from binary
                try {
                    val decoded = decodeBinaryRepeatedWireValues(schema, existing.values)
                    val mutableList = decoded.toMutableList()
                    fields[schema.descriptor.number] = ExtensionValue.RepeatedDecoded(schema, mutableList)
                    mutableList
                } catch (e: Exception) {
                    // Decode failed - replace with empty list
                    val newList = mutableListOf<V>()
                    fields[schema.descriptor.number] = ExtensionValue.RepeatedDecoded(schema, newList)
                    newList
                }
            }
            
            null -> {
                // Not present - create new list
                val newList = mutableListOf<V>()
                fields[schema.descriptor.number] = ExtensionValue.RepeatedDecoded(schema, newList)
                newList
            }
            
            else -> {
                // JSON or wrong type - replace with empty list
                val newList = mutableListOf<V>()
                fields[schema.descriptor.number] = ExtensionValue.RepeatedDecoded(schema, newList)
                newList
            }
        }
    }
}
```

#### 3. ExtensionValue with Smart Encoding

```kotlin
/**
 * Represents an extension field value in various states of decoding.
 */
internal sealed class ExtensionValue<M : ExtendableMessage<M>> {
    /**
     * Decoded singular extension with known schema and typed value.
     */
    class SingularDecoded<M : ExtendableMessage<M>, V : Any>(
        val schema: FieldSchema.Extension<M, *, V>,
        val value: V,
    ) : ExtensionValue<M>() {
        /**
         * Encode to binary WireValues for merging or serialization.
         */
        fun encodeToBinary(): List<WireValue> {
            return mutableListOf<WireValue>().also { values ->
                val writer = BinaryWireValueArrayWriter(values)
                val visitor = BinaryEncoderFieldVisitor(writer)
                visitor.visitField(schema.descriptor, schema.valueType, value)
            }
        }
    }
    
    /**
     * Decoded repeated extension with known schema and typed values.
     */
    class RepeatedDecoded<M : ExtendableMessage<M>, V : Any>(
        val schema: FieldSchema.RepeatedExtension<M, *, V>,
        val value: List<V>,
    ) : ExtensionValue<M>() {
        fun encodeToBinary(): List<WireValue> {
            return mutableListOf<WireValue>().also { values ->
                val writer = BinaryWireValueArrayWriter(values)
                val visitor = BinaryEncoderFieldVisitor(writer)
                visitor.visitRepeatedField(schema.descriptor, schema.valueType, value)
            }
        }
    }
    
    /**
     * Undecoded extension stored as binary WireValues.
     * Can be lazily decoded when accessed with appropriate schema.
     */
    class Binary<M : ExtendableMessage<M>>(
        val values: List<WireValue>
    ) : ExtensionValue<M>() {
        /**
         * Compute size for binary serialization.
         */
        fun binarySize(fieldNumber: Int): Int {
            return values.sumOf { wireValue ->
                Tag.size(fieldNumber) + wireValue.size
            }
        }
    }
    
    /**
     * Undecoded extension stored as JSON.
     * Can be lazily decoded when accessed with appropriate schema.
     */
    class Json<M : ExtendableMessage<M>>(
        val key: String,
        val value: pbandk.internal.json.WireValue,
    ) : ExtensionValue<M>()
}
```

#### 4. Decoding with Extension Support

```kotlin
internal class ExtendableMessageSchema<M : GeneratedExtendableMessage<M>, MM : MutableExtendableMessage<M>>(
    descriptor: MessageDescriptor,
    builder: (MM.() -> Unit) -> M,
    fields: Collection<FieldSchema<M, MM, *>>,
    oneofs: Collection<OneofDescriptor<M, *, *>>,
    private val extensionRanges: List<IntRange>,
) : GeneratedMessageSchema<M, MM>(descriptor, builder, fields, oneofs) {
    
    override fun decodeMessage(decoder: ProtoDecoder): M {
        // Get extension registry from decoder
        val extensionRegistry = decoder.extensionRegistry
        
        return builder {
            while (true) {
                val fieldNumber = decoder.peekFieldNumber()
                if (fieldNumber == 0) break  // End of message
                
                // Check regular fields first
                val regularField = fields[fieldNumber]
                if (regularField != null) {
                    regularField.decodeField(this, decoder)
                    continue
                }
                
                // Check if it's a known extension
                val extensionSchema = extensionRegistry?.get(descriptor, fieldNumber)
                if (extensionSchema != null) {
                    // Decode immediately as typed extension
                    decodeExtension(this, decoder, extensionSchema)
                    continue
                }
                
                // Check if field number is in extension range
                if (extensionRanges.any { fieldNumber in it }) {
                    // Store as binary for lazy decode later
                    val wireValues = decoder.readFieldAsWireValues(fieldNumber)
                    val binary = ExtensionValue.Binary<M>(wireValues)
                    this.extensionFields.fields[fieldNumber] = binary
                    continue
                }
                
                // True unknown field - store in unknownFields
                val unknownField = decoder.readUnknownField(fieldNumber)
                this.unknownFields[fieldNumber] = unknownField
            }
        }
    }
    
    private fun <V : Any> decodeExtension(
        destinationMessage: MM,
        decoder: ProtoDecoder,
        schema: FieldSchema.Extension<M, MM, V>
    ) {
        val fieldDescriptor = schema.descriptor
        
        when (schema) {
            is FieldSchema.Extension<M, MM, V> -> {
                // Singular extension
                val value = decoder.decodeValue(fieldDescriptor, schema.valueType)
                
                // Check if we need to merge with existing value
                val existing = destinationMessage.extensionFields.get(schema)
                val finalValue = if (existing != null && schema.valueType is MessageSchema<V>) {
                    schema.valueType.merge(existing, value)
                } else {
                    value
                }
                
                destinationMessage.extensionFields.set(schema, finalValue)
            }
            
            is FieldSchema.RepeatedExtension<M, MM, V> -> {
                // Repeated extension
                val list = destinationMessage.extensionFields.getOrCreate(schema)
                decoder.decodeRepeatedValue(fieldDescriptor, schema.valueType, list)
            }
        }
    }
}
```

#### 5. Extension Merging

```kotlin
override fun merge(destinationMessage: MM, message: M, other: M) {
    // Merge regular fields
    super.merge(destinationMessage, message, other)
    
    // Merge extensions
    mergeExtensions(
        destinationMessage.extensionFields,
        message.extensionFields,
        other.extensionFields
    )
}

private fun mergeExtensions(
    dest: MutableExtensionFieldSet<M>,
    msg1: ExtensionFieldSet<M>,
    msg2: ExtensionFieldSet<M>
) {
    // Collect all field numbers
    val allFieldNumbers = (msg1.fields.keys + msg2.fields.keys).toSet()
    
    for (fieldNumber in allFieldNumbers) {
        val value1 = msg1.fields[fieldNumber]
        val value2 = msg2.fields[fieldNumber]
        
        dest.fields[fieldNumber] = when {
            value1 == null -> value2!!
            value2 == null -> value1
            else -> mergeExtensionValue(value1, value2)
        }
    }
}

/**
 * Merge two extension values for the same field number.
 * Strategy:
 * 1. If both decoded with same schema -> type-safe merge
 * 2. If both decoded but different schemas -> binary merge (type conflict)
 * 3. If one/both binary -> binary merge
 * 4. If one/both JSON -> convert to binary and merge
 */
private fun mergeExtensionValue(
    v1: ExtensionValue<M>,
    v2: ExtensionValue<M>
): ExtensionValue<M> {
    return when {
        // Both singular decoded with matching schemas
        v1 is ExtensionValue.SingularDecoded && 
        v2 is ExtensionValue.SingularDecoded && 
        v1.schema.descriptor == v2.schema.descriptor &&
        v1.schema.valueType == v2.schema.valueType -> {
            mergeSingularDecoded(v1, v2)
        }
        
        // Both repeated decoded with matching schemas
        v1 is ExtensionValue.RepeatedDecoded && 
        v2 is ExtensionValue.RepeatedDecoded &&
        v1.schema.descriptor == v2.schema.descriptor &&
        v1.schema.valueType == v2.schema.valueType -> {
            mergeRepeatedDecoded(v1, v2)
        }
        
        // All other cases: merge as binary (type conflict or mixed types)
        else -> {
            mergeToBinary(v1, v2)
        }
    }
}

private fun <V : Any> mergeSingularDecoded(
    v1: ExtensionValue.SingularDecoded<M, V>,
    v2: ExtensionValue.SingularDecoded<M, V>
): ExtensionValue<M> {
    // If value type is a message, perform message merge
    val merged = if (v1.schema.valueType is MessageSchema<V>) {
        @Suppress("UNCHECKED_CAST")
        (v1.schema.valueType as MessageSchema<V>).merge(v1.value, v2.value)
    } else {
        // For primitives/enums, last one wins
        v2.value
    }
    
    return ExtensionValue.SingularDecoded(v1.schema, merged)
}

private fun <V : Any> mergeRepeatedDecoded(
    v1: ExtensionValue.RepeatedDecoded<M, V>,
    v2: ExtensionValue.RepeatedDecoded<M, V>
): ExtensionValue<M> {
    // Repeated fields: concatenate lists
    return ExtensionValue.RepeatedDecoded(
        v1.schema,
        v1.value + v2.value
    )
}

/**
 * Merge by encoding both to binary and concatenating.
 * This handles:
 * - Type conflicts (different schemas for same field number)
 * - Mixed decoded/binary values
 * - JSON values
 */
private fun mergeToBinary(
    v1: ExtensionValue<M>,
    v2: ExtensionValue<M>
): ExtensionValue.Binary<M> {
    val binary1 = when (v1) {
        is ExtensionValue.SingularDecoded -> v1.encodeToBinary()
        is ExtensionValue.RepeatedDecoded -> v1.encodeToBinary()
        is ExtensionValue.Binary -> v1.values
        is ExtensionValue.Json -> throw UnsupportedOperationException(
            "JSON extension merging requires decoding first"
        )
    }
    
    val binary2 = when (v2) {
        is ExtensionValue.SingularDecoded -> v2.encodeToBinary()
        is ExtensionValue.RepeatedDecoded -> v2.encodeToBinary()
        is ExtensionValue.Binary -> v2.values
        is ExtensionValue.Json -> throw UnsupportedOperationException(
            "JSON extension merging requires decoding first"
        )
    }
    
    return ExtensionValue.Binary(binary1 + binary2)
}
```

#### 6. ProtoDecoder Extension Support

```kotlin
public abstract class ProtoDecoder {
    /**
     * Extension registry for looking up known extensions during decode.
     * If null, all extensions stored as binary for lazy decode.
     */
    public abstract val extensionRegistry: ExtensionRegistry?
    
    /**
     * Peek at the next field number without consuming it.
     * Returns 0 if at end of message.
     */
    public abstract fun peekFieldNumber(): Int
    
    /**
     * Read all wire values for a field (for storing undecoded extensions).
     */
    public abstract fun readFieldAsWireValues(fieldNumber: Int): List<WireValue>
    
    /**
     * Read an unknown field.
     */
    public abstract fun readUnknownField(fieldNumber: Int): UnknownField
    
    // ... existing methods ...
}
```

### Key Design Decisions

#### 1. Lazy Decode-on-Access

**Decision**: Extensions are stored as binary during message decode and only decoded when accessed with a schema.

**Rationale**:
- Matches existing pbandk behavior
- Allows decoding messages without knowing all extension schemas
- Defers cost of decoding until actually needed
- Enables schema validation at access time

**Trade-offs**:
- ✅ Flexible: Can decode messages without all extensions known
- ✅ Efficient: Only decode what's accessed
- ⚠️ Performance: First access pays decode cost
- ⚠️ Complexity: Need to handle decode failures

#### 2. Extension Registry

**Decision**: Provide optional extension registry. If registry provided, known extensions decoded immediately. If not provided, all stored as binary.

**Rationale**:
- Supports both "I know all extensions" and "I don't know extensions" use cases
- Matches protobuf C++/Java behavior (ExtensionRegistry)
- Allows ahead-of-time decoding for better performance when schemas known

**Trade-offs**:
- ✅ Flexible: Supports both use cases
- ✅ Performance: Immediate decode faster than lazy decode
- ⚠️ API Complexity: Two modes of operation
- ⚠️ Migration: Existing code needs updating to use registry

#### 3. Binary Fallback for Conflicts

**Decision**: When merging extensions with type conflicts, fall back to binary merge.

**Rationale**:
- Handles case where different schemas use same field number
- Preserves all data even when types don't match
- Matches protobuf's "last one wins for unknown fields" semantics

**Trade-offs**:
- ✅ Safe: Never lose data
- ✅ Compatible: Handles schema evolution
- ⚠️ Untyped: Result is binary, not typed
- ⚠️ Confusing: May surprise users

#### 4. No JSON Extension Merging

**Decision**: JSON extensions must be decoded before merging. Throw exception if JSON extension encountered during merge.

**Rationale**:
- JSON encoding loses information (no field numbers in arrays)
- Can't reliably convert JSON back to binary without schema
- JSON shouldn't be stored long-term anyway (use binary)

**Trade-offs**:
- ✅ Correct: Avoids data loss
- ✅ Simple: No complex JSON→binary conversion
- ⚠️ Restrictive: Can't merge messages with undecoded JSON extensions
- ⚠️ Error-prone: Runtime exception possible

#### 5. FieldSchema.Extension for Both Encoding and Decoding

**Decision**: Use same `FieldSchema.Extension` class for both encoding (visiting) and decoding (lazy decode).

**Rationale**:
- Maintains visitor pattern consistency
- Reuses same schema object for all operations
- Simpler API: one schema type

**Trade-offs**:
- ✅ Consistent: Same pattern as regular fields
- ✅ Simple: One class to understand
- ⚠️ Hybrid: Doesn't pure visitor pattern (decoding is manual)

### Implementation Order

1. **Phase 1: Core Infrastructure (1 week)**
   - ExtensionRegistry interface and implementation
   - ExtensionValue sealed class with encodeToBinary()
   - ExtensionFieldSet.get() with lazy binary decode
   - MutableExtensionFieldSet.set() and getOrCreate()

2. **Phase 2: Decoding (1.5 weeks)**
   - ProtoDecoder extensions (peekFieldNumber, readFieldAsWireValues, extensionRegistry)
   - ExtendableMessageSchema.decodeMessage() with three-tier logic
   - Binary wire value → typed value decoding helpers
   - Unit tests for decoding

3. **Phase 3: Merging (1.5 weeks)**
   - mergeExtensions() implementation
   - mergeExtensionValue() with all type combinations
   - mergeSingularDecoded() / mergeRepeatedDecoded()
   - mergeToBinary() fallback
   - Unit tests for merging

4. **Phase 4: Visiting & Encoding (3 days)**
   - ExtensionFieldSet.visitFields()
   - Handle Binary extensions (encode as unknown fields)
   - Handle JSON extensions (error in binary context)
   - Unit tests for encoding

5. **Phase 5: Integration & Testing (1 week)**
   - End-to-end tests with real extensions
   - Conformance tests
   - Performance benchmarks
   - Documentation

**Total: 5-6 weeks**

### Testing Strategy

```kotlin
class ExtensionSupportTest {
    // Test lazy decode
    @Test
    fun `extension stored as binary, decoded on access with schema`()
    
    // Test immediate decode with registry
    @Test
    fun `extension decoded immediately when schema in registry`()
    
    // Test merge with same types
    @Test
    fun `merge two messages with same extension schema`()
    
    // Test merge with type conflict
    @Test
    fun `merge two messages with conflicting extension schemas falls back to binary`()
    
    // Test repeated extensions
    @Test
    fun `repeated extension concatenates lists on merge`()
    
    // Test message extension merge
    @Test
    fun `message extension performs recursive merge`()
    
    // Test unknown fields vs extensions
    @Test
    fun `field outside extension range stored as unknown field`()
    
    @Test
    fun `field inside extension range but not in registry stored as binary extension`()
    
    // Test encoding
    @Test
    fun `decoded extension encoded as typed field`()
    
    @Test
    fun `binary extension encoded as unknown field`()
    
    // Test cache
    @Test
    fun `decoded extension cached after first access`()
    
    @Test
    fun `failed decode does not cache, returns null`()
}
```

### Migration Path

For users of current pbandk:

```kotlin
// Old way (current pbandk)
val ext = message.getExtension(MyExtension.field)

// New way (nieuw with lazy decode)
val ext = message.extensionFields.get(MyExtension.fieldSchema)

// New way with registry (immediate decode)
val registry = ExtensionRegistry.DEFAULT.apply {
    register(MyExtension.fieldSchema)
}
val message = Foo.schema.decodeFromByteArray(bytes, registry = registry)
val ext = message.extensionFields.get(MyExtension.fieldSchema)
```

### Open Questions

1. **Should extension registry be global or per-decode?**
   - Global: Simpler API, set once
   - Per-decode: More flexible, can vary by use case
   - **Recommendation**: Per-decode (pass to decoder), with global default

2. **Should failed lazy decode be cached?**
   - Cache null: Faster repeated access, but wrong if schema changes
   - Don't cache: Always retry decode, slower but correct
   - **Recommendation**: Cache null with cache invalidation API

3. **Should visiting decode binary extensions?**
   - Yes: Always encode typed extensions
   - No: Encode binary as unknown fields (faster)
   - **Recommendation**: No (too expensive), encode as unknown

4. **JSON extension storage format?**
   - Store as kotlinx.serialization JsonElement
   - Store as internal json.WireValue
   - **Recommendation**: Internal WireValue (already exists)

## Conclusion

This three-tier extension system adapts the proven lazy-decode pattern from current pbandk to work with the visitor pattern architecture. Key insights:

1. **Extensions are special**: Don't force them into pure visitor pattern
2. **Lazy decode works**: Defer cost until access time
3. **Registry is optional**: Support both known and unknown extension scenarios  
4. **Binary fallback is safe**: Handles conflicts gracefully
5. **Hybrid approach**: Visitor for regular fields, special handling for extensions

This design resolves the extension challenges while maintaining the visitor pattern's benefits for regular fields.

---

**Next Step**: Implement Phase 1 (core infrastructure) and validate with prototype before committing to full implementation.
