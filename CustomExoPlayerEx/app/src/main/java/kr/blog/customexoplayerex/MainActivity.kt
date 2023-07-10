package kr.blog.customexoplayerex

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import kr.blog.customexoplayerex.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var simpleExoPlayer: ExoPlayer? = null
    private var videoUrl = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this,R.layout.activity_main)
        binding.lifecycleOwner = this
        initPlayer()
    }

    private fun initPlayer() = binding.run {
        close.bringToFront()

        simpleExoPlayer = ExoPlayer.Builder(this@MainActivity).build()
        simpleExoPlayer?.setMediaSource(buildMediaSource(videoUrl))

        playerView.run{
            player = simpleExoPlayer
            controller
                .setPlayer(this)
                .setBar(close)
        }
        close.setOnClickListener { finish() }
    }

    // to get media source from url to attach it into video
    private fun buildMediaSource(videoUrl: String) = ProgressiveMediaSource
        .Factory(DefaultDataSource.Factory(this))
        .createMediaSource(MediaItem.fromUri(videoUrl))

    // pause
    override fun onResume() {
        super.onResume()
        simpleExoPlayer?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        simpleExoPlayer?.stop()
        simpleExoPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        simpleExoPlayer?.release()
    }
}