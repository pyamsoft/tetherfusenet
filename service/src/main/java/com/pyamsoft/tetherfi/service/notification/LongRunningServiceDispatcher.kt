/*
 * Copyright 2026 pyamsoft
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

package com.pyamsoft.tetherfi.service.notification

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.annotation.CheckResult
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.pyamsoft.pydroid.notify.NotifyChannelInfo
import com.pyamsoft.pydroid.notify.NotifyData
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.service.R
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
internal class LongRunningServiceDispatcher
@Inject
internal constructor(
    context: Context,
    @Named("main_activity") mainActivityClass: Class<out Activity>,
    @StringRes @Named("app_name") appNameRes: Int,
    @param:Named("service") private val serviceClass: Class<out Service>,
) :
    BaseDispatcher<ServerNotificationData>(
        context = context,
        mainActivityClass = mainActivityClass,
        appNameRes = appNameRes,
    ) {

  @CheckResult
  private fun getServiceStopPendingIntent(): PendingIntent {
    val appContext = context.applicationContext
    val serviceIntent =
        Intent(appContext, serviceClass).apply {
          putExtra(
              NotificationLauncher.INTENT_EXTRA_SERVICE_ACTION,
              NotificationLauncher.Actions.STOP.name,
          )
        }
    return PendingIntent.getService(
        appContext,
        REQUEST_CODE_SERVICE,
        serviceIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  override fun onGuaranteeNotificationChannelExists(channelInfo: NotifyChannelInfo) {
    val notificationGroup =
        NotificationChannelGroup("${channelInfo.id} Group", "${channelInfo.title} Group")
    val notificationChannel =
        NotificationChannel(channelInfo.id, channelInfo.title, NotificationManager.IMPORTANCE_LOW)
            .apply {
              group = notificationGroup.id
              lockscreenVisibility = Notification.VISIBILITY_PUBLIC
              description = channelInfo.description
              enableLights(false)
              enableVibration(true)
            }

    channelCreator.apply {
      // Delete the group if it already exists with a bad group ID
      // Group ID and channel ID cannot match
      if (notificationChannelGroups.firstOrNull { it.id == channelInfo.id } != null) {
        deleteNotificationChannelGroup(channelInfo.id)
      }
      createNotificationChannelGroup(notificationGroup)
      createNotificationChannel(notificationChannel)
    }
  }

  override fun onCreateNotificationBuilder(
      appName: String,
      notification: ServerNotificationData,
      builder: NotificationCompat.Builder,
  ): NotificationCompat.Builder =
      builder
          .setOngoing(true)
          .setSilent(true)
          .setSmallIcon(R.drawable.ic_wifi_tethering_24)
          .setPriority(NotificationCompat.PRIORITY_LOW)
          .setCategory(NotificationCompat.CATEGORY_SERVICE)
          .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
          .addAction(
              R.drawable.ic_wifi_tethering_off_24,
              "Stop $appName Hotspot",
              getServiceStopPendingIntent(),
          )
          .setContentTitle("$appName Running")
          .setContentText(resolveContentText(appName, notification))

  @CheckResult
  private fun resolveContentText(appName: String, notification: ServerNotificationData): String {
    val openAppMessage = "Please open $appName and restart the Hotspot."
    return when (notification.broadcastStatus) {
      is RunningStatus.Error -> "Hotspot Broadcast Error. $openAppMessage"
      is RunningStatus.NotRunning -> "Hotspot Broadcast preparing..."
      is RunningStatus.Starting -> "Hotspot Broadcast starting..."
      is RunningStatus.Stopping -> "Hotspot Broadcast stopping..."
      is RunningStatus.Running -> {
        // WiFi direct group must be available
        when (notification.group) {
          is BroadcastNetworkStatus.GroupInfo.Unchanged -> "Hotspot preparing..."
          is BroadcastNetworkStatus.GroupInfo.Empty -> "Hotspot Group Unavailable. $openAppMessage"
          is BroadcastNetworkStatus.GroupInfo.Error -> "Hotspot Group Error. $openAppMessage"
          is BroadcastNetworkStatus.GroupInfo.Connected -> {
            // Wi-Fi direction connection info must be available
            when (notification.connection) {
              is BroadcastNetworkStatus.ConnectionInfo.Unchanged -> "Hotspot preparing..."
              is BroadcastNetworkStatus.ConnectionInfo.Empty ->
                  "Hotspot Connection Unavailable. $openAppMessage"
              is BroadcastNetworkStatus.ConnectionInfo.Error ->
                  "Hotspot Connection Error. $openAppMessage"
              is BroadcastNetworkStatus.ConnectionInfo.Connected -> {
                "Hotspot Ready. ${notification.clientCount} Clients. ${notification.blockCount} Blocked."
              }
            }
          }
        }
      }
    }
  }

  override fun canShow(notification: NotifyData): Boolean {
    return notification is ServerNotificationData
  }

  companion object {

    private const val REQUEST_CODE_SERVICE = 133769
  }
}
