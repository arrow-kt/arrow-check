package arrow.check.pretty

import parsley.CharPredicate
import parsley.Parser
import parsley.alt
import parsley.attempt
import parsley.char
import parsley.choice
import parsley.compile
import parsley.eof
import parsley.filter
import parsley.followedBy
import parsley.followedByDiscard
import parsley.many
import parsley.map
import parsley.parseOrNull
import parsley.pure
import parsley.recursive
import parsley.satisfy
import parsley.stringOf
import parsley.zip
import pretty.Doc
import pretty.align
import pretty.doc
import pretty.encloseSep
import pretty.fillBreak
import pretty.flatAlt
import pretty.group
import pretty.lineBreak
import pretty.nil
import pretty.plus
import pretty.softLineBreak
import pretty.spaced
import pretty.symbols.comma
import pretty.symbols.lBracket
import pretty.symbols.lParen
import pretty.symbols.rBracket
import pretty.symbols.rParen
import pretty.symbols.space
import pretty.text
import kotlin.math.min

internal sealed class KValue {
  data class RawString(val s: String) : KValue()
  data class Decimal(val l: Long) : KValue()
  data class Rational(val r: Double) : KValue()
  data class KList(val vals: List<KValue>) : KValue()
  data class KTuple(val vals: List<KValue>) : KValue()
  data class Record(val name: String, val kv: List<Pair<String, KValue>>) : KValue()
  data class Cons(val name: String, val props: List<KValue>) : KValue()

  fun doc(): Doc<Nothing> = when (this) {
    is RawString -> s.doc()
    is Decimal -> l.doc()
    is Rational -> r.doc()
    is KList -> vals.map { it.doc() }.newLineList().align()
    is KTuple -> vals.map { it.doc() }.newLineTupled().align()
    is Record -> {
      val max = min(10, kv.maxByOrNull { it.first.length }?.first?.length ?: 0)
      name.text() +
        (kv.map { (k, v) ->
          (k.text().fillBreak(max).flatAlt(k.text()) spaced pretty.symbols.equals() spaced v.doc().align()).align()
        }).newLineTupled().align()
    }
    is Cons -> name.text() softLineBreak
      (props.map { it.doc().align() })
        .newLineTupled()
        .align()
  }

  private fun <A> List<Doc<A>>.newLineTupled() =
    // Special case this because here the highest layout for () is actually (\n\n) and in the worst case that will get chosen
    if (isEmpty()) lParen() + rParen()
    else encloseSep(
      lParen() + lineBreak() + (space() + space()).flatAlt(nil()),
      lineBreak() + rParen(),
      comma() + space()
    ).group()

  private fun <A> List<Doc<A>>.newLineList() =
    // Special case this because here the highest layout for [] is actually [\n\n] and in the worst case that will get chosen
    if (isEmpty()) lBracket() + rBracket()
    else encloseSep(
      lBracket() + lineBreak() + (space() + space()).flatAlt(nil()),
      lineBreak() + rBracket(),
      comma() + space()
    ).group()

  companion object
}

/**
 * Uses [Show] to turn the value into a string, then parse it into a generic format and prettyprint.
 *
 * The result can then be rendered out.
 *
 * @param SA Optional [Show] instance, default uses [Any.toString].
 */
public fun <A> A.showPretty(SA: (A) -> String = { it.toString() }): Doc<Nothing> {
  val str = SA(this)
  return (rootParser.parseOrNull(str.toCharArray()) ?: KValue.RawString(str))
    .doc().group()
}

internal typealias CharParser<A> = Parser<Char, Nothing, A>

internal fun outputParser(): CharParser<KValue> =
  valueParser { true }.followedByDiscard(Parser.eof())

internal fun valueParser(pred: CharPredicate): CharParser<KValue> =
  Parser.choice(
    // Parser.recursive { listParser() }, TODO Debug sine this breaks parsley...
    Parser.recursive { recordParser() }.attempt(),
    Parser.recursive { tupleParser().map { KValue.KTuple(it) } }.attempt(),
    Parser.recursive { consParser() }.attempt(),
    decimal().attempt(),
    double().attempt(),
    stringValueParser(pred)
  )

private val listValueParser = valueParser { it != ',' && it != ']' }

internal fun listParser(): CharParser<KValue> = Parser.run {
  listValueParser
    .withSeparator(',')
    .between('[', ']')
    .map { KValue.KList(it) }
}

internal fun <A> CharParser<A>.withSeparator(c: Char): CharParser<List<A>> =
  this.zip(Parser.char(c).followedBy(this).many()) { x, xs ->
    listOf(x) + xs
  }

// TODO Why can I not use kotlin.text.isLetter here?!
private fun Char.isLetter(): Boolean =
  this in 'a'..'z' || this in 'A'..'Z'

internal fun consParser(): CharParser<KValue> = Parser.run {
  satisfy { it != '(' && it.isLetter() }.many()
    .zip(tupleParser()) { name, props -> KValue.Cons(name, props) }
}

private val tupleValueParser = valueParser { it != ',' && it != ')' }

internal fun tupleParser(): CharParser<List<KValue>> = Parser.run {
  tupleValueParser
    .withSeparator(',')
    .between('(', ')')
}

internal fun recordParser(): CharParser<KValue> = Parser.run {
  satisfy { it != '(' && it.isLetter() }.many()
    .zip(propertyParser().withSeparator(',').between('(', ')')) { name, props ->
      KValue.Record(name, props)
    }
}

private val propertyValueParser = valueParser { it != ',' && it != ')' }

internal fun propertyParser(): CharParser<Pair<String, KValue>> = Parser.run {
  satisfy { c: Char -> c != '=' && c.isLetter() }.many()
    .followedByDiscard(char('='))
    .zip(propertyValueParser)
}

internal fun <A> CharParser<A>.between(start: Char, end: Char): CharParser<A> =
  Parser.run { char(start).followedBy(this@between).followedByDiscard(char(end)) }

internal fun stringValueParser(pred: CharPredicate): CharParser<KValue> =
  Parser.run { satisfy(p = pred).many().map { KValue.RawString(it) } }

internal fun decimal(): CharParser<KValue> = Parser.run {
  char('+').alt(char('-')).alt(pure(null))
    .followedBy(satisfy { it in '0'..'9' }.many())
    .stringOf()
    .filter { it.isNotEmpty() }
    .map { KValue.Decimal(it.toLong()) }
}

internal fun double(): CharParser<KValue> = Parser.run {
  char('+').alt(char('-')).alt(pure(null))
    .followedBy(satisfy { it in '0'..'9' || it == '.' }.many())
    .stringOf()
    .filter { it.isNotEmpty() }
    .map { KValue.Rational(it.toDouble()) }
}

internal val rootParser = outputParser().compile()
