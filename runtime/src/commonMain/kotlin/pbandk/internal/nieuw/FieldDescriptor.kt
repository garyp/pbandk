package pbandk.internal.nieuw

import pbandk.ExperimentalProtoReflection
import pbandk.MessageEncoding
import pbandk.wkt.FeatureSet
import pbandk.wkt.FieldOptions
import pbandk.wkt.orDefault

public sealed class FieldDescriptor(
    /** The field's unqualified name. */
    @ExperimentalProtoReflection
    public val name: String,
    internal val number: Int,
    internal val jsonName: String,
    internal val hasPresence: Boolean,
    internal val isOneofMember: Boolean,
    internal val isExtension: Boolean,
    private val _options: FieldOptions? = null,
) {
    // This has to be indirect to avoid a circular dependency when initializing the [FieldOptions]
    // class, which has its own [FieldDescriptor]s that it tries to initialize.
    @ExperimentalProtoReflection
    public val options: FieldOptions get() = _options.orDefault()

    internal val messageEncoding: MessageEncoding = when (val encoding = _options?.features?.messageEncoding) {
        null, FeatureSet.MessageEncoding.LENGTH_PREFIXED -> MessageEncoding.LENGTH_PREFIXED
        FeatureSet.MessageEncoding.DELIMITED -> MessageEncoding.DELIMITED
        else -> throw IllegalStateException("Unexpected value for features.message_encoding=$encoding on message '$name'")
    }

    /**
     * The field's fully-qualified name.
     *
     * For a regular field, this will be the fully-qualified name of the field's enclosing message combined with the
     * field's [name].
     *
     * For an extension field, the fully-qualified name describes where the extension field is defined, _not_ which
     * message it extends. In other words, the `fullName` of these two extension fields:
     *
     * ```proto
     * package foo.bar;
     * extend Baz {
     *   optional int32 usage_count = 200;
     * }
     * message Fizz {
     *   extend Baz {
     *     optional string fizz_label = 300;
     *   }
     * }
     * ```
     *
     * is `foo.bar.usage_count` and `foo.bar.Fizz.fizz_label`, respectively. `Baz`, the message being extended, is not
     * part of the extension field's name.
     */
    @ExperimentalProtoReflection
    public abstract val fullName: String

    internal class Standard(
        private val messageDescriptor: MessageDescriptor,
        name: String,
        number: Int,
        jsonName: String,
        isOneofMember: Boolean,
        options: FieldOptions? = null,
    ) : FieldDescriptor(
        name = name,
        number = number,
        jsonName = jsonName,
        isOneofMember = isOneofMember,
        isExtension = false,
        _options = options,
    ) {
        override val fullName get() = "${messageDescriptor.fullName}.${name}"
    }

    internal class Extension(
        internal val extendeeDescriptor: MessageDescriptor,
        extensionName: String,
        number: Int,
        jsonName: String,
        options: FieldOptions? = null,
    ) : FieldDescriptor(
        name = extensionName.substringAfterLast('.'),
        number = number,
        jsonName = jsonName,
        isOneofMember = false,
        isExtension = true,
        _options = options,
    ) {
        override val fullName = extensionName
    }
}