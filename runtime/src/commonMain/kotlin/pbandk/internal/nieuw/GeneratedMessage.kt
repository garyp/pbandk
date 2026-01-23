package pbandk.internal.nieuw

import pbandk.PublicForGeneratedCode
import pbandk.UnknownField
import pbandk.binary.WireValue
import pbandk.wkt.Syntax

public abstract class GeneratedMessage<M : GeneratedMessage<M>>
@PublicForGeneratedCode
public constructor() : Message {
    @Suppress("UNCHECKED_CAST")
    private inline fun thisAsM(): M = this as M

    abstract override val companion: Message.Companion<M>

    override val protoSize: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        computeBinarySize(companion.schema, thisAsM())
    }

    override fun plus(other: Message?): M {
        TODO("Not yet implemented")
    }

    override fun <V> getFieldValue(fieldSchema: FieldSchema<*, *, V>): V {
        TODO()
    }
}

public abstract class GeneratedMutableMessage<M : GeneratedMutableMessage<M>>
@PublicForGeneratedCode
public constructor()

public abstract class GeneratedExtendableMessage<M : GeneratedExtendableMessage<M>>
@PublicForGeneratedCode
public constructor() : GeneratedMessage<M>(), ExtendableMessage<M> {
}

public class Foo private constructor(
    public val a: Int,
    public val b: String,
    public val c: Foo?,
    public val d: Int?,
    public val e: List<String>,
    override val unknownFields: Map<Int, UnknownField>,
) : GeneratedMessage<Foo>() {
    private constructor(m: MutableFoo) : this(
        m.a, m.b, m.c, m.d, m.e, m.unknownFields
    )

    override val companion: Message.Companion<Foo> get() = Companion

    private object FieldSchemas {
        val a = FieldSchema.ImplicitPresence(
            descriptor = FieldDescriptor.Standard(descriptor, "a", 1, "a", false),
            valueType = Int32Value,
            valueFn = Foo::a,
            setValueFn = MutableFoo::a::set,
        )
        val b = FieldSchema.ImplicitPresence(
            descriptor = FieldDescriptor.Standard(descriptor, "b", 2, "b", false),
            valueType = StringValue,
            valueFn = Foo::b,
            setValueFn = MutableFoo::b::set,
        )
        val c = FieldSchema.ExplicitPresence(
            descriptor = FieldDescriptor.Standard(descriptor, "c", 3, "c", false),
            valueType = Foo.schema,
            valueFn = Foo::c,
            setValueFn = MutableFoo::c::set,
        )
        val d = FieldSchema.ExplicitPresence(
            descriptor = FieldDescriptor.Standard(descriptor, "d", 4, "d", false),
            valueType = Int32Value,
            valueFn = Foo::d,
            setValueFn = MutableFoo::d::set,
        )
        val e = FieldSchema.Repeated(
            descriptor = FieldDescriptor.Standard(descriptor, "e", 5, "e", false),
            valueType = StringValue,
            valueFn = Foo::e,
            mutableValueFn = MutableFoo::e,
        )
    }

    public companion object : Message.Companion<Foo>() {
        private val descriptor = MessageDescriptor("test.Foo", Syntax.PROTO3)
        override val schema: MessageSchema<Foo> = GeneratedMessageSchema(
            descriptor = descriptor,
            builder = ::invoke,
            fields = buildList {
                add(FieldSchemas.a)
                add(FieldSchemas.b)
                add(FieldSchemas.c)
                add(FieldSchemas.d)
                add(FieldSchemas.e)
            },
            oneofs = emptyList()
        )

        public operator fun invoke(block: MutableFoo.() -> Unit): Foo {
            val m = MutableFoo()
            m.block()
            // if there are required fields, then we'd check here that they're all set
            return Foo(m)
        }
    }
}

public class MutableFoo internal constructor(): MutableMessage {
    public var a: Int = 0
    public var b: String = ""
    public var c: Foo? = null
    public var d: Int? = null
    public val e: MutableList<String> = mutableListOf()
    override val unknownFields: MutableMap<Int, UnknownField> = mutableMapOf()
}

private fun test() {
    val f = Foo {
        a = 1
        b = "hi"
        c = Foo {
            a = 2
            b = "bye"
            e.add("blether")
        }
        d = 5
        unknownFields[5] = UnknownField(5, listOf(UnknownField.Value(WireValue.i32(17U))))
    }
    val b = Foo.schema.encodeToByteArray(f)
}