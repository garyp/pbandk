package pbandk

import pbandk.internal.types.MessageValueType

public interface MessageDecoder {
    @Throws(InvalidProtocolBufferException::class)
    public fun <M : Any> readMessage(messageValueType: MessageValueType<M, *>): M
}