package net.devslash

import net.devslash.err.RetryOnTransitiveError
import java.time.Duration

@DslMarker
annotation class FetchDSL

enum class HttpMethod {
  GET, POST, DELETE, PUT, HEAD, OPTIONS, PATCH
}

class UnaryAddBuilder<T> {
  private var hooks = mutableListOf<T>()

  operator fun T.unaryPlus() {
    hooks = (hooks + this).toMutableList()
  }

  fun build(): MutableList<T> {
    return hooks
  }
}

data class RateLimitOptions(val enabled: Boolean, val count: Int, val duration: Duration)

@FetchDSL
open class CallBuilder<T>(private val url: String) {

  private var cookieJar: String? = null
  var data: RequestDataSupplier<T>? = null
  var body: HttpBody<T>? = null
  var type: HttpMethod = HttpMethod.GET
  var headers: Map<String, List<Any>>? = null
  var onError: OnError? = RetryOnTransitiveError<T>()

  private var preHooksList = mutableListOf<BeforeHook>()
  private var postHooksList = mutableListOf<AfterHook>()

  fun before(block: UnaryAddBuilder<BeforeHook>.() -> Unit) {
    preHooksList.addAll(UnaryAddBuilder<BeforeHook>().apply(block).build())
  }

  fun after(block: UnaryAddBuilder<AfterHook>.() -> Unit) {
    postHooksList.addAll(UnaryAddBuilder<AfterHook>().apply(block).build())
  }

  fun body(block: BodyBuilder<T>.() -> Unit) {
    body = BodyBuilder<T>().apply(block).build()
  }

  private fun mapHeaders(m: Map<String, List<Any>>?): Map<String, List<Value>>? {
    return m?.let { map ->
      map.map { entry ->
        entry.key to entry.value.map { value ->
          when (value) {
            is String -> StrValue(value)
            is Value -> value
            else -> throw RuntimeException()
          }
        }
      }.toMap()
    }
  }

  fun build(): Call<T> {
    val localHeaders = headers
    if (localHeaders == null || !localHeaders.contains("User-Agent")) {
      val set = mutableMapOf<String, List<Any>>()
      if (localHeaders != null) {
        set.putAll(localHeaders)
      }
      set["User-Agent"] = listOf("FetchDSL (Apache-HttpAsyncClient + Kotlin, version not set)")
      headers = set
    }
    return Call(
      url, mapHeaders(headers), cookieJar, type, data, body,
      onError, preHooksList, postHooksList
    )
  }
}

@FetchDSL
class BodyBuilder<T> {
  var value: String? = null
  var formParams: Map<String, List<String>>? = null
  var jsonObject: Any? = null
  var lazyJsonObject: ((RequestData<T>) -> Any)? = null

  fun build(): HttpBody<T> = HttpBody(value, formParams, jsonObject, lazyJsonObject)
}

@FetchDSL
class MultiCallBuilder {
  private var calls = mutableListOf<Call<*>>()

  fun call(url: String, block: CallBuilder<*>.() -> Unit = {}) {
    calls.add(CallBuilder<Any>(url).apply(block).build())
  }

  fun calls() = calls
}

@FetchDSL
class SessionBuilder {
  private var calls = mutableListOf<Call<*>>()
  private val chained = mutableListOf<List<Call<*>>>()

  var concurrency = 20
  var delay: Long? = null
  var rateOptions: RateLimitOptions = RateLimitOptions(false, 0, Duration.ZERO)

  fun rateLimit(count: Int, duration: Duration) {
    require(!(duration.isNegative || duration.isZero)) { "Invalid duration, must be more than zero" }
    require(count > 0) { "Count must be positive" }
    rateOptions = RateLimitOptions(true, count, duration)
  }

  @JvmName("nonStringCall")
  fun <T> call(url: String, block: CallBuilder<T>.() -> Unit = {}) {
    calls.add(CallBuilder<T>(url).apply(block).build())
  }

  fun call(url: String, block: CallBuilder<List<String>>.() -> Unit = {}) {
    calls.add(CallBuilder<List<String>>(url).apply(block).build())
  }

  fun build(): Session = Session(calls, concurrency, delay, rateOptions)
}

