package com.watchapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchapp.di.Container

@Composable
fun DebugScreen(container: Container) {
    val store = container.settingsStore
    val savedHost by store.serverHost.collectAsState()
    val savedPort by store.serverPort.collectAsState()

    var hostInput by remember(savedHost) { mutableStateOf(savedHost) }
    var portInput by remember(savedPort) { mutableStateOf(savedPort.toString()) }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Debug", style = MaterialTheme.typography.title3)

            Label("IMEI")
            Text(store.imei, style = MaterialTheme.typography.body2)

            Label("Server host")
            EditableField(
                value = hostInput,
                onValueChange = { hostInput = it },
                keyboard = KeyboardType.Uri,
            )
            Chip(
                onClick = { store.setServerHost(hostInput.trim()) },
                label = { Text("Save host") },
                modifier = Modifier.fillMaxWidth(),
            )

            Label("Server port")
            EditableField(
                value = portInput,
                onValueChange = { v -> portInput = v.filter { it.isDigit() }.take(5) },
                keyboard = KeyboardType.Number,
            )
            Chip(
                onClick = {
                    portInput.toIntOrNull()?.takeIf { it in 1..65535 }?.let(store::setServerPort)
                },
                label = { Text("Save port") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "Swipe right to return.",
                style = MaterialTheme.typography.caption3,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption2,
        color = Color.Gray,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditableField(
    value: String,
    onValueChange: (String) -> Unit,
    keyboard: KeyboardType,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.body1.copy(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1F1F1F), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
