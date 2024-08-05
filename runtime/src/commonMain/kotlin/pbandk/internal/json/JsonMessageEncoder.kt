package pbandk.internal.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import pbandk.MessageEncoder
import pbandk.internal.types.MessageValueType
import pbandk.json.JsonConfig
import pbandk.json.JsonFieldValueEncoder

internal class JsonMessageEncoder(private val jsonConfig: JsonConfig) : MessageEncoder {
    private val json = Json {
        prettyPrint = !jsonConfig.compactOutput
    }
    private val jsonFieldValueEncoder = JsonFieldValueEncoder(jsonConfig)

    fun toJsonString(): String = json.encodeToString(JsonElement.serializer(), jsonFieldValueEncoder.getResult())

    override fun <M : Any> writeMessage(message: M, messageValueType: MessageValueType<M, *>) {
//        check(currentMessage == null) { "JsonMessageEncoder can't be reused with multiple messages" }
        messageValueType.encodeToJson(message, jsonFieldValueEncoder)
    }
}