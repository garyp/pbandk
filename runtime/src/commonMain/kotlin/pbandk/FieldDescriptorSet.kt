package pbandk

public class FieldDescriptorSet<M : Any, MM : Any>(
    private val fields: Collection<FieldDescriptor<M, MM, out Any?>>
) : Collection<FieldDescriptor<M, MM, out Any?>> by fields {
    public operator fun get(fieldNumber: Int): FieldDescriptor<M, MM, out Any?>? =
        fields.firstOrNull { it.number == fieldNumber }

    public operator fun get(fieldName: String): FieldDescriptor<M, MM, out Any?>? =
        fields.firstOrNull { it.name == fieldName }

    internal val metadataSet: FieldMetadataSet = FieldMetadataSet(fields.map { it.metadata })
}

public class FieldMetadataSet internal constructor(
    private val fields: Collection<FieldMetadata>,
) : Collection<FieldMetadata> by fields {
    public operator fun get(fieldNumber: Int): FieldMetadata? = fields.firstOrNull { it.number == fieldNumber }
    public operator fun get(fieldName: String): FieldMetadata? = fields.firstOrNull { it.name == fieldName }
}