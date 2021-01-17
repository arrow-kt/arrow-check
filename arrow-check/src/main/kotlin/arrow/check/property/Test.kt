package arrow.check.property

import arrow.check.pretty.ValueDiffF
import arrow.check.pretty.diff
import arrow.check.pretty.showPretty
import arrow.check.pretty.toDoc
import arrow.core.extensions.list.foldable.combineAll
import arrow.typeclasses.Eq
import arrow.typeclasses.Show
import pretty.Doc
import pretty.annotate
import pretty.doc
import pretty.hardLine
import pretty.nil
import pretty.plus
import pretty.text

/**
 * The test interface allows basic unit test functionality.
 *
 * - [writeLog] allows annotating the test with [JournalEntry]'s.
 * - [failWith] fails the unit test with a prettified [Doc] which may contain the reason for the failure.
 *
 * Upon this all other relevant combinators can be built and the [PropertyTest] interface merely expands
 *  the functionality by adding [PropertyTest.forAll] to the primitives.
 */
interface Test {
  /**
   * Append a [JournalEntry] to this tests log.
   */
  fun writeLog(log: JournalEntry): Unit

  /**
   * Fail a test with a prettified [Doc].
   */
  fun failWith(msg: Doc<Markup>): Nothing

  // TODO Move when we can have multiple receivers...
  /**
   * Test two values for equality and should they be unequal provide a pretty-printed diff.
   *
   * The diff should make spotting the inequality much easier, especially in larger datatypes.
   *
   * @param other Object to compare to.
   * @param EQA Optional equality instance, default uses [Any.equals].
   * @param SA Optional show instance, default uses [Any.toString].
   *
   * @see neqv To test for inequality
   * @see diff As a more lower level operator
   */
  fun <A> A.eqv(other: A, EQA: Eq<A> = Eq.any(), SA: Show<A> = Show.any()): Unit =
    diff(this, other, SA) { a, b -> EQA.run { a.eqv(b) } }

  /**
   * Test two values for inequality and should they be equal provide a pretty-printed diff.
   *
   * The diff may seem useless here, however that entirely depends on the way equality is implemented.
   *  Two elements expected to be different may also be different and yet fail this test if the
   *  equality test itself is wrong.
   *
   * @param other Object to compare to.
   * @param EQA Optional equality instance, default uses [Any.equals].
   * @param SA Optional show instance, default uses [Any.toString].
   *
   * @see eqv To test for equality
   * @see diff As a more lower level operator
   */
  fun <A> A.neqv(other: A, EQA: Eq<A> = Eq.any(), SA: Show<A> = Show.any()): Unit =
    diff(this, other, SA) { a, b -> EQA.run { a.neqv(b) } }

  /**
   * Test if a pair of [encode]/[decode] end up at the same value again.
   *
   * Offers much nicer failure output than directly testing this.
   *
   * @param EQ Optional equality instance, default uses [Any.equals].
   * @param SA Optional show instance for the initial value, default uses [Any.toString].
   * @param SB Optional show instance for the encoded value, default uses [Any.toString].
   */
  suspend fun <A, B> A.roundtrip(
    encode: suspend (A) -> B,
    decode: suspend (B) -> A,
    EQ: Eq<A> = Eq.any(),
    SA: Show<A> = Show.any(),
    SB: Show<B> = Show.any()
  ): Unit {
    val intermediate = encode(this@roundtrip)
    val decoded = decode(intermediate)

    if (EQ.run { this@roundtrip.eqv(decoded) }) succeeded()
    else failWith(
      SA.run {
        val diff = this@roundtrip.show().diff(decoded.show())

        "━━━ Intermediate ━━━".text() + hardLine() +
          intermediate.showPretty(SB) + hardLine() +
          "━━━ Failed (".text() +
          "- Original".text().annotate(Markup.DiffRemoved(0)) +
          " =/= ".text() +
          "+ Roundtrip".text().annotate(Markup.DiffAdded(0)) +
          ") ━━━".text() +
          hardLine() + diff.toDoc()
      }
    )
  }
}

/**
 * Fail a test with a [String] rather than a [Doc]. Offered only for convenience.
 */
fun Test.failWith(msg: String): Nothing = failWith(msg.doc())

/**
 * Append an annotation to the test.
 *
 * This is lazy to only incur the sometimes larger cost of rendering data when a failed test is rendered.
 */
fun Test.annotate(msg: () -> Doc<Markup>): Unit =
  writeLog(JournalEntry.Annotate(msg))

/**
 * Append a footnote to the test.
 *
 * This is lazy to only incur the sometimes larger cost of rendering data when a failed test is rendered.
 */
fun Test.footnote(msg: () -> Doc<Markup>): Unit =
  writeLog(JournalEntry.Footnote(msg))

/**
 * Fail a test with an exception and display that exception.
 */
fun Test.failException(e: Throwable): Nothing =
  failWith(
    ("━━━ Failed: (Exception) ━━━".text() + hardLine() + e.toString().doc())
  )

/**
 * Fail a test without an explicit failure message.
 */
fun Test.failure(): Nothing = failWith(nil())

/**
 * Constant function which returns [Unit]
 *
 * Sometimes useful in combinators to explicitly show success like in assert
 */
fun Test.succeeded(): Unit = Unit

/**
 * Assert that a specific condition holds, otherwise fail the test.
 *
 * @param b Condition
 * @param msg Lazy message to use should [b] be false.
 */
fun Test.assert(b: Boolean, msg: () -> Doc<Markup> = { nil() }): Unit =
  if (b) succeeded() else failWith(msg())

/**
 * Compare two elements using a comparison function and, should that function produce false, create
 *  a diff of the two values as the failure output.
 *
 * @param SA Optional show instance, default uses [Any.toString]
 * @param cmp Comparison function to which [a] and [other] will be passed.
 */
fun <A> Test.diff(a: A, other: A, SA: Show<A> = Show.any(), cmp: (A, A) -> Boolean): Unit =
  if (cmp(a, other)) succeeded()
  else failWith(SA.run {
    val diff = a.show().diff(other.show())

    when (diff.unDiff) {
      is ValueDiffF.Same -> "━━━ Failed (no differences) ━━━".text() +
        hardLine() + diff.toDoc()
      else -> {
        // Not sure if this overloading of Markup.Result.Failed is good, but for now it works
        "━━━ Failed (".text() +
          "- lhs".text().annotate(Markup.DiffRemoved(0)) +
          " =/= ".text() +
          "+ rhs".text().annotate(Markup.DiffAdded(0)) +
          ") ━━━".text() +
          hardLine() + diff.toDoc()
      }
    }
  })

internal fun Log.coverage(): Coverage<CoverCount> =
  unLog.filterIsInstance<JournalEntry.JournalLabel>().map {
    val l = it.label
    Coverage(
      mapOf(
        l.table to mapOf(
          l.name to Label(
            l.table,
            l.name,
            l.min,
            if (l.annotation) CoverCount(1) else CoverCount(0)
          )
        )
      )
    )
  }.combineAll(Coverage.monoid(CoverCount.semigroup()))
