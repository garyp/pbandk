package pbandk.internal.binary

import pbandk.InvalidProtocolBufferException
import pbandk.MessageDecoder
import pbandk.internal.types.MessageValueType

internal class BinaryMessageDecoder(private val fieldDecoder: BinaryFieldDecoder) : MessageDecoder {

    override fun <M : Any> readMessage(messageValueType: MessageValueType<M, *>): M = try {
        messageValueType.decodeFieldsFromBinary(fieldDecoder)
    } catch (e: InvalidProtocolBufferException) {
        throw e
    } catch (e: Exception) {
        throw InvalidProtocolBufferException("unable to read message", e)
    }

    internal companion object
}

internal expect fun BinaryMessageDecoder.Companion.fromByteArray(arr: ByteArray): MessageDecoder