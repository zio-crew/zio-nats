package zio.nats.utils.ring_buffer

//import zio._
//import zio.nats.parser.NatsMessage._
import zio.test._
import zio.test.Assertion._
import zio.test.environment._
//import zio.test.TestAspect._

object RingBufferSpecs extends DefaultRunnableSpec {

  override def spec: ZSpec[TestEnvironment, Any] = suite("Ring buffer specs")(
    testM("Builds initial ring buffer") {
      for {
        buff <- RingBuffer.make[String](3)
        _    <- buff.append("1")
        _    <- buff.append("2")
        _    <- buff.append("4")
        _    <- buff.append("4")
        h1   <- buff.head
      } yield assert(h1)(equalTo(Some("2")))
    }
  )
}
