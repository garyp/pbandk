package pbandk.internal.nieuw

public class FieldSchemaSet<M : Any, MM : Any>(
    private val fields: Collection<FieldSchema<M, MM, out Any?>>
) : Collection<FieldSchema<M, MM, out Any?>> by fields {
    public operator fun get(fieldNumber: Int): FieldSchema<M, MM, out Any?>? =
        fields.firstOrNull { it.descriptor.number == fieldNumber }

    public operator fun get(fieldName: String): FieldSchema<M, MM, out Any?>? =
        fields.firstOrNull { it.descriptor.name == fieldName }

    public val descriptorSet: FieldDescriptorSet = FieldDescriptorSet(fields.map { it.descriptor })
}

public class FieldDescriptorSet internal constructor(
    private val fields: Collection<FieldDescriptor>,
) : Collection<FieldDescriptor> by fields {
    public operator fun get(fieldNumber: Int): FieldDescriptor? = fields.firstOrNull { it.number == fieldNumber }
    public operator fun get(fieldName: String): FieldDescriptor? = fields.firstOrNull { it.name == fieldName }
}