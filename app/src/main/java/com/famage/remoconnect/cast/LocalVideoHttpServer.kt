package com.famage.remoconnect.cast

import android.content.ContentResolver
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.NetworkInterface
import java.util.Locale

data class LocalVideoEntry(
    val id: String,
    val uri: Uri,
    val title: String,
    val contentType: String
)

class LocalVideoHttpServer(
    private val contentResolver: ContentResolver,
    entries: List<LocalVideoEntry>
) : NanoHTTPD(findOpenPort()) {

    private val entriesById = entries.associateBy { it.id }

    fun streamUrlFor(id: String): String = "http://${findLocalIpAddress()}:$listeningPort/video/$id"

    override fun serve(session: IHTTPSession): Response {
        val id = session.uri.removePrefix("/video/").takeIf { session.uri.startsWith("/video/") }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        val entry = entriesById[id]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").withCommonHeaders()
        }

        val length = resolveLength(entry.uri)
        val range = parseRange(session.headers["range"], length)
        if (session.method == Method.HEAD) {
            return newFixedLengthResponse(
                if (range.isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                entry.contentType,
                ""
            ).withRangeHeaders(length, range)
        }

        val input = contentResolver.openInputStream(entry.uri)
            ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Unable to open video"
            )

        skipFully(input, range.start)
        val response = if (range.length > 0L) {
            newFixedLengthResponse(
                if (range.isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                entry.contentType,
                input,
                range.length
            )
        } else {
            newChunkedResponse(Response.Status.OK, entry.contentType, input)
        }
        return response.withRangeHeaders(length, range)
    }

    private fun Response.withCommonHeaders(): Response {
        addHeader("Accept-Ranges", "bytes")
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Connection", "keep-alive")
        return this
    }

    private fun Response.withRangeHeaders(length: Long, range: ByteRange): Response {
        withCommonHeaders()
        if (length > 0L && range.length > 0L) {
            addHeader("Content-Length", range.length.toString())
            if (range.isPartial) {
                addHeader("Content-Range", "bytes ${range.start}-${range.end}/$length")
            }
        }
        return this
    }

    private fun resolveLength(uri: Uri): Long {
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun parseRange(rangeHeader: String?, length: Long): ByteRange {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.lowercase(Locale.US).startsWith("bytes=")) {
            return ByteRange(start = 0L, end = if (length > 0L) length - 1L else -1L, totalLength = length)
        }

        val rangeValue = rangeHeader.substringAfter("bytes=").substringBefore(",").trim()
        val startText = rangeValue.substringBefore("-")
        val endText = rangeValue.substringAfter("-", "")
        val requestedStart = startText.toLongOrNull() ?: 0L
        val requestedEnd = endText.toLongOrNull()
        val end = when {
            length <= 0L -> requestedEnd ?: -1L
            requestedEnd == null -> length - 1L
            else -> requestedEnd.coerceAtMost(length - 1L)
        }
        return ByteRange(start = requestedStart, end = end, totalLength = length, isPartial = true)
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) break
            remaining -= skipped
        }
    }

    private data class ByteRange(
        val start: Long,
        val end: Long,
        val totalLength: Long,
        val isPartial: Boolean = false
    ) {
        val length: Long
            get() = when {
                totalLength <= 0L -> -1L
                end < start -> 0L
                else -> end - start + 1L
            }
    }

    companion object {
        private fun findOpenPort(): Int = (18080..18120).firstOrNull { port ->
            try {
                java.net.ServerSocket(port).use { true }
            } catch (_: Exception) {
                false
            }
        } ?: 18080

        private fun findLocalIpAddress(): String {
            return NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress && address.hostAddress?.contains(":") == false
                }
                ?.hostAddress
                ?: "127.0.0.1"
        }
    }
}
