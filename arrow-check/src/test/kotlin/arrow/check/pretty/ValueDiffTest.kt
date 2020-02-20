package arrow.check.pretty

import arrow.test.UnitSpec
import arrow.test.generators.tuple2
import arrow.test.laws.BirecursiveLaws
import io.kotlintest.properties.Gen

class ValueDiffTest : UnitSpec() {
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
            )
        )
    }
}