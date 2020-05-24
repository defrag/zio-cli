package zio.cli

import java.nio.file.{ Path => JPath }
import java.time.{
  Instant => JInstant,
  LocalDate => JLocalDate,
  LocalDateTime => JLocalDateTime,
  LocalTime => JLocalTime,
  MonthDay => JMonthDay,
  OffsetDateTime => JOffsetDateTime,
  OffsetTime => JOffsetTime,
  Period => JPeriod,
  Year => JYear,
  YearMonth => JYearMonth,
  ZonedDateTime => JZonedDateTime,
  ZoneOffset => JZoneOffset,
  ZoneId => JZoneId
}

import zio.IO
import scala.collection.immutable.Nil

/**
 * A `Flag[A]` models a command-line flag that produces a value of type `A`.
 */
sealed trait Options[+A] { self =>

  def ::[That, A1 >: A](that: Options[That]): Options.Cons[That, A1] =
    Options.Cons(that, self)

  final def helpDoc: List[HelpDoc.Block] = ???

  final def requires[B](that: Options[B], suchThat: B => Boolean = (_: B) => true): Options[A] =
    Options.Requires(self, that, suchThat)

  final def requiresNot[B](that: Options[B], suchThat: B => Boolean = (_: B) => true): Options[A] =
    Options.RequiresNot(self, that, suchThat)

  def validate(args: List[String], opts: ParserOptions): IO[List[HelpDoc.Block], (List[String], A)]
}

object Options {
  // --verbose 3
  final case object Empty extends Options[Unit] {
    def validate(args: List[String], opts: ParserOptions): IO[List[HelpDoc.Block], (List[String], Unit)] = 
      IO.succeed(args -> ())
  }

  final case class Single[+A](
    name: String,
    aliases: Vector[String],
    optionType: Options.Type[A],
    description: Vector[String]
  ) extends Options[A] {
    import Options.Type._

    def ? : Options[Option[A]] = optional

    def ??(that: String): Single[A] = copy(description = description :+ that)

    def alias(name: String): Options[A] = copy(aliases = aliases :+ name)

    def aliases(names: String*): Options[A] = copy(aliases = aliases ++ names)

    def collect[B](message: String)(f: PartialFunction[A, B]): Options[B] =
      copy(optionType = Map(optionType, (a: A) => f.lift(a).fold[Either[String, B]](Left(message))(Right(_))))

    def optional: Options[Option[A]] = copy(optionType = Optional(optionType))

    def map[B](f: A => B): Options[B] = copy(optionType = Map(optionType, (a: A) => Right(f(a))))

    def mapTry[B](f: A => B): Options[B] =
      copy(optionType = Map(optionType, (a: A) => scala.util.Try(f(a)).toEither.left.map(_.getMessage)))

    def validate(args: List[String], opts: ParserOptions): IO[List[HelpDoc.Block], (List[String], A)] =
      optionType.validate(args, supportedOptions)

    private def supportedOptions = aliases :+ name
  }

  final case class Cons[A, B](left: Options[A], right: Options[B]) extends Options[(A, B)] {
    override def validate(args: List[String], opts: ParserOptions): IO[List[HelpDoc.Block], (List[String], (A, B))] =
      (for {
        tuple     <- left.validate(args, opts)
        (args, a) = tuple
        tuple     <- right.validate(args, opts)
        (args, b) = tuple
      } yield (args -> (a -> b))) orElse
        (for {
          tuple     <- right.validate(args, opts)
          (args, b) = tuple
          tuple     <- left.validate(args, opts)
          (args, a) = tuple
        } yield (args -> (a -> b)))
  }

  final case class Requires[A, B](options: Options[A], target: Options[B], predicate: B => Boolean) extends Options[A] {
    def validate(args: List[String], opts: ParserOptions): IO[List[HelpDoc.Block], (List[String], A)] = ???
  }

  final case class RequiresNot[A, B](options: Options[A], target: Options[B], predicate: B => Boolean) extends Options[A] {
    def validate(args: List[String], opts: ParserOptions): IO[List[HelpDoc.Block], (List[String], A)] = ???
  }

  sealed trait Type[+A] {
    def validate(args: List[String], supportedOptions: Vector[String]): IO[List[HelpDoc.Block], (List[String], A)]
  }
  object Type {

    final case class Toggle(negationName: Option[String], ifPresent: Boolean) extends Type[Boolean] {
      def validate(args: List[String], supportedOptions: Vector[String]): IO[List[HelpDoc.Block], (List[String], Boolean)] = ???
    }

    final case class Map[A, B](value: Type[A], f: A => Either[String, B]) extends Type[B] {
      def validate(args: List[String], supportedOptions: Vector[String]): IO[List[HelpDoc.Block], (List[String], B)] = 
        value.validate(args, supportedOptions).flatMap(r => f(r._2).fold(e => IO.fail(HelpDoc.Block.paragraph(e) :: Nil), s => IO.succeed(r._1 -> s)))
    }

    final case class Optional[A](value: Type[A]) extends Type[Option[A]] {
      def validate(args: List[String], supportedOptions: Vector[String]): IO[List[HelpDoc.Block], (List[String], Option[A])] = {
        def loop(args: List[String], fails: List[HelpDoc.Block], tally: List[String]): IO[List[HelpDoc.Block], (List[String], Option[A])] =
          args match {
            case head :: tail =>
              if (supportedOptions contains (head))
                value.validate(args, supportedOptions).map(r => r._1 -> Some(r._2))
              else loop(tail, fails, tally :+ head)
            case Nil => IO.succeed(tally -> None)
          }

        loop(args, Nil, Nil)
      }
    }

    final case class Primitive[A](primType: PrimType[A]) extends Type[A] {
      def validate(args: List[String], supportedOptions: Vector[String]): IO[List[HelpDoc.Block], (List[String], A)] = {
        def loop(args: List[String], fails: List[HelpDoc.Block], tally: List[String]): IO[List[HelpDoc.Block], (List[String], A)] =
          args match {
            case head :: tail =>
              if (supportedOptions contains (head))
                tail match {
                  case next :: remainder =>
                    primType
                      .validate(next)
                      .foldM(
                        e => loop(tail, HelpDoc.Block.paragraph(e) :: fails, tally),
                        a => IO.succeed(tally ++ remainder -> a)
                      )
                  case Nil => IO.fail(HelpDoc.Block.paragraph(s"Couldn't find value for option ${head}.") :: fails)
                }
              else loop(tail, fails, tally :+ head)
            case Nil =>
              IO.fail(HelpDoc.Block.paragraph(s"No options found!. Was expecting one of the following: ${supportedOptions.mkString(", ")}.") :: fails)
          }

        loop(args, Nil, Nil)
      }
    }
  }

  import Type._

  /**
   * Creates a boolean flag with the specified name, which, if present, will
   * produce the specified constant boolean value.
   */
  def bool(name: String, ifPresent: Boolean, negationName: Option[String] = None): Single[Boolean] =
    Single(name, Vector.empty, Type.Toggle(negationName, ifPresent), Vector.empty)

  def file(name: String, exists: Boolean): Single[JPath] =
    Single(name, Vector.empty, Primitive(PrimType.Path(PrimType.PathType.File, exists)), Vector.empty)

  def directory(name: String, exists: Boolean): Single[JPath] =
    Single(name, Vector.empty, Primitive(PrimType.Path(PrimType.PathType.Directory, exists)), Vector.empty)

  def text(name: String): Single[String] =
    Single(name, Vector.empty, Primitive(PrimType.Text), Vector.empty)

  def decimal(name: String): Single[BigDecimal] =
    Single(name, Vector.empty, Primitive(PrimType.Decimal), Vector.empty)

  def integer(name: String): Single[BigInt] =
    Single(name, Vector.empty, Primitive(PrimType.Integer), Vector.empty)

  def instant(name: String): Single[JInstant] =
    Single(name, Vector.empty, Primitive(PrimType.Instant), Vector.empty)

  def localDate(name: String): Single[JLocalDate] =
    Single(name, Vector.empty, Primitive(PrimType.LocalDate), Vector.empty)

  def localDateTime(name: String): Single[JLocalDateTime] =
    Single(name, Vector.empty, Primitive(PrimType.LocalDateTime), Vector.empty)

  def localTime(name: String): Single[JLocalTime] =
    Single(name, Vector.empty, Primitive(PrimType.LocalTime), Vector.empty)

  def monthDay(name: String): Single[JMonthDay] =
    Single(name, Vector.empty, Primitive(PrimType.MonthDay), Vector.empty)

  def offsetDateTime(name: String): Single[JOffsetDateTime] =
    Single(name, Vector.empty, Primitive(PrimType.OffsetDateTime), Vector.empty)

  def offsetTime(name: String): Single[JOffsetTime] =
    Single(name, Vector.empty, Primitive(PrimType.OffsetTime), Vector.empty)

  def period(name: String): Single[JPeriod] =
    Single(name, Vector.empty, Primitive(PrimType.Period), Vector.empty)

  def year(name: String): Single[JYear] =
    Single(name, Vector.empty, Primitive(PrimType.Year), Vector.empty)

  def yearMonth(name: String): Single[JYearMonth] =
    Single(name, Vector.empty, Primitive(PrimType.YearMonth), Vector.empty)

  def zonedDateTime(name: String): Single[JZonedDateTime] =
    Single(name, Vector.empty, Primitive(PrimType.ZonedDateTime), Vector.empty)

  def zoneId(name: String): Single[JZoneId] =
    Single(name, Vector.empty, Primitive(PrimType.ZoneId), Vector.empty)

  def zoneOffset(name: String): Single[JZoneOffset] =
    Single(name, Vector.empty, Primitive(PrimType.ZoneOffset), Vector.empty)

  val empty: Options[Unit] = Empty
}
