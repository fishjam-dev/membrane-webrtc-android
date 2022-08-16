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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusOrder
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dscout.membranevideoroomdemo.components.VideoViewLayout
import com.dscout.membranevideoroomdemo.components.ParticipantVideoView
import com.dscout.membranevideoroomdemo.models.Participant
import com.dscout.membranevideoroomdemo.styles.AppButtonColors
import com.dscout.membranevideoroomdemo.styles.Blue
import com.dscout.membranevideoroomdemo.styles.darker
import com.dscout.membranevideoroomdemo.viewmodels.RoomViewModel
import com.dscout.membranevideoroomdemo.viewmodels.viewModelByFactory
import kotlinx.android.parcel.Parcelize
import org.membraneframework.rtc.TrackEncoding
import timber.log.Timber

class RoomActivity : AppCompatActivity() {
    private val viewModel: RoomViewModel by viewModelByFactory {
        RoomViewModel(URL, application)
    }

    private val screencastLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

       result.data?.let {
           viewModel.startScreencast(it)
       }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forest = Timber.forest()
        forest.none {
            true
        }
        Timber.plant(Timber.DebugTree())

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
        val screencastSimulcastConfig = viewModel.screencastSimulcastConfig.collectAsState()
        val isScreenCastOn = viewModel.isScreenCastOn.collectAsState()
        val scrollState = rememberScrollState()

        Scaffold(
            modifier = Modifier .fillMaxSize(),
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
                        Text(it, color = Color.Red, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, textAlign = TextAlign.Center)
                    }

                    Row(
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Text(text = "Video quality:")
                        listOf(TrackEncoding.H, TrackEncoding.M, TrackEncoding.L).map {
                            Button(
                                onClick = {
                                    viewModel.toggleVideoTrackEncoding(it)
                                },
                                colors = AppButtonColors(),
                                modifier = Modifier.then(if(videoSimulcastConfig.value.activeEncodings.contains(it)) Modifier.alpha(1f) else Modifier.alpha(0.5f) ),
                                shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(it.name)
                        } }
                    }

                    if(isScreenCastOn.value) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Text(text = "Screencast quality:")
                            listOf(TrackEncoding.H, TrackEncoding.M, TrackEncoding.L).map {
                                Button(
                                    onClick = {
                                        viewModel.toggleScreencastTrackEncoding(it)
                                    },
                                    colors = AppButtonColors(),
                                    modifier = Modifier.then(if(screencastSimulcastConfig.value.activeEncodings.contains(it)) Modifier.alpha(1f) else Modifier.alpha(0.5f) ),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(it.name)
                                } }
                        }
                    }

                    primaryParticipant.value?.let {
                        ParticipantCard(
                            participant = it,
                            videoViewLayout = VideoViewLayout.FIT,
                            size = Size(150f, 150 * (16f / 9f))
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier
                            .padding(10.dp)
                            .fillMaxWidth()
                            .horizontalScroll(scrollState),
                    ) {
                        participants.value.forEach {
                            ParticipantCard(
                                participant = it,
                                videoViewLayout = VideoViewLayout.FILL,
                                size = Size(100f, 100f),
                                onClick = {
                                    viewModel.focusVideo(it.id)
                                }
                            )
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
    Box(
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) {
            onClick?.invoke()
        }
    ) {
        ParticipantVideoView(
            participant = participant,
            videoViewLayout = videoViewLayout ,
            modifier = Modifier
                .padding(10.dp)
                .clip(RoundedCornerShape(10.dp))
                .height(size.height.dp)
                .width(size.width.dp)
                .background(Blue.darker(0.7f))
        )

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
    }
}

@Composable
fun ControlIcons(
    roomViewModel: RoomViewModel,
    startScreencast: () -> Unit,
    onEnd: () -> Unit
) {
    val iconModifier =
        Modifier
            .padding(10.dp)
            .size(50.dp)

    val isMicOn = roomViewModel.isMicrophoneOn.collectAsState()
    val isCamOn = roomViewModel.isCameraOn.collectAsState()
    val isScreenCastOn = roomViewModel.isScreenCastOn.collectAsState()

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = { roomViewModel.toggleMicrophone() }) {
            Icon(
                painter = painterResource(if (isMicOn.value)  R.drawable.ic_mic_on else R.drawable.ic_mic_off),
                contentDescription = "microphone control",
                modifier = iconModifier,
                tint = Color.White
            )
        }

        IconButton(onClick = { roomViewModel.toggleCamera() }) {
            Icon(
                painter = painterResource(if (isCamOn.value)  R.drawable.ic_video_on else R.drawable.ic_video_off),
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
                painter = painterResource(if (!isScreenCastOn.value)  R.drawable.ic_screen_on else R.drawable.ic_screen_off),
                contentDescription = "screen cast control",
                modifier = iconModifier,
                tint = Color.White
            )
        }
    }
}

