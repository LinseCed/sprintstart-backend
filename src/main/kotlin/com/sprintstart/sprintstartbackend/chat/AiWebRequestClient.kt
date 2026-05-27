package com.sprintstart.sprintstartbackend.chat

import com.sprintstart.sprintstartbackend.WebRequestClient
import com.sprintstart.sprintstartbackend.chat.models.exceptions.AiResponseException
import com.sprintstart.sprintstartbackend.chat.models.responses.AiStreamMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class AiWebRequestClient : WebRequestClient() {
	companion object {

        /**
         * Opens up a new SSE stream with a POST request.
         *
         * This function calls the given `uri` and expects the receiver to open up a new SSE stream.
         * In order to initiate a new SSE stream, we hit the receiver with a POST request, containing the given
         * `payload`.
         *
         * @param uri The uri to call.
         * @param body The payload to transmit initially.
         * @return [Flow<String>] A flow of strings.
         * @see Flow
         */
        inline fun <reified PayloadType> streamPost(
            uri: URI,
            body: PayloadType
        ): Flow<String> {
            val jsonPayload = jsonParser.encodeToString(body)
            return streamRequestToAi(uri, "POST", jsonPayload)
        }

        /**
         * Opens up a new SSE stream.
         *
         * This function hits the receiver with a request of the given type, containing the given payload,
         * and expects the caller to start an SSE stream of strings.
         *
         * @param uri The uri to call.
         * @param method The http method to use.
         * @param payload The payload to transmit initially.
         * @return [Flow<String>] The flow of data.
         * @see [Http method docs](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Methods)
         * @see Flow
         */
        @PublishedApi
        internal fun streamRequestToAi(
            uri: URI,
            method: String,
            payload: String
        ): Flow<String> = flow {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream") // Tell AI we want a stream
                .method(method.uppercase(), HttpRequest.BodyPublishers.ofString(payload))
                .build()

            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).await()

            response.body().use { stream ->
                val iterator = stream.iterator()

                while (iterator.hasNext()) {
                    val line = iterator.next()

                    if (line.startsWith("data:")) {
                        val rawJson = line.removePrefix("data:").trim()

                        if (rawJson.isEmpty()) continue

                        try {
                            val message = jsonParser.decodeFromString<AiStreamMessage>(rawJson)

                            when (message.type) {
                                "done" -> break
                                "token" -> emit(rawJson)
                                "error" -> throw AiResponseException("Ai responded with error: ${message.content}")
                            }
                        } catch (e: Exception) {
                            System.err.println("Error parsing chunk incoming from ai: ${e.message}")
                        }
                    }
                }
            }
        }
    }
}
