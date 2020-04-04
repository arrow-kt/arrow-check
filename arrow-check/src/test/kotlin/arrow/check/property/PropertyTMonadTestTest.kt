package arrow.check.property

import arrow.Kind
import arrow.check.PropertySpec
import arrow.check.gen.GenT
import arrow.check.gen.GenTPartialOf
import arrow.check.gen.RandSeed
import arrow.check.gen.instances.monad
import arrow.check.gen.instances.monadTrans
import arrow.check.property.instances.monadTest
import arrow.core.Id
import arrow.core.extensions.id.eqK.eqK
import arrow.core.extensions.id.monad.monad
import arrow.core.toT
import arrow.fx.IO
import arrow.fx.extensions.io.monad.monad
import arrow.typeclasses.Monad

fun <M, F> GenT.Companion.genK(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>): GenK<M, GenTPartialOf<F>> =
    object : GenK<M, GenTPartialOf<F>> {
        override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<GenTPartialOf<F>, A>> =
            genK.genK(genA).genMap(MM) { GenT.monadTrans().run { it.liftT(MF) } }
    }

fun <M, F> PropertyT.Companion.genK(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>): GenK<M, PropertyTPartialOf<F>> =
    object : GenK<M, PropertyTPartialOf<F>> {
        override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<PropertyTPartialOf<F>, A>> =
            TestT.genK(MM, GenT.monad(MF), GenT.genK(MM, MF, genK)).genK(genA).genMap(MM) { PropertyT(it.fix()) }
    }

class PropertyTMonadTestTest : PropertySpec({
    "MonadTest laws"(
        laws(
            PropertyT.monadTest(Id.monad()),
            PropertyT.genK(IO.monad(), Id.monad(), Id.genK(IO.monad())),
            PropertyT.eqK(Id.eqK(), RandSeed(0) toT Size(0))
        )
    )
})
