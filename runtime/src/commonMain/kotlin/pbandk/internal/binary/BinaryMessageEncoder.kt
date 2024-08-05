package pbandk.internal.binary

import pbandk.MessageEncoder
import pbandk.internal.types.MessageValueType

internal open class BinaryMessageEncoder(private val fieldEncoder: BinaryFieldEncoder) : MessageEncoder {
    override fun <M : Any> writeMessage(message: M, messageValueType: MessageValueType<M, *>) {
        messageValueType.encodeFieldsToBinary(message, fieldEncoder)
    }

    companion object
}

internal expect fun BinaryMessageEncoder.Companion.allocate(size: Int): ByteArrayMessageEncoder

internal interface ByteArrayMessageEncoder : MessageEncoder {
    fun toByteArray(): ByteArray
}