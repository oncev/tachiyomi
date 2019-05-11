/*
 * Copyright (C) 2018 The Tachiyomi Open Source Project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package tachiyomi.domain.sync.api

import io.reactivex.Single
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.json
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import tachiyomi.core.http.Http
import tachiyomi.core.http.asSingle
import tachiyomi.domain.sync.prefs.SyncPreferences
import javax.inject.Inject

class SyncAPI @Inject constructor(
  http: Http,
  private val store: SyncPreferences,
  private val device: SyncDevice
) {

  private val client = http.defaultClient
  private val jsonMediaType by lazy { MediaType.parse("application/json; charset=utf-8") }

  private val addressPref = store.address()
  private val tokenPref = store.token()

  val address get() = addressPref.get()
  val token get() = tokenPref.get()

  fun login(address: String, username: String, password: String): Single<LoginResult> {
    @Serializable
    data class Response(val secret: String)

    val credentials = Credentials.basic(username, password)

    val reqBody = json {
      "deviceId" to device.getId()
      "deviceName" to device.getName()
      "platform" to device.getPlatform()
    }

    val request = Request.Builder()
      .url("$address/api/v3/auth/tokens")
      .post(RequestBody.create(jsonMediaType, reqBody.toString()))
      .addHeader("Authorization", credentials)
      .build()

    return client.newCall(request).asSingle()
      .map { response ->
        response.use {
          if (response.code() == 200) {
            val body = response.body()?.string() ?: throw Exception("Failed to read body")
            val responseBody = Json.parse(Response.serializer(), body)
            LoginResult.Token(responseBody.secret)
          } else {
            LoginResult.InvalidCredentials
          }
        }
      }
      .onErrorReturn(LoginResult::NetworkError)
  }

}