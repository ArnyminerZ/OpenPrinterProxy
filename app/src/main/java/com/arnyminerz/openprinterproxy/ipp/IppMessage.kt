package com.arnyminerz.openprinterproxy.ipp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream

/** Delimiter and value tags (RFC 8010 section 3.5). */
object IppTag {
    const val OPERATION = 0x01
    const val JOB = 0x02
    const val END = 0x03
    const val PRINTER = 0x04
    const val UNSUPPORTED_GROUP = 0x05

    const val UNSUPPORTED_VALUE = 0x10
    const val UNKNOWN = 0x12
    const val NO_VALUE = 0x13

    const val INTEGER = 0x21
    const val BOOLEAN = 0x22
    const val ENUM = 0x23

    const val OCTET_STRING = 0x30
    const val DATE_TIME = 0x31
    const val RESOLUTION = 0x32
    const val RANGE_OF_INTEGER = 0x33
    const val BEGIN_COLLECTION = 0x34
    const val TEXT_WITH_LANGUAGE = 0x35
    const val NAME_WITH_LANGUAGE = 0x36
    const val END_COLLECTION = 0x37

    const val TEXT = 0x41
    const val NAME = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val URI_SCHEME = 0x46
    const val CHARSET = 0x47
    const val NATURAL_LANGUAGE = 0x48
    const val MIME_MEDIA_TYPE = 0x49
    const val MEMBER_NAME = 0x4A

    private val STRING_TAGS = setOf(TEXT, NAME, KEYWORD, URI, URI_SCHEME, CHARSET, NATURAL_LANGUAGE, MIME_MEDIA_TYPE)

    fun isString(tag: Int) = tag in STRING_TAGS
}

sealed interface IppValue {
    val tag: Int

    /** `integer` or `enum`. */
    data class IntValue(override val tag: Int, val value: Int) : IppValue

    data class BoolValue(val value: Boolean) : IppValue {
        override val tag get() = IppTag.BOOLEAN
    }

    /** Any of the string types: text, name, keyword, uri, charset, mimeMediaType... */
    data class StringValue(override val tag: Int, val value: String) : IppValue

    /** A resolution in dots per inch. */
    data class ResolutionValue(val x: Int, val y: Int) : IppValue {
        override val tag get() = IppTag.RESOLUTION
    }

    data class RangeValue(val lower: Int, val upper: Int) : IppValue {
        override val tag get() = IppTag.RANGE_OF_INTEGER
    }

    data class CollectionValue(val members: List<IppAttribute>) : IppValue {
        override val tag get() = IppTag.BEGIN_COLLECTION

        fun member(name: String): IppAttribute? = members.firstOrNull { it.name == name }
    }

    /** Values we don't interpret (octetString, dateTime, out-of-band, language-tagged strings...). */
    class RawValue(override val tag: Int, val bytes: ByteArray) : IppValue
}

class IppAttribute(val name: String, val values: MutableList<IppValue>) {
    constructor(name: String, vararg values: IppValue) : this(name, values.toMutableList())

    val string: String? get() = (values.firstOrNull() as? IppValue.StringValue)?.value
    val strings: List<String> get() = values.mapNotNull { (it as? IppValue.StringValue)?.value }
    val int: Int? get() = (values.firstOrNull() as? IppValue.IntValue)?.value
    val boolean: Boolean? get() = (values.firstOrNull() as? IppValue.BoolValue)?.value
    val collection: IppValue.CollectionValue? get() = values.firstOrNull() as? IppValue.CollectionValue
}

class IppGroup(val tag: Int, val attributes: MutableList<IppAttribute> = mutableListOf()) {
    operator fun get(name: String): IppAttribute? = attributes.firstOrNull { it.name == name }
}

/**
 * An IPP request or response (RFC 8010). For requests, [code] is the operation id; for responses, the status code.
 */
class IppMessage(
    val versionMajor: Int,
    val versionMinor: Int,
    val code: Int,
    val requestId: Int,
    val groups: List<IppGroup>,
) {
    companion object {
        /** Guards against malicious requests: real ones have a few dozen attributes. */
        private const val MAX_ATTRIBUTES = 2000
        private const val MAX_COLLECTION_DEPTH = 8

        /**
         * Reads a message up to and including its end-of-attributes tag. For Print-Job and Send-Document, the
         * document data follows in [input].
         * @throws IOException if the message is malformed or truncated.
         */
        fun read(input: InputStream): IppMessage {
            val data = DataInputStream(input)
            val major = data.readUnsignedByte()
            val minor = data.readUnsignedByte()
            val code = data.readUnsignedShort()
            val requestId = data.readInt()

            val groups = mutableListOf<IppGroup>()
            var current: IppGroup? = null
            var count = 0
            while (true) {
                val tag = data.readUnsignedByte()
                if (tag == IppTag.END) break
                if (tag < 0x10) {
                    current = IppGroup(tag).also(groups::add)
                    continue
                }
                if (current == null) throw IOException("Attribute outside of a group")
                if (++count > MAX_ATTRIBUTES) throw IOException("Too many attributes")

                val name = readString(data)
                val value = readValue(tag, data, 0)
                if (name.isEmpty()) {
                    // An additional value of the previous attribute
                    val previous = current.attributes.lastOrNull() ?: throw IOException("Value without a name")
                    previous.values += value
                } else {
                    current.attributes += IppAttribute(name, value)
                }
            }
            return IppMessage(major, minor, code, requestId, groups)
        }

        private fun readString(data: DataInputStream): String {
            val length = data.readUnsignedShort()
            val bytes = ByteArray(length).also(data::readFully)
            return String(bytes, Charsets.UTF_8)
        }

        private fun readValue(tag: Int, data: DataInputStream, depth: Int): IppValue {
            val length = data.readUnsignedShort()
            val bytes = ByteArray(length).also(data::readFully)
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            return when {
                tag == IppTag.BEGIN_COLLECTION -> readCollection(data, depth + 1)
                (tag == IppTag.INTEGER || tag == IppTag.ENUM) && length == 4 -> IppValue.IntValue(tag, buffer.int)
                tag == IppTag.BOOLEAN && length == 1 -> IppValue.BoolValue(bytes[0].toInt() != 0)
                tag == IppTag.RESOLUTION && length == 9 -> {
                    val x = buffer.int
                    val y = buffer.int
                    // Units: 3 = dots per inch, 4 = dots per centimeter
                    if (bytes[8].toInt() == 4) IppValue.ResolutionValue(x * 254 / 100, y * 254 / 100)
                    else IppValue.ResolutionValue(x, y)
                }
                tag == IppTag.RANGE_OF_INTEGER && length == 8 -> IppValue.RangeValue(buffer.int, buffer.int)
                IppTag.isString(tag) || tag == IppTag.MEMBER_NAME ->
                    IppValue.StringValue(tag, String(bytes, Charsets.UTF_8))
                else -> IppValue.RawValue(tag, bytes)
            }
        }

        private fun readCollection(data: DataInputStream, depth: Int): IppValue.CollectionValue {
            if (depth > MAX_COLLECTION_DEPTH) throw IOException("Collections nested too deep")
            val members = mutableListOf<IppAttribute>()
            var count = 0
            while (true) {
                val tag = data.readUnsignedByte()
                readString(data) // Members have no attribute name; theirs comes in a memberAttrName value
                if (tag == IppTag.END_COLLECTION) {
                    data.skipBytes(data.readUnsignedShort())
                    break
                }
                if (++count > MAX_ATTRIBUTES) throw IOException("Too many collection members")
                val value = readValue(tag, data, depth)
                if (tag == IppTag.MEMBER_NAME) {
                    members += IppAttribute((value as IppValue.StringValue).value, mutableListOf())
                } else {
                    val member = members.lastOrNull() ?: throw IOException("Collection value without a name")
                    member.values += value
                }
            }
            return IppValue.CollectionValue(members)
        }
    }

    fun group(tag: Int): IppGroup? = groups.firstOrNull { it.tag == tag }

    /** Looks up an attribute in the operation group. */
    fun operationAttribute(name: String): IppAttribute? = group(IppTag.OPERATION)?.get(name)

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(versionMajor)
        out.writeByte(versionMinor)
        out.writeShort(code)
        out.writeInt(requestId)
        for (group in groups) {
            out.writeByte(group.tag)
            for (attribute in group.attributes) {
                attribute.values.forEachIndexed { index, value ->
                    writeValue(out, if (index == 0) attribute.name else "", value)
                }
            }
        }
        out.writeByte(IppTag.END)
        return bytes.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun writeValue(out: DataOutputStream, name: String, value: IppValue) {
        out.writeByte(value.tag)
        writeString(out, name)
        when (value) {
            is IppValue.IntValue -> {
                out.writeShort(4)
                out.writeInt(value.value)
            }
            is IppValue.BoolValue -> {
                out.writeShort(1)
                out.writeByte(if (value.value) 1 else 0)
            }
            is IppValue.StringValue -> writeString(out, value.value)
            is IppValue.ResolutionValue -> {
                out.writeShort(9)
                out.writeInt(value.x)
                out.writeInt(value.y)
                out.writeByte(3) // Dots per inch
            }
            is IppValue.RangeValue -> {
                out.writeShort(8)
                out.writeInt(value.lower)
                out.writeInt(value.upper)
            }
            is IppValue.CollectionValue -> {
                out.writeShort(0)
                for (member in value.members) {
                    out.writeByte(IppTag.MEMBER_NAME)
                    writeString(out, "")
                    writeString(out, member.name)
                    for (memberValue in member.values) writeValue(out, "", memberValue)
                }
                out.writeByte(IppTag.END_COLLECTION)
                writeString(out, "")
                out.writeShort(0)
            }
            is IppValue.RawValue -> {
                out.writeShort(value.bytes.size)
                out.write(value.bytes)
            }
        }
    }
}

/** Builds the attributes of a group. */
class IppAttributesBuilder {
    val attributes = mutableListOf<IppAttribute>()

    fun add(name: String, values: List<IppValue>) {
        if (values.isNotEmpty()) attributes += IppAttribute(name, values.toMutableList())
    }

    private fun strings(tag: Int, name: String, values: Array<out String>) =
        add(name, values.map { IppValue.StringValue(tag, it) })

    fun keyword(name: String, vararg values: String) = strings(IppTag.KEYWORD, name, values)
    fun text(name: String, vararg values: String) = strings(IppTag.TEXT, name, values)
    fun name(name: String, vararg values: String) = strings(IppTag.NAME, name, values)
    fun uri(name: String, vararg values: String) = strings(IppTag.URI, name, values)
    fun charset(name: String, vararg values: String) = strings(IppTag.CHARSET, name, values)
    fun language(name: String, vararg values: String) = strings(IppTag.NATURAL_LANGUAGE, name, values)
    fun mimeType(name: String, vararg values: String) = strings(IppTag.MIME_MEDIA_TYPE, name, values)
    fun uriScheme(name: String, vararg values: String) = strings(IppTag.URI_SCHEME, name, values)

    fun integer(name: String, vararg values: Int) = add(name, values.map { IppValue.IntValue(IppTag.INTEGER, it) })
    fun enum(name: String, vararg values: Int) = add(name, values.map { IppValue.IntValue(IppTag.ENUM, it) })
    fun boolean(name: String, value: Boolean) = add(name, listOf(IppValue.BoolValue(value)))
    fun range(name: String, lower: Int, upper: Int) = add(name, listOf(IppValue.RangeValue(lower, upper)))
    fun resolution(name: String, vararg dpi: Int) = add(name, dpi.map { IppValue.ResolutionValue(it, it) })
    fun collection(name: String, vararg values: IppValue.CollectionValue) = add(name, values.toList())
}

fun ippCollection(block: IppAttributesBuilder.() -> Unit) =
    IppValue.CollectionValue(IppAttributesBuilder().apply(block).attributes)

fun ippGroup(tag: Int, block: IppAttributesBuilder.() -> Unit) =
    IppGroup(tag, IppAttributesBuilder().apply(block).attributes)
