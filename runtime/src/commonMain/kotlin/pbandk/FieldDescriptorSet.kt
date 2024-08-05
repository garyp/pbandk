package pbandk

public class FieldDescriptorSet<M : Any, MM : Any>(
    private val fields: Collection<FieldDescriptor<M, MM, out Any?>>
) : Collection<FieldDescriptor<M, MM, out Any?>> by fields {
    public operator fun get(fieldNumber: Int): FieldDescriptor<M, MM, out Any?>? =
        fields.firstOrNull { it.number == fieldNumber }

    public operator fun get(fieldName: String): FieldDescriptor<M, MM, out Any?>? =
        fields.firstOrNull { it.name == fieldName }
}