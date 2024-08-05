package pbandk

import pbandk.gen.messageCompanion
import pbandk.internal.binary.BinaryDecoderContext
import pbandk.internal.binary.BinaryFieldEncoder
import pbandk.internal.binary.BinaryMessageDecoder
import pbandk.internal.binary.BinaryMessageEncoder
import pbandk.internal.binary.InputStreamWireReader
import pbandk.internal.binary.OutputStreamWireWriter
import pbandk.internal.binary.fromByteBuffer
import pbandk.internal.binary.fromInputStream
import pbandk.internal.types.MessageValueType
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Encode this message to [stream] using the protocol buffer binary encoding.
 *
 * @return number of bytes written to [stream]
 */
public fun <T : Message> T.encodeToStream(stream: OutputStream): Int =
    messageCompanion.valueType.encodeToStream(this, stream)

public fun <T : Any> MessageValueType<T, *>.encodeToStream(message: T, stream: OutputStream): Int {
    val wireWriter = OutputStreamWireWriter(stream)
    BinaryMessageEncoder(BinaryFieldEncoder(wireWriter)).writeMessage(message, this)
    wireWriter.flush()
    return wireWriter.totalBytesWritten
}

/**
 * Decode a binary protocol buffer message from [stream].
 */
@Throws(InvalidProtocolBufferException::class)
public fun <T : Message> Message.Companion<T, *>.decodeFromStream(
    stream: InputStream,
    expectedSize: Int = -1
): T = valueType.decodeFromStream(stream, expectedSize)

@Throws(InvalidProtocolBufferException::class)
public fun <T : Any> MessageValueType<T, *>.decodeFromStream(
    stream: InputStream,
    expectedSize: Int = -1
): T = BinaryMessageDecoder.fromInputStream(stream, expectedSize).readMessage(this)

/**
 * Decode a binary protocol buffer message from [buffer]. The data starting from the ByteBuffer's current position to
 * its limit will be read. Note that the ByteBuffer's position won't be changed by this function.
 */
@Throws(InvalidProtocolBufferException::class)
public fun <T : Message> Message.Companion<T, *>.decodeFromByteBuffer(buffer: ByteBuffer): T =
    valueType.decodeFromByteBuffer(buffer)


@Throws(InvalidProtocolBufferException::class)
public fun <T : Any> MessageValueType<T, *>.decodeFromByteBuffer(buffer: ByteBuffer): T =
    BinaryMessageDecoder.fromByteBuffer(buffer).readMessage(this)

/**
 * Decode the next message of type [T] from the [stream] of size-delimited binary protocol buffer messages. `null` is
 * returned when all messages have been read. The caller is responsible for closing [stream].
 *
 * Supports the same encoding as is used by the Java methods `Message.writeDelimitedTo(OutputStream)` and
 * `Message.parseDelimitedFrom(InputStream)`.
 *
 * @see [encodeDelimitedToStream]
 */
@Throws(InvalidProtocolBufferException::class)
public fun <T : Message> Message.Companion<T, *>.decodeDelimitedFromStream(stream: InputStream): T? =
    valueType.decodeDelimitedFromStream(stream)

@Throws(InvalidProtocolBufferException::class)
public fun <T : Any> MessageValueType<T, *>.decodeDelimitedFromStream(stream: InputStream): T? {
    val firstByte = stream.read()
    if (firstByte == -1) {  // eof
        return null
    }

    val decoderContext = BinaryDecoderContext(InputStreamWireReader(firstByte.toByte(), stream))
    return try {
        decodeFromBinary(decoderContext.lenValueDecoder)
    } catch (e: InvalidProtocolBufferException) {
        throw e
    } catch (e: Exception) {
        throw InvalidProtocolBufferException("unable to read message", e)
    }
}

/**
 * Encode this message and its size to [stream], a stream of size-delimited messages using the protocol buffer binary
 * encoding. Like [encodeToStream], but writes the size of the message as a varint before writing the data. This allows
 * more data to be written to the stream after the message without the need to delimit the message data yourself.
 *
 * Supports the same encoding as is used by the Java methods `Message.writeDelimitedTo(OutputStream)` and
 * `Message.parseDelimitedFrom(InputStream)`.
 *
 * @see [decodeDelimitedFromStream]
 */
public fun <T : Message> T.encodeDelimitedToStream(stream: OutputStream) {
    messageCompanion.valueType.encodeDelimitedToStream(this, stream)
}

public fun <T : Any> MessageValueType<T, *>.encodeDelimitedToStream(message: T, stream: OutputStream) {
    val wireWriter = OutputStreamWireWriter(stream)
    encodeToBinary(message, BinaryFieldEncoder(wireWriter).valueEncoder)
    wireWriter.flush()
}