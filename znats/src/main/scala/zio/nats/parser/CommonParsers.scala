package zio.nats.parser

import fastparse._
import fastparse.NoWhitespace._

trait CommonParsers {
  def stringChars(c: Char): Boolean = c != '\"' && c != '\\'

  def wspace[_: P]: P[Unit]     = P(CharsWhileIn(" \t", 1))
  def space[_: P]: P[Unit]      = P(CharsWhileIn(" \t\r\n", 0))
  def digits[_: P]: P[Unit]     = P(CharsWhileIn("0-9"))
  def exponent[_: P]: P[Unit]   = P(CharIn("eE") ~ CharIn("+\\-").? ~ digits)
  def fractional[_: P]: P[Unit] = P("." ~ digits)
  def integral[_: P]: P[Unit]   = P("0" | CharIn("1-9") ~ digits.?)

  def number[_: P]: P[JS.Num] = P(CharIn("+\\-").? ~ integral ~ fractional.? ~ exponent.?).!.map(
    x => JS.Num(x.toDouble)
  )

  def intNumber[_: P]: P[Int] = P(CharIn("+\\-").? ~ integral).!.map(_.toInt)

  def `null`[_: P]: P[JS.Null.type]   = P("null").map(_ => JS.Null)
  def `false`[_: P]: P[JS.False.type] = P("false").map(_ => JS.False)
  def `true`[_: P]: P[JS.True.type]   = P("true").map(_ => JS.True)

  def hexDigit[_: P]: P[Unit]      = P(CharIn("0-9a-fA-F"))
  def unicodeEscape[_: P]: P[Unit] = P("u" ~ hexDigit ~ hexDigit ~ hexDigit ~ hexDigit)
  def escape[_: P]: P[Unit]        = P("\\" ~ (CharIn("\"/\\\\bfnrt") | unicodeEscape))

  def strChars[_: P]: P[Unit] = P(CharsWhile(stringChars))
  def string[_: P]: P[JS.Str] = P(space ~ "\"" ~/ (strChars | escape).rep.! ~ "\"").map(JS.Str)

  def singleQuotedStringChars(c: Char): Boolean = c != '\''
  def singleQuotedStr[_: P]: P[Unit]            = P(CharsWhile(singleQuotedStringChars))
  def singleQuotedString[_: P]: P[String]       = P(space ~ "'" ~/ singleQuotedStr.rep.! ~ "'")

  def array[_: P] =
    P("[" ~/ jsonExpr.rep(sep = ","./) ~ space ~ "]").map(JS.Arr(_: _*))

  def pair[_: P] = P(string.map(_.value) ~/ ":" ~/ jsonExpr)

  def obj[_: P] =
    P("{" ~/ pair.rep(sep = ","./) ~ space ~ "}").map(JS.Obj(_: _*))

  def jsonExpr[_: P]: P[JS.Val] = P(
    space ~ (obj | array | string | `true` | `false` | `null` | number) ~ space
  )
}
