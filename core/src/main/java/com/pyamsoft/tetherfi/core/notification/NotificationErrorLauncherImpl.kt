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

package com.pyamsoft.tetherfi.core.notification

import com.pyamsoft.pydroid.notify.Notifier
import com.pyamsoft.pydroid.notify.NotifyChannelInfo
import com.pyamsoft.pydroid.notify.toNotifyId
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

@Singleton
internal class NotificationErrorLauncherImpl
@Inject
internal constructor(
    private val notifier: Notifier,
    private val dispatchers: AppDispatchers,
) : NotificationErrorLauncher {

  override suspend fun hideError() =
      withContext(context = dispatchers.default) {
        notifier.cancel(ERROR_ID)
        return@withContext
      }

  override suspend fun showError(throwable: Throwable) =
      withContext(context = dispatchers.default) {
        notifier
            .show(
                id = ERROR_ID,
                channelInfo = ERROR_CHANNEL_INFO,
                notification = ErrorNotificationData(throwable),
            )
            .also { Timber.d { "Show error notification: $it: $throwable" } }
        return@withContext
      }

  companion object {

    private val ERROR_ID = 133710.toNotifyId()
    private val ERROR_CHANNEL_INFO =
        NotifyChannelInfo(
            id = "channel_tetherfi_error_1",
            title = "TetherFuseNet Errors",
            description = "TetherFuseNet Errors",
        )
  }
}
