package com.filestreaming.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

/**
 * Odtwarzacz streamingowy — streamuje pliki wideo z serwera HTTP.
 *
 * Cechy:
 * - Streamuje bez pobierania na dysk (tylko bufor w RAM)
 * - Obsługuje przewijanie (Range requests)
 * - Obsługuje playlisty (wiele plików)
 * - Pełny ekran z automatycznym ukrywaniem kontrolek
 * - Zapętlanie / losowa kolejność
 * - Przejdź do następnego / poprzedniego pliku
 * - Muzyka w tle (drugi ExoPlayer) — wybór pliku audio z urządzenia
 * - Start z 5-sekundowym opóźnieniem po kliknięciu Play
 */
class StreamPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLAYLIST_URLS = "playlist_urls"
        const val EXTRA_PLAYLIST_NAMES = "playlist_names"
        const val EXTRA_START_INDEX = "start_index"

        private const val HIDE_CONTROLS_DELAY = 3000L
        private const val PLAY_DELAY_SECONDS = 5
    }

    // --- Player ---
    private lateinit var player: ExoPlayer
    private var audioPlayer: ExoPlayer? = null

    // --- UI ---
    private lateinit var playerView: PlayerView
    private lateinit var controlsPanel: View
    private lateinit var titleLabel: TextView
    private lateinit var fileCounterLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var audioLabel: TextView
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnFullscreen: MaterialButton
    private lateinit var btnLoop: MaterialButton
    private lateinit var btnRandom: MaterialButton
    private lateinit var btnSelectAudio: MaterialButton

    // --- State ---
    private var playlistUrls: Array<String> = emptyArray()
    private var playlistNames: Array<String> = emptyArray()
    private var isLooping = false
    private var isRandomMode = false
    private var isFullscreen = false
    private var controlsVisible = true
    private var backgroundAudioUri: Uri? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    private val playHandler = Handler(Looper.getMainLooper())
    private var countdownSeconds = 0

    // --- Activity Result Launcher for audio file picker ---
    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onAudioPicked(it) }
        }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        initViews()
        initPlayer()
        initGestureDetector()

        // Załaduj playlistę lub pojedynczy plik (BEZ auto-play)
        loadFromIntent()
    }

    override fun onPause() {
        super.onPause()
        player.pause()
        audioPlayer?.pause()
        playHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        playHandler.removeCallbacksAndMessages(null)
        player.release()
        audioPlayer?.release()
    }

    // =========================================================================
    // Init
    // =========================================================================

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        controlsPanel = findViewById(R.id.controlsPanel)
        titleLabel = findViewById(R.id.titleLabel)
        fileCounterLabel = findViewById(R.id.fileCounterLabel)
        statusLabel = findViewById(R.id.statusLabel)
        audioLabel = findViewById(R.id.audioLabel)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnLoop = findViewById(R.id.btnLoop)
        btnRandom = findViewById(R.id.btnRandom)
        btnSelectAudio = findViewById(R.id.btnSelectAudio)

        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnPrev.setOnClickListener { playPrevious() }
        btnNext.setOnClickListener { playNext() }
        btnFullscreen.setOnClickListener { toggleFullscreen() }
        btnLoop.setOnClickListener { toggleLoop() }
        btnRandom.setOnClickListener { toggleRandom() }
        btnSelectAudio.setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        statusLabel.text = getString(R.string.stream_ended)
                        btnPlayPause.text = getString(R.string.play)
                        showControls()
                    }
                    Player.STATE_READY -> {
                        if (player.isPlaying) {
                            statusLabel.text = getString(R.string.streaming)
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        statusLabel.text = getString(R.string.buffering)
                    }
                    Player.STATE_IDLE -> {
                        statusLabel.text = getString(R.string.ready)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    btnPlayPause.text = getString(R.string.pause)
                    statusLabel.text = getString(R.string.streaming)
                    scheduleHideControls()
                } else {
                    if (player.playbackState != Player.STATE_ENDED) {
                        btnPlayPause.text = getString(R.string.resume)
                        statusLabel.text = getString(R.string.paused)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateFileCounter()
                updateTitle()
            }

            override fun onPlayerError(error: PlaybackException) {
                statusLabel.text = getString(R.string.stream_error_format, error.message ?: "?")
                showControls()
                Toast.makeText(
                    this@StreamPlayerActivity,
                    getString(R.string.playback_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun initGestureDetector() {
        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (controlsVisible) hideControls() else showControlsTemporarily()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggleFullscreen()
                    return true
                }
            })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // =========================================================================
    // Load media (BEZ auto-play)
    // =========================================================================

    private fun loadFromIntent() {
        playlistUrls = intent.getStringArrayExtra(EXTRA_PLAYLIST_URLS) ?: emptyArray()
        playlistNames = intent.getStringArrayExtra(EXTRA_PLAYLIST_NAMES) ?: emptyArray()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        if (playlistUrls.isEmpty()) {
            // Pojedynczy plik
            val url = intent.getStringExtra(EXTRA_STREAM_URL)
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"

            if (url == null) {
                Toast.makeText(this, getString(R.string.no_url), Toast.LENGTH_LONG).show()
                finish()
                return
            }

            playlistUrls = arrayOf(url)
            playlistNames = arrayOf(title)
        }

        // Załaduj playlistę — ale NIE odtwarzaj jeszcze
        val mediaItems = playlistUrls.map { MediaItem.fromUri(it) }
        player.setMediaItems(mediaItems, startIndex, 0)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
        // NIE wywołujemy player.play() — czekamy na kliknięcie Play

        updateFileCounter()
        updateTitle()
        statusLabel.text = getString(R.string.ready)
        btnPlayPause.text = getString(R.string.play)

        // Pełny ekran od razu, ale kontrolki widoczne
        enterFullscreen()
        showControls()
        hideHandler.removeCallbacks(hideRunnable)
    }

    // =========================================================================
    // Background Audio — wybór muzyki z urządzenia
    // =========================================================================

    private fun onAudioPicked(uri: Uri) {
        // Zachowaj uprawnienia do odczytu
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}

        backgroundAudioUri = uri

        // Stwórz / odśwież odtwarzacz audio w tle
        audioPlayer?.release()
        val ap = ExoPlayer.Builder(this).build()
        ap.volume = 0.5f  // 50% głośności
        ap.repeatMode = Player.REPEAT_MODE_ONE  // Zapętlaj muzykę

        val mediaItem = MediaItem.fromUri(uri)
        ap.setMediaItem(mediaItem)
        ap.prepare()

        audioPlayer = ap

        // Pobierz nazwę pliku
        val filename = getFilenameFromUri(uri)
        audioLabel.text = getString(R.string.audio_format, filename)
        statusLabel.text = "Muzyka w tle: $filename"

        // Jeśli wideo jest odtwarzane, uruchom audio natychmiast
        if (player.isPlaying) {
            ap.play()
        }
    }

    private fun getFilenameFromUri(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    )
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "audio"
    }

    // =========================================================================
    // Playback controls (z 5-sekundowym opóźnieniem)
    // =========================================================================

    private fun togglePlayPause() {
        if (player.isPlaying) {
            // Pauza
            player.pause()
            audioPlayer?.pause()
            playHandler.removeCallbacksAndMessages(null)
            btnPlayPause.text = getString(R.string.resume)
            statusLabel.text = getString(R.string.paused)
            showControls()
            hideHandler.removeCallbacks(hideRunnable)
        } else {
            // Jeśli zakończono — przewiń na początek
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0, 0)
            }

            // Ukryj kontrolki od razu
            controlsPanel.visibility = View.GONE
            controlsVisible = false

            // Odliczanie 5 sekund
            countdownSeconds = PLAY_DELAY_SECONDS
            statusLabel.text = getString(R.string.start_countdown, countdownSeconds)
            playHandler.removeCallbacksAndMessages(null)
            startCountdown()
        }
    }

    private fun startCountdown() {
        if (countdownSeconds > 0) {
            statusLabel.text = getString(R.string.start_countdown, countdownSeconds)
            countdownSeconds--
            playHandler.postDelayed({ startCountdown() }, 1000)
        } else {
            // Czas minął — uruchom odtwarzanie
            player.play()
            audioPlayer?.let {
                if (backgroundAudioUri != null) it.play()
            }
            btnPlayPause.text = getString(R.string.pause)
            statusLabel.text = getString(R.string.streaming)
        }
    }

    private fun playNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            Toast.makeText(this, getString(R.string.last_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun playPrevious() {
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            // Przewiń do początku
            player.seekTo(0)
        }
    }

    // =========================================================================
    // Loop & Random
    // =========================================================================

    private fun toggleLoop() {
        isLooping = !isLooping
        player.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        btnLoop.text = getString(if (isLooping) R.string.loop_on else R.string.loop_off)
    }

    private fun toggleRandom() {
        isRandomMode = !isRandomMode
        player.shuffleModeEnabled = isRandomMode
        btnRandom.text = getString(if (isRandomMode) R.string.random_on else R.string.random_off)
    }

    // =========================================================================
    // Fullscreen
    // =========================================================================

    private fun enterFullscreen() {
        isFullscreen = true
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        btnFullscreen.text = getString(R.string.window_mode)
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            btnFullscreen.text = getString(R.string.window_mode)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            btnFullscreen.text = getString(R.string.fullscreen)
        }
        showControlsTemporarily()
    }

    // =========================================================================
    // Controls visibility
    // =========================================================================

    private fun showControls() {
        controlsPanel.visibility = View.VISIBLE
        controlsVisible = true
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun showControlsTemporarily() {
        controlsPanel.visibility = View.VISIBLE
        controlsVisible = true
        scheduleHideControls()
    }

    private fun hideControls() {
        controlsPanel.visibility = View.GONE
        controlsVisible = false
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, HIDE_CONTROLS_DELAY)
    }

    // =========================================================================
    // UI Updates
    // =========================================================================

    private fun updateFileCounter() {
        val total = playlistUrls.size
        val current = if (total > 0) player.currentMediaItemIndex + 1 else 0
        fileCounterLabel.text = getString(R.string.file_counter_format, current, total)
    }

    private fun updateTitle() {
        val idx = player.currentMediaItemIndex
        if (idx in playlistNames.indices) {
            titleLabel.text = playlistNames[idx]
        }
    }

    // =========================================================================
    // Back
    // =========================================================================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Anuluj odliczanie jeśli aktywne
        playHandler.removeCallbacksAndMessages(null)

        if (isFullscreen) {
            toggleFullscreen()
        } else {
            // Zatrzymaj audio w tle
            audioPlayer?.stop()
            super.onBackPressed()
        }
    }
}
