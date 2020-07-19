package zio.nats.parser

import NatsMessage._
import fastparse._
import fastparse.NoWhitespace._
import zio.{ Task, ZIO }

object NatsProtocolParser extends CommonParsers {

  def infoMessage[_: P]: P[NatsServerMessage]    = P("INFO" ~ space ~/ jsonExpr).map(Info)
  def connectMessage[_: P]: P[NatsClientMessage] = P("CONNECT " ~/ jsonExpr).map(Connect(_))

  private def subjectNameChars(c: Char) = c.isLetterOrDigit || c == '.' || c == '-'

  private def noWhitespaceString[_: P] = P(CharsWhile(subjectNameChars) ~ &(" "))
  private def subjectName[_: P]        = P(noWhitespaceString.!)

  private def maybeReplyTo[_: P]: P[Option[String]] = P(wspace ~ noWhitespaceString.!.? ~ wspace.?)

  private def maybeGroupId[_: P]: P[Option[String]] = P(wspace ~ noWhitespaceString.!.? ~ wspace.?)

  private def messagePayload[_: P](size: Int) = P(AnyChar.rep(exactly = size).!)

  private def pubMessageHead[_: P]: P[(String, Option[String], Int)] =
    P("PUB " ~/ subjectName ~ maybeReplyTo ~ digits.!.map(_.toInt))

  def pubMessage[_: P] = P(
    for {
      head    <- pubMessageHead
      subj    = head._1
      replyTo = head._2
      size    = head._3
      payload <- messagePayload(size)
    } yield Pub(subj, replyTo, size, payload)
  )

  def subMessage[_: P] =
    P(
      "SUB " ~/ subjectName ~ maybeGroupId ~ digits.!.map(_.toInt)
    ).map {
      case (subj, groupId, sid) => Sub(subj, groupId, sid)
    }

  def unsubMessage[_: P] = P("UNSUB " ~/ intNumber ~ wspace.? ~ intNumber.?).map {
    case (sid, None) => UnSub(sid, None)
    case (sid, max)  => UnSub(sid, max)
  }

  private def msgMessageHead[_: P] =
    P("MSG " ~/ subjectName ~ wspace ~ intNumber ~ maybeReplyTo ~ wspace.? ~ intNumber ~ "\r\n")

  def msgMessage[_: P] = P(
    for {
      meta                       <- msgMessageHead
      (subj, sid, replyTo, size) = meta
      payload                    <- messagePayload(size)
    } yield {
      Msg(subj, sid, replyTo, size, payload)
    }
  )

  private def lineEnd[_: P] = P(wspace.? ~ "\r\n".?)

  def pingMessage[_: P] = P("PING" ~ lineEnd).map(_ => Ping)

  def pongMessage[_: P] = P("PONG" ~ lineEnd).map(_ => Pong)

  def okMessage[_: P] = P("+OK" ~ lineEnd).map(_ => Ok)

  def errMessage[_: P] = P("-ERR" ~ wspace ~ singleQuotedString).map(err => Error(err))

  def fullProtocol[_: P]: P[NatsMessage] =
    P(
      space ~/ (
        infoMessage | connectMessage | pubMessage | subMessage | unsubMessage | msgMessage | pingMessage | pongMessage | okMessage | errMessage
      )
    )

  def fromServer[_: P]: P[NatsServerMessage] =
    P(
      infoMessage |
        msgMessage |
        okMessage |
        errMessage |
        pingMessage |
        pongMessage
    )

  def fromServerSeq[_: P]: P[Seq[NatsServerMessage]] =
    P(
      (infoMessage |
        msgMessage |
        okMessage |
        errMessage |
        pingMessage |
        pongMessage)
    ).rep(sep = space)

  def fromClient[_: P]: P[NatsClientMessage] =
    P(
      connectMessage |
        pubMessage |
        subMessage |
        unsubMessage |
        pingMessage |
        pongMessage
    )

//  def main(args: Array[String]): Unit = {
//    //    (sentByClient ++ sentByServer).foreach { msg =>
//    //      println(parse(msg, fullProtocol(_)))
//    //    }
//    //    sentByServer.foreach { msg =>
//    //      println(s"PARSING:\n$msg\n---")
//    //      parse(msg, fromServer(_)) match {
//    //        case Parsed.Success(value, index) => println(s"Parsed (at $index): $value\n===")
//    //        case failure: Parsed.Failure      => println(s"ERROR parsing $msg => $failure")
//    //      }
//    //    }
//    //
////    val combined = Seq(MsgSimpleExample, MsgWithReplyExample)
////    val combined = sentByServer
////    val combinedStr = MsgSimpleExample + MsgWithReplyExample
//    val combinedStr = sentByServer.mkString("\r\n").iterator
////    val combinedStr = ErrSample
//    val parsed = parse(
//      Seq(
//        "MSG ZZZZ.BAR 9 11\r\n",
//        "Hello World\r\n",
//        PingSample,
//        PongSample,
//        "PI",
//        "NG\r\n"
//      ).iterator,
//      fromServerSeq(_)
//    )
//    parsed match {
//      case Parsed.Success(value, index) =>
//        println(s"AT ${index} GOT:\n${value.mkString("\r\n")}")
//      case failure: Parsed.Failure =>
//        val unparsed = combinedStr.drop(failure.index)
//        println(s"ERROR GOT $failure\nLeft ${unparsed}")
//    }
//  }

  def parseServerMessage(combined: String): Task[(Seq[NatsServerMessage], String)] = ZIO.effect {
    parse(combined, fromServer(_)) match {
      case Parsed.Success(value, index) => (Seq(value), combined.substring(index))
      case failure: Parsed.Failure      => (Seq.empty, combined.substring(failure.index))
    }
  }

  def parseServerMessages(combined: String): Task[(Seq[NatsServerMessage], String)] = ZIO.effect {
    parse(combined, fromServerSeq(_)) match {
      case Parsed.Success(value, index) => (value, combined.substring(index))
      case failure: Parsed.Failure      => (Seq.empty, combined.substring(failure.index))
    }
  }

}
