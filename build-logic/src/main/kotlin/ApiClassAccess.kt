import java.io.DataInputStream
import java.io.File

/** Reads only class access and InnerClasses metadata (JVMS 21 sections 4.1 and 4.7.6). */
data class ApiClassAccess(val name: String, val flags: Int, val enclosing: String?) {
    val exported: Boolean get() = flags and 0x0005 != 0

    companion object {
        fun read(file: File): ApiClassAccess = DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt()) { "Not a class file: $file" }
            input.skipNBytes(4) // minor and major version
            val size = input.readUnsignedShort()
            val strings = arrayOfNulls<String>(size)
            val classes = IntArray(size)
            var index = 1
            while (index < size) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> strings[index] = input.readUTF()
                    7 -> classes[index] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.skipNBytes(2)
                    3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                    5, 6 -> { input.skipNBytes(8); index++ }
                    15 -> input.skipNBytes(3)
                    else -> error("Unknown constant pool tag $tag in $file")
                }
                index++
            }
            fun name(classIndex: Int) = requireNotNull(strings[classes[classIndex]]).replace('/', '.')
            var flags = input.readUnsignedShort()
            val thisClass = input.readUnsignedShort()
            input.skipNBytes(2) // superclass
            input.skipNBytes(2L * input.readUnsignedShort()) // interfaces
            repeat(2) { // fields and methods: their attributes are not needed for access filtering
                repeat(input.readUnsignedShort()) {
                    input.skipNBytes(6)
                    repeat(input.readUnsignedShort()) {
                        input.skipNBytes(2)
                        input.skipNBytes(Integer.toUnsignedLong(input.readInt()))
                    }
                }
            }
            var enclosing: String? = null
            repeat(input.readUnsignedShort()) {
                val attribute = strings[input.readUnsignedShort()]
                val length = Integer.toUnsignedLong(input.readInt())
                if (attribute == "InnerClasses") {
                    val count = input.readUnsignedShort()
                    check(length == 2L + count * 8L) { "Invalid InnerClasses length in $file" }
                    repeat(count) {
                        val inner = input.readUnsignedShort()
                        val outer = input.readUnsignedShort()
                        input.skipNBytes(2) // original simple name
                        val access = input.readUnsignedShort()
                        if (inner == thisClass) {
                            flags = access
                            enclosing = if (outer == 0) null else name(outer)
                        }
                    }
                } else input.skipNBytes(length)
            }
            ApiClassAccess(name(thisClass), flags, enclosing)
        }
    }
}
