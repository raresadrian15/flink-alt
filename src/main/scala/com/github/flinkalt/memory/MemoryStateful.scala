package com.github.flinkalt.memory

import cats.data.State
import cats.instances.vector._
import cats.syntax.traverse._
import com.github.flinkalt.typeinfo.TypeInfo
import com.github.flinkalt.{StateTrans, Stateful}
import org.apache.flink.api.scala.ClosureCleaner

object MemoryStateful extends Stateful[MemoryStream] {
  override def mapWithState[K: TypeInfo, S: TypeInfo, A, B: TypeInfo](f: MemoryStream[A])(stateTrans: StateTrans[K, S, A, B]): MemoryStream[B] = {
    ClosureCleaner.ensureSerializable(stateTrans)
    val vectorStateTrans = StateTrans(stateTrans.key, stateTrans.trans.andThen(_.map(b => Vector(b))))

    flatMapWithState(f)(vectorStateTrans)
  }

  override def flatMapWithState[K: TypeInfo, S: TypeInfo, A, B: TypeInfo](f: MemoryStream[A])(stateTrans: StateTrans[K, S, A, Vector[B]]): MemoryStream[B] = {
    ClosureCleaner.ensureSerializable(stateTrans)
    val trans: DataOrWatermark[A] => State[Map[K, S], Vector[DataOrWatermark[B]]] = {
      case JustData(time, value) => stateByKey(stateTrans.key(value), stateTrans.trans(value)).map(v => v.map(b => JustData(time, b)))
      case JustWatermark(time) => State.pure(Vector(JustWatermark(time)))
    }
    val elems = f.elems.flatTraverse(trans).runA(Map.empty).value
    f.copy(elems = elems)
  }

  private def stateByKey[K, S, A](key: K, st: State[Option[S], A]): State[Map[K, S], A] = {
    val read: Map[K, S] => Option[S] = map => map.get(key)
    val write: (Map[K, S], Option[S]) => Map[K, S] = (map, os) => os.map(s => map.updated(key, s)).getOrElse(map)
    st.transformS(read, write)
  }
}
