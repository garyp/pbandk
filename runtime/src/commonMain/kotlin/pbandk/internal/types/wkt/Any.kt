package pbandk.internal.types.wkt

import pbandk.InvalidProtocolBufferException
import pbandk.getTypeNameFromTypeUrl
import pbandk.getTypePrefixFromTypeUrl
import pbandk.getTypeUrl
import pbandk.internal.json.JsonFieldDecoder
import pbandk.internal.types.MessageValueType
import pbandk.internal.types.PbandkMessageValueType
import pbandk.internal.types.customJsonMappings
import pbandk.json.JsonFieldValueDecoder
import pbandk.json.JsonFieldValueEncoder
import pbandk.pack
import pbandk.unpack
import pbandk.wkt.Any
import pbandk.wkt.MutableAny

internal object Any : PbandkMessageValueType<Any, MutableAny>(Any.descriptor) {
    override fun encodeToJson(value: Any, encoder: JsonFieldValueEncoder) {
        val valueType = encoder.jsonConfig.typeRegistry.getTypeUrl(value.typeUrl)
            ?: throw InvalidProtocolBufferException("Type URL not found in type registry: ${value.typeUrl}")
        encodeToJson(valueType, value, encoder)
    }

    override fun decodeFromJson(decoder: JsonFieldValueDecoder): Any {
        if (decoder !is JsonFieldValueDecoder.Object) {
            throw InvalidProtocolBufferException("Unexpected JSON type for message value: ${decoder.wireType.name}")
        }

        return decoder.decodeFields { fieldDecoder ->
            val typeUrl = fieldDecoder.findField("@type") { valueDecoder ->
                if (valueDecoder !is JsonFieldValueDecoder.String) {
                    throw InvalidProtocolBufferException("'@type' field in google.protobuf.Any messages must contain a string")
                }
                valueDecoder.decodeAsString()
            } ?: throw InvalidProtocolBufferException("'@type' field not found in google.protobuf.Any message")

            val valueType = fieldDecoder.jsonConfig.typeRegistry.getTypeUrl(typeUrl)
                ?: throw InvalidProtocolBufferException("Type URL not found in type registry: $typeUrl")
            decodeFromJson(valueType, typeUrl, fieldDecoder)
        }
    }
}

private fun findValueField(keyDecoder: JsonFieldValueDecoder.String): Boolean {
    val key = keyDecoder.decodeAsString()
    return when {
        key == "value" -> true
        keyDecoder.jsonConfig.ignoreUnknownFieldsInInput -> false
        else -> throw InvalidProtocolBufferException("Unknown field name and ignoreUnknownFieldsInInput=false: $key")
    }
}

// helper function to make type checker happy
private inline fun <T : kotlin.Any> encodeToJson(
    valueType: MessageValueType<T, *>,
    value: Any,
    encoder: JsonFieldValueEncoder,
) {
    val unpackedMessage = value.unpack(valueType)
    encoder.encodeObject { fieldEncoder ->
        fieldEncoder.encodeField("@type") { it.encodeString(value.typeUrl) }
        val customValueType = customJsonMappings[valueType.descriptor]
        if (customValueType != null) {
            @Suppress("UNCHECKED_CAST")
            customValueType as MessageValueType<*, T>

            fieldEncoder.encodeField("value") {
                customValueType.encodeMessageToJson(unpackedMessage, it)
            }
        } else {
            valueType.encodeFieldsToJson(unpackedMessage, fieldEncoder)
        }
    }
}

// helper function to make type checker happy
private inline fun <M : kotlin.Any> decodeFromJson(
    valueType: MessageValueType<M, *>,
    typeUrl: String,
    fieldDecoder: JsonFieldDecoder,
): Any {
    val message = customJsonMappings[valueType.descriptor]?.let { customValueType ->
        @Suppress("UNCHECKED_CAST")
        customValueType as MessageValueType<*, M>

        var message: M? = null
        fieldDecoder.forEachField { keyDecoder, valueDecoder ->
            if (findValueField(keyDecoder)) {
                message = customValueType.decodeMessageFromJson(valueDecoder)
            } else {
                valueDecoder.skipValue()
            }
        }
        message ?: throw InvalidProtocolBufferException(
            "'value' field not found in google.protobuf.Any message containing a '${getTypeNameFromTypeUrl(typeUrl)}' message"
        )
    } ?: valueType.decodeFieldsFromJson(fieldDecoder)

    return if ('/' in typeUrl) {
        Any.pack(valueType, message, getTypePrefixFromTypeUrl(typeUrl))
    } else {
        Any.pack(valueType, message)
    }
}