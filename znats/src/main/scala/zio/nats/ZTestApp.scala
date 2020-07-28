package zio.nats

import zio._
import zio.console._
import zio.stream._

object ZTestApp extends App {
  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, ExitCode] = {
    val logic = for {
      natsS <- Nats.connectToNatsHost(Nats.NatsHost("localhost", 4222), Nats.BuffersConfig())
      _ <- natsS.use { nats =>
            val runs = 10
            for {
              _   <- nats.connect("zio-test")
              sid <- nats.subscribe("s1")(msg => putStrLn(s"-<><><>- ${msg.toString}").provideLayer(Console.live))
              timedRes <- Stream
                           .fromIterable(0 to runs)
                           .buffer(1)
                           .mapM(id => nats.publish("s1", s"msg $id"))
                           .runDrain
                           .timed
              _ <- putStrLn(
                    s"Execution time ${timedRes._1.toMillis} ms for $runs messages. Avg: ${timedRes._1.toMillis.toFloat / runs} ms"
                  )

              _ <- putStrLn("Press enter to exit")
              _ <- getStrLn
              _ <- nats.unsubscribe(sid)
            } yield ()
          }
    } yield ()
    logic.exitCode
  }
}
