package pbandk

import pbandk.gen.ExtendableMessageFieldDescriptors
import pbandk.wkt.Syntax

public class MessageMetadata @PublicForGeneratedCode constructor(
    /**
     * The message type's fully-qualified name, within the proto language's namespace. This differs from
     * the Kotlin name. For example, given this `.proto`:
     *
     * ```proto
     *   package foo.bar;
     *   option java_package = "com.example.protos"
     *   message Baz {}
     * ```
     *
     * `Baz`'s [fullName] is "foo.bar.Baz".
     */
    public val fullName: String,

    // The syntax used in the file that this message was defined in. Eventually when pbandk is generating
    // FileDescriptors too, then this can reference the value from the FileDescriptor. For now, we store it in each
    // message separately.
    internal val syntax: Syntax,
) {
    /** The message type's unqualified name. */
    public val name: String = fullName.substringAfterLast('.')
}

@Export
public abstract class MessageDescriptor<M : Any, MM : Any> internal constructor() {
    internal abstract val metadata: MessageMetadata
    internal abstract val builder: (MM.() -> Unit) -> M
    @ExperimentalProtoReflection
    public abstract val fields: FieldDescriptorSet<M, MM>
    internal abstract val oneofs: Collection<OneofDescriptor<M, MM, *>>

    @PublicForGeneratedCode
    public abstract fun addFields(vararg fields: FieldDescriptor<M, MM, *>)

    @PublicForGeneratedCode
    public abstract fun addOneofs(vararg oneofs: OneofDescriptor<M, MM, *>)

    @PublicForGeneratedCode
    public abstract fun finalize()

    /**
     * Returns the default value for this message type. Can throw an exception if [M] is a message with `required`
     * fields, since such messages do not have a default value.
     */
    @get:Throws(UnsupportedOperationException::class)
    public val defaultInstance: M by lazy(LazyThreadSafetyMode.PUBLICATION) { builder {} }

    /**
     * The message type's fully-qualified name, within the proto language's namespace. This differs from
     * the Kotlin name. For example, given this `.proto`:
     *
     * ```proto
     *   package foo.bar;
     *   option java_package = "com.example.protos"
     *   message Baz {}
     * ```
     *
     * `Baz`'s [fullName] is "foo.bar.Baz".
     */
    public val fullName: String get() = metadata.fullName

    /** The message type's unqualified name. */
    public val name: String get() = metadata.name

    internal fun fieldDescriptors(message: M, ordered: Boolean = false): Collection<FieldDescriptor<M, MM, out Any?>> =
        if (message !is ExtendableMessage<*> || message.extensionFields.isEmpty()) {
            fields
        } else {
            ExtendableMessageFieldDescriptors(ordered, fields, message.extensionFields as FieldSet<M, MM>)
        }

    internal fun computeBinarySize(message: M): Int {
        var size = 0

        fieldDescriptors(message).forEach { fd ->
            size += fd.binarySize(message)
        }

        size += unknownFields(message).values.sumOf { it.size }
        return size
    }

    internal open fun protoSize(message: M): Int {
        return computeBinarySize(message)
    }

    internal abstract fun unknownFields(message: M): Map<Int, UnknownField>
    internal abstract fun unknownFields(mutableMessage: MM): MutableMap<Int, UnknownField>
}

@PublicForGeneratedCode
public fun <M : Message, MM : MutableMessage<M>> messageDescriptor(
    metadata: MessageMetadata,
    builder: (MM.() -> Unit) -> M,
): MessageDescriptor<M, MM> = PbandkMessageDescriptor(
    metadata = metadata,
    builder = builder,
)

private open class PbandkMessageDescriptor<M : Message, MM : MutableMessage<M>>(
    override val metadata: MessageMetadata,
    override val builder: (MM.() -> Unit) -> M,
) : MessageDescriptor<M, MM>() {
    private val _fields = mutableListOf<FieldDescriptor<M, MM, *>>()
    final override lateinit var fields: FieldDescriptorSet<M, MM>
        private set

    private val _oneofs: MutableList<OneofDescriptor<M, MM, *>> = mutableListOf()
    override val oneofs: Collection<OneofDescriptor<M, MM, *>> by ::_oneofs

    override fun unknownFields(message: M) = message.unknownFields
    override fun unknownFields(mutableMessage: MM) = mutableMessage.unknownFields

    // Delegate to the message so that we can use the cached protoSize value
    override fun protoSize(message: M) = message.protoSize

    override fun addFields(vararg fields: FieldDescriptor<M, MM, *>) {
        require(fields.none { it.metadata.isOneofMember }) {
            "Fields that are part of a oneof must be added only via addOneofs()"
        }
        _fields.addAll(fields)
    }

    override fun addOneofs(vararg oneofs: OneofDescriptor<M, MM, *>) {
        _oneofs.addAll(oneofs)
        _fields.addAll(oneofs.flatMap { it.fields })
    }

    override fun finalize() {
        // Keep fields sorted by number so that message encoding outputs fields in order by number. This is not
        // required by the protobuf encoding spec, but is recommended (and is implemented by the official C++, Java,
        // and Python implementations).
        _fields.sortBy { it.number }
        fields = FieldDescriptorSet(_fields)
    }
}

private class ExtendableMessageDescriptor<M : ExtendableMessage<M>, MM : MutableExtendableMessage<M>>(
    metadata: MessageMetadata,
    builder: (MM.() -> Unit) -> M,
) : PbandkMessageDescriptor<M, MM>(metadata, builder) {
    override fun protoSize(message: M): Int {
        return super.protoSize(message) +
    }
}