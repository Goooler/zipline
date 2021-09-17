/*
 * Copyright (C) 2021 Square, Inc.
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
package app.cash.zipline.internal.bridge

import app.cash.zipline.Handle
import app.cash.zipline.InboundHandle
import app.cash.zipline.OutboundHandle
import kotlin.js.JsName
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource

@PublishedApi
internal interface CallChannel {
  /**
   * Internal function used to bridge method calls from Java or Android to JavaScript.
   *
   * The structure of [encodedArguments] is the following:
   *
   *  * 1 int (4 bytes): the number of parameters
   *  * For each parameter:
   *    * 1 int (4 bytes): the number of bytes in the parameter value. If the parameter value is
   *      null this is [BYTE_COUNT_NULL] and no bytes follow.
   *    * the bytes of the parameter value, encoded using [kotlinx.serialization.json.Json]
   *
   * The structure of the result is the following:
   *
   *  * 1 byte: the result type. Either [RESULT_TYPE_NORMAL] or [RESULT_TYPE_NORMAL]
   *  * 1 int (4 bytes): the number of bytes in the result value. If the result is null this is
   *    [BYTE_COUNT_NULL] and no bytes follow.
   *  * the bytes of the result value, encoded using [kotlinx.serialization.json.Json]
   */
  @JsName("invoke")
  fun invoke(instanceName: String, funName: String, encodedArguments: ByteArray): ByteArray

  /** Like [invoke], but the respose is delivered to the [SuspendCallback] named [callbackName]. */
  @JsName("invokeSuspending")
  fun invokeSuspending(
    instanceName: String,
    funName: String,
    encodedArguments: ByteArray,
    callbackName: String
  )
}

internal const val BYTE_COUNT_NULL = -1
internal const val RESULT_TYPE_NORMAL = 0 as Byte
internal const val RESULT_TYPE_EXCEPTION = 1 as Byte

internal fun <T> BufferedSink.writeJsonUtf8(serializer: SerializationStrategy<T>, value: T) {
  val json = Json.encodeToString(serializer, value)
  writeUtf8(json)
}

internal fun <T> BufferedSource.readJsonUtf8(serializer: DeserializationStrategy<T>): T {
  val json = readUtf8()
  return Json.decodeFromString(serializer, json)
}

internal object ThrowableSerializer : KSerializer<Throwable> {
  override val descriptor: SerialDescriptor = ThrowableSurrogate.serializer().descriptor

  override fun serialize(encoder: Encoder, value: Throwable) {
    val surrogate = ThrowableSurrogate(value.toString())
    encoder.encodeSerializableValue(ThrowableSurrogate.serializer(), surrogate)
  }

  override fun deserialize(decoder: Decoder): Throwable {
    val surrogate = decoder.decodeSerializableValue(ThrowableSurrogate.serializer())
    return Exception(surrogate.message)
  }
}

@Serializable
private class ThrowableSurrogate(
  val message: String
)

/**
 * This is a special serializer because it's scoped to an endpoint. It is not a general-purpose
 * serializer and only works in Zipline.
 *
 * To send a handle to an inbound service, we register it with the endpoint and transmit the
 * registered identifier.
 *
 * To receive a handle, we record the received identifier and return it when making calls against
 * the referenced service.
 */
internal class HandleSerializer<T : Any>(
  val endpoint: Endpoint
) : KSerializer<Handle<T>> {
  override val descriptor = PrimitiveSerialDescriptor("Handle", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Handle<T>) {
    val name = endpoint.generateName()
    if (value is InboundHandle<T>) {
      value.connect(endpoint, name)
      encoder.encodeString(name)
    } else {
      error("serializing an outbound handle is not implemented")
    }
  }

  override fun deserialize(decoder: Decoder): Handle<T> {
    val name = decoder.decodeString()
    val handle = OutboundHandle<T>()
    handle.connect(endpoint, name)
    return handle
  }
}
