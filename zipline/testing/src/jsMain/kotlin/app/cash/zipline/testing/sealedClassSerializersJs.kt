/*
 * Copyright (C) 2022 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.testing

import app.cash.zipline.FlowReference
import app.cash.zipline.Zipline
import app.cash.zipline.asFlowReference
import app.cash.zipline.testing.SealedMessage.BlueMessage
import app.cash.zipline.testing.SealedMessage.RedMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class JsSealedClassMessageService : SealedClassMessageService {
  override fun colorSwap(request: SealedMessage): SealedMessage {
    return when (request) {
      is BlueMessage -> RedMessage(request.message)
      is RedMessage -> BlueMessage(request.message)
    }
  }

  override fun colorSwapFlow(request: FlowReference<SealedMessage>): FlowReference<SealedMessage> {
    val requestFlow = request.get()
    val flow: Flow<SealedMessage> = flow {
      requestFlow.collect { message ->
        emit(colorSwap(message))
      }
    }
    return flow.asFlowReference()
  }
}

private val zipline by lazy { Zipline.get() }

@JsExport
fun prepareSealedClassMessageService() {
  zipline.set<SealedClassMessageService>(
    "sealedClassMessageService",
    JsSealedClassMessageService()
  )
}
