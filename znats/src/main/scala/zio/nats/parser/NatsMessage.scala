package zio.nats.parser

import zio._
import fastparse._

sealed trait NatsMessage
sealed trait NatsServerMessage extends NatsMessage
sealed trait NatsClientMessage extends NatsMessage {
  def serialize: String
  def serializeChunk: UIO[Chunk[Byte]] = UIO.effectTotal(Chunk.fromArray((serialize + "\r\n").getBytes))
}

object NatsMessage {
  final case class Info(payload: JS.Val) extends NatsServerMessage
  final case class Connect(payload: JS.Val) extends NatsClientMessage {
    override def serialize: String = Connect.defaultConnect
  }
  object Connect {
    //      """CONNECT {"verbose":true,"pedantic":false,"tls_required":false,"name":"","lang":"scala","version":"1.2.2","protocol":1}"""
    private val defaultConnect     = buildMsg()
    val default: NatsClientMessage = parse(defaultConnect, NatsProtocolParser.connectMessage(_)).get.value

    private def buildMsg(
      name: String = "",
      verbose: Boolean = false,
      pedantic: Boolean = false,
      tlsRequired: Boolean = false,
      version: String = "1.0.0"
    ): String =
      s"""CONNECT {"verbose":$verbose,"pedantic":$pedantic,"tls_required":$tlsRequired,"name":"$name","lang":"scala","version":"$version","protocol":1}"""

    def build(
      name: String,
      verbose: Boolean = false,
      pedantic: Boolean = false,
      tlsRequired: Boolean = false,
      version: String = "1.0.0"
    ): NatsClientMessage =
      parse(buildMsg(name, verbose, pedantic, tlsRequired, version), NatsProtocolParser.connectMessage(_)).get.value
  }

  final case class Pub(subject: String, replyTo: Option[String], size: Int, data: String) extends NatsClientMessage {
    override def serialize: String = replyTo match {
      case Some(replT) => s"PUB $subject $replT $size\r\n$data"
      case None        => s"PUB $subject $size\r\n$data"
    }
  }

  final case class Sub(subject: String, queue: Option[String], sid: Int) extends NatsClientMessage {
    override def serialize: String = queue match {
      case Some(q) => s"SUB $subject $q $sid"
      case None    => s"SUB $subject $sid"
    }
  }
  final case class UnSub(sid: Int, maxMessages: Option[Int]) extends NatsClientMessage {
    override def serialize: String = maxMessages match {
      case Some(max) => s"UNSUB $sid $max"
      case None      => s"UNSUB $sid"
    }
  }
  final case class Msg(subject: String, sid: Int, replyTo: Option[String], size: Int, data: String)
      extends NatsServerMessage
  final case object Ping extends NatsServerMessage with NatsClientMessage {
    override def serialize: String = "PING"
  }
  final case object Pong extends NatsServerMessage with NatsClientMessage {
    override def serialize: String = "PONG"
  }
  final case object Ok                extends NatsServerMessage
  final case class Error(msg: String) extends NatsServerMessage
}
