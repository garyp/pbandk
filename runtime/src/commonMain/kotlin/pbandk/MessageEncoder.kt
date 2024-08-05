package pbandk

import pbandk.internal.types.MessageValueType

public interface MessageEncoder {
    public fun <M : Any> writeMessage(message: M, messageValueType: MessageValueType<M, *>)
}