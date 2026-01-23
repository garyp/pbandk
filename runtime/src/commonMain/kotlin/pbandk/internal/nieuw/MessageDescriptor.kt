package pbandk.internal.nieuw

import pbandk.PublicForGeneratedCode
import pbandk.wkt.Syntax

public class MessageDescriptor @PublicForGeneratedCode constructor(
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