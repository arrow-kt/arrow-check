package arrow.check.laws

import arrow.Kind
import arrow.check.UseColor
import arrow.check.gen.Gen
import arrow.check.gen.GenT
import arrow.check.gen.coarbitrary
import arrow.check.gen.func
import arrow.check.gen.generalize
import arrow.check.gen.monadGen
import arrow.check.gen.toFunction
import arrow.check.property.Failure
import arrow.check.property.ForTestT
import arrow.check.property.JournalEntry
import arrow.check.property.Label
import arrow.check.property.LabelTable
import arrow.check.property.Log
import arrow.check.property.MonadTest
import arrow.check.property.Property
import arrow.check.property.TestT
import arrow.check.property.TestTPartialOf
import arrow.check.property.fix
import arrow.check.property.instances.monad
import arrow.check.property.monoid
import arrow.check.property.property
import arrow.check.render
import arrow.core.ForId
import arrow.core.Id
import arrow.core.ListK
import arrow.core.Option
import arrow.core.Tuple2
import arrow.core.extensions.eq
import arrow.core.extensions.id.monad.monad
import arrow.core.extensions.listk.eq.eq
import arrow.core.extensions.option.eq.eq
import arrow.core.k
import arrow.core.left
import arrow.core.right
import arrow.core.toT
import arrow.mtl.EitherT
import arrow.mtl.EitherTPartialOf
import arrow.mtl.WriterT
import arrow.mtl.WriterTPartialOf
import arrow.mtl.extensions.eithert.eqK.eqK
import arrow.mtl.extensions.writert.eqK.eqK
import arrow.mtl.extensions.writert.monad.monad
import arrow.mtl.fix
import arrow.typeclasses.Eq
import arrow.typeclasses.EqK
import arrow.typeclasses.Monad
import pretty.doc

interface GenK<M, F> {
    fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<F, A>>
}

fun <M, F, L> EitherT.Companion.genK(
    MM: Monad<M>,
    MF: Monad<F>,
    genK: GenK<M, F>,
    genL: GenT<M, L>
): GenK<M, EitherTPartialOf<L, F>> =
    object : GenK<M, EitherTPartialOf<L, F>> {
        override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<EitherTPartialOf<L, F>, A>> = GenT.monadGen(MM) {
            sized { s ->
                frequency(
                    3 toT genK.genK(genL).fromGenT().map {
                        MF.run { EitherT(it.map { it.left() }) }
                    },
                    s.unSize + 1 toT genK.genK(genA).fromGenT().map {
                        MF.run { EitherT(it.map { it.right() }) }
                    }
                )
            }
        }
    }

fun <M, F, W> WriterT.Companion.genK(
    MM: Monad<M>,
    MF: Monad<F>,
    genK: GenK<M, F>,
    genW: GenT<M, W>
): GenK<M, WriterTPartialOf<W, F>> =
    object : GenK<M, WriterTPartialOf<W, F>> {
        override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<WriterTPartialOf<W, F>, A>> =
            GenT.monadGen(MM) {
                mapN(genW, genK.genK(genA)) { (w, mf) -> WriterT(MF.run { mf.map { w toT it } }) }
            }
    }

fun <M> Id.Companion.genK(MM: Monad<M>): GenK<M, ForId> = object :
    GenK<M, ForId> {
    override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<ForId, A>> = GenT.monadGen(MM) {
        genA.fromGenT().map(::Id)
    }
}

fun <M> Log.Companion.gen(MM: Monad<M>): GenT<M, Log> = GenT.monadGen(MM) {
    just(Log(emptyList()))
}

fun failureGen(): Gen<Failure> = Gen.monadGen { ascii().string(0..100).map {
    Failure(
        it.doc()
    )
} }

fun <M, F> TestT.Companion.genK(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>): GenK<M, Kind<ForTestT, F>> =
    object : GenK<M, Kind<ForTestT, F>> {
        override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<Kind<ForTestT, F>, A>> =
            GenT.monadGen(MM) {
                EitherT.genK(
                    MM,
                    WriterT.monad(MF, Log.monoid()),
                    WriterT.genK(MM, MF, genK, Log.gen(MM)),
                    failureGen().generalize(MM)
                )
                    .genK(genA).map { TestT(it.fix()) }
            }
    }

// TODO move
fun Log.Companion.eq(): Eq<Log> = object : Eq<Log> {
    override fun Log.eqv(b: Log): Boolean = ListK.eq(
        JournalEntry.eq()).run {
        unLog.k().eqv(b.unLog.k())
    }
}

// TODO better instances?
fun JournalEntry.Companion.eq(): Eq<JournalEntry> = Eq { a, b ->
    when (a) {
        is JournalEntry.Input -> b is JournalEntry.Input && a.text().render(UseColor.DisableColor) == b.text().render(
            UseColor.DisableColor
        )
        is JournalEntry.Annotate -> b is JournalEntry.Annotate && a.text().render(UseColor.DisableColor) == b.text().render(
            UseColor.DisableColor
        )
        is JournalEntry.Footnote -> b is JournalEntry.Footnote && a.text().render(UseColor.DisableColor) == b.text().render(
            UseColor.DisableColor
        )
        is JournalEntry.JournalLabel -> b is JournalEntry.JournalLabel && Label.eq(Boolean.eq()).run { a.label.eqv(b.label) }
    }
}

fun <A> Label.Companion.eq(eqA: Eq<A>): Eq<Label<A>> = Eq { a, b ->
    a.min.unCoverPercentage == b.min.unCoverPercentage &&
            a.name.unLabelName == b.name.unLabelName &&
            Option.eq(Eq<LabelTable> { a, b -> a.unLabelTable == b.unLabelTable }).run { a.table.eqv(b.table) } &&
            eqA.run { a.annotation.eqv(b.annotation) }
}

fun failureEq(): Eq<Failure> = Eq { a, b ->
    a.unFailure.render(UseColor.DisableColor) == b.unFailure.render(UseColor.DisableColor)
}

fun <F> TestT.Companion.eqK(eqK: EqK<F>): EqK<TestTPartialOf<F>> = object : EqK<TestTPartialOf<F>> {
    override fun <A> Kind<TestTPartialOf<F>, A>.eqK(other: Kind<TestTPartialOf<F>, A>, EQ: Eq<A>): Boolean =
        EitherT.eqK(WriterT.eqK(eqK, Log.eq()),
            failureEq()
        ).liftEq(EQ).run {
            fix().runTestT.eqv(other.fix().runTestT)
        }
}

object MonadTestLaws {
    fun <F> laws(
        MT: MonadTest<F>,
        eqK: EqK<F>
    ): List<Tuple2<String, Property>> = listOf(
        "TestT.just(Id.monad(), x).liftTest() == just(x)" toT property {
            val x = forAll { int(0..100) }.bind()

            val lhs = MT.run { TestT.just(Id.monad(), x).liftTest() }

            lhs.eqv(MT.just(x), eqK.liftEq(Int.eq())).bind()
        },
        "x.flatMap(f).liftTest() == x.liftTest().flatMap { f(it).liftTest() }" toT property {
            val x = forAllT {
                TestT.genK(BM(), Id.monad(), Id.genK(BM()))
                    .genK(int(0..100).toGenT())
            }.bind()
            val f = forAllFn {
                TestT.genK(BM(), Id.monad(), Id.genK(BM()))
                    .genK(int(0..100).toGenT())
                    .toFunction(Int.func(), Int.coarbitrary())
            }.bind()

            val lhs = MT.run {
                TestT.monad(Id.monad()).run { x.flatMap(f) }.fix().liftTest()
            }
            val rhs = MT.run { x.fix().liftTest().flatMap { f(it).fix().liftTest() } }

            lhs.eqv(rhs, eqK.liftEq(Int.eq())).bind()
        }
    )
}
