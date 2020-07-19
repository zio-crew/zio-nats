package zio.nats.parser

trait Samples {
  val InfoSample =
    "INFO {\"server_id\":\"Zk0GQ3JBSrg3oyxCRRlE09\",\"version\":\"1.2.0\",\"proto\":1,\"go\":\"go1.10.3\",\"host\":\"0.0.0.0\",\"port\":4222,\"max_payload\":1048576,\"client_id\":2392}\r\n"
  val ConnectSample =
    "CONNECT {\"verbose\":false,\"pedantic\":false,\"tls_required\":false,\"name\":\"\",\"lang\":\"go\",\"version\":\"2.1.7\",\"protocol\":1.0}\r\n"

  val PubToSubjectSample            = "PUB FOO 11\r\nHello NATS!\r\n"
  val PubToSubjectWithReplyToSample = "PUB FRONT.DOOR INBOX.22 11\r\nKnock Knock\r\n"
  val PubEmptyToSubjectSample       = "PUB NOTIFY 0\r\n\r\n"

  val SubNoGroupSample = "SUB FOO 1\r\n"
  val SubGroupSample   = "SUB BAR G1 44\r\n"

  val UnsubSample        = "UNSUB 1\r\n"
  val UnsubWithMaxSample = "UNSUB 1 5\r\n"

  val MsgSimpleExample    = "MSG ZZZZ.BAR 9 11\r\nHello World\r\n"
  val MsgWithReplyExample = "MSG AAAA.BAR 9 INBOX.34 15\r\n[1] Hello World\r\n"

  val PingSample = "PING\r\n"

  val PongSample = "PONG\r\n"
  val OkSample   = "+OK\r\n"
  val ErrSample  = "-ERR 'Maximum Connections Exceeded'\r\n"

  val sentByServer = Seq(
    InfoSample,
    MsgSimpleExample,
    MsgWithReplyExample,
    OkSample,
    ErrSample,
    PingSample,
    PongSample
  )

  val sentByClient = Seq(
    ConnectSample,
    PubToSubjectSample,
    PubToSubjectWithReplyToSample,
    PubEmptyToSubjectSample,
    SubNoGroupSample,
    SubGroupSample,
    UnsubSample,
    UnsubWithMaxSample
  )
}
