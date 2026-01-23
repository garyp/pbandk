package pbandk.internal.nieuw

@DslMarker
public annotation class TypeRegistryDsl

/**
 * A `TypeRegistry` is used to resolve `Any` messages in the JSON conversion. You must provide a `TypeRegistry`
 * containing all message types used in `Any` message fields, or the JSON conversion will fail because data in `Any`
 * message fields is unrecognizable. You don't need to supply a `TypeRegistry` if you don't use `Any` message fields.
 */
public interface TypeRegistry {
    public operator fun contains(typeName: String): Boolean

//    public fun containsExtension(extensionName: String): Boolean

    /**
     * Get a message type by its full name. Returns null if it cannot be found in this [TypeRegistry].
     */
    public fun getMessageSchema(typeName: String): MessageSchema<*>?

//    public fun getExtensionSchema(extensionName: String): FieldSchema<*, *, *>?

    @TypeRegistryDsl
    public interface Builder : TypeRegistry {
        /**
         * Add message [schema] to the registry and recursively add the schemas of all fields referenced by
         * this message schema.
         */
        public fun add(schema: MessageSchema<*>)

        public fun add(extension: FieldSchema<*, *, *>)
    }

    public companion object {
        public val EMPTY: TypeRegistry = TypeRegistryImpl()
    }
}

/**
 * Returns the type name after the last '/' character in [typeUrl].
 */
internal fun getTypeNameFromTypeUrl(typeUrl: String): String {
    return typeUrl.substringAfterLast('/', "")
}

/**
 * Returns the type prefix before the last '/' character in [typeUrl].
 */
internal fun getTypePrefixFromTypeUrl(typeUrl: String): String {
    return typeUrl.substringBeforeLast('/')
}

/**
 * Checks if the type represented by [typeUrl] is contained in this registry.
 */
public fun TypeRegistry.containsTypeUrl(typeUrl: String): Boolean = contains(getTypeNameFromTypeUrl(typeUrl))

/**
 * Returns the type represented by [typeUrl] from this registry, or `null` if not found.
 */
public fun TypeRegistry.getByTypeUrl(typeUrl: String): MessageSchema<*>? =
    getMessageSchema(getTypeNameFromTypeUrl(typeUrl))

public operator fun TypeRegistry.contains(messageCompanion: Message.Companion<*>): Boolean =
    contains(messageCompanion.schema)

public operator fun TypeRegistry.contains(schema: MessageSchema<*>): Boolean =
    getMessageSchema(schema.descriptor.fullName) == schema

/**
 * Add the descriptor from [messageCompanion] to the registry and recursively add the descriptors of all fields
 * referenced by this message descriptor.
 */
public fun TypeRegistry.Builder.add(messageCompanion: Message.Companion<*>) {
    add(messageCompanion.schema)
}

public fun typeRegistry(builderAction: TypeRegistry.Builder.() -> Unit): TypeRegistry {
    val typeRegistry = TypeRegistryImpl()
    typeRegistry.builderAction()
    return typeRegistry
}