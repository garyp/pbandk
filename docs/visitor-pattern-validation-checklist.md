# Visitor Pattern (Nieuw) Validation Checklist

## Nieuw Package: What's Needed to Validate the Visitor Pattern

### 1. Core Decoding Infrastructure (blocking for any real testing)

**`BinaryDecoder.nextField()` — returns `null` for unknown field numbers**
The method at `ProtoDecoder.kt:78` returns `null` when a field number isn't in the descriptor set. But `GeneratedMessageSchema.decodeMessage()` at `MessageSchema.kt:94` has `TODO("deal with unknown field")`. This is the **gateway blocker** — without handling unknown fields, you can't decode any message that has extensions or unknown data.

What's needed:
- `BinaryDecoder.nextField()` should return a sentinel descriptor (or the unknown field number + wire type) when it doesn't match a known field
- `GeneratedMessageSchema.decodeMessage()` needs to handle the unknown field case by storing the raw `WireValue` in `unknownFields`
- Same for `JsonDecoder.nextField()` — it already returns `null` for unknown field names, but needs to store them

**`BinaryDecoder.skipValue()` — `WireType.START_GROUP` is `TODO()`**
At `ProtoDecoder.kt:100`. Proto2 groups need START/END_GROUP handling. Without this, proto2 group fields can't be decoded.

---

### 2. Extension Field Decoding (the problematic area)

This is where the existing code has the most sophisticated logic, and `nieuw` has the least. Here's the full comparison:

**Existing approach** (`FieldSet.kt`):
- `ExtensionFieldSet` stores extension values as `FieldDescriptor + value` pairs
- On `get(fieldDescriptor)`, it first checks the field set, then the cache, then **decodes from `UnknownField` data** via `tryDecodeUnknownField()`
- `UnknownField.decodeAs()` knows the `FieldType` so it can decode raw binary data into the correct Kotlin type

**Nieuw approach** (`Message.kt`):
- `ExtensionFieldSet` stores `ExtensionValue<SingularDecoded|RepeatedDecoded|Binary|Json>`
- `Binary` variant stores raw `WireValue` list (correct design for unknown schemas)
- But `FieldSchema.Extension.decodeField()` is `TODO()` — there's no way to decode an extension from binary data
- `FieldSchema.RepeatedExtension` is **missing** `decodeField()`, `merge()`, and even `visitField()` entirely

What's needed for extension decoding:

1. **`FieldSchema.Extension.decodeField()`** — needs to decode a single extension value from the `ProtoDecoder`. Since the decoder doesn't know the schema upfront, it should:
   - Decode the value using the `ValueType`
   - Store it as `ExtensionValue.SingularDecoded` in the `ExtensionFieldSet`

2. **`FieldSchema.RepeatedExtension.decodeField()`** — same but for repeated fields, appending to a `MutableList`

3. **Unknown field routing** — when `BinaryDecoder.nextField()` returns an unknown field number, the decode loop needs to:
   - Check if this field number falls within any extension range
   - If yes, decode it as an extension (storing raw `WireValue` if the schema isn't known yet)
   - If no, store it as an `UnknownField`

4. **`ExtensionFieldSet` needs a `set(schema, value)` method** — currently `ExtensionValue.SingularDecoded` and `RepeatedDecoded` store the schema, but there's no way to populate them from the decoder. The existing `MutableExtensionFieldSet.set(fieldDescriptor, value)` does this.

---

### 3. Extension Field Merge (complexity multiplier)

The existing code handles extension merge through `FieldSet` iteration and `FieldType.mergeValues()`. Nieuw has a partial implementation in `ExtendableMessageSchema.merge()` but it's incomplete:

**What exists** (`MessageSchema.kt:177-238`):
- `mergeMessageField()` — merges two singular decoded extensions (correct for message types, overwrites for primitives)
- `binaryMerge()` — merges two binary extension values by concatenating their `WireValue` lists
- Full merge dispatch table for all 4 x 4 combinations of `ExtensionValue` variants

**What's missing:**
- `FieldSchema.Extension.merge()` is `TODO()` — needed for the base `GeneratedMessageSchema.merge()` path
- `ExtensionValue.Json` merge handling is `TODO()` in 6 places — the Json variant is barely implemented
- `ExtensionFieldSet.visitFields()` has `TODO()` for `ExtensionValue.Json`
- `ExtendableMessageSchema.decodeMessage()` is `TODO()` — extendable messages can't be decoded at all

The merge logic is the most complex part because it needs to handle:
- Two `SingularDecoded` with same schema → merge values (message types) or overwrite (primitives)
- Two `SingularDecoded` with different schemas → binary merge (concatenate raw bytes)
- `SingularDecoded` + `RepeatedDecoded` → binary merge
- `Binary` + anything → binary merge
- `Json` + anything → ??? (not implemented)

---

### 4. FieldSchema Gaps

| FieldSchema subclass | Missing |
|---|---|
| `Map` | `decodeField()` — `TODO()` |
| `Extension` | `decodeField()` — `TODO()`, `merge()` — `TODO()` |
| `RepeatedExtension` | `decodeField()` — missing entirely, `merge()` — missing entirely, `visitField()` — missing entirely |

---

### 5. ValueType Gaps

| Type | Missing |
|---|---|
| `StringValue` | `decodeValue()` — `TODO()` |
| `WktDurationToKotlinDuration` | `decodeValue()` — `TODO()`, `visitValue()` — truncated (`...` at line 135) |
| All non-primitive, non-message value types | `ProtoFieldVisitor.visitField()` has `TODO()` at line 187 and 312 (the `else` branch after `PrimitiveValueType` and `MessageSchema`) |

---

### 6. GeneratedMessage Gaps

| Method | Status |
|---|---|
| `plus()` | `TODO()` — message merge operator |
| `getFieldValue()` | `TODO()` — reflection-based field access by schema |

---

### 7. Oneof Handling

`GeneratedMessageSchema.merge()` at `MessageSchema.kt:112` has `TODO()` for oneof handling. The oneof fields are stored in the `fields` collection with `isOneofMember = true`, and the merge loop skips them (line 108: `if (fieldSchema.descriptor.isOneofMember) continue`). But there's no logic to handle oneof selection during merge — when one of the input messages has a oneof variant set, the destination needs to select that variant.

---

### 8. Missing Primitive Value Types

Nieuw's `ValueType.kt` only implements:
- `Int32Value` — complete
- `StringValue` — decode missing

The existing `pbandk.internal.types.primitive.*` has 17 types. Nieuw is missing: `UInt32`, `Int64`, `UInt64`, `SInt32`, `SInt64`, `Fixed32`, `Fixed64`, `SFixed32`, `SFixed64`, `Bool`, `Float`, `Double`, `Enum`, `Bytes`. These are all needed for the visitor pattern to work.

---

### 9. Code Generator Integration

The code generator currently produces `pbandk.FieldDescriptor` instances. To test the visitor pattern end-to-end, the generator would need to produce `pbandk.internal.nieuw.FieldSchema` instances instead. This means:

- `writeMessageType()` generates classes extending `GeneratedMessage<M>` instead of sealed interfaces
- `writeExtensionFieldDescriptor()` generates `FieldSchema.Extension` / `FieldSchema.RepeatedExtension`
- `writeMessageDescriptor()` creates `GeneratedMessageSchema` / `ExtendableMessageSchema`
- Value type functions return nieuw `ValueType` instances instead of `pbandk.types.*()` calls
- The `companion` class has `schema: MessageSchema<M>` instead of `valueType: MessageValueType<M, M>`

---

### 10. Type Registry Integration

The `TypeRegistry` in nieuw has `add(extension: FieldSchema)` and an `extensionRegistry` map in `TypeRegistryImpl`, but:
- `TypeRegistry.getMessageSchema()` returns `MessageSchema<*>` but extension schemas are stored separately
- There's no `getExtensionSchema()` method (it's commented out)
- The `Any` message JSON encoding in `JsonEncoderFieldVisitor` uses `jsonConfig.typeRegistry.getTypeUrl()` which returns `MessageSchema`, not `FieldSchema`

---

## Priority Order for Testing

### Phase 1: Foundations (cannot test anything without these)

1. **Unknown field handling** in `BinaryDecoder.nextField()` + `GeneratedMessageSchema.decodeMessage()` — blocks everything
   - Decide on sentinel/unknown descriptor approach
   - Store unknown fields as `UnknownField` with raw `WireValue`
   - Handle in both `BinaryDecoder` and `JsonDecoder`

2. **Missing primitive value types** — need at least the basic ones to encode/decode anything
   - Implement `UInt32Value`, `Int64Value`, `UInt64Value`, `SInt32Value`, `SInt64Value`
   - Implement `Fixed32Value`, `Fixed64Value`, `SFixed32Value`, `SFixed64Value`
   - Implement `BoolValue`, `FloatValue`, `DoubleValue`
   - Implement `EnumValue` (with enum companion reference)
   - Implement `BytesValue`
   - Complete `StringValue.decodeValue()`

3. **`ProtoFieldVisitor.visitField()` else branch** — handle non-primitive, non-message value types
   - Either add a fallback mechanism or ensure all value types are covered

### Phase 2: Basic Message Operations

4. **`FieldSchema.Extension.decodeField()`** — core extension decoding
   - Decode value using `ValueType.decodeValue()`
   - Store as `ExtensionValue.SingularDecoded` in `ExtensionFieldSet`

5. **`FieldSchema.RepeatedExtension.decodeField()`** — repeated extension decoding
   - Decode values using `decodeRepeatedValue()`
   - Store as `ExtensionValue.RepeatedDecoded`

6. **`FieldSchema.RepeatedExtension.visitField()`** — repeated extension encoding
   - Iterate values and call `visitor.visitRepeatedField()`

7. **`BinaryDecoder.skipValue()` for `WireType.START_GROUP`** — proto2 group support
   - Read until matching `END_GROUP` tag

8. **`FieldSchema.Map.decodeField()`** — map field decoding
   - Use the existing `FieldType.Map` decode logic as reference

### Phase 3: Message Merge

9. **`GeneratedMessage.plus()`** — message merge operator
   - Use `MessageSchema.merge()` to implement

10. **`FieldSchema.Extension.merge()`** — extension merge
    - Same logic as `ExtendableMessageSchema.mergeMessageField()`

11. **Oneof merge** — `GeneratedMessageSchema.merge()` oneof handling
    - Determine which oneof variant is set in each input
    - Select the non-null variant for the destination

### Phase 4: Extension Merge (hardest part)

12. **`ExtendableMessageSchema.decodeMessage()`** — extendable message decoding
    - Route unknown field numbers in extension ranges to extension decoding
    - Route unknown field numbers outside extension ranges to unknown fields

13. **`FieldSchema.Extension.decodeField()`** with extension schema lookup
    - When decoding from unknown fields, look up the extension schema to get the `ValueType`

14. **`ExtensionValue.Json` handling** — complete the Json variant
    - `ExtensionValue.Json` merge handling in 6 places
    - `ExtensionFieldSet.visitFields()` for `ExtensionValue.Json`
    - Decide: is Json extension storage needed, or can it be removed?

15. **Extension field merge — all 16 combination cases**
    - `SingularDecoded` + `SingularDecoded` (same schema) — merge or overwrite
    - `SingularDecoded` + `SingularDecoded` (different schema) — binary merge
    - `SingularDecoded` + `RepeatedDecoded` — binary merge
    - `SingularDecoded` + `Binary` — binary merge
    - `SingularDecoded` + `Json` — ???
    - `RepeatedDecoded` + `RepeatedDecoded` (same schema) — concatenate lists
    - `RepeatedDecoded` + `RepeatedDecoded` (different schema) — binary merge
    - `RepeatedDecoded` + `SingularDecoded` — binary merge
    - `RepeatedDecoded` + `Binary` — binary merge
    - `RepeatedDecoded` + `Json` — ???
    - `Binary` + anything — binary merge
    - `Json` + anything — ???

### Phase 5: Code Generator + Integration

16. **Code generator changes** — produce nieuw-based output
    - Generate `FieldSchema` instances instead of `FieldDescriptor`
    - Generate `GeneratedMessage` subclasses instead of sealed interfaces
    - Generate `GeneratedMessageSchema` / `ExtendableMessageSchema` in companions
    - Wire up `companion.schema` instead of `companion.valueType`

17. **Type Registry extension support**
    - Implement `getExtensionSchema()` method
    - Wire extension schemas into `TypeRegistryImpl`
    - Support `Any` message decoding with extension types

18. **`WktDurationToKotlinDuration` completion**
    - Complete `visitValue()` (truncated at line 135)
    - Implement `decodeValue()`

19. **`getFieldValue()` on GeneratedMessage** — runtime field access by schema

20. **Integration testing** — run conformance tests against nieuw-generated code
    - Binary encode/decode round-trip
    - JSON encode/decode round-trip
    - Message merge semantics
    - Extension field encode/decode/merge
    - Proto2 required fields (if supported)
    - Proto2 groups (if supported)
    - Oneof fields
    - Map fields
    - Repeated packed fields
    - `google.protobuf.Any` handling

---

## Key Architectural Questions to Answer During Testing

### Q1: Unknown Field Routing Strategy
The existing code stores unknown fields as raw `UnknownField` objects and lazily decodes them as extension fields when accessed. Nieuw's `ExtensionValue.Binary` stores raw `WireValue` lists. Shouldnieuw:
- (a) Keep the lazy decode approach (decode from unknown fields on first access)?
- (b) eagerly decode into `ExtensionValue.Binary` and only decode if the schema is known?
- (c) Something else?

### Q2: Extension Schema Registration
The existing code uses `FieldDescriptor` objects that are generated at compile time and registered on the message. Nieuw's `FieldSchema.Extension` doesn't have value accessors (no `valueFn`/`setValueFn`), so it can't be used directly to read/write extension values on a message. How should extension schemas be associated with message instances?
- (a) Generate extension field accessors on the message class (like regular fields)?
- (b) Store extension schemas separately and look them up by field number?
- (c) Generate `FieldSchema.Extension` instances that include accessor lambdas like other field schemas?

### Q3: `ExtensionValue.Json` Necessity
The `ExtensionValue.Json` variant is barely implemented and has `TODO()` in 6+ places. Is JSON-encoded extension storage actually needed? The existing code doesn't have an equivalent — it only stores binary unknown fields. If it's not needed, removing it would eliminate a large chunk of work.

### Q4: `FieldType.Required` vs `FieldSchema.ExplicitPresence`
The existing code distinguishes `Required` (proto2), `Optional` (proto2 explicit), and `Singular` (proto3 implicit). Nieuw only has `ExplicitPresence` (nullable) and `ImplicitPresence` (default-value suppression). Should nieuw support proto2 `required` fields? If not, `ExplicitPresence` covers all cases.

### Q5: Two Parallel Value Type Systems
Nieuw has its own `ValueType` hierarchy (154 lines) while the existing code has `pbandk.internal.types.ValueType` + `pbandk.internal.types.primitive.*` + `pbandk.internal.types.wkt.*` (1,300+ lines). During integration, should nieuw reuse the existing value types, or should the existing types be replaced entirely? The existing types are more complete (17 primitives vs 2 in nieuw).

### Q6: `TranslatingValueType` for WKT Wrappers
Nieuw has `TranslatingValueType<T, ProtobufType>` for custom type translations (e.g., `kotlin.time.Duration` ↔ `pbandk.wkt.Duration`). The existing code has `TranslatingMessageValueType` for messages and specific WKT value types like `DurationValueType`. Should nieuw add a `TranslatingMessageValueType` equivalent?
