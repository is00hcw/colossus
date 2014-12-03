package colossus
package service

import java.net.InetSocketAddress

import akka.actor._
import akka.event.Logging
import colossus.core._
import colossus.metrics._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}


/**
 * Configuration used to specify a Client's parameters
 * @param address The address with which to connect
 * @param requestTimeout The request timeout value
 * @param name The MetricAddress associated with this client
 * @param pendingBufferSize Size of the pending buffer
 * @param sentBufferSize Size of the sent buffer
 * @param failFast  When a failure is detected, immediately fail all pending requests.
 * @param connectionAttempts Polling configuration to govern retry behavior for both initial connect attempts
 *                           and for connection lost events.
 *
 */
case class ClientConfig(
  address: InetSocketAddress, 
  requestTimeout: Duration, 
  name: MetricAddress,
  pendingBufferSize: Int = 100,
  sentBufferSize: Int = 20,
  failFast: Boolean = false,
  connectionAttempts : PollingDuration = PollingDuration(250.milliseconds, None)
)

class ServiceClientException(message: String) extends Exception(message)

/**
 * Thrown when a request is lost in transit
 * @param message Cause
 */
class ConnectionLostException(message: String) extends ServiceClientException(message)

/**
 * Throw when a request is attempted while not connected
 * @param message Cause
 */
class NotConnectedException(message: String) extends ServiceClientException(message)

/**
 * Thrown when the pending buffer is full
 * @param message Cause
 */
class ClientOverloadedException(message: String) extends ServiceClientException(message)

/**
 * Returned when a request has been pending for too long
 */
class RequestTimeoutException extends ServiceClientException("Request Timed out")

/**
 * Thrown when there's some kind of data error
 * @param message Cause
 */
class DataException(message: String) extends ServiceClientException(message)

/**
 * The ClientLike trait is intended to be an interface for anything that you
 * can connect to and send messages.  It may be a single connection (as it is
 * with ServiceClient) or may be a pool of connections
 */
trait ClientLike[I,O,M[_]] {
  def send(request: I): M[O]
}

trait LocalClient[I,O] extends ClientLike[I,O,Callback]{
}

trait SharedClient[I,O] extends ClientLike[I,O,Future] {
}

trait ServiceClientLike[I,O] extends LocalClient[I,O] {
  def connect()
  def gracefulDisconnect()
  def config: ClientConfig
  def shared: SharedClient[I,O]
}


object ServiceClient {

  def apply[I,O](codec: Codec[I,O], config: ClientConfig, worker: WorkerRef): ServiceClient[I,O] = new ServiceClient(codec, config, worker)

}

/**
 * This is thrown when a Client is manually disconnected, and subsequent attempt is made to reconnect.
 * To simplify the internal workings of Clients, instead of trying to reset its internal state, it throws.  Create
 * a new Client to reestablish a connection.
 * @param msg error message
 */
class StaleClientException(msg : String) extends Exception(msg)


/**
 * A ServiceClient is a non-blocking, synchronous interface that handles
 * sending atomic commands on a connection and parsing their replies
 *
 * Notice - a service client will not connect on it's own, the connect() method
 * must be called.  This is to avoid multiple connection requests when a
 * service client handler is created in the closure of a
 * ConnectionCommand.Connect message. 
 *
 * Therefore, if you are directly creating a client inside a server delegator,
 * you should call connect().  If you are creating the handler as part of a
 * Connect message to a worker or IOSystem, you should not call connect()
 */
class ServiceClient[I,O](
  val codec: Codec[I,O], 
  val config: ClientConfig,
  val worker: WorkerRef
) extends ClientConnectionHandler with ServiceClientLike[I,O] with InputController[O,I] with OutputController[O,I]{
  import colossus.IOCommand._
  import colossus.core.WorkerCommand._
  import config._

  type ResponseHandler = Try[O] => Unit

  private val periods = List(1.second, 1.minute)
  private val requests  = worker.metrics.getOrAdd(Rate(name / "requests", periods))
  private val errors    = worker.metrics.getOrAdd(Rate(name / "errors", periods))
  private val droppedRequests    = worker.metrics.getOrAdd(Rate(name / "dropped_requests", periods))
  private val connectionFailures    = worker.metrics.getOrAdd(Rate(name / "connection_failures", periods))
  private val disconnects  = worker.metrics.getOrAdd(Rate(name / "disconnects", periods))

  private val latency = worker.metrics.getOrAdd(Histogram(name / "latency", periods = List(60.seconds), sampleRate = 0.10, percentiles = List(0.75,0.99)))

  case class SourcedRequest(message: I, handler: ResponseHandler) {
    val start: Long = System.currentTimeMillis
  }


  private var manuallyDisconnected = false
  private var connectionAttempts = 0
  private val sentBuffer    = mutable.Queue[SourcedRequest]()
  protected var writer: Option[WriteEndpoint] = None
  protected val maxQueueSize = config.pendingBufferSize
  private var disconnecting: Boolean = false //set to true during graceful disconnect
  val log = Logging(worker.system.actorSystem, s"client:$address")

  //TODO way too application specific
  private val hpTags: TagMap = Map("client_host" -> address.getHostName, "client_port" -> address.getPort.toString)
  private val hTags: TagMap = Map("client_host" -> address.getHostName)

  case class AsyncRequest(request: I, handler: ResponseHandler)

  /**
   * Checks if the underlying WriteEndpoint's status is ConnectionStatus.Connected
   * @return
   */
  def isConnected: Boolean = writer.fold(false){_.status == ConnectionStatus.Connected}

  /**
   *
   * @return Underlying WriteEndpoint's ConnectionStatus, defaults to Connecting if there is no WriteEndpoint
   */
  def connectionStatus: ConnectionStatus = writer.map{_.status}.getOrElse(
    if (canReconnect) ConnectionStatus.Connecting else ConnectionStatus.NotConnected
  )

  def connect() {
    if(!manuallyDisconnected){
      worker ! Connect(address, _ => this)
    }else{
      throw new StaleClientException("This client has already been manually disconnected, create a new one.")
    }
  }

  //todo: this should now auto-connect on binding
  //override def onBind(){}

  /**
   * Allow any requests in transit to complete, but cancel all pending requests
   * and don't allow any new ones
   */
  def gracefulDisconnect() {
    log.info(s"Terminating connection to $address")
    //clearPendingBuffer(new NotConnectedException("Connection is closing"))
    //todo, we should maybe make the Cancelled OutputResult take a Throwable
    purgePending()
    disconnecting = true
    manuallyDisconnected = true
    checkGracefulDisconnect()
  }

  /**
   * Sent a request to the service, along with a handler for processing the response.
   */
  private def sendNow(request: I)(handler: ResponseHandler){
    val s = SourcedRequest(request, handler)
    attemptWrite(s)
  }

  /** Create a shared interface that is thread safe and returns Futures
   */
  def shared: SharedClient[I,O] = new SharedClient[I,O] {
    def send(request: I): Future[O] = {
      val promise = Promise[O]()
      val handler = {response: Try[O] => promise.complete(response);()}
      id.map{id => worker ! Message(id, (AsyncRequest(request, handler)))}.getOrElse(promise.failure(new NotConnectedException("Not Bound to worker")))
      promise.future
    }
  }

  /**
   * Create a callback for sending a request.  this allows you to do something like
   * service.sendCB("request"){response => "YAY"}.map{str => println(str)}.execute()
   */
  def send(request: I): Callback[O] = UnmappedCallback[O](sendNow(request))

  def processMessage(response: O) {
    val now = System.currentTimeMillis
    try {
      val source = sentBuffer.dequeue()
      latency.add(tags = hTags, value = (now - source.start).toInt) //notice only grouping by host for now
      source.handler(Success(response))
      requests.hit(tags = hpTags)
    } catch {
      case e: java.util.NoSuchElementException => {
        throw new DataException(s"No Request for response ${response.toString}!")
      }
    }
    checkGracefulDisconnect()
    if (paused) resumeWrites()
  }

  def receivedMessage(message: Any, sender: ActorRef) {
    message match {
      case AsyncRequest(request, handler) => sendNow(request)(handler)
      case other => throw new Exception(s"Received invalid message $other")
    }
  }

  def connected(endpoint: WriteEndpoint) {
    log.info(s"Connected to $address")
    codec.reset()    
    writer = Some(endpoint)
    connectionAttempts = 0
    readyForData()
  }

  private def purgeBuffers(pendingException : => Throwable) {
    writer = None
    sentBuffer.foreach{s => 
      errors.hit(tags = hpTags)
      s.handler(Failure(new ConnectionLostException("Connection closed while request was in transit")))
    }
    sentBuffer.clear()
    purgeOutgoing()
    if (failFast) {
      purgePending()
    }
  }

  override protected def connectionClosed(cause: DisconnectCause): Unit = {
    manuallyDisconnected = true
    purgeBuffers(new NotConnectedException("Connection closed"))
  }

  override protected def connectionLost(cause : DisconnectError) {
    purgeBuffers(new NotConnectedException("Connection lost"))
    log.warning(s"connection to ${address.toString} lost: $cause")
    disconnects.hit(tags = hpTags)
    attemptReconnect()
  }

  def connectionFailed() {

    log.error(s"failed to connect to ${address.toString}")
    connectionFailures.hit(tags = hpTags)
    attemptReconnect()
  }

  private def attemptReconnect() {
    connectionAttempts += 1
    if(!disconnecting) {
      if(canReconnect) {
        log.warning(s"attempting to reconnect to ${address.toString} after $connectionAttempts unsuccessful attempts.")
        worker ! Schedule(config.connectionAttempts.interval, Reconnect(address, this))
      }
      else {
        log.error(s"failed to connect to ${address.toString} after $connectionAttempts tries, giving up.")
      }
    }
  }

  private def canReconnect = config.connectionAttempts.isExpended(connectionAttempts)


  private def attemptWrite(s: SourcedRequest) {
    if (disconnecting) {
      //don't allow any new requests, appear as if we're dead
      s.handler(Failure(new NotConnectedException("Not Connected")))
    } else if (writer.isDefined || !failFast) {
      val pushed = push(s.message){
        case OutputResult.Success   => sentBuffer.enqueue(s)
        case OutputResult.Failure   => s.handler(Failure(new NotConnectedException("Error while sending")))
        case OutputResult.Cancelled => s.handler(Failure(new RequestTimeoutException))
      }
      if (pushed) {
        if (sentBuffer.size >= config.sentBufferSize) {
          pauseWrites()
        }
      } else {
        s.handler(Failure(new ClientOverloadedException("Client is overloaded")))
      }
    } else {
      droppedRequests.hit(tags = hpTags)
      s.handler(Failure(new NotConnectedException("Not Connected")))
    }
  }

  private def checkGracefulDisconnect() {
    if (disconnecting && sentBuffer.size == 0) {
      writer.foreach{_.disconnect()}
      writer = None
    }
  }


  def idleCheck(period: Duration) {
    //TODO: timeout pending requests
  }
}
