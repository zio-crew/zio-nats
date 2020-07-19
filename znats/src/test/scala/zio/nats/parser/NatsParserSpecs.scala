package zio.nats.parser

import zio._
import zio.nats.parser.NatsMessage._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._

object NatsParserSpecs extends DefaultRunnableSpec with Samples {
  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("NATS protocol parser")(
      suite("Client side parser") {
        testM("Parses all possible messages sent by server") {
          for {
            input  <- ZIO.effectTotal(sentByServer.mkString)
            parsed <- NatsProtocolParser.parseServerMessages(input)
          } yield assert(parsed._1.drop(1))(
            equalTo(
              Seq(
                Msg("ZZZZ.BAR", 9, None, 11, "Hello World"),
                Msg("AAAA.BAR", 9, Some("INBOX.34"), 15, "[1] Hello World"),
                Ok,
                Error("Maximum Connections Exceeded"),
                Ping,
                Pong
              )
            )
          )
        }
      }
    ) @@ parallel @@ timed
}
