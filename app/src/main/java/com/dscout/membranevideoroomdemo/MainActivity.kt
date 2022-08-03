@file:OptIn(ExperimentalFoundationApi::class)

package com.dscout.membranevideoroomdemo

import android.Manifest.permission.CAMERA
import android.Manifest.permission.RECORD_AUDIO
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusOrder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dscout.membranevideoroomdemo.styles.AppButtonColors
import com.dscout.membranevideoroomdemo.styles.AppTextFieldColors
import com.dscout.membranevideoroomdemo.styles.Blue
import com.dscout.membranevideoroomdemo.styles.darker
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

const val URL = "https://dscout-us.membrane.work/socket"
//val URL = "http://192.168.83.26:4000/socket"

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MainContent(onConnect = { room, displayName ->
                Intent(this@MainActivity, RoomActivity::class.java).apply {
                    putExtra(
                        RoomActivity.ARGS,
                        RoomActivity.BundleArgs(room, displayName)
                    )
                }.let {
                    startActivity(it)
                }
            })
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Preview(
        showBackground = true,
        showSystemUi = true,
    )
    @Composable
    fun MainContent(onConnect: (String, String) -> Unit = { _, _ -> }) {

        val scrollableState = rememberScrollState()
        val roomName = remember { mutableStateOf(TextFieldValue("room")) }
        val displayName = remember { mutableStateOf(TextFieldValue("Android User")) }

        val (first, second, third) = FocusRequester.createRefs()
        val focusManager = LocalFocusManager.current

        val keyboardController = LocalSoftwareKeyboardController.current

        Scaffold(
            modifier = Modifier .fillMaxSize(),
            backgroundColor = Blue.darker(0.5f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollableState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = "Application logo",
                    modifier= Modifier
                        .height(200.dp)
                        .fillMaxWidth(0.9f)
                )

                OutlinedTextField(
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = AppTextFieldColors(),
                    value = roomName.value,
                    onValueChange =  { roomName.value = it},
                    placeholder = { Text("Room name...")},
                    label = { Text("Room name") },
                    modifier = Modifier.focusOrder(first) { down = second },
                    keyboardOptions = KeyboardOptions( imeAction = ImeAction.Next ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = AppTextFieldColors(),
                    value = displayName.value,
                    onValueChange =  { displayName.value = it},
                    placeholder = { Text("Display name...")},
                    label = { Text("Display name") },
                    modifier = Modifier.focusOrder(second) { down = third },
                    keyboardOptions = KeyboardOptions( imeAction = ImeAction.Next ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                ConnectWithPermissions {
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()

                            onConnect(roomName.value.text, displayName.value.text)
                        },
                        enabled =  !(roomName.value.text.isEmpty() || displayName.value.text.isEmpty()),
                        colors = AppButtonColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .width(200.dp)
                            .focusOrder(third)
                    ) {
                        Text("Join room")
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    fun ConnectWithPermissions(content: @Composable () -> Unit) {
        val multiplePermissionsState = rememberMultiplePermissionsState(
            listOf(
                RECORD_AUDIO,
                CAMERA
            )
        )

        val alreadyRequested = remember { mutableStateOf(false) }

        if (multiplePermissionsState.allPermissionsGranted) {
            content()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val textToShow = when {
                    multiplePermissionsState.shouldShowRationale ->
                        "Application requires an access to a microphone and camera for it to work"

                    !multiplePermissionsState.shouldShowRationale && alreadyRequested.value ->
                        "You need to explicitly grant the access to the camera and microphone in system settings..."

                    else ->
                        null
                }

                Button(
                    colors = AppButtonColors(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = {
                        if (!multiplePermissionsState.shouldShowRationale && alreadyRequested.value) {
                            val intent =  Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.addCategory(Intent.CATEGORY_DEFAULT)
                            intent.data = Uri.parse("package:$packageName")

                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

                            startActivity(intent)
                        } else {
                            multiplePermissionsState.launchMultiplePermissionRequest()
                        }

                        alreadyRequested.value = true
                    }
                ) {
                    Text("Request permissions")
                }

                textToShow?.let {
                    Text(it, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
    }
}