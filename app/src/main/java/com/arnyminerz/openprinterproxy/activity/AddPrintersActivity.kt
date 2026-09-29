package com.arnyminerz.openprinterproxy.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arnyminerz.openprinterproxy.ui.icon.Icons
import com.arnyminerz.openprinterproxy.ui.icon.close
import com.arnyminerz.openprinterproxy.ui.icon.refresh
import com.arnyminerz.openprinterproxy.viewmodel.AddPrintersViewModel

class AddPrintersActivity : AppCompatActivity() {
    private val viewModel: AddPrintersViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val printers by viewModel.printers.collectAsState()

            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = ::finish) {
                                Icon(Icons.close, contentDescription = "Close")
                            }
                        },
                        title = { Text("Add Printers") },
                        actions = {
                            IconButton(onClick = viewModel::listPrinters) {
                                Icon(Icons.refresh, contentDescription = "Refresh")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues)
                ) {
                    items(printers) { printer ->
                        Text(printer.deviceName)
                    }
                }
            }
        }
    }
}
