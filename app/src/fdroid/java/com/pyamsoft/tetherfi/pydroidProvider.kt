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

package com.pyamsoft.tetherfi

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.billing.BillingModule
import com.pyamsoft.pydroid.billing.noop.NoopBillingModule
import com.pyamsoft.pydroid.bootstrap.noop.rating.NoopRatingModule
import com.pyamsoft.pydroid.bootstrap.noop.version.NoopVersionModule
import com.pyamsoft.pydroid.bootstrap.rating.RatingModule
import com.pyamsoft.pydroid.bootstrap.version.VersionModule

@CheckResult
internal fun provideBillingModule(params: BillingModule.Parameters): BillingModule {
  return NoopBillingModule(params)
}

@CheckResult
internal fun provideRatingModule(params: RatingModule.Parameters): RatingModule {
  return NoopRatingModule(params)
}

@CheckResult
internal fun provideVersionModule(params: VersionModule.Parameters): VersionModule {
  return NoopVersionModule(params)
}
