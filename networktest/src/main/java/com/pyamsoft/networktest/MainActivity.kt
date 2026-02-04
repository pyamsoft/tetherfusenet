/*
 * Copyright 2025 pyamsoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pyamsoft.networktest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Enumeration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val (lines, setLines) = remember { mutableStateOf<List<String>>(emptyList()) }

      LaunchedEffect(setLines) {
        withContext(context = Dispatchers.IO) {
          val allIfaces: Enumeration<NetworkInterface>? = NetworkInterface.getNetworkInterfaces()
          if (allIfaces != null) {
            val newLines = mutableListOf<String>()
            for (iface in allIfaces) {
              val name = iface.name.orEmpty()
              if (name.isBlank()) {
                continue
              }

              val addresses =
                  iface.inetAddresses
                      .asSequence()
                      // TODO(Peter): Support both IPv4 and IPv6
                      //              Right now UDP CONNECT does not fully support IPv6 tunneling
                      //              All upstream requests are converted to IPv4
                      .filter { it is Inet4Address }
                      .filterNot { it.isLoopbackAddress }
                      .mapIndexed { index, addr ->
                        addr.hostName.orEmpty().ifBlank { "$index: MISSING HOSTNAME" }
                      }
                      .toList()

              if (addresses.isEmpty()) {
                continue
              }

              newLines.add("NAME: $name\nADDRESSES: $addresses")
            }
            setLines(newLines)
          }
        }
      }

      Scaffold { pv ->
        LazyColumn(
            modifier = Modifier.padding(pv),
        ) {
          items(
              items = lines,
          ) { line ->
            Text(
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
                text = line,
                style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
    }
  }
}