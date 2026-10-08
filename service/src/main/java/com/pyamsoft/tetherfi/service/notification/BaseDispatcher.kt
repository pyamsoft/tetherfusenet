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
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.CheckResult
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.notify.NotifyChannelInfo
import com.pyamsoft.pydroid.notify.NotifyData
import com.pyamsoft.pydroid.notify.NotifyDispatcher
import com.pyamsoft.pydroid.notify.NotifyId

internal abstract class BaseDispatcher<T : NotifyData>
protected constructor(
    protected val context: Context,
    private val mainActivityClass: Class<out Activity>,
    private val appNameRes: Int,
) : NotifyDispatcher<T> {

  protected val channelCreator by lazy {
    context.applicationContext.getSystemService<NotificationManager>().requireNotNull()
  }

  @CheckResult
  private fun getActivityPendingIntent(): PendingIntent {
    val appContext = context.applicationContext
    val activityIntent =
        Intent(appContext, mainActivityClass).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    return PendingIntent.getActivity(
        appContext,
        REQUEST_CODE_ACTIVITY,
        activityIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun guaranteeNotificationChannelExists(channelInfo: NotifyChannelInfo) {
    onGuaranteeNotificationChannelExists(channelInfo)
  }

  @CheckResult
  private fun createNotificationBuilder(
      appName: String,
      channelInfo: NotifyChannelInfo,
      notification: T,
  ): NotificationCompat.Builder {
    return NotificationCompat.Builder(context.applicationContext, channelInfo.id)
        .setShowWhen(false)
        .setAutoCancel(false)
        .setContentIntent(getActivityPendingIntent())
        .let { onCreateNotificationBuilder(appName, notification, it) }
  }

  final override fun build(
      id: NotifyId,
      channelInfo: NotifyChannelInfo,
      notification: T,
  ): Notification {
    val appName = context.getString(appNameRes)
    guaranteeNotificationChannelExists(channelInfo)
    return createNotificationBuilder(appName, channelInfo, notification)
        .setContentTitle(appName)
        .build()
  }

  protected abstract fun onGuaranteeNotificationChannelExists(channelInfo: NotifyChannelInfo)

  @CheckResult
  protected abstract fun onCreateNotificationBuilder(
      appName: String,
      notification: T,
      builder: NotificationCompat.Builder,
  ): NotificationCompat.Builder

  companion object {

    private const val REQUEST_CODE_ACTIVITY = 1337420
  }
}
