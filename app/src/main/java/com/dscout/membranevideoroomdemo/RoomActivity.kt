package com.dscout.membranevideoroomdemo

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dscout.membranevideoroomdemo.components.ParticipantVideoView
import com.dscout.membranevideoroomdemo.components.VideoViewLayout
import com.dscout.membranevideoroomdemo.models.Participant
import com.dscout.membranevideoroomdemo.styles.AppButtonColors
import com.dscout.membranevideoroomdemo.styles.Blue
import com.dscout.membranevideoroomdemo.styles.darker
import com.dscout.membranevideoroomdemo.viewmodels.RoomViewModel
import com.dscout.membranevideoroomdemo.viewmodels.viewModelByFactory
import kotlinx.android.parcel.Parcelize
import org.membraneframework.rtc.TrackEncoding
import org.membraneframework.rtc.models.VadStatus

class RoomActivity : AppCompatActivity() {
    private val viewModel: RoomViewModel by viewModelByFactory {
        RoomViewModel(BuildConfig.VIDEOROOM_URL, application)
    }

    private val screencastLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

            result.data?.let {
                viewModel.startScreencast(it)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val (room, displayName) = intent.getParcelableExtra<BundleArgs>(ARGS)
            ?: throw NullPointerException("Failed to decode intent's parcelable")
        viewModel.connect(room, displayName)

        setContent {
            Content(
                viewModel = viewModel,
                startScreencast = {
                    val mediaProjectionManager =
                        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screencastLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                },
                onEnd = { finish() }
            )
        }
    }

    @Composable
    fun Content(viewModel: RoomViewModel, startScreencast: () -> Unit, onEnd: () -> Unit) {
        val participants = viewModel.participants.collectAsState()
        val primaryParticipant = viewModel.primaryParticipant.collectAsState()
        val errorMessage = viewModel.errorMessage.collectAsState()
        val videoSimulcastConfig = viewModel.videoSimulcastConfig.collectAsState()
        val soundVolumedB = viewModel.soundVolumedB.collectAsState()
        val isSoundDetectionOn = viewModel.isSoundDetectionOn.collectAsState()
        val scrollState = rememberScrollState()

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            backgroundColor = Blue.darker(0.5f)
        ) {
            Box {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                ) {
                    errorMessage.value?.let {
                        Text(
                            it,
                            color = Color.Red,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 30.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        if (isSoundDetectionOn.value) {
                            "volume (dB): ${soundVolumedB.value} "
                        } else {
                            "volume (dB): turn on sound detection first"
                        },
                        color = Color.White,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )

                    Row(
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(text = "Video quality:")
                        listOf(TrackEncoding.H, TrackEncoding.M, TrackEncoding.L).map {
                            Button(
                                onClick = {
                                    viewModel.toggleVideoTrackEncoding(it)
                                },
                                colors = AppButtonColors(),
                                modifier = Modifier.then(
                                    if (videoSimulcastConfig.value.activeEncodings.contains(it)) {
                                        Modifier.alpha(1f)
                                    } else {
                                        Modifier.alpha(
                                            0.5f
                                        )
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(it.name)
                            }
                        }
                    }

                    primaryParticipant.value?.let {
                        ParticipantCard(
                            participant = it,
                            videoViewLayout = VideoViewLayout.FIT,
                            size = Size(200f, 200 * (16f / 9f))
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(248.dp)
                            .padding(10.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        participants.value.chunked(2).forEach {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                            ) {
                                ParticipantCard(
                                    participant = it[0],
                                    videoViewLayout = VideoViewLayout.FILL,
                                    size = Size(100f, 100f),
                                    onClick = {
                                        viewModel.focusVideo(it[0].id)
                                    }
                                )
                                Box(modifier = Modifier.width(16.dp)) { }
                                if (it.size > 1) {
                                    ParticipantCard(
                                        participant = it[1],
                                        videoViewLayout = VideoViewLayout.FILL,
                                        size = Size(100f, 100f),
                                        onClick = {
                                            viewModel.focusVideo(it[1].id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    ControlIcons(roomViewModel = viewModel, startScreencast = startScreencast, onEnd = onEnd)
                }
            }
        }
    }

    companion object {
        const val ARGS = "args"
    }

    @Parcelize
    data class BundleArgs(val room: String, val displayName: String) : Parcelable

    override fun onDestroy() {
        super.onDestroy()

        viewModel.disconnect()
    }
}

@Composable
fun ParticipantCard(
    participant: Participant,
    videoViewLayout: VideoViewLayout,
    size: Size,
    onClick: (() -> Unit)? = null
) {
    fun isTrackNotActive(trackType: String): Boolean {
        return when (trackType) {
            "audio" -> {
                (participant.tracksMetadata[participant.audioTrack?.id()]?.get("active") as? Boolean) != true
            }

            "video" -> {
                (participant.tracksMetadata[participant.videoTrack?.id()]?.get("active") as? Boolean) != true
            }

            else -> {
                throw IllegalArgumentException("Invalid media type: $trackType")
            }
        }
    }

    fun shouldShowIcon(trackType: String): Boolean {
        return when (trackType) {
            "audio" -> {
                participant.audioTrack == null || (
                    participant.tracksMetadata.isNotEmpty() && isTrackNotActive(trackType)
                    )
            }

            "video" -> {
                participant.videoTrack == null || (
                    participant.tracksMetadata.isNotEmpty() && isTrackNotActive(trackType)
                    )
            }

            else -> {
                throw IllegalArgumentException("Invalid media type: $trackType")
            }
        }
    }

    val iconModifier =
        Modifier
            .padding(10.dp)
            .size(20.dp)

    Box(
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onClick?.invoke()
            }
            .clip(RoundedCornerShape(10.dp))
            .height(size.height.dp)
            .width(size.width.dp)
            .border(if (participant.vadStatus == VadStatus.SPEECH) 10.dp else 0.dp, Color.White)
            .background(Blue.darker(0.7f))
    ) {
        if (shouldShowIcon("video")) {
            Box(
                modifier = Modifier
                    .background(Blue.darker(0.7f))
                    .fillMaxHeight()
                    .fillMaxWidth()
            ) {
                Row(modifier = Modifier.align(Alignment.Center)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_video_off),
                        contentDescription = "no camera",
                        modifier = iconModifier,
                        tint = Color.White
                    )
                }
            }
        } else {
            ParticipantVideoView(
                participant = participant,
                videoViewLayout = videoViewLayout,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(Blue.darker(0.7f))
            )
        }

        Text(
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            text = participant.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(size.width.dp - 20.dp)
                .padding(20.dp)
        )

        if (shouldShowIcon("audio")) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic_off),
                    contentDescription = "microphone control",
                    modifier = iconModifier,
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
fun ControlIcons(roomViewModel: RoomViewModel, startScreencast: () -> Unit, onEnd: () -> Unit) {
    val iconModifier =
        Modifier
            .padding(10.dp)
            .size(50.dp)

    val isMicOn = roomViewModel.isMicrophoneOn.collectAsState()
    val isSoundDetectionOn = roomViewModel.isSoundDetectionOn.collectAsState()
    val isSoundDetected = roomViewModel.isSoundDetected.collectAsState()
    val isCamOn = roomViewModel.isCameraOn.collectAsState()
    val isScreenCastOn = roomViewModel.isScreenCastOn.collectAsState()
    LazyRow(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Blue.darker(0.7f))
    ) {
        item {
            IconButton(onClick = { roomViewModel.toggleMicrophone() }) {
                Icon(
                    painter = painterResource(if (isMicOn.value) R.drawable.ic_mic_on else R.drawable.ic_mic_off),
                    contentDescription = "microphone control",
                    modifier = iconModifier,
                    tint = Color.White
                )
            }
            if (isMicOn.value) {
                IconButton(onClick = { roomViewModel.toggleSoundDetection() }) {
                    Icon(
                        painter = painterResource(
                            if (isSoundDetectionOn.value) R.drawable.ic_mic_on else R.drawable.ic_mic_off
                        ),
                        contentDescription = "sound detection control",
                        modifier = iconModifier,
                        tint = if (isSoundDetected.value) Color.Blue else Color.DarkGray
                    )
                }
            }

            IconButton(onClick = { roomViewModel.toggleCamera() }) {
                Icon(
                    painter = painterResource(if (isCamOn.value) R.drawable.ic_video_on else R.drawable.ic_video_off),
                    contentDescription = "camera control",
                    modifier = iconModifier,
                    tint = Color.White
                )
            }

            IconButton(onClick = {
                onEnd()
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_call_end),
                    contentDescription = "call end control",
                    modifier = iconModifier,
                    tint = Color.Red
                )
            }

            IconButton(onClick = { roomViewModel.flipCamera() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_camera_flip),
                    contentDescription = "camera flip control",
                    modifier = iconModifier,
                    tint = Color.White
                )
            }

            IconButton(onClick = {
                if (isScreenCastOn.value) {
                    roomViewModel.stopScreencast()
                } else {
                    startScreencast()
                }
            }) {
                Icon(
                    painter = painterResource(
                        if (!isScreenCastOn.value) R.drawable.ic_screen_on else R.drawable.ic_screen_off
                    ),
                    contentDescription = "screen cast control",
                    modifier = iconModifier,
                    tint = Color.White
                )
            }
        }
    }
}
