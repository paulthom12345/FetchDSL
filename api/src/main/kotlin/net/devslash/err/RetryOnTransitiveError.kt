package net.devslash.err

import kotlinx.coroutines.channels.Channel
import net.devslash.ChannelReceiving
import net.devslash.Envelope
import net.devslash.HttpRequest
import net.devslash.RequestData
import java.net.SocketTimeoutException

class RetryOnTransitiveError<T> : ChannelReceiving<Pair<HttpRequest, RequestData<T>>> {
  override suspend fun accept(channel: Channel<Envelope<Pair<HttpRequest, RequestData<T>>>>,
                              envelope: Envelope<Pair<HttpRequest, RequestData<T>>>,
                              e: Exception) {
    if (!envelope.shouldProceed()) {
      // fail after a few failures
      throw e
    }
    when (e) {
      is SocketTimeoutException -> {
        envelope.fail()
        channel.send(envelope)
      }
      else                      -> throw e
    }
  }
}
