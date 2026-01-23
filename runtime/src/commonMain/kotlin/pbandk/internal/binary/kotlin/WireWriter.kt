package pbandk.internal.binary.kotlin

internal interface WireWriter {
    val totalBytesWritten: Int
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun write(maxSize: Int, block: (buffer: ByteArray, offset: Int) -> Int)
    fun writeByte(byte: Byte)
}