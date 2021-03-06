package com.github.flinkalt.api

import com.github.flinkalt.typeinfo.TypeInfo
import simulacrum.typeclass

@typeclass
trait DStream[F[_]] {
  def map[A, B: TypeInfo](fa: F[A])(f: A => B): F[B]
  def flatMap[A, B: TypeInfo](f: F[A])(fun: A => Seq[B]): F[B]
  def collect[A, B: TypeInfo](f: F[A])(pf: PartialFunction[A, B]): F[B]
  def filter[A](f: F[A])(predicate: A => Boolean): F[A]

  def union[A](f: F[A])(g: F[A]): F[A]
}

