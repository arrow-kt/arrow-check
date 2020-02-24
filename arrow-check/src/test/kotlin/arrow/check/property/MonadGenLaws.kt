package arrow.check.property

import arrow.Kind
import arrow.Kind2
import arrow.check.gen.*
import arrow.check.property.instances.monad
import arrow.core.*
import arrow.core.extensions.eq
import arrow.core.extensions.id.monad.monad
import arrow.core.extensions.list.eq.eqv
import arrow.core.extensions.listk.eq.eq
import arrow.core.extensions.listk.eqK.eqK
import arrow.fx.ForIO
import arrow.mtl.*
import arrow.mtl.extensions.eithert.eqK.eqK
import arrow.mtl.extensions.writert.eqK.eqK
import arrow.mtl.extensions.writert.monad.monad
import arrow.typeclasses.Eq
import arrow.typeclasses.EqK
import arrow.typeclasses.Monad
import pretty.doc

interface GenK<M, F> {
    fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<F, A>>
}

interface GenK2<M, F> {
    fun <A, B> genK2(genA: GenT<M, A>, genB: GenT<M, B>): GenT<M, Kind2<F, A, B>>
}

fun <M, F, L> EitherT.Companion.genK(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>, genL: GenT<M, L>): GenK<M, EitherTPartialOf<F, L>> =
    object : GenK<M, EitherTPartialOf<F, L>> {
        override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<EitherTPartialOf<F, L>, A>> =
            EitherT.genK2(MM, MF, genK).genK2(genL, genA)
    }

fun <M, F> EitherT.Companion.genK2(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>): GenK2<M, Kind<ForEitherT, F>> =
    object : GenK2<M, Kind<ForEitherT, F>> {
        override fun <A, B> genK2(
            genA: GenT<M, A>,
            genB: GenT<M, B>
        ): GenT<M, Kind2<Kind<ForEitherT, F>, A, B>> = GenT.monadGen(MM) {
            sized { s ->
                frequency(
                    3 toT genK.genK(genA).fromGenT().map {
                        MF.run { EitherT(it.map { it.left() }) }
                    },
                    s.unSize + 1 toT genK.genK(genB).fromGenT().map {
                        MF.run { EitherT(it.map { it.right() }) }
                    }
                )
            }
        }
    }

fun <M, F, W> WriterT.Companion.genK(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>, genW: GenT<M, W>): GenK<M, WriterTPartialOf<F, W>> =
    object : GenK<M, WriterTPartialOf<F, W>> {
        override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<WriterTPartialOf<F, W>, A>> =
            WriterT.genK2(MM, MF, genK).genK2(genW, genA)
    }

fun <M, F> WriterT.Companion.genK2(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>): GenK2<M, Kind<ForWriterT, F>> =
    object : GenK2<M, Kind<ForWriterT, F>> {
        override fun <A, B> genK2(genA: GenT<M, A>, genB: GenT<M, B>): GenT<M, Kind2<Kind<ForWriterT, F>, A, B>> =
            GenT.monadGen(MM) {
                mapN(genA, genK.genK(genB)) { (w, mf) -> WriterT(MF.run { mf.map { w toT it } }) }
            }
    }

fun <M> Id.Companion.genK(MM: Monad<M>): GenK<M, ForId> = object : GenK<M, ForId> {
    override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<ForId, A>> = GenT.monadGen(MM) {
        genA.fromGenT().map(::Id)
    }
}

fun <M> Log.Companion.gen(MM: Monad<M>): GenT<M, Log> = GenT.monadGen(MM) {
    just(Log(emptyList()))
}

fun failureGen(): Gen<Failure> = Gen.monadGen { ascii().string(0..100).map { Failure(it.doc()) } }

fun <M, F> TestT.Companion.genK(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>): GenK<M, Kind<ForTestT, F>> = object : GenK<M, Kind<ForTestT, F>> {
    override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<Kind<ForTestT, F>, A>> =
        GenT.monadGen(MM) {
            EitherT.genK2(MM, WriterT.monad(MF, Log.monoid()), WriterT.genK(MM, MF, genK, Log.gen(MM)))
                .genK2(failureGen().generalize(MM), genA).map { TestT(it.fix()) }
        }
}

// TODO move
fun Log.Companion.eq(): Eq<Log> = object : Eq<Log> {
    override fun Log.eqv(b: Log): Boolean = ListK.eq(JournalEntry.eq()).run {
        unLog.k().eqv(b.unLog.k())
    }
}

// TODO better instances?
fun JournalEntry.Companion.eq(): Eq<JournalEntry> = Eq.any()
fun failureEq(): Eq<Failure> = Eq.any()

fun <F> TestT.Companion.eqK(eqK: EqK<F>): EqK<TestTPartialOf<F>> = object : EqK<TestTPartialOf<F>> {
    override fun <A> Kind<TestTPartialOf<F>, A>.eqK(other: Kind<TestTPartialOf<F>, A>, EQ: Eq<A>): Boolean =
        EitherT.eqK(WriterT.eqK(eqK, Log.eq()), failureEq()).liftEq(EQ).run {
            fix().runTestT.eqv(other.fix().runTestT)
        }
}

fun <F> laws(
    MT: MonadTest<F>,
    genK: GenK<ForIO, F>,
    eqK: EqK<F>
): List<Tuple2<String, Property>> = listOf(
    // lift test has no side effects
    "x.liftTest().followedBy(m) == m" toT property {
        val (x, m) = forAllT { tupledN(TestT.genK(BM(), Id.monad(), Id.genK(BM())).genK(int(0..100).toGenT()), genK.genK(int(0..100).toGenT())) }.bind()

        val lhs = MT.run { x.fix().liftTest().followedBy(m) }

        lhs.eqv(m, eqK.liftEq(Int.eq())).bind()
    },
    "TestT.just(Id.monad(), x).liftTest() == just(x)" toT property {
        val x = forAll { int(0..100) }.bind()

        val lhs = MT.run { TestT.just(Id.monad(), x).liftTest() }

        lhs.eqv(MT.just(x), eqK.liftEq(Int.eq())).bind()
    },
    "x.flatMap(f).liftTest() == x.liftTest().flatMap { f(it).liftTest() }" toT property {
        val x = forAllT { TestT.genK(BM(), Id.monad(), Id.genK(BM())).genK(int(0..100).toGenT()) }.bind()
        val f = forAllFn { TestT.genK(BM(), Id.monad(), Id.genK(BM())).genK(int(0..100).toGenT()).toFunction(Int.func(), Int.coarbitrary()) }.bind()

        val lhs = MT.run { TestT.monad(Id.monad()).run { x.flatMap(f) }.fix().liftTest() }
        val rhs = MT.run { x.fix().liftTest().flatMap { f(it).fix().liftTest() } }

        lhs.eqv(rhs, eqK.liftEq(Int.eq())).bind()
    }
)