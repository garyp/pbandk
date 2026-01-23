package pbandk.internal.nieuw

import pbandk.Export

@DslMarker
public annotation class BinaryConfigDsl

@Export
public interface BinaryConfig {
    /**
     * The provided [TypeRegistry] will be used to decode any extension fields store within a binary message. If an
     * extension is not found in [typeRegistry] during binary decoding of an extension field (based on the field
     * number), then decoding of the field will be deferred until the extension field is accessed. Note that deferred
     * decoding of extension fields will re-decode the field on every access; whereas if the extension is found in
     * [typeRegistry] at message decoding time, then the decoding will only happen once.
     */
    public val typeRegistry: TypeRegistry

    @BinaryConfigDsl
    public interface Builder : BinaryConfig {
        override var typeRegistry: TypeRegistry
    }

    public companion object {
        public val DEFAULT: BinaryConfig = BinaryConfigImpl()
    }
}

public fun binaryConfig(builderAction: BinaryConfig.Builder.() -> Unit): BinaryConfig {
    val binaryConfig = BinaryConfigImpl()
    binaryConfig.builderAction()
    return binaryConfig
}

internal class BinaryConfigImpl : BinaryConfig.Builder {
    override var typeRegistry: TypeRegistry = TypeRegistry.EMPTY
}