package com.arnyminerz.openprinterproxy.ipp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class IppMessageTest {
    /** A Get-Printer-Attributes request, byte by byte as RFC 8010 lays it out. */
    private val getPrinterAttributes = byteArrayOf(
        0x02, 0x00, // Version 2.0
        0x00, 0x0B, // Get-Printer-Attributes
        0x00, 0x00, 0x00, 0x2A, // Request id 42
        0x01, // Operation attributes
        0x47, 0x00, 0x12, *"attributes-charset".toByteArray(), 0x00, 0x05, *"utf-8".toByteArray(),
        0x44, 0x00, 0x14, *"requested-attributes".toByteArray(), 0x00, 0x03, *"all".toByteArray(),
        0x44, 0x00, 0x00, 0x00, 0x0B, *"media-ready".toByteArray(), // Additional value
        0x03, // End of attributes
    )

    @Test
    fun `Parses a request with additional values`() {
        val message = IppMessage.read(ByteArrayInputStream(getPrinterAttributes))
        assertEquals(2, message.versionMajor)
        assertEquals(0, message.versionMinor)
        assertEquals(0x0B, message.code)
        assertEquals(42, message.requestId)
        assertEquals("utf-8", message.operationAttribute("attributes-charset")?.string)
        assertEquals(listOf("all", "media-ready"), message.operationAttribute("requested-attributes")?.strings)
    }

    @Test
    fun `Encoding a parsed request gives back the same bytes`() {
        val message = IppMessage.read(ByteArrayInputStream(getPrinterAttributes))
        assertArrayEquals(getPrinterAttributes, message.encode())
    }

    @Test
    fun `Document data after the attributes is left in the stream`() {
        val input = ByteArrayInputStream(getPrinterAttributes + "%PDF-1.7".toByteArray())
        IppMessage.read(input)
        assertEquals("%PDF-1.7", input.readBytes().toString(Charsets.US_ASCII))
    }

    @Test
    fun `Round trips every value type, including nested collections`() {
        val message = IppMessage(
            1, 1, 0x0000, 7,
            listOf(
                ippGroup(IppTag.OPERATION) {
                    charset("attributes-charset", "utf-8")
                    language("attributes-natural-language", "en")
                },
                ippGroup(IppTag.PRINTER) {
                    integer("copies-default", 1)
                    enum("operations-supported", 0x02, 0x0B)
                    boolean("color-supported", true)
                    range("copies-supported", 1, 99)
                    resolution("printer-resolution-supported", 300, 600)
                    text("printer-info", "Brother DCP-1510 · shared")
                    collection("media-col-default", ippCollection {
                        collection("media-size", ippCollection {
                            integer("x-dimension", 21000)
                            integer("y-dimension", 29700)
                        })
                        keyword("media-type", "stationery")
                    })
                },
            ),
        )

        val parsed = IppMessage.read(ByteArrayInputStream(message.encode()))
        val printer = parsed.group(IppTag.PRINTER)!!
        assertEquals(1, printer["copies-default"]?.int)
        assertEquals(
            listOf(0x02, 0x0B),
            printer["operations-supported"]?.values?.map { (it as IppValue.IntValue).value },
        )
        assertEquals(true, printer["color-supported"]?.boolean)
        assertEquals(IppValue.RangeValue(1, 99), printer["copies-supported"]?.values?.single())
        assertEquals(
            listOf(IppValue.ResolutionValue(300, 300), IppValue.ResolutionValue(600, 600)),
            printer["printer-resolution-supported"]?.values,
        )
        assertEquals("Brother DCP-1510 · shared", printer["printer-info"]?.string)

        val mediaCol = printer["media-col-default"]?.collection!!
        val size = mediaCol.member("media-size")?.collection!!
        assertEquals(21000, size.member("x-dimension")?.int)
        assertEquals(29700, size.member("y-dimension")?.int)
        assertEquals("stationery", mediaCol.member("media-type")?.string)
        assertNull(mediaCol.member("media-source"))

        assertArrayEquals(message.encode(), parsed.encode())
    }
}
