package com.example.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lab.ui.theme.LabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LabTheme {
                TextBoxList()
            }
        }
    }
}

@Composable
fun TextBoxList() {
    val textFields = remember {
        mutableStateListOf("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        for (i in textFields.indices) {
            OutlinedTextField(
                value = textFields[i],
                onValueChange = { newText ->
                    textFields[i] = newText
                },
                label = { Text("Enter a text...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }

        Button(
            onClick = {
                textFields.add("")
            }
        ) {
            Text("+")
        }
    }
}
