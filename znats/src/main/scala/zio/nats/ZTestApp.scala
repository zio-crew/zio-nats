package zio.nats

import zio._
import zio.console._
import zio.stream._
import zio.nats.parser.NatsMessage.Connect

object ZTestApp extends App {
  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, ExitCode] = {
    val logic = for {
      natsS <- Nats.connectToNatsHost(Nats.NatsHost("localhost", 4222))
      _ <- natsS.use { nats =>
            val runs = 10000
            for {
              _   <- nats.sendRaw(Connect.default)
              sid <- nats.subscribe("s1")
              timedRes <- Stream
                           .fromIterable(0 to 100)
                           .mapM(id => nats.publish("s1", s"msg $id"))
                           .runDrain
                           .timed
              _ <- putStrLn(
                    s"Execution time ${timedRes._1.toMillis} ms for $runs messages. Avg: ${timedRes._1.toMillis.toFloat / runs} ms"
                  )
              _ <- nats.unsubscribe(sid)

              _ <- putStrLn("Press enter to exit")
              _ <- getStrLn
            } yield ()
          }
    } yield ()
    logic.exitCode
  }
}
