package pbandk.internal.nieuw

internal class TypeRegistryImpl : TypeRegistry.Builder {
    private val messageRegistry = mutableMapOf<String, MessageSchema<*>>()

    // Map of extendee message name -> field number -> extension field schema
    private val extensionRegistry = mutableMapOf<String, MutableMap<Int, FieldSchema<*, *, *>>>()

    override operator fun contains(typeName: String) = typeName in messageRegistry

    override fun getMessageSchema(typeName: String) = messageRegistry[typeName]

    override fun add(schema: MessageSchema<*>) {
        if (schema.descriptor.fullName in messageRegistry) return

        messageRegistry[schema.descriptor.fullName] = schema
        if (schema !is GeneratedMessageSchema<*, *>) return

        for (fieldSchema in schema.fields) {
            when (fieldSchema) {
                is FieldSchema.SingleValue<*, *, *> -> (fieldSchema.valueType as? MessageSchema<*>)?.let { add(it) }
                is FieldSchema.Repeated<*, *, *> -> (fieldSchema.valueType as? MessageSchema<*>)?.let { add(it) }
                is FieldSchema.Map<*, *, *, *> -> (fieldSchema.valueValueType as? MessageSchema<*>)?.let { add(it) }
                is FieldSchema.RepeatedExtension<*, *, *> -> (fieldSchema.valueType as? MessageSchema<*>)?.let { add(it) }
            }
        }
    }

    override fun add(extension: FieldSchema<*, *, *>) {
        if (extension !is FieldSchema.Extension) return

        extensionRegistry
            .getOrPut(extension.descriptor.extendeeDescriptor.fullName) { mutableMapOf() }
            .getOrPut(extension.descriptor.number) { extension }

        (extension.valueType as? MessageSchema<*>)?.let { add(it) }
    }
}