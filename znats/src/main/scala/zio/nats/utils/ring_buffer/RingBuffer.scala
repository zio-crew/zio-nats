package zio.nats.utils.ring_buffer

import zio._
//import zio.nats.RingBuffer._

object RingBuffer {

  class BufferNode[T](
    val id: Int,
    var data: Option[T],
    private var _prev: BufferNode[T],
    private var _next: BufferNode[T]
  ) {
    def prev: BufferNode[T]                = _prev
    def setPrev(prev: BufferNode[T]): Unit = _prev = prev

    def next: BufferNode[T]               = _next
    def setNext(nxt: BufferNode[T]): Unit = _next = nxt

    override def toString: String = s"BufferNode($id,$data,${_prev.id})"
  }

  def buildRing[T](cap: Int): BufferNode[T] = {
    val uninitializedBufferNode = new BufferNode[T](-1, None, null, null)
    val nodes =
      (0 until cap).map(idx => new BufferNode[T](idx, None, uninitializedBufferNode, uninitializedBufferNode))
    val head = nodes.head
    val last = nodes.last
    nodes.drop(1).foldLeft(head) {
      case (prev, nxt) =>
        nxt.setPrev(prev)
        prev.setNext(nxt)
        nxt
    }
    head.setPrev(last)
    last.setNext(head)
    head
  }

  def make[T](cap: Int): UIO[RingBuffer[T]] =
    for {
      rng     <- ZIO.effectTotal(buildRing[T](cap))
      curHead <- Ref.make(rng)
      curTail <- Ref.make(rng.prev)
    } yield {
      new RingBuffer[T] {
        def head: Task[Option[T]] = curHead.get.map(_.data)
        def read: Task[Option[T]] = head <* curHead.update(_.prev)
        def append(data: T): Task[Unit] =
          for {
            tail <- curTail.get
            _    <- ZIO.effect(tail.data = Option(data))
            _    <- curTail.update(_.next)
          } yield ()
      }
    }
}

trait RingBuffer[T] {
  def head: Task[Option[T]]
  def read: Task[Option[T]]
  def append(data: T): Task[Unit]
}
