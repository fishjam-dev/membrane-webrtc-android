package com.dscout.membranevideoroomdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dscout.membranevideoroomdemo.viewmodels.RoomViewModel

import com.dscout.membranevideoroomdemo.viewmodels.viewModelByFactory
import timber.log.Timber


val URL = "http://192.168.83.149:4000/socket"

class MainActivity : AppCompatActivity() {
    private val viewModel: RoomViewModel by viewModelByFactory {
        RoomViewModel(URL, application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val forest = Timber.forest()
        forest.none {
            true
        }
        Timber.plant(Timber.DebugTree())

        super.onCreate(savedInstanceState)

        setContent {
            MainContent()
        }
    }

    @Preview(
        showBackground = true,
        showSystemUi = true
    )
    @Composable
    fun MainContent() {
        Surface(
            color = MaterialTheme.colors.background,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Hello mate")
                Button(onClick = { viewModel.connect() }) {
                    Text("Connect")

                }
            }
        }
    }
}