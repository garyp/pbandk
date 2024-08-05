package pbandk

import pbandk.gen.messageCompanion
import pbandk.internal.types.MessageValueType
import pbandk.wkt.Any

private const val DEFAULT_TYPE_URL_PREFIX = "type.googleapis.com"

/**
 * Constructs a new [Any] instance by packing [message] using the given [typeUrlPrefix] (or a default prefix if one is
 * not provided). The type URL will be constructed by concatenating the message type's full name to the prefix with an
 * optional "/" separator if the prefix doesn't end with "/" already.
 */
public fun <T : Message> Any.Companion.pack(message: T, typeUrlPrefix: String = DEFAULT_TYPE_URL_PREFIX): Any =
    pack(message.messageCompanion.valueType, message, typeUrlPrefix)

public fun <T : kotlin.Any> Any.Companion.pack(
    valueType: MessageValueType<T, *>,
    message: T,
    typeUrlPrefix: String = DEFAULT_TYPE_URL_PREFIX
): Any = Any {
    typeUrl = "$typeUrlPrefix${if (typeUrlPrefix.endsWith('/')) "" else "/"}${valueType.descriptor.fullName}"
    value = ByteArr(valueType.encodeToByteArray(message))
}

/**
 * Returns `true` if [Any.typeUrl] matches the fully-qualified type name of [companion].
 */
public fun <T : Message> Any.isA(companion: Message.Companion<T, *>): Boolean {
    return companion.valueType.descriptor.fullName == getTypeNameFromTypeUrl(typeUrl)
}

public fun <T : kotlin.Any> Any.isA(valueType: MessageValueType<T, *>): Boolean {
    return valueType.descriptor.fullName == getTypeNameFromTypeUrl(typeUrl)
}

/**
 * Unpacks the data in [Any.value] using [companion]. Throws [InvalidProtocolBufferException] if [Any.typeUrl] does not
 * match the fully-qualified type name of [companion], or if [Any.value] does not contain a valid message.
 */
@Throws(InvalidProtocolBufferException::class)
public fun <T : Message> Any.unpack(companion: Message.Companion<T, *>): T = unpack(companion.valueType)

@Throws(InvalidProtocolBufferException::class)
public fun <T : kotlin.Any> Any.unpack(valueType: MessageValueType<T, *>): T {
    if (!isA(valueType)) {
        throw InvalidProtocolBufferException("Type of the Any message does not match the given class.")
    }
    // TODO: cache the decoded value
    return descriptor.decodeFromByteArray(value.array)
}