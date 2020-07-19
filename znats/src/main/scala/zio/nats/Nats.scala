package zio.nats

import java.util.concurrent.Executors

import zio._
import zio.nats.parser._
import zio.nats.parser.NatsMessage._
import zio.nio.channels._
import zio.nio.core._
import zio.nio.core.channels.AsynchronousChannelGroup
import zio.stream._

import scala.concurrent.ExecutionContext

object Nats {

  case class NatsHost(host: String, port: Int, bufferSize: Int = 1024 * 2)

  private def trace(msg: => String): UIO[Unit] = ZIO.effectTotal(println(s"[ nats trace ] $msg"))

  type SubscriptionId = Int
  final class ZNatsSession(channel: AsynchronousSocketChannel, curSidRef: Ref[Int]) {
    def sendRaw(msg: NatsClientMessage): Task[Unit] =
      for {
        resChunk <- msg.serializeChunk
        sent     <- channel.write(resChunk)
        _        <- trace(s"Sent $sent bytes for: ${msg.serialize}").fork.map(_.await)
      } yield ()

    def subscribe(subj: String, queue: Option[String] = None): Task[SubscriptionId] =
      for {
        curSid <- curSidRef.getAndUpdate(_ + 1)
        _      <- sendRaw(Sub(subj, queue, curSid))
      } yield curSid

    def unsubscribe(sid: SubscriptionId, maxMessages: Option[Int] = None): Task[Unit] =
      sendRaw(UnSub(sid, maxMessages))

    def publish(subj: String, msg: String, replyTo: Option[String] = None): Task[Unit] =
      sendRaw(Pub(subj, replyTo, msg.length, msg))

  }

  def connectToNatsHost(hostInfo: NatsHost): Task[TaskManaged[ZNatsSession]] =
    AsynchronousChannelGroup(ExecutionContext.fromExecutorService(Executors.newWorkStealingPool()))
      .map(group => connectToNatsHost(hostInfo, group))

  private def connectToNatsHost(hostInfo: NatsHost, grp: AsynchronousChannelGroup): TaskManaged[ZNatsSession] =
    AsynchronousSocketChannel(grp).mapM { client =>
      for {
        sidRef           <- Ref.make[Int](0)
        znats            <- ZIO.effect(new ZNatsSession(client, sidRef))
        address          <- SocketAddress.inetSocketAddress(hostInfo.host, hostInfo.port)
        _                <- client.connect(address)
        _                <- trace("Starting processing fibers")
        chunkBufferQ     <- Queue.bounded[Chunk[Byte]](1024)
        parsedBufferQ    <- Queue.bounded[NatsServerMessage](1024)
        _                <- trace("Queues initialized")
        parserBufferPage <- RefM.make("")
        _ <- (for {
              chunksFromBuffer <- chunkBufferQ.takeBetween(1, 10)
              _ <- (for {
                    lastBuffer             <- parserBufferPage.get
                    combined               = (lastBuffer + chunksFromBuffer.map(_.map(_.toChar).mkString).mkString).trim
                    _                      <- trace(s"Parsing combined page:\n$combined")
                    parsed                 <- NatsProtocolParser.parseServerMessages(combined)
                    (parsedMessages, left) = parsed
                    fiber                  <- parsedBufferQ.offerAll(parsedMessages).fork
                    _                      <- parserBufferPage.set(left)
                    _                      <- fiber.await
                  } yield ()).when(chunksFromBuffer.nonEmpty)

            } yield ()).forever.fork

        _ <- Stream
              .fromQueue(parsedBufferQ)
              .tap(msg => trace(s"Received nats message: $msg"))
              .tap {
                case NatsMessage.Ping => trace("Sending PONG response") *> znats.sendRaw(Pong).unit
                case _                => ZIO.unit
              }
              .runDrain
              .fork

        _ <- (for {
              readChunk <- client.read(10 * 1025)
              _         <- chunkBufferQ.offer(readChunk)
            } yield ()).forever.fork
      } yield znats
    }
}
