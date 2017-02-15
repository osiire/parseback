/*
 * Copyright 2017 Daniel Spiewak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package parseback

import shims.{Applicative, Monad}

import scala.util.matching.{Regex => SRegex}

import util.Catenable
import util.EitherSyntax._

import scala.annotation.unchecked.uncheckedVariance

import MemoTable.ParserId

sealed trait Parser[+A] {

  // these are not volatile since the common case is not crossing thread boundaries
  // the consequence of a thread-local cache miss is simply recomputation
  protected var nullableMemo: Nullable = Nullable.Maybe

  // the following fields are used by FieldMemoTable to avoid hashmap usage
  // they should NEVER be accessed directly; only through the MemoTable abstraction

  private[parseback] var table: FieldMemoTable = _

  private[parseback] var derivedC: Char = _
  private[parseback] var derivedR: Parser[A @uncheckedVariance] = _

  private[parseback] var finished: Results.Cacheable[A @uncheckedVariance] = _

  def map[B](f: A => B): Parser[B] =
    Parser.Apply(this, { (_, as: Catenable[A]) => as map f })

  def mapWithLines[B](f: (List[Line], A) => B): Parser[B] =
    Parser.Apply(this, { (line, as: Catenable[A]) => as map { f(line, _) } })

  final def map2[B, C](that: Parser[B])(f: (A, B) => C): Parser[C] = (this ~ that) map f.tupled

  def filter(p: A => Boolean): Parser[A] = Parser.Filter(this, p)

  // TODO come up with a better name
  final def ~!~[B](that: Parser[B]): Parser[A ~ B] =
    Parser.Sequence(this, None, that)

  final def ~[B](that: Parser[B])(implicit W: Whitespace): Parser[A ~ B] =
    Parser.Sequence(this, W.layout, that)

  final def ~>[B](that: Parser[B])(implicit W: Whitespace): Parser[B] =
    (this ~ that) map { case _ ~ b => b }

  final def <~[B](that: Parser[B])(implicit W: Whitespace): Parser[A] =
    (this ~ that) map { case a ~ _ => a }

  def ^^^[B](b: B): Parser[B] = map { _ => b }

  // ebnf operators

  def ?(): Parser[Option[A]] =
    Parser.Epsilon(None) | (this map { Some(_) })

  def *(): Parser[List[A]] = {
    lazy val back: Parser[List[A]] = (
        this ~ back   ^^ { (_, h, t) => h :: t }
      | ""           ^^^ Nil
    )

    back
  }

  def +(): Parser[List[A]] = {
    lazy val back: Parser[List[A]] = (
        this ~ back   ^^ { (_, h, t) => h :: t }
      | this          ^^ { (_, h) => h :: Nil }
    )

    back
  }

  /**
   * Among other things, it should be possible to instantiate F[_] with an fs2 Pull,
   * which should allow fully generalizable, incremental parsing on an ephemeral stream.
   * A prerequisite step would be to convert a Stream[Task, Bytes] (or whatever format and
   * effect it is in) into a Stream[Task, Line], which could then in turn be converted
   * pretty trivially into a Pull[Task, LineStream, Nothing].
   *
   * Please note that F[_] should be lazy and stack-safe.  If F[_] is not stack-safe,
   * this function will SOE on inputs with a very large number of lines.
   */
  final def apply[F[+_]: Monad](ls: LineStream[F])(implicit W: Whitespace): F[List[ParseError] \/ Catenable[A]] = {
    import LineStream._

    def inner(self: Parser[A], table: MemoTable)(ls: LineStream[F]): F[List[ParseError] \/ Catenable[A]] = {
      ls match {
        case More(line, tail) =>
          trace(s"current line = ${line.project}")
          val derivation = self.derive(line, table.recreate())

          val ls2: F[LineStream[F]] = line.next map { l =>
            Monad[F] point More(l, tail)
          } getOrElse tail

          Monad[F].flatMap(ls2)(inner(derivation, table))

        case Empty() =>
          // don't clear prior table when we hit the end
          Monad[F] point self.finish(Set(), table).toEither
      }
    }

    val wrapped = W.layout map { ws =>
      ws ~!~ this ~!~ ws map {
        case ((_, v), _) => v   // we don't care about whitespace results
      }
    } getOrElse this

    inner(wrapped, new InitialMemoTable)(ls)
  }

  protected[parseback] final def isNullable: Boolean = {
    import Nullable._
    import Parser._

    def inner(p: Parser[_], tracked: Set[ParserId[_]]): Nullable = {
      val pid = new ParserId(p)

      if ((tracked contains pid) || p.nullableMemo != Maybe) {
        p.nullableMemo
      } else {
        p match {
          case p @ Sequence(left, layout, right) =>
            val tracked2 = tracked + pid

            val wsNullable = layout map { inner(_, tracked2) } getOrElse True

            p.nullableMemo = inner(left, tracked2) && wsNullable && inner(right, tracked2)
            p.nullableMemo

          case p @ Union(_, _) =>
            val tracked2 = tracked + pid

            p.nullableMemo = inner(p.left, tracked2) || inner(p.right, tracked2)
            p.nullableMemo

          case p @ Apply(target, _, _) =>
            p.nullableMemo = inner(target, tracked + pid)
            p.nullableMemo

          case p @ Filter(target, _) =>
            p.nullableMemo = inner(target, tracked + pid)
            p.nullableMemo

          // the following four cases should never be hit, but they
          // are correctly defined here for documentation purposes
          case p @ Literal(_, _) =>
            p.nullableMemo = False
            False

          case p @ Regex(_) =>
            p.nullableMemo = False
            False

          case p @ Epsilon(_) =>
            p.nullableMemo = True
            True

          case p @ Failure(_) =>
            p.nullableMemo = True
            True
        }
      }
    }

    if (nullableMemo == Maybe) {
      inner(this, Set()) match {
        case True => true
        case False => false

        /*
         * Ok, this gets a little complicated...
         *
         * If we're "stuck", that means there is no more short-
         * circuiting, no more undelegating, no more anything
         * that can be done.  So we cannot have any terms of the
         * form Or(true, X) or And(false, X), since those terms
         * immediately short-circuit.  Furthermore, being stuck
         * also requires a system of equations which is
         * intrinsically recursive in some way.  Thus, we could
         * make progress by picking a non-Solved/Delegate variable
         * and hypothesizing it has a particular truth value, and
         * the system would eventually loop back around to
         * calculating a value for that original value derived
         * from the hypothesis.  Presumably, any hypothesis which
         * does not derive a contradiction would be a valid
         * solution.
         *
         * Critically though, we do not have a negation constraint.
         * The only way that a contradiction can be derived is if
         * there exists some way to build a cyclic path which
         * "flips" a hypothesis to its negation.  There are only
         * two forms in our algebra which can achieve on some input:
         * Or(true, X) and And(false, X), with the former flipping
         * false to true, and the latter flipping true to false.
         * However, we've already ruled out the existence of those
         * terms since we are stuck in the first place, meaning that
         * there would be no hypothesis which could derive to a
         * contradiction by any stuck cycle.  Either true or false
         * would be valid answers.
         *
         * At this point, we take a step back and look at the
         * broader semantic picture.  An answer of "true" in the case
         * of an intrinsically recursive constraint set would mean
         * that the `finish` function should be able to produce a
         * non-error result given enough "looping".  This is clearly
         * incorrect.  In fact, in the semantics of PWD, any
         * intrinsically set of variables must ALL correspond to
         * false in the question of nullability, as `finish` would
         * discover when it runs into the cycles.
         */
        case Maybe =>
          nullableMemo = False
          false
      }
    } else {
      nullableMemo.toBoolean
    }
  }

  // memoized version of derive
  protected final def derive(line: Line, table: MemoTable): Parser[A] = {
    require(!line.isEmpty)

    trace(s"deriving $this by '${line.head}'")

    table.derive(this, line.head) getOrElse {
      val back = _derive(line, table)
      table.derived(this, line.head, back)
      back
    }
  }

  // TODO generalize to deriving in bulk, rather than by character
  protected def _derive(line: Line, table: MemoTable): Parser[A]

  protected final def finish(seen: Set[ParserId[_]], table: MemoTable): Results[A] = {
    val id = new ParserId(this)

    if (seen contains id) {
      Results.Hypothetical(ParseError.UnboundedRecursion(this) :: Nil)
    } else {
      val cached = table.finish(this)

      cached foreach { back =>
        trace(s"finished from cache $this => $back")
      }

      val back = cached getOrElse {
        val back = _finish(seen + id, table)

        back match {
          case back: Results.Cacheable[A] => table.finished(this, back)
          case _ => ()
        }

        trace(s"finished fresh calculation $this => $back")

        back
      }

      back
    }
  }

  protected def _finish(seen: Set[ParserId[_]], table: MemoTable): Results[A]
}

object Parser {

  implicit val applicative: Applicative[Parser] = new Applicative[Parser] {
    def point[A](a: A): Parser[A] = Epsilon(a)
    def ap[A, B](fa: Parser[A])(ff: Parser[A => B]): Parser[B] = (ff map2 fa) { _(_) }
    def map[A, B](fa: Parser[A])(f: A => B): Parser[B] = fa map f
  }

  /**
   * Two sequentialized parsers with optionally interleaving whitespace.
   */
  final case class Sequence[+A, +B](left: Parser[A], layout: Option[Parser[_]], right: Parser[B]) extends Parser[A ~ B] {
    nullableMemo = {
      val wsNullable = layout map { _.nullableMemo } getOrElse Nullable.True

      left.nullableMemo && wsNullable && right.nullableMemo
    }

    protected def _derive(line: Line, table: MemoTable): Parser[A ~ B] = {
      trace(s">> deriving $this")

      lazy val nonNulled = Sequence(left.derive(line, table), layout, right)

      if (left.isNullable) {
        trace(s"  >> left is nullable")

        left.finish(Set(), table).toEither match {

          /*
           * Failure is nullable, but it will return a left when finished
           */
          case -\/(_) => nonNulled

          case \/-(results) =>
            // iff we have interleaving whitespace, rewrite self to
            // have whitespace be the new left, interleaved with no
            // new whitespace
            lazy val rhs = layout map { ws =>
              Sequence(ws, None, right) map {
                case (_, r) => r
              }
            } getOrElse right

            nonNulled | Apply(rhs.derive(line, table), { (_, bs: Catenable[B]) =>
              bs flatMap { b => results map { (_, b) } }
            })
        }
      } else {
        trace(s"  >> left is not nullable")
        nonNulled
      }
    }

    protected def _finish(seen: Set[ParserId[_]], table: MemoTable) = {
      val wsFinish =
        layout map { _.finish(seen, table) } getOrElse Results.Success(Catenable(()))

      val leftFinish = (left.finish(seen, table) && wsFinish) map {
        case (a, _) => a
      }

      leftFinish && right.finish(seen, table)
    }
  }

  final case class Union[+A](_left: () => Parser[A], _right: () => Parser[A]) extends Parser[A] {
    lazy val left = _left()
    lazy val right = _right()

    protected def _derive(line: Line, table: MemoTable): Parser[A] =
      left.derive(line, table) | right.derive(line, table)

    protected def _finish(seen: Set[ParserId[_]], table: MemoTable) =
      left.finish(seen, table) || right.finish(seen, table)
  }

  final case class Apply[A, +B](target: Parser[A], f: (List[Line], Catenable[A]) => Catenable[B], lines: Vector[Line] = Vector.empty) extends Parser[B] {
    nullableMemo = target.nullableMemo

    override def map[C](f2: B => C): Parser[C] =
      Apply(target, { (lines, as: Catenable[A]) => f(lines, as) map f2 }, lines)

    override def mapWithLines[C](f2: (List[Line], B) => C): Parser[C] =
      Apply(target, { (lines, a: Catenable[A]) => f(lines, a) map { f2(lines, _) } }, lines)

    protected def _derive(line: Line, table: MemoTable): Parser[B] =
      Apply(target.derive(line, table), f, Line.addTo(lines, line))

    protected def _finish(seen: Set[ParserId[_]], table: MemoTable) =
      target.finish(seen, table) pmap { f(lines.toList, _) }
  }

  final case class Filter[A](target: Parser[A], p: A => Boolean) extends Parser[A] {
    nullableMemo = target.nullableMemo

    override def filter(p2: A => Boolean): Parser[A] =
      Filter(target, { a: A => p(a) && p2(a) })

    protected def _derive(line: Line, table: MemoTable) =
      Filter(target.derive(line, table), p)

    protected def _finish(seen: Set[ParserId[_]], table: MemoTable) =
      target.finish(seen, table) pmap { _ filter p }
  }

  final case class Literal(literal: String, offset: Int = 0) extends Parser[String] {
    require(literal.length > 0)
    require(offset < literal.length)

    nullableMemo = Nullable.False

    protected def _derive(line: Line, table: MemoTable): Parser[String] = {
      if (literal.charAt(offset) == line.head) {
        if (offset == literal.length - 1)
          Epsilon(literal)
        else
          Literal(literal, offset + 1)
      } else {
        Failure(ParseError.UnexpectedCharacter(line, Set(literal substring offset)) :: Nil)
      }
    }

    protected def _finish(seen: Set[ParserId[_]], table: MemoTable) =
      Results.Failure(ParseError.UnexpectedEOF(Set(literal substring offset)) :: Nil)
  }

  // note that regular expressions cannot cross line boundaries
  final case class Regex(r: SRegex) extends Parser[String] {
    require(!r.pattern.matcher("").matches)

    nullableMemo = Nullable.False

    protected def _derive(line: Line, table: MemoTable): Parser[String] = {
      val m = r findPrefixOf line.project

      val success = m map { lit =>
        if (lit.length == 1)
          Epsilon(lit)
        else
          Literal(lit, 1)
      }

      success getOrElse Failure(ParseError.UnexpectedCharacter(line, Set(r.toString)) :: Nil)
    }

    protected def _finish(seen: Set[ParserId[_]], table: MemoTable) =
      Results.Failure(ParseError.UnexpectedEOF(Set(r.toString)) :: Nil)
  }

  final case class Epsilon[+A](value: A) extends Parser[A] {
    nullableMemo = Nullable.True

    override def ^^^[B](b: B): Parser[B] = Epsilon(b)

    protected def _derive(line: Line, table: MemoTable): Parser[A] =
      Failure(ParseError.UnexpectedTrailingCharacters(line) :: Nil)

    protected def _finish(seen: Set[ParserId[_]], table: MemoTable) =
      Results.Success(Catenable(value))
  }

  final case class Failure(errors: List[ParseError]) extends Parser[Nothing] {
    nullableMemo = Nullable.True

    protected def _derive(line: Line, table: MemoTable): Parser[Nothing] = this

    protected def _finish(seen: Set[ParserId[_]], table: MemoTable) =
      Results.Failure(errors)
  }
}
