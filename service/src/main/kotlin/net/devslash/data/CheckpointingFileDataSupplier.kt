package net.devslash.data

import kotlinx.coroutines.channels.Channel
import net.devslash.*
import java.io.File
import java.io.FileNotFoundException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A checkpointing file data supplier works similarly to the [FileDataSupplier] in use
 * under good conditions. When failing through, the checkpointing supplier will be able
 * to capture those requests that did or didn't pass a predicate, and subsequently restart
 * from a known state.
 *
 * A caveat to this, is that depending on the side-effects of the server being called. It
 * is possible that the request (or at least the data mutation effects) have completed
 * from the server.
 *
 * This means, that this should *only* be used in the event that the server requests are
 * idempotent. Or retry is safe.
 *
 * There is a startup cost to using this data supplier as well, as it has to take an
 * exclusive copy of the request data.
 *
 * Checkpoint data suppliers only create List<String> request data. This ensures serialization
 * isn't something we have to worry about.. Maybe another day.
 *
 * A checkpointing supplier is also not expected to work
 */
@DSLLockedValue
class CheckpointingFileDataSupplier(
  fileName: String, //
  checkpointName: String, //
  private val split: String = " ", //
  private val checkpointPredicate: CheckpointPredicate = defaultCheckpointPredicate
) :
  RequestDataSupplier<List<String>>,
  FullDataAfterHook,
  AutoCloseable,
  OnErrorWithState,
  AcceptCallContext<List<String>> {

  class CheckpointException(message: String) : RuntimeException(message)

  private var lines: List<String>
  private val inflightRequests = ConcurrentHashMap(mutableMapOf<UUID, Int>())
  private val failedRequests = Collections.synchronizedList<String>(mutableListOf())
  private val line = AtomicInteger(0)
  private val checkpointFile: File

  init {
    val sourceFile = File(fileName)
    if (!sourceFile.exists()) {
      throw FileNotFoundException(fileName)
    }
    checkpointFile = File(checkpointName)
    if (!checkpointFile.createNewFile()) {
      throw CheckpointException(
        "There is an existing checkpoint file at $checkpointFile. " +
          "An existing checkpoint file may mean that an older call has failed. Resolution " +
          "should be to either use the checkpoint file to restart the call. Or delete the" +
          " checkpoint file."
      )
    }
    lines = sourceFile.readLines()
  }

  override fun inject(): CallBuilder<List<String>>.() -> Unit = {
    data = this@CheckpointingFileDataSupplier
    onError = this@CheckpointingFileDataSupplier
    after {
      +this@CheckpointingFileDataSupplier
    }
  }

  override suspend fun getDataForRequest(): RequestData? {
    val currentLine = line.getAndIncrement()
    if (currentLine >= lines.size) {
      return null
    }

    val data = ListRequestData(lines[currentLine].split(split))
    inflightRequests[data.id] = currentLine
    return data
  }

  override fun accept(req: HttpRequest, resp: HttpResponse, data: RequestData) {
    val succeeded = checkpointPredicate.invoke(AfterCtx(req, resp, data))
    val currentLine = inflightRequests.getValue(data.id)
    inflightRequests.remove(data.id)

    if (!succeeded) {
      failedRequests.add(lines[currentLine])
    }
  }

  override suspend fun accept(
    channel: Channel<Envelope<Pair<HttpRequest, RequestData>>>,
    envelope: Envelope<Pair<HttpRequest, RequestData>>,
    e: Exception
  ) {
    val id = envelope.get().second.id
    val line = inflightRequests.getValue(id)
    inflightRequests.remove(id)
    failedRequests.add(lines[line])
  }

  override fun close() {
    if (inflightRequests.isEmpty() && line.get() == lines.size) {
      // We succeeded. Kill the checkpoint file
      checkpointFile.delete()
    } else {
      // Here we've either failed while there are some outstanding requests, or we've
      // had prior ones fail the predicate and we're wanting to persist the ones to retry
      // 1. All the requests we're yet to do
      // 2. All the requests that are underway
      // 3. All the requests that failed the predicate

      val left = lines.subList(line.get(), lines.size)
      val underway = inflightRequests.map {
        lines[it.value]
      }
      val allToRetry = failedRequests + underway + left
      val printWriter = checkpointFile.printWriter()
      printWriter.use { writer ->
        allToRetry.forEach {
          writer.println(it)
        }
      }
    }
  }
}

typealias CheckpointPredicate = AfterCtx.() -> Boolean

val defaultCheckpointPredicate: CheckpointPredicate = { resp.statusCode == 200 }
