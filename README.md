# ZIO-NATS 
zio-nats is purely zio-nio based NATS driver. it uses fasparse and parser library to handle protocol messages

## TO DO
* [x] Add server sent messages parser specs
* [ ] Add multi-host cluster connections
* [ ] Add integration tests using real nats in docker
* [ ] Publish to maven/bintray   
* [ ] Add event subscriptions manager
* [ ] Add client sent messages parser specs
* [ ] Add proxy/server implementation of NATS protocol (parsers already able to work with both parts)
* [ ] Add metrics and configurable logger

## Example 
Simple benchmark and usage example
```scala
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
```