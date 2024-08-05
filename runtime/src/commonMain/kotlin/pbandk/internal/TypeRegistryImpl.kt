package pbandk.internal

import pbandk.TypeRegistry
import pbandk.internal.types.FieldType
import pbandk.internal.types.MessageValueType

internal class TypeRegistryImpl : TypeRegistry.Builder {
    private val registry = mutableMapOf<String, MessageValueType<*, *>>()

    override operator fun contains(typeName: String) = typeName in registry

    override operator fun get(typeName: String) = registry[typeName]

    override fun add(valueType: MessageValueType<*, *>) {
        if (valueType.descriptor.fullName in registry) return

        registry[valueType.descriptor.fullName] = valueType
        for (fieldType in valueType.descriptor.fields.map { it.fieldType }) {
            when (fieldType) {
                is FieldType.Map<*, *> -> (fieldType.valueValueType as? MessageValueType<*, *>)?.let {
                    add(it)
                }
                is FieldType.Repeated<*> -> (fieldType.valueType as? MessageValueType<*, *>)?.let {
                    add(it)
                }

                is FieldType.Optional<*> -> (fieldType.valueType as? MessageValueType<*, *>)?.let {
                    add(it)
                }

                is FieldType.Singular<*> -> (fieldType.valueType as? MessageValueType<*, *>)?.let {
                    add(it)
                }

                is FieldType.Required<*> -> (fieldType.valueType as? MessageValueType<*, *>)?.let {
                    add(it)
                }
            }
        }
    }
}