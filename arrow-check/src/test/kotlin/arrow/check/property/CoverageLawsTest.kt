package arrow.check.property

import arrow.core.extensions.semigroup
import arrow.test.UnitSpec
import arrow.test.generators.option
import arrow.test.laws.MonoidLaws
import arrow.test.laws.SemigroupLaws
import arrow.typeclasses.Eq
import io.kotlintest.properties.Gen

class CoverageLawsTest : UnitSpec() {
    init {
        testLaws(
            /* Horrendously slow. Re-enable when the map gen is faster...
            MonoidLaws.laws(
                Coverage.monoid(Int.semigroup()),
                Gen.map(Gen.option(Gen.string().map { LabelTable(it) }), Gen.map(Gen.string().map { LabelName(it) }, Label.gen(Gen.int())))
                    .map { Coverage(it) },
                Eq.any() // Should be more than enough. Maybe add a good eq instance at some point later...
            )*/
        )
    }
}

class CoverCountLawsTest : UnitSpec() {
    init {
        testLaws(
            SemigroupLaws.laws(
                CoverCount.semigroup(),
                Gen.int().map { CoverCount(it) },
                Eq.any() // Sufficient here
            )
        )
    }
}