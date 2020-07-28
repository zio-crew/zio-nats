package zio.nats

import java.util.concurrent.Executors
import java.util.UUID

import zio._
import zio.nats.parser._
import zio.nats.parser.NatsMessage._
import zio.nio.channels._
import zio.nio.core._
import zio.nio.core.channels.AsynchronousChannelGroup
import zio.stream._

import scala.concurrent.ExecutionContext

object Nats {

  final case class BuffersConfig(
    maxNatsMessage: Int = 10 * 1024,
    maxBuffersInProcessing: Int = 10,
    maxParsedQueue: Int = 128
  )
  final case class NatsHost(host: String, port: Int)

//  private def trace(msg: => String): UIO[Unit] = ZIO.succeedNow(msg).unit
  private def trace(msg: => String): UIO[Unit] = ZIO.effectTotal(println(s"[ nats trace ] $msg")).fork.unit

  type SubscriptionId = Int
  type SubjectId      = String
  type MessageData    = String

  case class SubscriptionEventHandler(sid: SubscriptionId, handle: Msg => Task[Unit])

  final class ZNatsSession(
    channel: AsynchronousSocketChannel,
    curSidRef: Ref[Int],
    handlers: Ref[Vector[SubscriptionEventHandler]]
  ) {
    private[Nats] def sendRaw(msg: NatsClientMessage): Task[Unit] =
      (for {
        resChunk <- msg.serializeChunk
        sent     <- channel.write(resChunk)
        _        <- trace(s"Sent $sent bytes for: ${msg.serialize}")
      } yield ()).fork.unit

    private def buildAndAddHandler(handler: Msg => Task[Unit]): UIO[SubscriptionId] =
      for {
        curSid       <- curSidRef.getAndUpdate(_ + 1)
        subscription = SubscriptionEventHandler(curSid, handler)
        _            <- handlers.update(_ :+ subscription)
      } yield curSid

    def connect(name: String): Task[Unit] = sendRaw(Connect.build(name))

    def subscribe(subj: SubjectId, queue: Option[String] = None)(handler: Msg => Task[Unit]): Task[SubscriptionId] =
      for {
        curSid <- buildAndAddHandler(handler)
        _      <- sendRaw(Sub(subj, queue, curSid))
      } yield curSid

    def unsubscribe(sid: SubscriptionId, maxMessages: Option[Int] = None): Task[Unit] =
      for {
        _ <- handlers.update(_.filterNot(_.sid == sid))
        _ <- sendRaw(UnSub(sid, maxMessages))
      } yield ()

    def publish(subj: SubjectId, msg: MessageData, replyTo: Option[String] = None): Task[Unit] =
      sendRaw(Pub(subj, replyTo, msg.length, msg))

    def request(subj: SubjectId, msg: MessageData): Task[Msg] =
      for {
        responsePromise <- Promise.make[Throwable, Msg]
        replyTo         = Option(s"$subj-reply-${UUID.randomUUID()}")
        sid <- buildAndAddHandler { msg =>
                responsePromise
                  .succeed(msg)
                  .when(msg.replyTo == replyTo)
                  .unit
              }
        _    <- publish(subj, msg, replyTo)
        resp <- responsePromise.await <* handlers.update(_.filterNot(_.sid == sid))
      } yield resp
  }

  def connectToNatsHost(hostInfo: NatsHost, buffersConfig: BuffersConfig): Task[TaskManaged[ZNatsSession]] =
    AsynchronousChannelGroup(ExecutionContext.fromExecutorService(Executors.newWorkStealingPool()))
      .map(group => doConnectToNatsHost(hostInfo, buffersConfig, group))

  private def doConnectToNatsHost(
    hostInfo: NatsHost,
    buffersConfig: BuffersConfig,
    grp: AsynchronousChannelGroup
  ): TaskManaged[ZNatsSession] =
    AsynchronousSocketChannel(grp).mapM { client =>
      for {
        sidRef           <- Ref.make[Int](0)
        handlers         <- Ref.make[Vector[SubscriptionEventHandler]](Vector.empty)
        address          <- SocketAddress.inetSocketAddress(hostInfo.host, hostInfo.port)
        _                <- client.connect(address)
        _                <- trace("Starting processing fibers")
        chunkBufferQ     <- Queue.bounded[Chunk[Byte]](buffersConfig.maxBuffersInProcessing)
        parsedBufferQ    <- Queue.bounded[NatsServerMessage](buffersConfig.maxParsedQueue)
        _                <- trace("Queues initialized")
        parserBufferPage <- RefM.make("")

        _ <- client
              .read(buffersConfig.maxNatsMessage)
              .flatMap(readChunk => chunkBufferQ.offer(readChunk))
              .catchAll(ex => trace(s"Error reading data from NATS $ex") <* client.connect(address))
              .forever
              .fork

        _ <- (for {
              chunksFromBuffer <- chunkBufferQ.takeBetween(1, buffersConfig.maxBuffersInProcessing)
              _ <- (for {
                    lastBuffer <- parserBufferPage.get
                    //                    combined               = (lastBuffer + chunksFromBuffer.map(_.map(_.toChar).mkString).mkString).trim
                    combined = {
                      val chunks = new String(chunksFromBuffer.flatMap(_.toVector).toArray)
                      (lastBuffer + chunks).trim
                    }
                    _                      <- trace(s"Parsing combined page:\n$combined")
                    parsed                 <- NatsProtocolParser.parseServerMessages(combined)
                    (parsedMessages, left) = parsed
                    fiber                  <- parsedBufferQ.offerAll(parsedMessages).fork
                    _                      <- parserBufferPage.set(left)
                    _                      <- fiber.await
                  } yield ()).when(chunksFromBuffer.nonEmpty)

            } yield ()).forever.fork

        znats <- ZIO.effect(new ZNatsSession(client, sidRef, handlers))
        _ <- Stream
              .fromQueue(parsedBufferQ)
              .tap(msg => trace(s"Received nats message: $msg"))
              .tap {
                case NatsMessage.Ping => trace("Sending PONG response") *> znats.sendRaw(Pong).unit
                case msg: NatsMessage.Msg =>
                  for {
                    processors <- handlers.get
                    _          <- trace(s"Processing message $msg with ${processors.size} processors")
                    _          <- ZIO.foreachPar(processors)(_.handle(msg))
                  } yield ()
                case _ => ZIO.unit
              }
              .runDrain
              .fork
      } yield znats
    }

  def ringIterator[T](nextIterator: => Iterator[T]): Iterator[T] = new Iterator[T] {
    private var it                = nextIterator
    override def hasNext: Boolean = true
    override def next(): T = {
      if (!it.hasNext) it = nextIterator
      it.next()
    }
  }

//  class RingBuffer[T](seq: Seq[T]) {
//    def iterator: Iterator[T] = ringIterator(seq.iterator)
//  }

//
//  private def doConnectToNatsHosts(
//    hostInfo: Seq[NatsHost],
//    buffersConfig: BuffersConfig,
//    grp: AsynchronousChannelGroup
//  ): TaskManaged[ZNatsSession] = {
//    def nextHost =
//      Stream.fromIterable(hostInfo).forever
//    AsynchronousSocketChannel(grp).mapM { client =>
//      for {
//        sidRef           <- Ref.make[Int](0)
//        handlers         <- Ref.make[Vector[SubscriptionEventHandler]](Vector.empty)
//        address          <- SocketAddress.inetSocketAddress(hostInfo.host, hostInfo.port)
//        _                <- client.connect(address)
//        _                <- trace("Starting processing fibers")
//        chunkBufferQ     <- Queue.bounded[Chunk[Byte]](buffersConfig.maxBuffersInProcessing)
//        parsedBufferQ    <- Queue.bounded[NatsServerMessage](buffersConfig.maxParsedQueue)
//        _                <- trace("Queues initialized")
//        parserBufferPage <- RefM.make("")
//
//        _ <- client
//              .read(buffersConfig.maxNatsMessage)
//              .flatMap(readChunk => chunkBufferQ.offer(readChunk))
//              .catchAll(ex => trace(s"Error reading data from NATS $ex") <* client.connect(address))
//              .forever
//              .fork
//
//        _ <- (for {
//              chunksFromBuffer <- chunkBufferQ.takeBetween(1, buffersConfig.maxBuffersInProcessing)
//              _ <- (for {
//                    lastBuffer <- parserBufferPage.get
//                    //                    combined               = (lastBuffer + chunksFromBuffer.map(_.map(_.toChar).mkString).mkString).trim
//                    combined = {
//                      val chunks = new String(chunksFromBuffer.flatMap(_.toVector).toArray)
//                      (lastBuffer + chunks).trim
//                    }
//                    _                      <- trace(s"Parsing combined page:\n$combined")
//                    parsed                 <- NatsProtocolParser.parseServerMessages(combined)
//                    (parsedMessages, left) = parsed
//                    fiber                  <- parsedBufferQ.offerAll(parsedMessages).fork
//                    _                      <- parserBufferPage.set(left)
//                    _                      <- fiber.await
//                  } yield ()).when(chunksFromBuffer.nonEmpty)
//
//            } yield ()).forever.fork
//
//        znats <- ZIO.effect(new ZNatsSession(client, sidRef, handlers))
//        _ <- Stream
//              .fromQueue(parsedBufferQ)
//              .tap(msg => trace(s"Received nats message: $msg"))
//              .tap {
//                case NatsMessage.Ping => trace("Sending PONG response") *> znats.sendRaw(Pong).unit
//                case msg: NatsMessage.Msg =>
//                  for {
//                    processors <- handlers.get
//                    _          <- trace(s"Processing message $msg with ${processors.size} processors")
//                    _          <- ZIO.foreachPar(processors)(_.handle(msg))
//                  } yield ()
//                case _ => ZIO.unit
//              }
//              .runDrain
//              .fork
//      } yield znats
//    }
//  }
}
