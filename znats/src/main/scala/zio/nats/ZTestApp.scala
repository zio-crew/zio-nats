package zio.nats

import zio._
import zio.console._
import zio.nats.parser.NatsMessage.Connect

object ZTestApp extends App {
  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, ExitCode] = {
    val logic = for {
      natsS <- Nats.connectToNatsHost(Nats.NatsHost("localhost", 4222))
      _ <- natsS.use { nats =>
            for {
              _   <- nats.send(Connect.default)
              sid <- nats.subscribe("s1")
              _   <- nats.publish("s1", "123")
              _   <- putStrLn("Press enter to exit")
              _   <- nats.unsubscribe(sid)
              _   <- getStrLn
            } yield ()
          }
    } yield ()
    logic.exitCode
  }
}
