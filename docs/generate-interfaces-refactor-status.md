# Generate Interfaces Refactor Status

## Overview

The branch has **143 commits** since it diverged from master (merge base: `cde6fb4`), spanning from May 2021 to the present. The diff against master touches **223 files** with +123,563 / -65,451 lines.

## Refactor Phases

### Phase 1: Builders / Interfaces (May 2021)
Started with commit `43d9835` ("WIP: generate an interface for each message type"). This was the initial exploration of replacing data classes with sealed interfaces.

**Completed:**
- Generated messages are now `sealed interface` instead of `data class`
- Builder pattern with `MutableFoo` classes and `copy { }` DSL
- `@DslMarker` on mutable classes, builder action scoping
- `FieldDescriptor` split into `FieldMetadata` + `FieldAccessor` (via `FieldDescriptor.ofSingular/ofRepeated/ofMap/etc`)
- Renamed `MessageMap` → `MapField`, `ListWithSize` → `ListField`
- `@Deprecated` annotations with `ReplaceWith` on old APIs
- Map fields no longer generate classes for map entry types
- `decodeWith` replaced with runtime `decodeFromByteArray`/`decodeFromJsonString`

### Phase 2: FieldType / ValueType System (Jun 2021 – ongoing)
Started with commit `5e9fbca` ("WIP: add new FieldType and ValueType abstractions"). This is the deeper architectural change.

**Completed:**
- `FieldType<KotlinType>` sealed hierarchy: `Required`, `Optional`, `Singular`, `Repeated`, `Map`
- `ValueType<T>` hierarchy with `PrimitiveValueType`, `IntValueType`, etc.
- Primitive type implementations in `pbandk.internal.types.primitive.*` (17 types)
- WKT type implementations in `pbandk.internal.types.wkt.*` (16 types)
- `MessageValueType` for message types
- Binary encoding/decoding fully uses the new system (`BinaryFieldEncoder`, `BinaryFieldValueDecoder`)
- JSON encoding/decoding fully uses the new system (`JsonFieldEncoder`, `JsonFieldValueDecoder`)
- `Sizer` functionality moved into `ValueType`
- `protobuf.js` dependency removed from Kotlin/JS
- `WireValue` for wire type handling
- Extension fields fully working with `setExtension()`, full names in JSON/toString
- Proto3 extension field optional presence support
- Proto2 required fields support
- `EnumDescriptor` added
- `FieldDescriptor` rewritten with `FieldMetadata` base class + `Standard`/`Extension` subclasses

### Phase 3: Visitor Pattern / Message Descriptor Routing (Aug 2024 – Feb 2026)
Started with commit `de43dbf` ("WIP: refactor to route all message ops via message descriptor").

**Completed:**
- `ProtoFieldVisitor` in `nieuw` directory - visitor pattern for iterating over message fields
- `FieldSchema` / `MessageSchema` in `nieuw` - schema-based message representation
- `BinaryConfig.kt` - binary encoding configuration
- `MessageValueType` uses visitor pattern for encoding/decoding
- Code generator generates `FieldDescriptors` companion objects on each message type
- `UnrecognizedEnumValue` class for unknown enum values
- Group field support (proto2)

## New Runtime Infrastructure

| New File | Purpose |
|----------|---------|
| `gen/AbstractGeneratedMessage` | Base class for generated messages |
| `gen/GeneratedMessage` | Generated message companion base |
| `gen/GeneratedEnumValue` | Generated enum value base |
| `gen/GeneratedOneOf` | Oneof field descriptor |
| `gen/ListField` | Immutable list field with protoSize |
| `types/FieldType.kt` | Field type hierarchy (820 lines) |
| `types/MessageValueType.kt` | Message value type (329 lines) |
| `types/primitive/*` | 17 primitive type implementations |
| `types/wkt/*` | 16 WKT type implementations |
| `types/ValueType.kt` | Public ValueType API |
| `types/primitives.kt` | Factory functions for primitive types |
| `types/wkts.kt` | Factory functions for WKT types |
| `binary/WireValue.kt` | Wire value handling |
| `binary/BinaryFieldValueEncoder.kt` | Binary field value encoding |
| `binary/BinaryFieldValueDecoder.kt` | Binary field value decoding |
| `json/JsonFieldValueEncoder.kt` | JSON field value encoding |
| `json/JsonFieldValueDecoder.kt` | JSON field value decoding |
| `internal/nieuw/*` | Experimental visitor/schema system |

## Deleted Legacy Code

- `internal/binary/KotlinBinaryWireDecoder.kt` (264 lines) - replaced by new decoder
- `internal/binary/KotlinBinaryWireEncoder.kt` (266 lines) - replaced by new encoder
- `internal/binary/Sizer.kt` - moved into ValueType
- `internal/binary/AbstractSizer.kt` - moved into ValueType
- `internal/binary/BinaryWireDecoder.kt` - replaced
- `internal/binary/BinaryWireEncoder.kt` - replaced
- `internal/binary/Utf8Len.kt` - removed
- `internal/json/JsonMessageAdapters.kt` - replaced by visitor pattern
- `internal/json/JsonValueDecoder.kt` - replaced by JsonFieldValueDecoder
- `internal/json/JsonValueEncoder.kt` - replaced by JsonFieldValueEncoder
- `ListWithSize.kt` - renamed to ListField
- `MessageMap.kt` - removed (map fields now use built-in Kotlin maps)

## What Still Needs Work (from TODO.md)

**Immediate priorities:**
- [ ] Replace interfaces with classes in generated code (for Kotlin/JS `@JsExport` compatibility)
- [ ] Update unknown field code gen to work with new UnknownField type
- [ ] Write unit test for recursively-nested protobuf message
- [ ] `FieldType.Required.visitField()` is `TODO("Not yet implemented")`
- [ ] `ProtoFieldVisitor.decodeAny()` has `TODO()` placeholder
- [ ] `ValueType.kt` has `TODO()` in `WktDurationToKotlinDuration.decodeValue()`

**Later items:**
- [ ] Add support for parsing extension fields during decoding (via `ExtensionRegistry`)
- [ ] Implement consistent configurable unknown field handling
- [ ] Generate accessors for extension fields nested in messages
- [ ] `Any.unpack(TypeRegistry)` method
- [ ] JSON decoding of `Any` with unknown type should not fail when `ignoreUnknownFieldsInInput == true`
- [ ] Store unknown JSON fields (like binary unknown fields)
- [ ] Explore delegated properties for generated code

## Current State Summary

The refactor is **~80% complete**. The core architecture (interfaces, builders, FieldType/ValueType system, encoding/decoding) is implemented and generates working code. The generated code compiles and the tests pass. However:

1. There are **two parallel implementations** coexisting: the current `FieldType`/`ValueType` system (in `internal/types/`) and the newer visitor-based `nieuw` system (in `internal/nieuw/`). The generated code currently uses the `internal/types/` path.

2. The `nieuw` system (visitor pattern + schema-based) represents the **future direction** but is not yet fully integrated into code generation.

3. Several `TODO()` placeholders remain in the encoding/decoding paths, particularly for required fields and WKT type translation.

4. The branch has been kept in sync with master via cherry-picks (411 commits on the branch include ~270 cherry-picks from master for bug fixes, Kotlin version updates, conformance test updates, etc.).
