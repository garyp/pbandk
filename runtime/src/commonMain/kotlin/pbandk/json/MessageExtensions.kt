package pbandk.json

import pbandk.ExperimentalProtoJson
import pbandk.Export
import pbandk.InvalidProtocolBufferException
import pbandk.Message
import pbandk.gen.messageCompanion
import pbandk.internal.json.JsonMessageEncoder
import pbandk.internal.json.JsonMessageDecoder
import pbandk.internal.types.MessageValueType

/**
 * Encode this message to a String using the protocol buffer JSON encoding.
 */
@ExperimentalProtoJson
@Export
public fun <T : Message> T.encodeToJsonString(jsonConfig: JsonConfig = JsonConfig.DEFAULT): String =
    messageCompanion.valueType.encodeToJsonString(this, jsonConfig)

@ExperimentalProtoJson
@Export
public fun <T : Any> MessageValueType<T, *>.encodeToJsonString(
    message: T,
    jsonConfig: JsonConfig = JsonConfig.DEFAULT
): String = JsonMessageEncoder(jsonConfig).also { it.writeMessage(message, this) }.toJsonString()

/**
 * Decode a JSON protocol buffer message from [data].
 */
@ExperimentalProtoJson
@Export
@Throws(InvalidProtocolBufferException::class)
public fun <T : Message> Message.Companion<T, *>.decodeFromJsonString(
    data: String,
    jsonConfig: JsonConfig = JsonConfig.DEFAULT
): T = valueType.decodeFromJsonString(data, jsonConfig)

@ExperimentalProtoJson
@Export
@Throws(InvalidProtocolBufferException::class)
public fun <T : Any> MessageValueType<T, *>.decodeFromJsonString(
    data: String,
    jsonConfig: JsonConfig = JsonConfig.DEFAULT
): T = JsonMessageDecoder.fromString(data, jsonConfig).readMessage(this)