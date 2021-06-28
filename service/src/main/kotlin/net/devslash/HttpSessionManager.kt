package net.devslash

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

typealias Contents = Pair<HttpRequest, RequestData>

class HttpSessionManager(private val engine: Driver, private val session: Session) : SessionManager {

  private val jobThreadPool = System.getProperty("HTTP_THREAD_POOL_SIZE")?.toInt()
    ?: Runtime.getRuntime().availableProcessors() * 2

  private val httpThreadPool = Executors.newFixedThreadPool(jobThreadPool)
  private val dispatcher = httpThreadPool.asCoroutineDispatcher()

  private var lastCall = 0L
  private val clock = Clock.systemUTC()

  fun run() {
    val jar = DefaultCookieJar()
    try {
      for (call in session.calls) {
        call(call, jar)?.let {
          throw it
        }
      }
    } finally {
      engine.close()
      httpThreadPool.shutdownNow()
      httpThreadPool.awaitTermination(100L, TimeUnit.MILLISECONDS)
    }
  }

  override fun <T> call(call: Call<T>): Exception? = call(call, DefaultCookieJar())

  override fun <T> call(call: Call<T>, jar: CookieJar): Exception? = runBlocking {
    val channel: Channel<Envelope<Contents>> = Channel(session.concurrency * 2)
    launch(Dispatchers.IO) { RequestProducer().produceHttp(this@HttpSessionManager, call, jar, channel) }

    val afterRequest: MutableList<AfterHook> = mutableListOf(jar)
    afterRequest.addAll(call.afterHooks)

    val delay = session.delay
    val hasDelay = delay != null && delay > 0

    if (hasDelay) {
      println(
        "Delay has been set to $delay ms. This means that after a call has been made, " + //
          "there will be a delay of at least $delay ms before the beginning of the next one.\n" + //
          "Due to a delay being set - the number of HTTP threads has been locked to 1. " + //
          "Effectively `session.concurrency = 1`"
      )
    }

    val limiter = AcquiringRateLimiter(session.rateOptions)
    val jobs = mutableListOf<Job>()
    val concurrency = if (hasDelay) 1 else session.concurrency
    val storedException = AtomicReference<Exception>(null)

    repeat(concurrency) {
      jobs += launch(dispatcher) {
        launchHttpProcessor(
          call,
          limiter,
          afterRequest,
          channel,
          storedException
        )
      }
    }

    jobs.joinAll()
    storedException.get()
  }

  private suspend fun <T> launchHttpProcessor(
    call: Call<T>,
    rateLimiter: AcquiringRateLimiter,
    afterRequest: List<AfterHook>,
    channel: Channel<Envelope<Pair<HttpRequest, RequestData>>>,
    storedException: AtomicReference<Exception>
  ) {
    for (next in channel) {
      if (storedException.get() != null) {
        // Break out in the event we've detected an exception somewhere
        return
      }
      try {
        if (next.shouldProceed()) {
          val contents = next.get()
          rateLimiter.acquire()

          when (val resp = makeRequest(contents.first)) {
            is Failure -> handleFailure(call, channel, next, resp)
            is Success -> handleSuccess(resp, afterRequest, contents)
          }
        }
      } catch (e: Exception) {
        storedException.set(e)
        return
      }
    }
  }

  private fun handleSuccess(
    resp: Success<HttpResponse>,
    afterRequest: List<AfterHook>,
    contents: Pair<HttpRequest, RequestData>
  ) {
    val mappedResponse = resp.value
    afterRequest.forEach {
      when (it) {
        is SimpleAfterHook -> it.accept(mappedResponse.copy())
        is BodyMutatingAfterHook -> it.accept(mappedResponse)
        is FullDataAfterHook -> it.accept(contents.first, mappedResponse, contents.second)
      }
    }
  }

  private suspend fun <T> handleFailure(
    call: Call<T>,
    channel: Channel<Envelope<Pair<HttpRequest, RequestData>>>,
    next: Envelope<Pair<HttpRequest, RequestData>>,
    exception: Failure<java.lang.Exception>
  ) {
    when (val onError = call.onError) {
      is OnErrorWithState -> onError.accept(channel, next, exception.err)
      else -> {
        throw exception.err
      }
    }
  }


  private suspend fun makeRequest(modelRequest: HttpRequest): HttpResult<HttpResponse, java.lang.Exception> {
    maybeDelay()
    val result = engine.call(modelRequest)
    lastCall = clock.millis()
    return result
  }

  private suspend fun maybeDelay() {
    session.delay?.let {
      require(it > 0)
      // Then between every call, we have to have waited at least that many ms
      val diff = clock.millis() - lastCall
      // we have to wait for the diff. Due to the fact that delays institute a single threaded
      // system, this is safe. Negative delay returns instantly
      delay(it - diff)
    }
  }
}


