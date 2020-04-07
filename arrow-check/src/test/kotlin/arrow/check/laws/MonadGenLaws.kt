package arrow.check.laws

import arrow.check.gen.GenT
import arrow.check.gen.GenTPartialOf
import arrow.check.gen.MonadGen
import arrow.check.gen.fix
import arrow.check.gen.monadGen
import arrow.check.property.Property
import arrow.check.property.property
import arrow.core.Tuple2
import arrow.core.extensions.eq
import arrow.core.toT
import arrow.fx.IO
import arrow.fx.IOPartialOf
import arrow.fx.extensions.io.monad.monad
import arrow.typeclasses.EqK

object MonadGenLaws {
    fun <M, B> laws(
        MG: MonadGen<M, B>,
        genKM: GenK<IOPartialOf<Nothing>, M>,
        genKGenT: GenK<IOPartialOf<Nothing>, GenTPartialOf<B>>,
        eqKM: EqK<M>,
        eqKGenT: EqK<GenTPartialOf<B>>
    ): List<Tuple2<String, Property>> = listOf(
        "x.toGenT().fromGenT() == x" toT property {
            val x = forAllT { genKM.genK<Int>(GenT.monadGen(IO.monad()) { int_(0..100) }) }.bind()

            val lhs = MG.run { x.toGenT().fromGenT() }

            lhs.eqv(x, eqKM.liftEq(Int.eq())).bind()
        },
        "x.fromGenT().toGenT() == x" toT property {
            val x = forAllT { genKGenT.genK<Int>(GenT.monadGen(IO.monad()) { int_(0..100) }) }.bind().fix()

            val lhs = MG.run { x.fromGenT().toGenT() }

            lhs.eqv(x, eqKGenT.liftEq(Int.eq())).bind()
        }
    )
}
