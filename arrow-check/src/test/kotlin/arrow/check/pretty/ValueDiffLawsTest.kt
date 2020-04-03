package arrow.check.pretty

import arrow.Kind
import arrow.core.extensions.eq
import arrow.core.test.UnitSpec
import arrow.core.test.generators.GenK
import arrow.core.test.generators.tuple2
import arrow.core.test.laws.EqLaws
import arrow.core.test.laws.FunctorLaws
import arrow.typeclasses.Eq
import arrow.typeclasses.EqK
import io.kotlintest.properties.Gen

class ValueDiffLawsTest : UnitSpec() {
    init {
        val genRandVal: Gen<Any> = Gen.oneOf(
            Gen.int(),
            Gen.string(),
            Gen.double(),
            Gen.list(Gen.int()),
            Gen.list(Gen.double()),
            Gen.list(Gen.string()),
            Gen.tuple2(Gen.int(), Gen.int())
        )

        val genDiff = Gen.bind(genRandVal, genRandVal) { a, b -> a.toString().diff(b.toString()) }

        testLaws(
            /* FIXME This takes too long.
            BirecursiveLaws.laws(
                ValueDiff.birecursive(),
                genDiff,
                ValueDiff.eq(),
                {
                    when (val dF = it.fix()) {
                        is ValueDiffF.Same -> 0
                        is ValueDiffF.ValueD -> 1
                        is ValueDiffF.Cons -> dF.props.sum()
                        is ValueDiffF.ListD -> dF.vals.sum()
                        is ValueDiffF.Record -> dF.props.map { it.b }.sum()
                        is ValueDiffF.TupleD -> dF.vals.sum()
                        is ValueDiffF.ValueDAdded -> 1
                        is ValueDiffF.ValueDRemoved -> 1
                    }
                }, {
                    if (it > 0) ValueDiffF.ListD(listOf(it - 1))
                    else ValueDiffF.Same(KValue.Decimal(it.toLong()))
                }
            ),*/
            EqLaws.laws(
                ValueDiff.eq(),
                genDiff
            )
        )
    }
}

class ValueDiffFLawsTest : UnitSpec() {
    init {
        fun <A> ValueDiffF.Companion.gen(gen: Gen<A>) = Gen.oneOf(
            Gen.bind(KValue.gen(), KValue.gen()) { l, r -> ValueDiffF.ValueD(l, r) },
            KValue.gen().map { ValueDiffF.ValueDRemoved(it) },
            KValue.gen().map { ValueDiffF.ValueDAdded(it) },
            Gen.list(gen).map { ValueDiffF.ListD(it) },
            Gen.list(gen).map { ValueDiffF.TupleD(it) },
            KValue.gen().map { ValueDiffF.Same(it) },
            Gen.bind(Gen.string(), Gen.list(gen)) { n, xs -> ValueDiffF.Cons(n, xs) },
            Gen.bind(Gen.string(), Gen.list(Gen.tuple2(Gen.string(), gen))) { n, xs -> ValueDiffF.Record(n, xs) }
        )

        testLaws(
            EqLaws.laws(ValueDiffF.eq(Int.eq()), ValueDiffF.gen(Gen.int())),
            FunctorLaws.laws(
                ValueDiffF.functor(),
                object : GenK<ForValueDiffF> {
                    override fun <A> genK(gen: Gen<A>): Gen<Kind<ForValueDiffF, A>> =
                        ValueDiffF.gen(gen) as Gen<Kind<ForValueDiffF, A>>
                },
                object : EqK<ForValueDiffF> {
                    override fun <A> Kind<ForValueDiffF, A>.eqK(other: Kind<ForValueDiffF, A>, EQ: Eq<A>): Boolean =
                        ValueDiffF.eq(EQ).run { fix().eqv(other.fix()) }
                }
            )
        )
    }
}
