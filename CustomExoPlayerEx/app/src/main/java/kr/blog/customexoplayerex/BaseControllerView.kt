package kr.blog.customexoplayerex

import android.annotation.SuppressLint
import android.content.Context
import android.transition.Slide
import android.transition.Transition
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.ui.DefaultTimeBar
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.ui.TimeBar
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Formatter
import java.util.Locale

class BaseControllerView : LinearLayout {

    private lateinit var playerValue: Player
    private var bar: View? = null

    private lateinit var controllerView: LinearLayout
    private lateinit var rewindButton: ImageView
    private lateinit var forwardButton: ImageView
    private lateinit var playButton: ImageButton
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var curPositionView: TextView
    private lateinit var durPositionView: TextView
    private lateinit var playerView: StyledPlayerView
    private lateinit var playSpeedView: TextView

    private lateinit var listener: CustomComponentListener
    private val formatBuilder: StringBuilder by lazy { StringBuilder() }
    private val formatter: Formatter by lazy { Formatter(formatBuilder, Locale.getDefault()) }
    private val window: Timeline.Window by lazy { Timeline.Window() }
    private val updateProgressAction = this::updateProgress

    private var playSpeedPosition = DEFAULT_SPEED_POSITION
    private var coroutineForCancelAndCreate = CoroutineScope(Dispatchers.Main)
    private val speedList = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    inner class CustomComponentListener :
        Player.Listener,
        TimeBar.OnScrubListener,
        OnClickListener,
        PopupWindow.OnDismissListener {

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED))
                updatePlayPauseButton()

            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_IS_PLAYING_CHANGED))
                updateProgress()

            if (events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED))
                updateTimeline()

            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED))
                updateSpeedText()
        }

        override fun onScrubStart(timeBar: TimeBar, position: Long) { setTimeText(position) }
        override fun onScrubMove(timeBar: TimeBar, position: Long) { setTimeText(position) }
        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) { seekToTimeBarPosition(position) }
        override fun onClick(view: View?) {
            if(view != playerView) delayVisibleGone()
            when(view){
                playerView -> setControllerVisible()
                playButton -> dispatchPlayPause()
                rewindButton -> playerValue.currentPosition.minus(DEFAULT_SEEK_VALUE).let { playerValue.seekTo(it) }
                forwardButton -> playerValue.currentPosition.plus(DEFAULT_SEEK_VALUE).let { playerValue.seekTo(it) }
                playSpeedView -> setNextSpeed()
            }
        }
        override fun onDismiss() {}
    }

    constructor(context: Context) : super(context) { initView() }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { initView() }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { initView() }

    private fun initView() {
        val infService = Context.LAYOUT_INFLATER_SERVICE
        val li = context.getSystemService(infService) as LayoutInflater
        val v = li.inflate(R.layout.layout_controller, this, false)
        addView(v)
        init()
    }

    private fun init() {
        controllerView = findViewById(R.id.controller_view)
        rewindButton = findViewById(R.id.rewind)
        forwardButton = findViewById(R.id.forward)
        playButton = findViewById(R.id.play)
        timeBar = findViewById(R.id.time_bar)
        curPositionView = findViewById(R.id.position)
        durPositionView = findViewById(R.id.duration)
        playSpeedView = findViewById(R.id.play_speed)
    }

    fun setPlayer(styledPlayerView: StyledPlayerView) = this.apply {
        playerView = styledPlayerView.apply {
            playerValue = player ?: ExoPlayer.Builder(context).build()
            playerValue.prepare()
            listener = CustomComponentListener()
            updateAll()
        }
        setListener()
    }

    fun setBar(barView: View) = this.apply { bar = barView }

    private fun updateAll() {
        updateTimeline()
        updatePlayPauseButton()
        updateSpeedText()
        setControllerVisible()
    }

    private fun setListener(){
        playerView.setOnClickListener(listener)
        rewindButton.setOnClickListener(listener)
        forwardButton.setOnClickListener(listener)
        playButton.setOnClickListener(listener)
        playSpeedView.setOnClickListener(listener)
        playerValue.addListener(listener)
        timeBar.addListener(listener)
        setOnClickListener(listener)
    }

    private fun updateProgress() {
        if (!isAttachedToWindow) return

        val position =  playerValue.contentPosition
        val bufferedPosition =  playerValue.contentBufferedPosition

        setTimeText(position)
        timeBar.setPosition(position)
        timeBar.setBufferedPosition(bufferedPosition)

        // Cancel any pending updates and schedule a new one if necessary.
        removeCallbacks(updateProgressAction)
        val playbackState = playerValue.playbackState
        if (playerValue.isPlaying) {
            var mediaTimeDelayMs = timeBar.preferredUpdateDelay

            // Limit delay to the start of the next full second to ensure position display is smooth.
            val mediaTimeUntilNextFullSecondMs = 1000 - position % 1000
            mediaTimeDelayMs = mediaTimeDelayMs.coerceAtMost(mediaTimeUntilNextFullSecondMs)

            // Calculate the delay until the next update in real time, taking playback speed into account.
            val playbackSpeed: Float = playerValue.playbackParameters.speed
            var delayMs = if (playbackSpeed > 0) (mediaTimeDelayMs / playbackSpeed).toLong() else MAX_UPDATE_INTERVAL_MS.toLong()

            // Constrain the delay to avoid too frequent / infrequent updates.
            delayMs = Util.constrainValue(
                delayMs,
                DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS.toLong(),
                MAX_UPDATE_INTERVAL_MS.toLong()
            )
            postDelayed(updateProgressAction, delayMs)
        } else if (playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE) postDelayed(updateProgressAction, MAX_UPDATE_INTERVAL_MS.toLong())
    }

    @SuppressLint("SetTextI18n")
    private fun setTimeText(position: Long){
        val remainingTime = playerValue.duration - position // 남은 시간
        curPositionView.text = Util.getStringForTime(formatBuilder, formatter, position)
        durPositionView.text = "-${Util.getStringForTime(formatBuilder, formatter, remainingTime)}"
    }

    private fun updateTimeline(){
        val durationMs = playerValue.duration
        timeBar.setDuration(durationMs)
        updateProgress()
    }

    private fun shouldShowPauseButton()
            = playerValue.playbackState != Player.STATE_ENDED && playerValue.playbackState != Player.STATE_IDLE && playerValue.playWhenReady

    private fun dispatchPlayPause() {
        val state: @Player.State Int = playerValue.playbackState
        if ( (state == Player.STATE_IDLE) || (state == Player.STATE_ENDED) || !playerValue.playWhenReady) dispatchPlay()
        else dispatchPause()
    }

    private fun dispatchPlay() {
        val state: @Player.State Int = playerValue.playbackState
        if (state == Player.STATE_IDLE) playerValue.prepare()
        else if (state == Player.STATE_ENDED) seekTo(playerValue, playerValue.currentMediaItemIndex, C.TIME_UNSET)
        playerValue.play()
    }

    private fun dispatchPause() = playerValue.pause()

    private fun seekTo(player: Player, windowIndex: Int, positionMs: Long) = player.seekTo(windowIndex, positionMs)

    private fun updatePlayPauseButton() {
        if (!isAttachedToWindow) return
        if (shouldShowPauseButton()) playButton.setBackgroundResource(R.drawable.pause_circle)
        else playButton.setBackgroundResource(R.drawable.play_circle)
    }

    private fun seekToTimeBarPosition(positionMs: Long) {
        var positionMs = positionMs
        var windowIndex: Int
        val timeline = playerValue.currentTimeline
        if (!timeline.isEmpty) {
            val windowCount = timeline.windowCount
            windowIndex = 0
            while (true) {
                val windowDurationMs = timeline.getWindow(windowIndex, window).durationMs
                if (positionMs < windowDurationMs) break
                else if (windowIndex == windowCount - 1) {
                    // Seeking past the end of the last window should seek to the end of the timeline.
                    positionMs = windowDurationMs
                    break
                }
                positionMs -= windowDurationMs
                windowIndex++
            }
        } else windowIndex = playerValue.currentMediaItemIndex
        seekTo(playerValue, windowIndex, positionMs)
        updateProgress()
    }

    @SuppressLint("SetTextI18n")
    private fun updateSpeedText() = playSpeedView.run {
        val speed = speedList[playSpeedPosition].toString()
        text = "${speed}x"
    }

    private fun setNextSpeed() = speedList.run {
        if(playSpeedPosition == size - 1) playSpeedPosition = 0
        else playSpeedPosition += 1
        setPlaybackSpeed(speedList[playSpeedPosition])
    }

    private fun setPlaybackSpeed(speed: Float) = playerValue.run { playbackParameters = playbackParameters.withSpeed(speed) }

    private fun setControllerVisible() {
        val transition: Transition = Slide(Gravity.BOTTOM)
        transition.duration = 600
        transition.addTarget(this@BaseControllerView)
        TransitionManager.beginDelayedTransition(this@BaseControllerView as ViewGroup, transition)
        if (isVisible) {
            setVisible(false)
            coroutineForCancelAndCreate.cancel()
        }
        else {
            setVisible(true)
            delayVisibleGone()
        }
    }

    private fun delayVisibleGone() {
        coroutineForCancelAndCreate.cancel()
        coroutineForCancelAndCreate = CoroutineScope(Dispatchers.Main)
        coroutineForCancelAndCreate.launch {
            delay(DEFAULT_DISAPPEARED_TIME)
            if(isVisible) setVisible(false)
        }
    }

    private fun setVisible(visible: Boolean) {
        isVisible = visible
        bar?.isVisible = visible
    }

    companion object {
        const val MAX_UPDATE_INTERVAL_MS = 1000
        const val DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS = 200
        const val DEFAULT_SEEK_VALUE = 5000
        const val DEFAULT_SPEED_POSITION = 3
        const val DEFAULT_DISAPPEARED_TIME = 6000L
    }
}