package com.famage.remoconnect.cast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class CastPlaybackState(
    val isCastAvailable: Boolean = true,
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val isPlaying: Boolean = false,
    val title: String = "",
    val contentUrl: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val statusText: String = "Choose a Cast device",
    val errorMessage: String? = null,
    val localStreamUrl: String? = null,
    val queueSize: Int = 0,
    val queueIndex: Int = 0
)

private data class CastPlaylistItem(
    val url: String,
    val title: String,
    val contentType: String,
    val localStreamUrl: String? = null
)

private data class ResolvedStreamUrl(
    val url: String,
    val contentType: String,
    val title: String
)

class CastStreamingController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var castContext: CastContext? = null
    private var remoteMediaClient: RemoteMediaClient? = null
    private var progressJob: Job? = null
    private var localServer: LocalVideoHttpServer? = null
    private var playlist: List<CastPlaylistItem> = emptyList()
    private var playlistIndex: Int = 0
    private var advancingFromIdle = false

    private val _playbackState = MutableStateFlow(CastPlaybackState())
    val playbackState: StateFlow<CastPlaybackState> = _playbackState.asStateFlow()

    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            syncPlaybackState()
            advancePlaylistIfFinished()
        }

        override fun onMetadataUpdated() {
            syncPlaybackState()
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            attachSession(session)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            attachSession(session)
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            detachSession("Choose a Cast device")
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            detachSession("Cast reconnect failed")
        }

        override fun onSessionStarting(session: CastSession) {
            _playbackState.value = _playbackState.value.copy(statusText = "Connecting to Cast device...")
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _playbackState.value = _playbackState.value.copy(
                isConnected = false,
                statusText = "Cast connection failed",
                errorMessage = "Unable to connect to the selected Cast device."
            )
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _playbackState.value = _playbackState.value.copy(statusText = "Reconnecting to Cast device...")
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            _playbackState.value = _playbackState.value.copy(statusText = "Cast connection suspended")
        }
    }

    init {
        try {
            val contextInstance = CastContext.getSharedInstance(appContext)
            castContext = contextInstance
            contextInstance.sessionManager.addSessionManagerListener(
                sessionListener,
                CastSession::class.java
            )
            contextInstance.sessionManager.currentCastSession?.let { attachSession(it) }
        } catch (e: Exception) {
            _playbackState.value = CastPlaybackState(
                isCastAvailable = false,
                statusText = "Cast unavailable",
                errorMessage = e.localizedMessage ?: "Google Cast is unavailable on this device."
            )
        }
    }

    fun castUrl(url: String, title: String = "Remote video") {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            _playbackState.value = _playbackState.value.copy(errorMessage = "Enter a video URL first.")
            return
        }

        scope.launch {
            _playbackState.value = _playbackState.value.copy(
                statusText = "Checking video URL...",
                errorMessage = null
            )
            val resolved = withContext(Dispatchers.IO) { resolveStreamUrl(cleanUrl, title) }
            if (resolved == null) {
                _playbackState.value = _playbackState.value.copy(
                    statusText = if (_playbackState.value.isConnected) "Ready to cast" else "Choose a Cast device",
                    errorMessage = "This URL does not look like a direct Cast-playable media stream. YouTube and many webpage links need a dedicated resolver or receiver."
                )
                return@launch
            }
            localServer?.stop()
            localServer = null
            playPlaylist(
                listOf(
                    CastPlaylistItem(
                        url = resolved.url,
                        title = resolved.title,
                        contentType = resolved.contentType
                    )
                )
            )
        }
    }

    fun castLocalVideo(uri: Uri) {
        castLocalVideos(listOf(uri))
    }

    fun castLocalVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            try {
                val resolver = appContext.contentResolver
                _playbackState.value = _playbackState.value.copy(
                    statusText = "Preparing ${uris.size} video${if (uris.size == 1) "" else "s"}...",
                    errorMessage = null
                )

                val entries = withContext(Dispatchers.IO) {
                    uris.mapIndexed { index, uri ->
                        LocalVideoEntry(
                            id = index.toString(),
                            uri = uri,
                            title = resolveDisplayName(uri) ?: "Phone video ${index + 1}",
                            contentType = resolver.getType(uri) ?: "video/mp4"
                        )
                    }
                }

                val server = withContext(Dispatchers.IO) {
                    localServer?.stop()
                    LocalVideoHttpServer(resolver, entries).also { it.start() }
                }
                localServer = server
                playPlaylist(
                    entries.map { entry ->
                        val streamUrl = server.streamUrlFor(entry.id)
                        CastPlaylistItem(
                            url = streamUrl,
                            title = entry.title,
                            contentType = entry.contentType,
                            localStreamUrl = streamUrl
                        )
                    }
                )
            } catch (e: Exception) {
                _playbackState.value = _playbackState.value.copy(
                    errorMessage = e.localizedMessage ?: "Unable to stream the selected video."
                )
            }
        }
    }

    fun togglePlayback() {
        val client = remoteMediaClient ?: return
        if (client.playerState == MediaStatus.PLAYER_STATE_PLAYING) {
            client.pause()
        } else {
            client.play()
        }
        syncPlaybackState()
    }

    fun stop() {
        remoteMediaClient?.stop()
        localServer?.stop()
        localServer = null
        playlist = emptyList()
        playlistIndex = 0
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            title = "",
            contentUrl = "",
            positionMs = 0L,
            durationMs = 0L,
            statusText = if (_playbackState.value.isConnected) "Ready to cast" else "Choose a Cast device",
            localStreamUrl = null,
            queueSize = 0,
            queueIndex = 0
        )
    }

    fun seekTo(positionMs: Long) {
        val options = MediaSeekOptions.Builder()
            .setPosition(positionMs.coerceAtLeast(0L))
            .build()
        remoteMediaClient?.seek(options)
        syncPlaybackState()
    }

    fun clearError() {
        _playbackState.value = _playbackState.value.copy(errorMessage = null)
    }

    fun reportCastButtonError(message: String?) {
        _playbackState.value = _playbackState.value.copy(
            isCastAvailable = false,
            statusText = "Cast picker unavailable",
            errorMessage = message ?: "Unable to open the Cast device picker on this phone."
        )
    }

    fun release() {
        progressJob?.cancel()
        remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        castContext?.sessionManager?.removeSessionManagerListener(
            sessionListener,
            CastSession::class.java
        )
        localServer?.stop()
        localServer = null
    }

    private fun playPlaylist(items: List<CastPlaylistItem>, startIndex: Int = 0) {
        playlist = items
        playlistIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        loadCurrentPlaylistItem()
    }

    private fun loadCurrentPlaylistItem() {
        val client = remoteMediaClient
        if (client == null) {
            _playbackState.value = _playbackState.value.copy(
                errorMessage = "Pick a Cast device before streaming."
            )
            return
        }
        val item = playlist.getOrNull(playlistIndex) ?: return

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
        }
        val mediaInfo = MediaInfo.Builder(item.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(item.contentType)
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        client.load(request)
        _playbackState.value = _playbackState.value.copy(
            title = item.title,
            contentUrl = item.url,
            durationMs = 0L,
            positionMs = 0L,
            statusText = "Loading on ${_playbackState.value.deviceName ?: "Cast device"}...",
            errorMessage = null,
            localStreamUrl = item.localStreamUrl,
            queueSize = playlist.size,
            queueIndex = playlistIndex + 1
        )
    }

    private fun attachSession(session: CastSession) {
        remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        remoteMediaClient = session.remoteMediaClient?.also { it.registerCallback(remoteMediaClientCallback) }
        _playbackState.value = _playbackState.value.copy(
            isConnected = true,
            deviceName = session.castDevice?.friendlyName,
            statusText = "Ready to cast",
            errorMessage = null
        )
        startProgressUpdates()
        syncPlaybackState()
    }

    private fun detachSession(status: String) {
        remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        remoteMediaClient = null
        progressJob?.cancel()
        _playbackState.value = _playbackState.value.copy(
            isConnected = false,
            deviceName = null,
            isPlaying = false,
            statusText = status
        )
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                syncPlaybackState()
                delay(1000L)
            }
        }
    }

    private fun syncPlaybackState() {
        val client = remoteMediaClient ?: return
        val mediaInfo = client.mediaInfo
        val metadataTitle = mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE)
        val isPlaying = client.playerState == MediaStatus.PLAYER_STATE_PLAYING
        val duration = mediaInfo?.streamDuration ?: 0L
        val position = client.approximateStreamPosition
        _playbackState.value = _playbackState.value.copy(
            isPlaying = isPlaying,
            title = metadataTitle ?: _playbackState.value.title,
            durationMs = duration.coerceAtLeast(0L),
            positionMs = position.coerceAtLeast(0L),
            statusText = if (isPlaying) "Playing on ${_playbackState.value.deviceName ?: "Cast device"}" else "Ready to cast"
        )
    }

    private fun advancePlaylistIfFinished() {
        val client = remoteMediaClient ?: return
        if (playlist.size <= 1 || advancingFromIdle) return
        val finished = client.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            client.idleReason == MediaStatus.IDLE_REASON_FINISHED
        if (!finished) return

        val nextIndex = playlistIndex + 1
        if (nextIndex >= playlist.size) {
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                statusText = "Playlist finished"
            )
            return
        }

        advancingFromIdle = true
        scope.launch {
            playlistIndex = nextIndex
            loadCurrentPlaylistItem()
            delay(500L)
            advancingFromIdle = false
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    private fun guessContentType(url: String): String {
        val path = url.substringBefore("?").lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mov") -> "video/quicktime"
            else -> "video/mp4"
        }
    }

    private fun resolveStreamUrl(url: String, title: String): ResolvedStreamUrl? {
        if (isLikelyWebPageUrl(url)) return null
        val fallbackType = guessContentType(url)
        val fallbackTitle = title.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "Remote video" } }

        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", "RemoConnect/1.0")
            }
            val status = connection.responseCode
            val finalUrl = connection.url.toString()
            val contentType = connection.contentType?.substringBefore(";")?.trim().orEmpty()
            connection.disconnect()

            val usableType = when {
                isCastPlayableContentType(contentType) -> contentType
                isLikelyDirectMediaUrl(finalUrl) -> fallbackType
                status in 200..399 && isCastPlayableContentType(fallbackType) -> fallbackType
                else -> ""
            }
            if (usableType.isBlank()) null else ResolvedStreamUrl(finalUrl, usableType, fallbackTitle)
        } catch (_: Exception) {
            if (isLikelyDirectMediaUrl(url)) ResolvedStreamUrl(url, fallbackType, fallbackTitle) else null
        }
    }

    private fun isLikelyWebPageUrl(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase(Locale.US) }.getOrDefault("")
        return host.contains("youtube.com") ||
            host.contains("youtu.be") ||
            host.contains("vimeo.com") ||
            host.contains("facebook.com") ||
            host.contains("instagram.com") ||
            host.contains("tiktok.com")
    }

    private fun isLikelyDirectMediaUrl(url: String): Boolean {
        val path = url.substringBefore("?").lowercase(Locale.US)
        return listOf(".mp4", ".m4v", ".webm", ".mov", ".m3u8", ".mpd").any { path.endsWith(it) }
    }

    private fun isCastPlayableContentType(contentType: String): Boolean {
        val type = contentType.lowercase(Locale.US)
        return type.startsWith("video/") ||
            type == "application/x-mpegurl" ||
            type == "application/vnd.apple.mpegurl" ||
            type == "application/dash+xml" ||
            type == "application/octet-stream"
    }
}
