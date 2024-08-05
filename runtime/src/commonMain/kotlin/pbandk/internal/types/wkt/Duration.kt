package pbandk.internal.types.wkt

import pbandk.InvalidProtocolBufferException
import pbandk.MessageDescriptor
import pbandk.binary.BinaryFieldValueDecoder
import pbandk.binary.BinaryFieldValueEncoder
import pbandk.binary.WireType
import pbandk.binary.tryDecodeField
import pbandk.internal.PlatformUtil
import pbandk.internal.binary.BinaryFieldDecoder
import pbandk.internal.binary.BinaryFieldEncoder
import pbandk.internal.types.MessageValueType
import pbandk.internal.types.PbandkMessageValueType
import pbandk.json.JsonFieldValueDecoder
import pbandk.json.JsonFieldValueEncoder
import pbandk.wkt.Duration
import pbandk.wkt.MutableDuration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

internal object DurationNew : MessageValueType<kotlin.time.Duration, Duration>() {
    override val descriptor = Duration.valueType.descriptor

    private val secondsField = Duration.FieldDescriptors.seconds
    private val nanosField = Duration.FieldDescriptors.nanos

    override val defaultValue = kotlin.time.Duration.ZERO

    override fun isDefaultValue(value: kotlin.time.Duration) = false

    // Both fields of `google.protobuf.Duration` are scalar types, so the result of merging two `Duration` messages will
    // be equal to the contents of the second message as long as it's non-null.
    override fun mergeValues(currentValue: kotlin.time.Duration, newValue: kotlin.time.Duration) = newValue

    override val binaryWireType = WireType.LENGTH_DELIMITED

    override fun binarySize(value: kotlin.time.Duration): Int {
        return value.toComponents { seconds, nanoseconds ->
            secondsField.fieldType.binarySize(secondsField.metadata, seconds) +
                    nanosField.fieldType.binarySize(nanosField.metadata, nanoseconds)
        }
    }

    override fun encodeFieldsToBinary(value: kotlin.time.Duration, fieldEncoder: BinaryFieldEncoder) {
        value.toComponents { seconds, nanoseconds ->
            secondsField.fieldType.encodeToBinary(secondsField.metadata, seconds, fieldEncoder)
            nanosField.fieldType.encodeToBinary(nanosField.metadata, nanoseconds, fieldEncoder)
        }
    }

    override fun encodeToBinary(value: kotlin.time.Duration, encoder: BinaryFieldValueEncoder) {
        encoder.encodeLenFields(binarySize(value)) { fieldEncoder -> encodeFieldsToBinary(value, fieldEncoder) }
    }

    override fun decodeFieldsFromBinary(fieldDecoder: BinaryFieldDecoder): kotlin.time.Duration {
        var seconds = 0L
        var nanoseconds = 0

        fieldDecoder.forEachField { fieldNumber, valueDecoder ->
            when {
                valueDecoder.tryDecodeField(secondsField, fieldNumber) { seconds = it } -> {}
                valueDecoder.tryDecodeField(nanosField, fieldNumber) { nanoseconds = it } -> {}
                else -> valueDecoder.skipValue()
            }
        }

        return seconds.seconds + nanoseconds.nanoseconds
    }

    override fun decodeFromBinary(decoder: BinaryFieldValueDecoder): kotlin.time.Duration {
        if (decoder !is BinaryFieldValueDecoder.Len) {
            throw InvalidProtocolBufferException("Unexpected wire type for message value: ${decoder.wireType}")
        }
        return decoder.decodeFields(::decodeFieldsFromBinary)
    }

    override fun encodeToJson(value: kotlin.time.Duration, encoder: JsonFieldValueEncoder) {
        value.toComponents { seconds, nanoseconds ->
            encoder.encodeString(PlatformUtil.durationToString(Duration {
                this.seconds = seconds
                this.nanos = nanoseconds
            }))
        }
    }

    override fun encodeMessageToJson(message: Duration, encoder: JsonFieldValueEncoder) {
        encoder.encodeString(PlatformUtil.durationToString(message))
    }

    override fun encodeToJsonMapKey(value: kotlin.time.Duration) =
        throw UnsupportedOperationException("google.protobuf.Duration cannot be used as a map key")

    override fun decodeFromJson(decoder: JsonFieldValueDecoder): kotlin.time.Duration {
        if (decoder !is JsonFieldValueDecoder.String) {
            throw InvalidProtocolBufferException("Unexpected JSON type for google.protobuf.Duration value: ${decoder.wireType.name}")
        }
        val wktDuration = PlatformUtil.stringToDuration(decoder.decodeAsString())
        return wktDuration.seconds.seconds
            .plus(wktDuration.nanos.nanoseconds)
    }

    override fun decodeMessageFromJson(decoder: JsonFieldValueDecoder): Duration {
        if (decoder !is JsonFieldValueDecoder.String) {
            throw InvalidProtocolBufferException("Unexpected JSON type for google.protobuf.Duration value: ${decoder.wireType.name}")
        }
        return PlatformUtil.stringToDuration(decoder.decodeAsString())
    }

    override fun decodeFromJsonMapKey(decoder: JsonFieldValueDecoder.String) =
        throw UnsupportedOperationException("google.protobuf.Duration cannot be used as a map key")
}

internal class Duration(
    descriptor: MessageDescriptor<Duration, MutableDuration>
) : PbandkMessageValueType<Duration, MutableDuration>(descriptor) {
    override fun encodeToJson(value: Duration, encoder: JsonFieldValueEncoder) {
        encoder.encodeString(PlatformUtil.durationToString(value))
    }

    override fun decodeFromJson(decoder: JsonFieldValueDecoder): Duration {
        if (decoder !is JsonFieldValueDecoder.String) {
            throw InvalidProtocolBufferException("Unexpected JSON type for google.protobuf.Duration value: ${decoder.wireType.name}")
        }
        return PlatformUtil.stringToDuration(decoder.decodeAsString())
    }
}