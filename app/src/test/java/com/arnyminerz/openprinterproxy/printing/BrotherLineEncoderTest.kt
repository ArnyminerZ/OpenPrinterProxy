// Ported from brlaser's test/test_line.cc (https://github.com/pdewacht/brlaser).
//
// Copyright 2013 Peter De Wachter
//
// brlaser is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 2 of the License, or
// (at your option) any later version.
//
// brlaser is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with brlaser.  If not, see <http://www.gnu.org/licenses/>.

package com.arnyminerz.openprinterproxy.printing

import com.arnyminerz.openprinterproxy.printing.BrotherLineEncoder.encodeLine
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BrotherLineEncoderTest {
    private fun sub(offset: Int, count: Int): Int {
        require(offset < 16 && count < 8)
        return (offset shl 3) or count
    }

    private fun rep(offset: Int, count: Int): Int {
        require(offset < 4 && count < 32)
        return 128 or (offset shl 5) or count
    }

    private fun vec(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun vec(values: List<Int>) = vec(*values.toIntArray())

    @Test
    fun `Don't crash on zero-length lines`() {
        assertArrayEquals(vec(0xFF), encodeLine(vec()))
        assertArrayEquals(vec(0xFF), encodeLine(vec(), vec()))
    }

    @Test
    fun `Encoding an initial blank line`() {
        assertArrayEquals(vec(0xFF), encodeLine(vec(0, 0, 0)))
    }

    @Test
    fun `Encoding an initial non-blank line`() {
        assertArrayEquals(vec(1, sub(0, 2), 1, 2, 3), encodeLine(vec(1, 2, 3)))
    }

    @Test
    fun `Encoding a (non-initial) blank line`() {
        assertArrayEquals(vec(0xFF), encodeLine(vec(0, 0, 0), vec(1, 2, 3)))
    }

    @Test
    fun `Encoding a repeated line`() {
        assertArrayEquals(vec(0), encodeLine(vec(1, 2, 3), vec(1, 2, 3)))
    }

    @Test
    fun `Using a substitute command`() {
        assertArrayEquals(vec(1, sub(2, 2), 1, 2, 3), encodeLine(vec(0, 0, 1, 2, 3, 0, 0), ByteArray(7)))
    }

    @Test
    fun `Using a repeat command`() {
        assertArrayEquals(vec(1, rep(2, 0), 1), encodeLine(vec(0, 0, 1, 1, 0, 0), ByteArray(6)))
    }

    @Test
    fun `Repeat command followed by substitute command`() {
        assertArrayEquals(vec(2, rep(0, 1), 1, sub(0, 1), 2, 3), encodeLine(vec(1, 1, 1, 2, 3), ByteArray(5)))
    }

    @Test
    fun `Substitute command followed by repeat command`() {
        assertArrayEquals(vec(2, sub(0, 1), 3, 2, rep(0, 1), 1), encodeLine(vec(3, 2, 1, 1, 1), ByteArray(5)))
    }

    @Test
    fun `Substitute with an unmodified byte in the middle`() {
        assertArrayEquals(
            vec(1, sub(0, 6), 1, 2, 3, 0, 1, 2, 3),
            encodeLine(vec(1, 2, 3, 0, 1, 2, 3), ByteArray(7))
        )
    }

    @Test
    fun `Substitute with two unmodified bytes in the middle`() {
        assertArrayEquals(
            vec(2, sub(0, 2), 1, 2, 3, sub(2, 2), 1, 2, 3),
            encodeLine(vec(1, 2, 3, 0, 0, 1, 2, 3), ByteArray(8))
        )
    }

    @Test
    fun `Repeat with an unmodified byte in the middle`() {
        assertArrayEquals(vec(2, rep(0, 1), 1, rep(1, 1), 1), encodeLine(vec(1, 1, 1, 0, 1, 1, 1), ByteArray(7)))
    }

    @Test
    fun `254 edits needed for a single line`() {
        val line = List(254) { listOf(0, 0, 1) }.flatten()
        val result = listOf(254) + List(254) { listOf(sub(2, 0), 1) }.flatten()
        assertArrayEquals(vec(result), encodeLine(vec(line), ByteArray(line.size)))
    }

    @Test
    fun `Give up if more than 254 edits needed`() {
        val line = List(255) { listOf(0, 0, 1) }.flatten()
        val result = listOf(254) + List(253) { listOf(sub(2, 0), 1) }.flatten() + listOf(sub(2, 3), 1, 0, 0, 1)
        assertArrayEquals(vec(result), encodeLine(vec(line), ByteArray(line.size)))
    }

    @Test
    fun `Repeat command with overflow bytes`() {
        val line = List(3) { 0 } + List(512) { 1 }
        assertArrayEquals(vec(1, rep(3, 31), 0, 255, 224, 1), encodeLine(vec(line), ByteArray(line.size)))
    }

    @Test
    fun `Substitute command with overflow bytes`() {
        val line = List(270) { 0 } + List(250) { listOf(1, 2) }.flatten()
        val expected = listOf(1, sub(15, 7), 255, 0, 255, 237) + List(250) { listOf(1, 2) }.flatten()
        assertArrayEquals(vec(expected), encodeLine(vec(line), ByteArray(line.size)))
    }
}
