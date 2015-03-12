package colossus
package service

import core._
import controller._

import akka.event.Logging
import metrics._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import Codec._

/**
 * Configuration class for a Service Server Connection Handler
 * @param name the prefix to use for all metrics generated by the service
 * @param requestTimeout how long to wait until we timeout the request
 * @param requestBufferSize how many concurrent requests a single connection can be processing
 * @param logErrors if true, any uncaught exceptions or service-level errors will be logged
 */
case class ServiceConfig(
  name: MetricAddress,
  requestTimeout: Duration,
  requestBufferSize: Int = 100, //how many concurrent requests can be processing at once
  logErrors: Boolean = true
)

class RequestBufferFullException extends Exception("Request Buffer full")
class DroppedReply extends Error("Dropped Reply")


/**
 * The ServiceServer provides an interface and basic functionality to create a server that processes
 * requests and returns responses over a codec.
 *
 * A Codec is simply the format in which the data is represented.  Http, Redis protocol, Memcached protocl are all
 * examples(and natively supported).  It is entirely possible to use an additional Codec by creating a Codec to parse
 * the desired protocol.
 *
 * Requests can be processed synchronously or
 * asynchronously.  The server will ensure that all responses are written back
 * in the order that they are received.
 *
 */
abstract class ServiceServer[I,O]
  (codec: ServerCodec[I,O], config: ServiceConfig, worker: WorkerRef)
  (implicit ex: ExecutionContext, tagDecorator: TagDecorator[I,O] = TagDecorator.default[I,O]) 
extends Controller[I,O](codec, ControllerConfig(50, config.requestTimeout)) {
  import ServiceServer._
  import WorkerCommand._
  import config._

  implicit val callbackExecutor: CallbackExecutor = CallbackExecutor(worker.worker)
  val log = Logging(worker.system.actorSystem, name.toString())

  val requests  = worker.metrics.getOrAdd(Rate(name / "requests", List(1.second, 1.minute)))
  val latency   = worker.metrics.getOrAdd(Histogram(name / "latency", periods = List(1.second, 1.minute), sampleRate = 0.25))
  val errors    = worker.metrics.getOrAdd(Rate(name / "errors", List(1.second, 1.minute)))
  val requestsPerConnection = worker.metrics.getOrAdd(Histogram(name / "requests_per_connection", periods = List(1.minute), sampleRate = 0.5, percentiles = List(0.5, 0.75, 0.99)))
  val concurrentRequests = worker.metrics.getOrAdd(Counter(name / "concurrent_requests"))

  //set to true when graceful disconnect has been triggered
  private var disconnecting = false

  def addError(err: Throwable, extraTags: TagMap = TagMap.Empty) {
    val tags = extraTags + ("type" -> err.getClass.getName.replaceAll("[^\\w]", ""))
    errors.hit(tags = tags)
  }

  case class SyncPromise(request: I) {
    val creationTime = System.currentTimeMillis

    def isTimedOut(time: Long) = !isComplete && (time - creationTime) > requestTimeout.toMillis

    private var _response: Option[O] = None
    def isComplete = _response.isDefined
    def response = _response.getOrElse(throw new Exception("Attempt to use incomplete response"))

    def complete(response: O) {
      _response = Some(response)
      checkBuffer()
    }

  }

  private val requestBuffer = collection.mutable.Queue[SyncPromise]()
  private var numRequests = 0

  override def idleCheck(period: Duration) {
    super.idleCheck(period)

    val time = System.currentTimeMillis
    while (requestBuffer.size > 0 && requestBuffer.head.isTimedOut(time)) {
      //notice - completing the response will call checkBuffer which will write the error immediately
      requestBuffer.head.complete(handleFailure(requestBuffer.head.request, new TimeoutError))

    }
  }
    
  /**
   * This is the only function that actually writes to the channel.  It will
   * write any queued responses until it hits an incomplete promise (or the
   * write buffer fills up)
   */
  private def checkBuffer() {
    while (isConnected && requestBuffer.size > 0 && requestBuffer.head.isComplete) {
      val done = requestBuffer.dequeue()
      val comp = done.response
      val tags = tagDecorator.tagsFor(done.request, comp)
      requests.hit(tags = tags)
      latency.add(tags = tags, value = (System.currentTimeMillis - done.creationTime).toInt)
      concurrentRequests.decrement()
      push(comp, done.creationTime) {
        case OutputResult.Success => {}
        case _ => println("dropped reply")
      }
      //todo: deal with output-controller full
    }
    checkGracefulDisconnect()
  }

  override def connectionClosed(cause : DisconnectCause) {
    super.connectionClosed(cause)
    requestsPerConnection.add(numRequests)
    concurrentRequests.delta(- requestBuffer.size)
  }

  override def connectionLost(cause : DisconnectError) {
    connectionClosed(cause)
  }

  protected def processMessage(request: I) {
    numRequests += 1
    val promise = new SyncPromise(request)
    requestBuffer.enqueue(promise)
    concurrentRequests.increment()
    /**
     * Notice, if the request buffer is full we're still adding to it, but by skipping
     * processing of requests we can hope to alleviate overloading
     */
    val response: Callback[O] = if (requestBuffer.size < requestBufferSize) {
      try {
        processRequest(request) 
      } catch {
        case t: Throwable => {
          Callback.successful(handleFailure(request, t))
        }
      }
    } else {
      Callback.successful(handleFailure(request, new RequestBufferFullException))
    }
    response.execute{
      case Success(res) => promise.complete(res)
      case Failure(err) => promise.complete(handleFailure(promise.request, err))
    }
  }

  private def handleFailure(request: I, reason: Throwable): O = {
    addError(reason)
    if (logErrors) {
      log.error(s"Error processing request: $request: $reason")
    }
    processFailure(request, reason)
  }

  def schedule(in: FiniteDuration, message: Any) {
    id.foreach{i => 
      worker ! Schedule(in, Message(i, message))
    }
  }

  /**
   * Terminate the connection, but allow any outstanding requests to complete
   * (or timeout) before disconnecting
   */
  override def gracefulDisconnect() {
    pauseReads()
    disconnecting = true

  }

  private def checkGracefulDisconnect() {
    if (disconnecting && requestBuffer.size == 0) {
      super.gracefulDisconnect()
    }
  }

  // ABSTRACT MEMBERS

  protected def processRequest(request: I): Callback[O]

  //DO NOT CALL THIS METHOD INTERNALLY, use handleFailure!!
  protected def processFailure(request: I, reason: Throwable): O

}

object ServiceServer {
  class TimeoutError extends Error("Request Timed out")

}
