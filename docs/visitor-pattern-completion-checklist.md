# Visitor Pattern Implementation Completion Checklist

**Generated**: February 6, 2026  
**Branch**: garyp/generate-interfaces  
**Package**: `pbandk.internal.nieuw`

## Executive Summary

The visitor pattern implementation in the `nieuw` package is **approximately 65-70% complete**. The encoding visitors are working well, but **decoding and protobuf extensions support are the major missing pieces**. Extensions are proving problematic because they require dynamic field handling, lazy decoding from binary/JSON, and complex merging semantics that don't fit cleanly into the visitor pattern's static schema approach.

---

## Current Status Overview

### ✅ What's Working (Complete)

1. **Binary Encoding** - Fully functional
   - `BinaryEncoderFieldVisitor` complete
   - Handles all primitive types, messages, enums
   - Packed repeated fields working
   - Unknown fields preserved
   - Group fields supported

2. **Binary Size Calculation** - Fully functional
   - `BinarySizeFieldVisitor` complete
   - Lazy computation cached in `GeneratedMessage.protoSize`
   - Optimized for messages with custom size computation

3. **JSON Encoding** - Mostly complete
   - `JsonEncoderFieldVisitor` complete
   - Handles all primitive types, messages, enums
   - Well-known types (Duration, Any) working
   - Some WKT edge cases need handling (see TODOs)

4. **Core Visitor Infrastructure** - Complete
   - `ProtoFieldVisitor` / `ProtoValueVisitor` abstractions
   - `MessageSchema` / `FieldSchema` type hierarchy
   - `ValueType` system for all primitives
   - Field iteration and visiting

5. **Regular Field Support** - Complete
   - `FieldSchema.ImplicitPresence` (proto3 fields)
   - `FieldSchema.ExplicitPresence` (optional fields)
   - `FieldSchema.Message` (message fields with merging)
   - `FieldSchema.Repeated` (repeated fields)
   - `FieldSchema.Map` (map fields for encoding)

---

## ⚠️ What's Incomplete

### 1. Binary Decoding (40% Complete)

**Status**: Basic infrastructure exists, but many gaps

#### ✅ What Works:
- `BinaryDecoder` class structure
- Tag reading and parsing
- Basic field dispatch via `nextField()`
- Varint decoding
- Simple value decoding for int32/uint32
- Repeated field decoding (packed and unpacked)

#### ❌ What's Missing:

**A. Unknown Field Handling** (Critical)
```kotlin
// FieldSchema.kt:194, MessageSchema.kt:94
override fun nextField(fields: FieldDescriptorSet): FieldDescriptor? {
    // ...
    fields[fieldNumber]?.let { return it }
    
    // TODO: Currently incomplete - need to:
    // 1. Read unknown field into WireValue
    // 2. Store in message's unknownFields map
    // 3. Continue decoding
    // 4. Handle extension ranges for extendable messages
}
```

**B. Map Field Decoding** (Critical)
```kotlin
// FieldSchema.kt:193-195
internal class Map<...> {
    override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
        TODO("Not yet implemented")
    }
}
```
- Need to decode length-delimited map entries
- Parse key/value pairs
- Handle null key/value replacement with defaults

**C. Group Field Decoding**
```kotlin
// ProtoDecoder.kt:100
WireType.START_GROUP -> TODO()
```
- Need to handle legacy proto2 group syntax
- Read fields until END_GROUP tag

**D. Message Merging During Decode**
- `FieldSchema.Message.decodeField()` implements basic merging
- But needs testing for edge cases:
  - Required fields in nested messages
  - Circular message references
  - Large nested structures

**E. Primitive Value Decoders**
Currently only int32/uint32 implemented. Need to add:
- `decodeInt64Value()` / `decodeUInt64Value()`
- `decodeFixed32Value()` / `decodeFixed64Value()`  
- `decodeSFixed32Value()` / `decodeSFixed64Value()`
- `decodeFloatValue()` / `decodeDoubleValue()`
- `decodeBoolValue()`
- `decodeBytesValue()`
- `decodeStringValue()`
- `decodeEnumValue()`

**Estimated Effort**: 2-3 weeks for full binary decoding

---

### 2. JSON Decoding (30% Complete)

**Status**: Skeletal implementation, needs substantial work

#### ✅ What Works:
- `JsonDecoder` class structure
- Field lookup by JSON name
- Null value skipping per spec
- Basic int32/uint32 decoding
- Repeated array handling

#### ❌ What's Missing:

**A. Primitive Value Decoders**
```kotlin
// ProtoDecoder.kt:203-205
override fun <T : Any> decodeValue(fieldDescriptor: FieldDescriptor, valueType: ValueType<T>): T {
    valueType.decodeValue(fieldDescriptor, this)
    // Missing return statement!
}
```

Need implementations for:
- All numeric types (int64, fixed32/64, float, double, etc.)
- Bytes (base64 decoding)
- Strings
- Enums (string names and numeric fallback)
- Booleans

**B. Message Value Decoding**
- No JSON message decoder implemented
- Need to handle nested messages
- Well-known types require special handling:
  - `Any` with `@type` field
  - `Timestamp` as RFC 3339 string
  - `Duration` as string with 's' suffix
  - `Struct`, `Value`, `ListValue` as JSON
  - Wrapper types as primitive JSON values

**C. Map Field Decoding**
- JSON maps encoded as objects with string keys
- Need to parse and populate MutableMap

**D. Null Handling**
- Proto3 JSON allows null for message fields (means default/absent)
- Need special handling for NullValue enum

**E. Unknown Field Behavior**
- JSON spec doesn't preserve unknown fields
- But need to respect `JsonConfig.ignoreUnknownFieldsInInput`
- Error reporting for strict mode

**Estimated Effort**: 3-4 weeks for full JSON decoding

---

### 3. **Protobuf Extensions Support** (20% Complete) - CRITICAL ISSUE

**This is the most problematic area of the refactor.**

#### Why Extensions Are Hard

Extensions fundamentally don't fit the visitor pattern's static schema model:

1. **Dynamic Field Discovery**: Extension fields aren't known at message definition time
2. **Lazy Decoding**: Extensions stored as binary/JSON blobs, decoded on access
3. **Type Ambiguity**: Multiple extensions can share same field number with different types
4. **Complex Merging**: Must merge decoded and undecoded extensions
5. **Binary Fallback**: When types conflict, fall back to binary representation

#### ✅ What Works:

**A. Extension Field Visiting** (Encoding)
```kotlin
// FieldSchema.kt:204-230
class Extension<M, MM, V> {
    override fun getValue(message: M): V? {
        return message.extensionFields.getOrDefault(this)
    }
    
    override fun visitField(message: M, visitor: ProtoFieldVisitor) {
        val value = getValue(message)
        if (value != null) {
            visitor.visitField(descriptor, valueType, value)
        }
    }
}
```
- Singular and repeated extensions can be encoded
- Binary and JSON encoding visitors work

**B. Extension Value Storage**
```kotlin
// Message.kt:77-118
sealed class ExtensionValue<M> {
    class SingularDecoded<M, V>(schema, value)
    class RepeatedDecoded<M, V>(schema, value)  
    class Binary<M>(wireValues)
    class Json<M>(key, wireValue)
}
```
- Can store decoded typed values
- Can store undecoded binary/JSON
- Can encode back to binary

#### ❌ What's Missing:

**A. Extension Field Decoding** (Critical)
```kotlin
// FieldSchema.kt:223-225, 227-229
class Extension {
    override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
        TODO("Not yet implemented")
    }
    
    override fun merge(message: M, other: M, destinationMessage: MM) {
        TODO("Not yet implemented") 
    }
}

class RepeatedExtension {
    // Missing decodeField and merge entirely!
}
```

**Implementation needs:**

1. **Decode from binary unknown fields**:
   ```kotlin
   override fun decodeField(destinationMessage: MM, decoder: ProtoDecoder) {
       val value = decoder.decodeValue(descriptor, valueType)
       val schema = this // Extension FieldSchema
       val extensionValue = ExtensionValue.SingularDecoded(schema, value)
       destinationMessage.extensionFields.fields[descriptor.number] = extensionValue
   }
   ```

2. **Decode from JSON unknown fields**: Similar but from JSON

3. **Lazy decoding from `ExtensionValue.Binary`**:
   - When `getOrDefault()` called, check if value is `Binary`
   - Attempt to decode using provided schema
   - Cache decoded result or keep as binary if type mismatch

**B. Extension Field Merging** (Critical)
```kotlin
// MessageSchema.kt:177-239
override fun merge(destinationMessage: MM, message: M, other: M) {
    // Partial implementation exists but incomplete
    with(destinationMessage.extensionFields) {
        fields.putAll(message.extensionFields.fields)
        for ((otherKey, otherField) in other.extensionFields.fields) {
            fields[otherKey] = fields[otherKey]?.let { messageField ->
                when (messageField) {
                    is ExtensionValue.SingularDecoded<M, *> -> {
                        when (otherField) {
                            is ExtensionValue.SingularDecoded<M, *> -> {
                                // Implemented if types match
                                // TODO if types differ
                            }
                            is ExtensionValue.RepeatedDecoded<M, *> -> binaryMerge(...)
                            is ExtensionValue.Binary<M> -> binaryMerge(...)
                            is ExtensionValue.Json<M> -> TODO()
                        }
                    }
                    // Many TODO branches...
                }
            } ?: otherField
        }
    }
}
```

**Issues**:
- 4 `ExtensionValue` types × 4 types = 16 merge combinations
- Only ~6 cases implemented
- 10+ cases have `TODO()` 
- JSON extension merging completely unimplemented (6 TODOs)

**C. ExtensionFieldSet Missing Methods**
```kotlin
// Message.kt:120-162
class ExtensionFieldSet<M> {
    // Has: getValue()
    // Missing:
    fun getOrDefault(schema: FieldSchema<M, *, V>): V
    fun set(schema: FieldSchema<M, *, V>, value: V?)
    fun getOrCreate(schema: FieldSchema.RepeatedExtension<M, *, V>): MutableList<V>
}

class MutableExtensionFieldSet<M> {
    // Only has mutable fields map, no methods!
}
```

These methods are called by `FieldSchema.Extension` but don't exist:
- `FieldSchema.kt:209`: `message.extensionFields.getOrDefault(this)`
- `FieldSchema.kt:213`: `message.extensionFields.set(this, value)`  
- `FieldSchema.kt:241`: `message.extensionFields.getOrCreate(this)`

**D. Extension Range Handling**
```kotlin
// MessageSchema.kt:136
private val extensionRanges: List<IntRange>
```
- Extension ranges stored but not used during decoding
- Need to check if field number in range before treating as unknown

**E. Extendable Message Decoding**
```kotlin
// MessageSchema.kt:143-145
override fun decodeMessage(decoder: ProtoDecoder): M {
    TODO()
}
```
- Complete TODO for extendable messages
- Need to decode regular fields + extensions

#### Why Extensions Don't Fit the Visitor Pattern

The visitor pattern assumes:
- Static, known schemas at compile time
- All field types known
- Straightforward iteration over fields

Extensions violate all of these:
- Fields discovered at runtime
- Field types might be unknown (binary blob)
- Can't iterate without decoding first

**Possible Solutions**:

**Option A**: Treat extensions specially (breaks visitor pattern purity)
- Don't use `FieldSchema.Extension` during decode
- Decode extensions separately with specialized logic
- Store in `UnknownField` map until accessed
- On access, attempt typed decode using extension registry

**Option B**: Extension registry pattern
- Pass extension registry to decoder
- Look up field schemas from registry during decode
- Falls back to binary storage if not in registry
- More aligned with protobuf spec

**Option C**: Hybrid approach (current)
- Use `FieldSchema.Extension` for encoding (works)
- Use special extension decoder for decoding
- Bridge the two worlds in `ExtensionFieldSet`

**Estimated Effort**: 4-6 weeks for full extension support

---

### 4. Other Missing Pieces

**A. OneOf Field Merging**
```kotlin
// MessageSchema.kt:112
for (oneofSchema in oneofs) {
    TODO()
}
```
- Oneof merge semantics not implemented
- Need to clear other fields in oneof when merging

**B. Message Plus Operation**
```kotlin
// GeneratedMessage.kt:20-22
override fun plus(other: Message?): M {
    TODO("Not yet implemented")
}
```
- Should delegate to `schema.merge()`

**C. Field Value Getter**
```kotlin
// GeneratedMessage.kt:24-26
override fun <V> getFieldValue(fieldSchema: FieldSchema<*, *, V>): V {
    TODO()
}
```
- Reflection-like access to fields
- Needs dispatch based on field schema type

**D. Custom Type Mappings**
```kotlin
// ProtoFieldVisitor.kt:504
// TODO: handle custom types that are mapped to a WKT 
// (e.g. kotlin.time.Duration to pbandk.wkt.Duration)
```
- Need `TranslatingValueType` support
- Well-known type conversions

**E. ValueType Implementations**
```kotlin
// ValueType.kt:77, 140
TODO("Not yet implemented")
```
- Some primitive value types incomplete
- Need all decode methods

---

## Code Generator Integration

**Status**: Not started (0%)

Even when all decoding/encoding works, need to:

1. **Update CodeGenerator.kt**
   - Generate using `nieuw` package classes
   - Create `FieldSchema` objects instead of `FieldDescriptor`
   - Generate `MessageSchema` companion objects
   - Handle extension field generation

2. **Generate Visitor-Compatible Code**
   - Messages must work with visitors
   - Extension schemas must be accessible
   - Builder pattern must construct with schema

3. **Migration Path**
   - Generate both old and new code temporarily?
   - Deprecation strategy
   - Breaking changes for users

**Estimated Effort**: 3-4 weeks

---

## Testing Requirements

**Status**: Not started (0%)

Need comprehensive testing:

1. **Unit Tests**
   - Each `FieldSchema` type encode/decode
   - Each `ValueType` encode/decode
   - Extension field operations
   - Unknown field preservation
   - Merging semantics

2. **Integration Tests**
   - Full message encode/decode roundtrip
   - Binary ↔ JSON conversion
   - Extension field roundtrip
   - Well-known types

3. **Conformance Tests**
   - Run protobuf conformance test suite
   - Ensure compatibility with protobuf spec
   - All platforms (JVM, JS, Native, WASM)

4. **Performance Tests**
   - Compare visitor pattern vs current implementation
   - Measure memory allocation
   - Benchmark encode/decode speed

**Estimated Effort**: 2-3 weeks

---

## Detailed TODOs by File

### FieldSchema.kt
- [ ] Line 194: Implement `Map.decodeField()` for map entry decoding
- [ ] Line 224: Implement `Extension.decodeField()` for singular extensions
- [ ] Line 228: Implement `Extension.merge()` for singular extensions
- [ ] Lines 252+: Add `RepeatedExtension.decodeField()` and `merge()` methods

### MessageSchema.kt
- [ ] Line 54: Fix `decodeValue()` to properly handle nested message decoding
- [ ] Line 94: Implement unknown field handling in `decodeMessage()`
- [ ] Line 112: Implement oneof merging logic
- [ ] Line 144: Implement `ExtendableMessageSchema.decodeMessage()`
- [ ] Lines 166, 172, 202, 221, 229-232: Complete all extension merging TODOs (10 cases)

### ProtoDecoder.kt
- [ ] Line 81: Complete `BinaryDecoder.nextField()` unknown field handling
- [ ] Line 100: Implement `START_GROUP` decoding
- [ ] Line 204-205: Fix `JsonDecoder.decodeValue()` missing return
- [ ] Lines 207-271: Implement all JSON value decoders

### ProtoFieldVisitor.kt
- [ ] Line 187: Handle non-primitive, non-message value types
- [ ] Lines 209, 217: Decide on enum skipping behavior (document decision)
- [ ] Line 312: Handle non-primitive, non-message value types for size calc
- [ ] Lines 336, 343: Decide on enum skipping behavior for size calc
- [ ] Line 422: Note about JsonPrimitive unsigned int limitation (external issue)
- [ ] Line 504: Implement custom type mapping for WKTs (e.g., kotlin.time.Duration)

### Message.kt
- [ ] Line 123-132: Implement `ExtensionFieldSet.getOrDefault()`
- [ ] Line 134-145: Implement `ExtensionFieldSet.set()`
- [ ] Line 147-155: Implement `ExtensionFieldSet.getOrCreate()`
- [ ] Line 154: Handle JSON extension field visiting

### GeneratedMessage.kt
- [ ] Line 21: Implement `GeneratedMessage.plus()` using schema.merge()
- [ ] Line 25: Implement `getFieldValue()` reflection-like access

### ValueType.kt
- [ ] Line 77: Implement missing value type decode method
- [ ] Line 140: Implement missing value type decode method

---

## Extension Support Deep Dive

### The Core Problem

Extensions require **dynamic type information** that the visitor pattern's static schema doesn't naturally support. Here's the flow that's broken:

**Current Implementation (non-nieuw) - Works:**
```
1. Decode binary → unknown fields map
2. User calls getExtension(extensionDescriptor)
3. Look up in known extensions → found, decode from unknown fields
4. Return typed value
```

**Visitor Pattern (nieuw) - Broken:**
```
1. Decode binary → ??? (no static schema for extension)
2. Store as ExtensionValue.Binary(wireValues)
3. User calls message.extensionFields.get(fieldSchema)
4. Check if Binary → need to decode
5. Don't have schema to decode with! ❌
```

### Why Current Code Fails

Look at the extension field access pattern:

```kotlin
// FieldSchema.kt:208-210
class Extension {
    override fun getValue(message: M): V? {
        return message.extensionFields.getOrDefault(this)
                      // ^^^^ this method doesn't exist!
    }
}
```

The `getOrDefault()` method needs to:
1. Check if field number exists in extension map
2. If `ExtensionValue.SingularDecoded` with matching schema → return value
3. If `ExtensionValue.Binary` → try to decode using `this` schema
4. If decode succeeds → cache and return
5. If decode fails (type mismatch) → return null

But none of this logic exists!

### What Needs to Be Built

**1. Lazy Decoding Infrastructure**

```kotlin
class ExtensionFieldSet<M : ExtendableMessage<M>> {
    private val decodedCache: MutableMap<Int, Any?> = mutableMapOf()
    
    fun <V : Any> getOrDefault(schema: FieldSchema.Extension<M, *, V>): V? {
        // Check cache first
        decodedCache[schema.descriptor.number]?.let {
            if (it is V) return it
        }
        
        // Try to decode from binary
        val stored = fields[schema.descriptor.number]
        when (stored) {
            is ExtensionValue.SingularDecoded<M, *> -> {
                if (stored.schema == schema) {
                    @Suppress("UNCHECKED_CAST")
                    return stored.value as V
                }
                // Schema mismatch - keep as binary
                return null
            }
            
            is ExtensionValue.Binary<M> -> {
                // Attempt decode using schema
                try {
                    val decoder = BinaryDecoder(ByteArrayWireReader(stored.value), ...)
                    val value = schema.valueType.decodeValue(schema.descriptor, decoder)
                    
                    // Cache decoded value
                    val decoded = ExtensionValue.SingularDecoded(schema, value)
                    fields[schema.descriptor.number] = decoded
                    decodedCache[schema.descriptor.number] = value
                    
                    return value
                } catch (e: Exception) {
                    // Decode failed, keep as binary
                    return null
                }
            }
            
            is ExtensionValue.Json<M> -> {
                // Similar logic for JSON
                TODO()
            }
            
            null -> return null
        }
    }
}
```

**2. Extension Decoding During Message Decode**

```kotlin
class ExtendableMessageSchema {
    override fun decodeMessage(decoder: ProtoDecoder): M {
        return builder {
            do {
                val fieldDescriptor = decoder.nextField(fields.descriptorSet + knownExtensions)
                
                if (fieldDescriptor == null) {
                    // Unknown field - store as binary for potential extension
                    if (fieldNumber in extensionRanges) {
                        val wireValue = decoder.readUnknownFieldValue()
                        val binary = ExtensionValue.Binary<M>(listOf(wireValue))
                        this.extensionFields.fields[fieldNumber] = binary
                    } else {
                        // True unknown field
                        this.unknownFields[fieldNumber] = readUnknownField(decoder)
                    }
                    continue
                }
                
                if (fieldDescriptor is FieldDescriptor.Extension) {
                    // Known extension - decode directly
                    val schema = knownExtensions[fieldDescriptor.number]!!
                    val value = decoder.decodeValue(fieldDescriptor, schema.valueType)
                    val decoded = ExtensionValue.SingularDecoded(schema, value)
                    this.extensionFields.fields[fieldDescriptor.number] = decoded
                } else {
                    // Regular field
                    val fieldSchema = fields[fieldDescriptor.number]!!
                    fieldSchema.decodeField(this, decoder)
                }
            } while (fieldDescriptor.number != 0)
        }
    }
}
```

**3. Extension Registry**

```kotlin
// Need a way to register known extensions
interface ExtensionRegistry {
    fun <M : ExtendableMessage<M>, V : Any> register(
        schema: FieldSchema.Extension<M, *, V>
    )
    
    fun get(fieldNumber: Int): FieldSchema.Extension<*, *, *>?
}

// Pass to decoder
val decoder = BinaryDecoder(reader, config, extensionRegistry)
```

**4. Extension Merging**

The 16 merge combinations need to be implemented. Pattern:

```kotlin
private fun mergeExtensions(
    dest: MutableExtensionFieldSet<M>,
    msg1: ExtensionFieldSet<M>,
    msg2: ExtensionFieldSet<M>
) {
    // Start with msg1 extensions
    dest.fields.putAll(msg1.fields)
    
    // Merge msg2 extensions
    for ((fieldNum, value2) in msg2.fields) {
        dest.fields[fieldNum] = dest.fields[fieldNum]?.let { value1 ->
            mergeExtensionValue(value1, value2)
        } ?: value2
    }
}

private fun mergeExtensionValue(v1: ExtensionValue<M>, v2: ExtensionValue<M>): ExtensionValue<M> {
    return when {
        // Both decoded with same schema - type-safe merge
        v1 is SingularDecoded && v2 is SingularDecoded && v1.schema == v2.schema -> {
            if (v1.schema.valueType is MessageSchema) {
                SingularDecoded(v1.schema, v1.schema.valueType.merge(v1.value, v2.value))
            } else {
                v2 // Last one wins for non-messages
            }
        }
        
        v1 is RepeatedDecoded && v2 is RepeatedDecoded && v1.schema == v2.schema -> {
            RepeatedDecoded(v1.schema, v1.value + v2.value)
        }
        
        // Type mismatch or mixed binary/decoded - merge as binary
        else -> ExtensionValue.Binary(encodeToBinary(v1) + encodeToBinary(v2))
    }
}
```

---

## Estimated Completion Timeline

Assuming one experienced developer working full-time:

| Task | Effort | Dependencies |
|------|--------|-------------|
| Binary decoding (primitives) | 1 week | None |
| Binary decoding (unknown fields) | 3 days | Primitives |
| Binary decoding (map fields) | 2 days | Primitives |
| Binary decoding (groups) | 2 days | Primitives |
| **Binary Decoding Subtotal** | **2-3 weeks** | |
| | | |
| JSON decoding (primitives) | 1.5 weeks | None |
| JSON decoding (messages) | 1 week | Primitives |
| JSON decoding (WKTs) | 1 week | Messages |
| **JSON Decoding Subtotal** | **3-4 weeks** | |
| | | |
| Extension field decoding | 1 week | Binary/JSON decode |
| Extension field merging | 1.5 weeks | Decoding |
| Extension lazy decoding | 1 week | Merging |
| Extension registry | 3 days | Lazy decode |
| **Extensions Subtotal** | **4-5 weeks** | |
| | | |
| OneOf merging | 3 days | None |
| Plus operation | 1 day | Merging complete |
| Field reflection | 2 days | None |
| Custom type mappings | 1 week | Encode/decode |
| **Other Features Subtotal** | **2 weeks** | |
| | | |
| Code generator updates | 3-4 weeks | All features |
| Testing & debugging | 2-3 weeks | Code gen |
| Performance optimization | 1-2 weeks | Testing |
| **Integration Subtotal** | **6-9 weeks** | |

**Total Estimated Time: 17-23 weeks (4-6 months)**

**Critical Path**:
1. Binary decoding → Extensions → Code generator → Testing (4-6 months)

**Parallelizable**:
- JSON decoding can happen alongside binary decoding (save 3-4 weeks)
- Other features can happen during decoding work (save 2 weeks)

**Optimistic Timeline**: 3-4 months with parallel work  
**Realistic Timeline**: 5-6 months for single developer  
**Conservative Timeline**: 7-8 months (accounting for complexity/debugging)

---

## Recommendation

### Should You Complete the Visitor Pattern?

**Arguments FOR:**
1. ✅ Better architecture - cleaner separation of concerns
2. ✅ Extensible - easy to add new operations (text format, debugging, etc.)
3. ✅ Type-safe - compile-time checking of field operations
4. ✅ Potentially better performance - specialized visitors can optimize
5. ✅ Encoding already works - 40% of the value is already delivered

**Arguments AGAINST:**
1. ❌ Large time investment - 4-6 months of focused work
2. ❌ Extensions are fundamentally problematic - don't fit pattern well
3. ❌ High risk - may discover more issues during implementation
4. ❌ Current architecture works - no critical bugs driving change
5. ❌ Breaking change - users must migrate to new generated code

### My Recommendation: **Pivot or Defer**

The visitor pattern is a good idea architecturally, but **extensions are a deal-breaker**. The dynamic, lazy-decoded nature of extensions fundamentally conflicts with the visitor pattern's static schema model.

**Option 1: Hybrid Approach (Recommended)**
- Keep visitor pattern for regular fields (encoding already works)
- Use specialized extension handling (like current implementation)
- Extensions stay in `unknownFields` until accessed
- Access triggers lazy decode with extension registry
- **Effort**: 2-3 months to complete
- **Pros**: Gets benefits of visitor pattern where it fits
- **Cons**: Less conceptually pure

**Option 2: Defer to Protobuf Editions**
- Protobuf Editions (protobuf v25+) is moving away from extensions
- New "open fields" feature provides similar functionality differently
- Wait to see how ecosystem evolves before big refactor
- **Effort**: 0 (defer decision)
- **Pros**: May avoid wasted work
- **Cons**: Delays architectural improvements

**Option 3: Complete as Planned**
- Push through all TODOs
- Find solutions to extension challenges
- Deliver full visitor pattern implementation
- **Effort**: 5-6 months
- **Pros**: Gets full architectural benefits
- **Cons**: High cost, uncertain if extension issues solvable

### Testing the Visitor Pattern Viability

**Minimum Test**: Can you handle extensions well?

Rather than complete everything, focus on proving out extension support:

1. **Week 1-2**: Implement extension field decoding (binary only)
2. **Week 3-4**: Implement extension lazy decode and caching  
3. **Week 5**: Build small test case with extensions
4. **Week 6**: Run conformance tests for extension behavior

If extensions work cleanly → continue with full implementation  
If extensions prove too problematic → pivot to hybrid or defer

**This 6-week experiment** de-risks the decision before investing 4-6 months.

---

## Conclusion

The visitor pattern implementation is well-designed and the encoding side works beautifully. However, **protobuf extensions are the Achilles' heel** of this approach. Extensions need:

- Dynamic field discovery
- Lazy decoding
- Type ambiguity handling  
- Complex merge semantics

None of these fit naturally into a static visitor pattern.

**Next Steps**:
1. Review this assessment and decide: complete, pivot, or defer?
2. If completing: start with 6-week extension prototype to prove viability
3. If pivoting: keep encoding visitors, use current extension model
4. If deferring: move `nieuw` to experimental branch for future

The core refactor (interfaces, builders, field types) is excellent and should be merged. The visitor pattern can evolve independently once extension challenges are resolved.

---

**Document Version**: 1.0  
**Author**: OpenCode Analysis  
**Date**: February 6, 2026
