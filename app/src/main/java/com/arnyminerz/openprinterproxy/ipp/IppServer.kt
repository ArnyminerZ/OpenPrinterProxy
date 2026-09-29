package com.arnyminerz.openprinterproxy.ipp

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A minimal HTTP/1.1 server for IPP (RFC 8010 section 4): `POST`s with an `application/ipp` body are dispatched to
 * the [IppPrinter] owning the request path.
 *
 * @param isAllowed Whether a client may connect. Connections from other addresses are closed right away.
 * @param printerForPath The printer serving a request path, if any.
 * @param statusPage HTML shown to browsers that open the server's address.
 */
class IppServer(
    private val port: Int,
    private val isAllowed: (InetAddress) -> Boolean,
    private val printerForPath: (String) -> IppPrinter?,
    private val statusPage: () -> String,
) {
    private companion object {
        const val TAG = "IppServer"
        const val MAX_CONNECTIONS = 8
        const val IDLE_TIMEOUT_MS = 30_000
        const val MAX_LINE_LENGTH = 8192
        const val MAX_HEADERS = 100
        const val MAX_DRAIN_BYTES = 1024 * 1024L
    }

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val connections = ThreadPoolExecutor(
        0, MAX_CONNECTIONS, 60, TimeUnit.SECONDS, SynchronousQueue()
    ) { runnable -> Thread(runnable, "ipp-connection").apply { isDaemon = true } }

    /** @throws IOException if the port can't be bound. */
    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = socket
        acceptThread = Thread({ acceptLoop(socket) }, "ipp-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Listening on port $port")
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
        acceptThread = null
        connections.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                break // Closed by stop()
            } catch (e: IOException) {
                Log.w(TAG, "Accept failed", e)
                continue
            }

            if (!isAllowed(socket.inetAddress)) {
                Log.w(TAG, "Rejected connection from ${socket.inetAddress}")
                socket.close()
                continue
            }
            try {
                connections.execute { serve(socket) }
            } catch (_: RejectedExecutionException) {
                socket.close() // Too many connections
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = IDLE_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                var open = true
                while (open) open = handleRequest(input, output, socket)
            } catch (e: IOException) {
                Log.d(TAG, "Connection from ${socket.inetAddress} ended: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error serving ${socket.inetAddress}", e)
            }
        }
    }

    /** @return whether the connection stays open for another request. */
    private fun handleRequest(input: InputStream, output: OutputStream, socket: Socket): Boolean {
        val requestLine = readLine(input) ?: return false
        if (requestLine.isEmpty()) return true // Stray line break between requests
        val parts = requestLine.split(' ')
        if (parts.size != 3) {
            writeResponse(output, 400, "Bad Request", "text/plain", "Bad request".toByteArray(), close = true)
            return false
        }
        val (method, target, version) = parts

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return false
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw IOException("Too many headers")
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val keepAlive = version == "HTTP/1.1" && headers["connection"]?.equals("close", ignoreCase = true) != true
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val contentLength = headers["content-length"]?.toLongOrNull()
        val body: InputStream = when {
            chunked -> ChunkedInputStream(input)
            contentLength != null -> BoundedInputStream(input, contentLength)
            else -> BoundedInputStream(input, 0)
        }
        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        val host = headers["host"] ?: "${socket.localAddress.hostAddress}:$port"
        // Usually just the path, but it may be an absolute URI
        val path = (if ("://" in target) "/" + target.substringAfter("://").substringAfter('/', "") else target)
            .substringBefore('?')

        when {
            method == "POST" && headers["content-type"]?.startsWith("application/ipp") == true -> {
                val request = try {
                    IppMessage.read(body)
                } catch (e: IOException) {
                    Log.w(TAG, "Malformed IPP request", e)
                    writeResponse(output, 400, "Bad Request", "text/plain", "Malformed IPP".toByteArray(), close = true)
                    return false
                }
                val printer = printerForPath(path)
                val response = printer?.handle(request, body, host) ?: notFound(request)
                // Skip whatever the handler didn't read (e.g. a rejected document), so the next request starts in
                // the right place. If there's a lot left, closing the connection is cheaper.
                val drained = drain(body)
                writeResponse(
                    output, 200, "OK", "application/ipp", response.encode(), close = !keepAlive || !drained
                )
                return keepAlive && drained
            }
            method == "GET" || method == "HEAD" -> {
                val page = statusPage().toByteArray()
                writeResponse(
                    output, 200, "OK", "text/html; charset=utf-8", if (method == "HEAD") ByteArray(0) else page,
                    close = !keepAlive, contentLength = page.size,
                )
            }
            else -> {
                writeResponse(output, 405, "Method Not Allowed", "text/plain", ByteArray(0), close = true)
                return false
            }
        }
        return keepAlive
    }

    /** Reads and discards up to [MAX_DRAIN_BYTES] of [body]. @return whether it reached the end. */
    private fun drain(body: InputStream): Boolean {
        val buffer = ByteArray(8192)
        var total = 0L
        while (total < MAX_DRAIN_BYTES) {
            val read = body.read(buffer)
            if (read < 0) return true
            total += read
        }
        return false
    }

    private fun notFound(request: IppMessage) = IppMessage(
        request.versionMajor, request.versionMinor, IppPrinter.STATUS_NOT_FOUND, request.requestId,
        listOf(ippGroup(IppTag.OPERATION) {
            charset("attributes-charset", "utf-8")
            language("attributes-natural-language", "en")
            text("status-message", "No printer at this address")
        }),
    )

    private fun writeResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        close: Boolean,
        contentLength: Int = body.size,
    ) {
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $contentLength\r\n")
            if (close) append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    /** Reads a CRLF (or LF) terminated line. @return null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
            if (line.length > MAX_LINE_LENGTH) throw IOException("Line too long")
        }
        return line.toString()
    }

    /** Reads at most [remaining] bytes from [input]. */
    private class BoundedInputStream(private val input: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val c = input.read()
            if (c == -1) throw IOException("Unexpected end of body")
            remaining--
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val read = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (read == -1) throw IOException("Unexpected end of body")
            remaining -= read
            return read
        }
    }

    /** Decodes a `Transfer-Encoding: chunked` body. */
    private inner class ChunkedInputStream(private val input: InputStream) : InputStream() {
        private var chunkRemaining = 0L
        private var finished = false

        private fun nextChunk(): Boolean {
            if (finished) return false
            if (chunkRemaining > 0) return true
            val sizeLine = readLine(input) ?: throw IOException("Unexpected end of chunked body")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Bad chunk size: $sizeLine")
            if (size == 0L) {
                // Trailer headers, up to an empty line
                while (!readLine(input).isNullOrEmpty()) Unit
                finished = true
                return false
            }
            chunkRemaining = size
            return true
        }

        private fun endChunkIfDone() {
            if (chunkRemaining == 0L) readLine(input) // CRLF after the chunk data
        }

        override fun read(): Int {
            if (!nextChunk()) return -1
            val c = input.read()
            if (c == -1) throw IOException("Unexpected end of chunk")
            chunkRemaining--
            endChunkIfDone()
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!nextChunk()) return -1
            val read = input.read(b, off, minOf(len.toLong(), chunkRemaining).toInt())
            if (read == -1) throw IOException("Unexpected end of chunk")
            chunkRemaining -= read
            endChunkIfDone()
            return read
        }
    }
}
