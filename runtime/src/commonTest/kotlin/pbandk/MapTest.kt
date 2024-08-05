package pbandk

import pbandk.testpb.Foo
import pbandk.testpb.FooMap
import pbandk.testpb.FooMapEntries
import pbandk.testpb.MapEntry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MapTest {
    @Test
    fun testMapsAreEqualToRepeatedMapEntries() {
        val fooMap = FooMap {
            map["a"] = Foo { `val` = "5" }
            map["b"] = Foo {}
        }
        val fooMapBytes = fooMap.encodeToByteArray()

        val fooMapEntries = FooMapEntries {
            map += listOf(
                MapEntry {
                    key = "a"
                    value = Foo { `val` = "5" }
                },
                MapEntry {
                    key = "b"
                    value = Foo {}
                },
            )
        }
        val fooMapEntriesBytes = fooMapEntries.encodeToByteArray()

        assertEquals(fooMap.protoSize, fooMapEntries.protoSize)
        assertContentEquals(fooMapBytes, fooMapEntriesBytes)

        assertEquals(fooMap, FooMap.decodeFromByteArray(fooMapEntriesBytes))
        assertEquals(fooMapEntries, FooMapEntries.decodeFromByteArray(fooMapBytes))
    }

    @Test
    fun testMapsAreEqualToRepeatedMapEntries_withNullValues() {
        val fooMap = FooMap {
            map["c"] = Foo {}
        }
        val fooMapBytes = fooMap.encodeToByteArray()

        val fooMapEntries = FooMapEntries {
            map += listOf(
                MapEntry {
                    key = "c"
                    value = null
                },
            )
        }
        val fooMapEntriesBytes = fooMapEntries.encodeToByteArray()

        val fooMapEntriesNoNulls = FooMapEntries {
            map += listOf(
                MapEntry {
                    key = "c"
                    value = Foo {}
                }
            )
        }

        // Null values won't produce exactly equal maps since we don't support protobuf maps with null values in pbandk.
        // Instead, a null value on the wire gets translated to the default value for that type (e.g. 0 for int32, or
        // the default instance for a message type). This is similar to how some of the official protobuf
        // implementations treat null map values (such as Java and C#).

        assertNotEquals(fooMap.protoSize, fooMapEntries.protoSize)

        // Decoding a manually-created map entry with a null value should produce a map entry with a default value.
        assertEquals(fooMap, FooMap.decodeFromByteArray(fooMapEntriesBytes))
        // The reverse is not true however. Decoding a map entry with a default value will produce a manually-created
        // map entry that has the default value rather than anull.
        assertNotEquals(fooMapEntries, FooMapEntries.decodeFromByteArray(fooMapBytes))
        assertEquals(fooMapEntriesNoNulls, FooMapEntries.decodeFromByteArray(fooMapBytes))
    }
}