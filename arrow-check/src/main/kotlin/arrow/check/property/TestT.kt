package arrow.check.property

import arrow.Kind
import arrow.check.pretty.ValueDiffF
import arrow.check.pretty.diff
import arrow.check.pretty.showPretty
import arrow.check.pretty.toDoc
import arrow.core.Either
import arrow.core.ForId
import arrow.core.Id
import arrow.core.ListK
import arrow.core.None
import arrow.core.Tuple2
import arrow.core.extensions.id.applicative.applicative
import arrow.core.extensions.id.eq.eq
import arrow.core.extensions.list.foldable.combineAll
import arrow.core.left
import arrow.core.right
import arrow.core.some
import arrow.core.toT
import arrow.core.value
import arrow.mtl.EitherT
import arrow.mtl.WriterT
import arrow.mtl.WriterTPartialOf
import arrow.mtl.extensions.writert.applicative.applicative
import arrow.mtl.extensions.writert.monad.monad
import arrow.mtl.value
import arrow.syntax.function.andThen
import arrow.typeclasses.Applicative
import arrow.typeclasses.Eq
import arrow.typeclasses.Monad
import arrow.typeclasses.Show
import pretty.Doc
import pretty.annotate
import pretty.doc
import pretty.hardLine
import pretty.nil
import pretty.plus
import pretty.text

// ---------------------------- TestT
typealias Test<A> = TestT<ForId, A>

// @higherkind boilerplate
class ForTestT private constructor() {
    companion object
}
typealias TestTOf<M, A> = arrow.Kind<TestTPartialOf<M>, A>
typealias TestTPartialOf<M> = arrow.Kind<ForTestT, M>

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <M, A> TestTOf<M, A>.fix(): TestT<M, A> =
    this as TestT<M, A>

data class TestT<M, A>(val runTestT: EitherT<Failure, WriterTPartialOf<Log, M>, A>) : TestTOf<M, A> {

    fun <B> map(MM: Monad<M>, f: (A) -> B): TestT<M, B> = TestT(runTestT.map(WriterT.monad(MM, Log.monoid()), f))

    fun <B> ap(MM: Monad<M>, ff: TestT<M, (A) -> B>): TestT<M, B> =
        TestT(runTestT.ap(WriterT.monad(MM, Log.monoid()), ff.runTestT))

    companion object {
        fun <M, A> just(MM: Monad<M>, a: A): TestT<M, A> =
            TestT(EitherT.just(WriterT.applicative(MM, Log.monoid()), a))
    }
}

// TODO refractor when https://github.com/arrow-kt/arrow/pull/1767 is merged
fun <M, A> Test<A>.hoist(MM: Monad<M>): TestT<M, A> = TestT(EitherT(WriterT(MM.just(runTestT.value().value().value()))))

fun <M, A> Kind<M, Tuple2<Log, Either<Failure, A>>>.toTestT(): TestT<M, A> = TestT(EitherT(WriterT(this)))

fun <A> Tuple2<Log, Either<Failure, A>>.toTest(): Test<A> = Id(this).toTestT()

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

interface MonadTest<M> : Monad<M> {

    fun <A> Test<A>.liftTest(): Kind<M, A>

    fun writeLog(l: JournalEntry): Kind<M, Unit> =
        (Log(ListK.just(l)) toT Unit.right()).toTest().liftTest()

    fun failWith(msg: String): Kind<M, Unit> = failWith(msg.doc())

    fun failWith(msg: Doc<Markup>): Kind<M, Unit> =
        (Log(ListK.empty()) toT Failure(msg).left())
            .toTest().liftTest()

    fun annotate(msg: () -> Doc<Markup>): Kind<M, Unit> =
        writeLog(JournalEntry.Annotate(msg))

    fun footnote(msg: () -> Doc<Markup>): Kind<M, Unit> =
        writeLog(JournalEntry.Footnote(msg))

    fun cover(p: Double, name: String, bool: Boolean): Kind<M, Unit> =
        writeLog(JournalEntry.JournalLabel(Label(None, LabelName(name), CoverPercentage(p), bool)))

    fun classify(name: String, bool: Boolean): Kind<M, Unit> =
        cover(0.0, name, bool)

    fun label(name: String): Kind<M, Unit> =
        cover(0.0, name, true)

    fun <A> A.collect(SA: Show<A> = Show.any()): Kind<M, Unit> =
        cover(0.0, SA.run { show() }, true)

    fun coverTable(table: String, p: Double, name: String, bool: Boolean): Kind<M, Unit> =
        writeLog(JournalEntry.JournalLabel(Label(LabelTable(table).some(), LabelName(name), CoverPercentage(p), bool)))

    fun tabulate(table: String, name: String): Kind<M, Unit> =
        coverTable(table, 0.0, name, true)

    fun failException(e: Throwable): Kind<M, Unit> =
        failWith(
            ("━━━ Failed: (Exception) ━━━".text() + hardLine() + e.toString().doc())
        )

    fun failure(): Kind<M, Unit> = failWith(nil())

    fun succeeded(): Kind<M, Unit> = just(Unit)

    fun assert(b: Boolean): Kind<M, Unit> =
        if (b) succeeded()
        else failure()

    fun <A> diff(a: A, other: A, SA: Show<A> = Show.any(), cmp: (A, A) -> Boolean): Kind<M, Unit> =
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

    fun <A> A.eqv(other: A, EQA: Eq<A> = Eq.any(), SA: Show<A> = Show.any()): Kind<M, Unit> =
        diff(this, other, SA) { a, b -> EQA.run { a.eqv(b) } }

    fun <A> A.neqv(other: A, EQA: Eq<A> = Eq.any(), SA: Show<A> = Show.any()): Kind<M, Unit> =
        diff(this, other, SA) { a, b -> EQA.run { a.neqv(b) } }

    fun <A, B> A.roundtrip(
        encode: (A) -> B,
        decode: (B) -> A,
        EQ: Eq<A> = Eq.any(),
        SA: Show<A> = Show.any(),
        SB: Show<B> = Show.any()
    ): Kind<M, Unit> = this.roundtrip(
        encode,
        decode.andThen(::Id),
        Id.applicative(),
        Id.eq(EQ) as Eq<Kind<ForId, A>>,
        Show {
            SA.run { value().show() }
        }, SB
    )

    fun <A, B> A.roundtripEffect(
        encode: (A) -> Kind<M, B>,
        decode: (B) -> Kind<M, A>,
        EQ: Eq<A> = Eq.any(),
        SA: Show<A> = Show.any(),
        SB: Show<B> = Show.any()
    ): Kind<M, Unit> = this.roundtripEffect(
        encode,
        decode.andThen { it.map(::Id) },
        Id.applicative(),
        Id.eq(EQ) as Eq<Kind<ForId, A>>,
        Show {
            SA.run { value().show() }
        }, SB
    )

    fun <F, A, B> A.roundtrip(
        encode: (A) -> B,
        decode: (B) -> Kind<F, A>,
        AP: Applicative<F>,
        EQF: Eq<Kind<F, A>> = Eq.any(),
        SFA: Show<Kind<F, A>> = Show.any(),
        SB: Show<B> = Show.any()
    ): Kind<M, Unit> = roundtripEffect(encode.andThen { just(it) }, decode.andThen { just(it) }, AP, EQF, SFA, SB)

    fun <F, A, B> A.roundtripEffect(
        encode: (A) -> Kind<M, B>,
        decode: (B) -> Kind<M, Kind<F, A>>,
        AP: Applicative<F>,
        EQF: Eq<Kind<F, A>> = Eq.any(),
        SFA: Show<Kind<F, A>> = Show.any(),
        SB: Show<B> = Show.any()
    ): Kind<M, Unit> = fx.monad {
        val fa = AP.just(this@roundtripEffect)
        val intermediate = encode(this@roundtripEffect).bind()
        val decoded = decode(intermediate).bind()

        if (EQF.run { fa.eqv(decoded) }) succeeded().bind()
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
        ).bind()
    }
}
