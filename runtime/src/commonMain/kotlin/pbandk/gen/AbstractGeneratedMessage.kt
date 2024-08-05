package pbandk.gen

import pbandk.ExtendableMessage
import pbandk.FieldDescriptor
import pbandk.Message
import pbandk.MessageDescriptor
import pbandk.MutableMessage
import pbandk.PublicForGeneratedCode

public abstract class AbstractGeneratedMessage<M : Message>
@PublicForGeneratedCode
protected constructor() : Message {
    internal open fun fieldDescriptors(ordered: Boolean = false): Collection<FieldDescriptor<M, MutableMessage<M>, out Any?>> {
        return messageDescriptor.fields
    }

    override val protoSize: Int get() = messageCompanion.valueType.descriptor.computeBinarySize(this)

    override fun hashCode(): Int {
        var hash = 1
        fieldDescriptors(ordered = true).forEachWithValue(asMessage()) { _, value ->
            hash = (31 * hash) + value.hashCode()
        }
        hash = (31 * hash) + unknownFields.hashCode()
        return hash
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false

        if (companion.valueType.descriptor != other.companion.valueType.descriptor) return false
        @Suppress("UNCHECKED_CAST")
        other as M

        forEachField(companion.valueType.descriptor, this, other) { _, value, otherValue ->
            if (value != otherValue) return false
        }

        if (unknownFields != other.unknownFields) return false

        return true
    }

    override fun toString(): String = buildString {
        append(companion.valueType.descriptor.name)
        append("(")

        fieldDescriptors(ordered = true).forEachWithValue(this@AbstractGeneratedMessage.asMessage()) { fd, value ->
            if (fd.metadata.isExtension) {
                append("[${fd.metadata.fullName}]")
            } else {
                append(fd.metadata.name)
            }
            append("=${value}, ")
        }

        if (unknownFields.isNotEmpty()) {
            append("unknownFields=${unknownFields}")
        } else if (this.endsWith(", ")) {
            setLength(length - 2)
        }

        append(")")
    }

    public open fun <MM : MutableMessage<M>> copy(builderAction: MM.() -> Unit): M {
        val newMessage = messageDescriptor.builder {
            this@AbstractGeneratedMessage.fieldDescriptors().forEach { fieldDescriptor ->
                fieldDescriptor.copyValue(this@AbstractGeneratedMessage.asMessage(), this)
            }
            unknownFields += this@AbstractGeneratedMessage.unknownFields
            @Suppress("UNCHECKED_CAST")
            (this as MM).builderAction()
        }
        return newMessage
    }

    override operator fun plus(other: Message?): M {
        if (companion.valueType.descriptor != other?.companion?.valueType?.descriptor) return this.asMessage()
        @Suppress("UNCHECKED_CAST")
        other as M

//        return messageCompanion.valueType.mergeValues(this as M, other)
        return copy<MutableMessage<M>> {
            this@AbstractGeneratedMessage.fieldDescriptors().forEach { field ->
                if (field.metadata.isOneofMember) return@forEach
                field.mergeValues(this@AbstractGeneratedMessage.asMessage(), other, this)
            }

            for (oneof in this@AbstractGeneratedMessage.messageDescriptor.oneofs) {
                oneof.mergeValues(this@AbstractGeneratedMessage.asMessage(), other, this)
            }

            other.unknownFields.forEach { (fieldNum, unknownField) ->
                unknownFields[fieldNum] = unknownFields[fieldNum]?.let { prevValue ->
                    prevValue.copy(values = prevValue.values + unknownField.values)
                } ?: unknownField
            }
        }
    }

    override fun <V> getFieldValue(fieldDescriptor: FieldDescriptor<*, *, V>): V {
        require(fieldDescriptor.messageDescriptor == messageDescriptor)
        @Suppress("UNCHECKED_CAST")
        return (fieldDescriptor as FieldDescriptor<M, MutableMessage<M>, V>).getValue(this as M)
    }
}

internal inline fun <M : Message, T : AbstractGeneratedMessage<M>> T.asMessage(): M {
    @Suppress("UNCHECKED_CAST")
    return this as M
}

internal inline val <M : Message> M.messageCompanion: Message.Companion<M, MutableMessage<M>>
    @Suppress("UNCHECKED_CAST")
    get() = companion as Message.Companion<M, MutableMessage<M>>

internal inline val <M : Message> M.messageDescriptor: MessageDescriptor<M, MutableMessage<M>>
    @Suppress("UNCHECKED_CAST")
    get() = descriptor as MessageDescriptor<M, MutableMessage<M>>

internal inline val <M : Message, T : AbstractGeneratedMessage<M>> T.messageDescriptor: MessageDescriptor<M, MutableMessage<M>>
    @Suppress("UNCHECKED_CAST")
    get() = companion.valueType.descriptor as MessageDescriptor<M, MutableMessage<M>>

private inline fun <M : Any, MM : Any, T> FieldDescriptor<M, MM, T>.copyValue(
    fromMessage: M,
    toMessage: MM,
) = setValue(toMessage, getValue(fromMessage))

internal inline fun <M : Any, MM : Any> Iterable<FieldDescriptor<M, MM, out Any?>>.forEachWithValue(
    message: M,
    action: (FieldDescriptor<M, MM, out Any?>, Any?) -> Unit,
) {
    forEach { fd ->
        val value = fd.getValue(message)
        action(fd, value)
    }
}

private fun <T : Any> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null

private typealias ForEachFieldFn<M, T> = (FieldDescriptor<M, *, out T>, T, T) -> Unit

internal inline fun <M : Message> forEachField(
    descriptor: MessageDescriptor<M, *>,
    first: M,
    second: M,
    operation: ForEachFieldFn<M, Any?>
) {
    descriptor.fields.forEach { fd ->
        operation(fd, fd.getValue(first), fd.getValue(second))
    }

    @Suppress("UNCHECKED_CAST")
    val firstExtensionFields = (first as? ExtendableMessage<M>)?.extensionFields

    @Suppress("UNCHECKED_CAST")
    val secondExtensionFields = (second as? ExtendableMessage<M>)?.extensionFields

    if (firstExtensionFields != null && secondExtensionFields != null) {
        val firstExtensionIter = firstExtensionFields.iterator()
        val secondExtensionIter = secondExtensionFields.iterator()

        var firstFd = firstExtensionIter.nextOrNull()
        var secondFd = secondExtensionIter.nextOrNull()

        while (firstFd != null && secondFd != null) {
            when {
                firstFd == secondFd -> {
                    operation(firstFd, firstExtensionIter.nextValue(), secondExtensionIter.nextValue())
                    firstFd = firstExtensionIter.nextOrNull()
                    secondFd = secondExtensionIter.nextOrNull()
                }

                firstFd.number <= secondFd.number -> {
                    operation(firstFd, firstExtensionIter.nextValue(), firstFd.fieldType.defaultValue)
                    firstFd = firstExtensionIter.nextOrNull()
                }

                else -> {
                    operation(secondFd, secondFd.fieldType.defaultValue, secondExtensionIter.nextValue())
                    secondFd = secondExtensionIter.nextOrNull()
                }
            }
        }

        while (firstFd != null) {
            operation(firstFd, firstExtensionIter.nextValue(), firstFd.fieldType.defaultValue)
            firstFd = firstExtensionIter.nextOrNull()
        }

        while (secondFd != null) {
            operation(secondFd, secondFd.fieldType.defaultValue, secondExtensionIter.nextValue())
            secondFd = secondExtensionIter.nextOrNull()
        }
    }
}