package arrow.check.property

import arrow.Kind
import arrow.check.pretty.ValueDiffF
import arrow.check.pretty.diff
import arrow.check.pretty.showPretty
import arrow.check.pretty.toDoc
import arrow.core.*
import arrow.core.extensions.id.applicative.applicative
import arrow.core.extensions.id.eq.eq
import arrow.core.extensions.list.foldable.combineAll
import arrow.typeclasses.Applicative
import arrow.typeclasses.Eq
import arrow.typeclasses.Show
import pretty.*

interface Test {
    fun writeLog(log: JournalEntry): Unit

    fun failWith(msg: Doc<Markup>): Nothing

    // Move when we can have multiple receivers...
    fun <A> A.eqv(other: A, EQA: Eq<A> = Eq.any(), SA: Show<A> = Show.any()): Unit =
        diff(this, other, SA) { a, b -> EQA.run { a.eqv(b) } }

    fun <A> A.neqv(other: A, EQA: Eq<A> = Eq.any(), SA: Show<A> = Show.any()): Unit =
        diff(this, other, SA) { a, b -> EQA.run { a.neqv(b) } }

    suspend fun <A, B> A.roundtrip(
        encode: suspend (A) -> B,
        decode: suspend (B) -> A,
        EQ: Eq<A> = Eq.any(),
        SA: Show<A> = Show.any(),
        SB: Show<B> = Show.any()
    ): Unit = this.roundtrip(
        encode,
        { Id(decode(it)) },
        Id.applicative(),
        Id.eq(EQ) as Eq<Kind<ForId, A>>,
        Show {
            SA.run { value().show() }
        }, SB
    )

    suspend fun <F, A, B> A.roundtrip(
        encode: suspend (A) -> B,
        decode: suspend (B) -> Kind<F, A>,
        AP: Applicative<F>,
        EQF: Eq<Kind<F, A>> = Eq.any(),
        SFA: Show<Kind<F, A>> = Show.any(),
        SB: Show<B> = Show.any()
    ): Unit {
        val fa = AP.just(this@roundtrip)
        val intermediate = encode(this@roundtrip)
        val decoded = decode(intermediate)

        if (EQF.run { fa.eqv(decoded) }) succeeded()
        else failWith(
            SFA.run {
                val diff = fa.show().diff(decoded.show())

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

fun Test.failWith(msg: String): Nothing = failWith(msg.doc())

fun Test.annotate(msg: () -> Doc<Markup>): Unit =
    writeLog(JournalEntry.Annotate(msg))

fun Test.footnote(msg: () -> Doc<Markup>): Unit =
    writeLog(JournalEntry.Footnote(msg))

fun Test.cover(p: Double, name: String, bool: Boolean): Unit =
    writeLog(JournalEntry.JournalLabel(Label(null, LabelName(name), CoverPercentage(p), bool)))

fun Test.classify(name: String, bool: Boolean): Unit =
    cover(0.0, name, bool)

fun Test.label(name: String): Unit =
    cover(0.0, name, true)

fun <A> Test.collect(a: A, SA: Show<A> = Show.any()): Unit =
    cover(0.0, SA.run { a.show() }, true)

fun Test.coverTable(table: String, p: Double, name: String, bool: Boolean): Unit =
    writeLog(JournalEntry.JournalLabel(Label(LabelTable(table), LabelName(name), CoverPercentage(p), bool)))

fun Test.tabulate(table: String, name: String): Unit =
    coverTable(table, 0.0, name, true)

fun Test.failException(e: Throwable): Nothing =
    failWith(
        ("━━━ Failed: (Exception) ━━━".text() + hardLine() + e.toString().doc())
    )

fun Test.failure(): Nothing = failWith(nil())

// Sometimes useful in combinators to explicitly show success like in assert
fun Test.succeeded(): Unit = Unit

fun Test.assert(b: Boolean): Unit =
    if (b) succeeded() else failure()

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

fun Log.coverage(): Coverage<CoverCount> =
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
