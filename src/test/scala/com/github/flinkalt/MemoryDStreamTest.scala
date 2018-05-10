package com.github.flinkalt

import cats.data.State
import cats.kernel.Semigroup
import com.github.flinkalt.memory.{Data, MemoryDStream}
import com.github.flinkalt.time.{Duration, Instant}
import org.scalatest.FunSuite

class MemoryDStreamTest extends FunSuite {

  import DStream.ops._

  test("Total Word Count") {
    def wordCountProgram[DS[_] : DStream](lines: DS[String]): DS[Count[String]] = {
      lines
        .flatMap(splitToWords)
        .mapWithState(zipWithCount)
    }

    val stream = MemoryDStream(Vector(
      syncedData(timeAt(0), "x"),
      syncedData(timeAt(1), "y z"),
      syncedData(timeAt(2), ""),
      syncedData(timeAt(5), "z q y y")
    ))

    val outStream: MemoryDStream[Count[String]] = wordCountProgram(stream)

    val actualValues = outStream.vector
    assert(actualValues == Vector(
      syncedData(timeAt(0), Count("x", 1)),
      syncedData(timeAt(1), Count("y", 1)),
      syncedData(timeAt(1), Count("z", 1)),
      syncedData(timeAt(5), Count("z", 2)),
      syncedData(timeAt(5), Count("q", 1)),
      syncedData(timeAt(5), Count("y", 2)),
      syncedData(timeAt(5), Count("y", 3))
    ))
  }

  test("Sliding Word Count") {
    implicit def countSemigroup[T]: Semigroup[Count[T]] = new Semigroup[Count[T]] {
      override def combine(x: Count[T], y: Count[T]): Count[T] = Count(y.value, x.count + y.count)
    }

    def wordCountProgram[DS[_] : DStream](lines: DS[String]): DS[Count[String]] = {
      lines
        .flatMap(splitToWords)
        .map(s => Count(s, 1))
        .windowReduce(SlidingWindow(Duration(4), Duration(2)), _.value)(WindowTrigger.identity)
    }

    val stream = MemoryDStream(Vector(
      syncedData(timeAt(0), "x"),
      syncedData(timeAt(2), "y z"),
      syncedData(timeAt(3), "y"),
      syncedData(timeAt(6), "z q y y")
    ))

    val outStream: MemoryDStream[Count[String]] = wordCountProgram(stream)

    val actualValues = outStream.vector
    assert(actualValues == Vector(
      Data(time = timeAt(2), watermark = timeAt(2), Count("x", 1)),

      Data(time = timeAt(4), watermark = timeAt(6), Count("y", 2)),
      Data(time = timeAt(4), watermark = timeAt(6), Count("x", 1)),
      Data(time = timeAt(4), watermark = timeAt(6), Count("z", 1)),

      Data(time = timeAt(6), watermark = timeAt(6), Count("z", 1)),
      Data(time = timeAt(6), watermark = timeAt(6), Count("y", 2)),

      Data(time = timeAt(8), watermark = timeAt(8), Count("q", 1)),
      Data(time = timeAt(8), watermark = timeAt(8), Count("z", 1)),
      Data(time = timeAt(8), watermark = timeAt(8), Count("y", 2)),

      Data(time = timeAt(10), watermark = timeAt(10), Count("q", 1)),
      Data(time = timeAt(10), watermark = timeAt(10), Count("z", 1)),
      Data(time = timeAt(10), watermark = timeAt(10), Count("y", 2))
    ))
  }

  test("Sliding number ladder") {
    import cats.instances.int._

    sealed trait Size
    case object Small extends Size
    case object Large extends Size

    def slidingSumsByDecimal[DS[_] : DStream](nums: DS[Int]): DS[(Size, Window, Int)] = {
      nums.windowReduce(SlidingWindow(Duration(10), Duration(5)), i => if (i < 10) Small else Large)((size, win, a) => (size, win, a))
    }

    val stream = MemoryDStream(Vector(
      syncedData(timeAt(0), 1),
      syncedData(timeAt(0), 2),
      syncedData(timeAt(0), 10),
      syncedData(timeAt(0), 12),

      syncedData(timeAt(3), 3),
      syncedData(timeAt(3), 3),

      syncedData(timeAt(7), 4),
      syncedData(timeAt(7), 13),

      syncedData(timeAt(11), 11),
      syncedData(timeAt(12), 7)
    ))

    val outStream: MemoryDStream[(Size, Window, Int)] = slidingSumsByDecimal(stream)
    val actualValues = outStream.vector
    assert(actualValues == Vector(
      Data(time = timeAt(5), watermark = timeAt(7), (Large, Window(start = timeAt(-5), end = timeAt(5)), 10 + 12)),
      Data(time = timeAt(5), watermark = timeAt(7), (Small, Window(start = timeAt(-5), end = timeAt(5)), 1 + 2 + 3 + 3)),

      Data(time = timeAt(10), watermark = timeAt(11), (Large, Window(start = timeAt(0), end = timeAt(10)), 10 + 12 + 13)),
      Data(time = timeAt(10), watermark = timeAt(11), (Small, Window(start = timeAt(0), end = timeAt(10)), 1 + 2 + 3 + 3 + 4)),

      Data(time = timeAt(15), watermark = timeAt(15), (Large, Window(start = timeAt(5), end = timeAt(15)), 13 + 11)),
      Data(time = timeAt(15), watermark = timeAt(15), (Small, Window(start = timeAt(5), end = timeAt(15)), 4 + 7)),
      
      Data(time = timeAt(20), watermark = timeAt(20), (Small, Window(start = timeAt(10), end = timeAt(20)), 7)),
      Data(time = timeAt(20), watermark = timeAt(20), (Large, Window(start = timeAt(10), end = timeAt(20)), 11))
    ))
  }

  test("Sliding numbers with late watermarks") {
    import cats.instances.list._

    def slidingSumsByDecimal[DS[_] : DStream](nums: DS[Int]): DS[List[Int]] = {
      nums
        .map(i => List(i))
        .windowReduce(SlidingWindow(Duration(10), Duration(2)), _ => ())(WindowTrigger.identity)
    }

    val stream = MemoryDStream(Vector(
      Data(time = timeAt(1), watermark = timeAt(1), value = 1),
      Data(time = timeAt(2), watermark = timeAt(1), value = 2),
      Data(time = timeAt(3), watermark = timeAt(1), value = 3),
      Data(time = timeAt(4), watermark = timeAt(1), value = 4),
      Data(time = timeAt(5), watermark = timeAt(3), value = 5),
      Data(time = timeAt(6), watermark = timeAt(3), value = 6),
      Data(time = timeAt(7), watermark = timeAt(3), value = 7),
      Data(time = timeAt(8), watermark = timeAt(6), value = 8),
      Data(time = timeAt(9), watermark = timeAt(6), value = 9)
    ))

    val outStream: MemoryDStream[List[Int]] = slidingSumsByDecimal(stream)

    val actualValues = outStream.vector
    assert(actualValues == Vector(
      Data(time = timeAt(2), watermark = timeAt(3), value = List(1)),
      Data(time = timeAt(4), watermark = timeAt(6), value = List(1, 2, 3)),
      Data(time = timeAt(6), watermark = timeAt(6), value = List(1, 2, 3, 4, 5)),
      Data(time = timeAt(8), watermark = timeAt(8), value = List(1, 2, 3, 4, 5, 6, 7)),
      Data(time = timeAt(10), watermark = timeAt(10), value = List(1, 2, 3, 4, 5, 6, 7, 8, 9)),
      Data(time = timeAt(12), watermark = timeAt(12), value = List(2, 3, 4, 5, 6, 7, 8, 9)),
      Data(time = timeAt(14), watermark = timeAt(14), value = List(4, 5, 6, 7, 8, 9)),
      Data(time = timeAt(16), watermark = timeAt(16), value = List(6, 7, 8, 9)),
      Data(time = timeAt(18), watermark = timeAt(18), value = List(8, 9))
    ))
  }

  private def syncedData[T](time: Instant, value: T): Data[T] = Data(time, time, value)

  private def timeAt(i: Int): Instant = Instant(1000L + i)

  def splitToWords(line: String): Seq[String] = {
    line.toLowerCase().split("\\W+").filter(_.nonEmpty)
  }

  case class Count[T](value: T, count: Int)

  def zipWithCount[T]: StateTrans[T, Int, T, Count[T]] = {
    StateTrans(
      identity,
      t => State(maybeCount => {
        val count = maybeCount.getOrElse(0) + 1
        (Some(count), Count(t, count))
      }))
  }
}
