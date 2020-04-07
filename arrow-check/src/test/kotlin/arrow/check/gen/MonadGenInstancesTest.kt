package arrow.check.gen

import arrow.Kind
import arrow.check.PropertySpec
import arrow.check.gen.instances.monad
import arrow.check.gen.instances.monadGen
import arrow.check.gen.instances.monadTrans
import arrow.check.laws.GenK
import arrow.check.laws.MonadGenLaws
import arrow.check.laws.genK
import arrow.check.property.Size
import arrow.core.ForId
import arrow.core.Id
import arrow.core.None
import arrow.core.extensions.eq
import arrow.core.extensions.id.eqK.eqK
import arrow.core.extensions.id.monad.monad
import arrow.core.extensions.monoid
import arrow.core.toT
import arrow.fx.IO
import arrow.fx.IOPartialOf
import arrow.fx.extensions.io.monad.monad
import arrow.mtl.EitherT
import arrow.mtl.EitherTPartialOf
import arrow.mtl.Kleisli
import arrow.mtl.KleisliPartialOf
import arrow.mtl.OptionT
import arrow.mtl.OptionTPartialOf
import arrow.mtl.WriterT
import arrow.mtl.extensions.eithert.eqK.eqK
import arrow.mtl.extensions.eithert.monad.monad
import arrow.mtl.extensions.optiont.eqK.eqK
import arrow.mtl.extensions.optiont.monad.monad
import arrow.mtl.extensions.writert.eqK.eqK
import arrow.mtl.extensions.writert.monad.monad
import arrow.typeclasses.Monad

fun <M, F> GenT.Companion.genK(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>): GenK<M, GenTPartialOf<F>> =
    object : GenK<M, GenTPartialOf<F>> {
        override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<GenTPartialOf<F>, A>> =
            genK.genK(genA).genMap(MM) { GenT.monadTrans().run { it.liftT(MF) } }
    }

fun <M, F> OptionT.Companion.genK(MM: Monad<M>, MF: Monad<F>, genK: GenK<M, F>): GenK<M, OptionTPartialOf<F>> =
    object : GenK<M, OptionTPartialOf<F>> {
        override fun <A> genK(genA: GenT<M, A>): GenT<M, Kind<OptionTPartialOf<F>, A>> = GenT.monadGen(MM) {
            sized { s ->
                frequency(
                    1 + s.unSize toT genK.genK(genA).map { OptionT.liftF(MF, it) },
                    2 toT genK.genK(genA).map { MF.run { OptionT(it.mapConst(None)) } }
                )
            }
        }
    }

// TODO Test this and the stateT version when I have a way of generalizing this to M
fun <F, D> Kleisli.Companion.genK(
    genK: GenK<ForId, F>,
    coarb: Coarbitrary<D>,
    func: Func<D>
): GenK<ForId, KleisliPartialOf<D, F>> =
    object : GenK<ForId, KleisliPartialOf<D, F>> {
        override fun <A> genK(genA: GenT<ForId, A>): GenT<ForId, Kind<KleisliPartialOf<D, F>, A>> =
            genK.genK(genA).toFunction(func, coarb).genMap(Id.monad()) { Kleisli(it.component1()) }
    }

class MonadGenInstancesTest : PropertySpec({
    val zeroSeed = (RandSeed(0) toT Size(0))

    "GenT: MonadGen laws"(
        MonadGenLaws.laws(
            GenT.monadGen(),
            GenT.genK(IO.monad(), Id.monad(), Id.genK(IO.monad())),
            GenT.genK(IO.monad(), Id.monad(), Id.genK(IO.monad())),
            GenT.eqK(Id.eqK(), zeroSeed), GenT.eqK(Id.eqK(), zeroSeed)
        )
    )

    "OptionT: MonadGen laws"(
        MonadGenLaws.laws(
            OptionT.monadGen(GenT.monadGen()),
            OptionT.genK(IO.monad(), GenT.monad(Id.monad()), GenT.genK(IO.monad(), Id.monad(), Id.genK(IO.monad()))),
            GenT.genK(IO.monad(), OptionT.monad(Id.monad()), OptionT.genK(IO.monad(), Id.monad(), Id.genK(IO.monad()))),
            OptionT.eqK(GenT.eqK(Id.eqK(), zeroSeed)),
            GenT.eqK(OptionT.eqK(Id.eqK()), zeroSeed)
        )
    )

    "EitherT: MonadGen laws"(
        MonadGenLaws.laws<EitherTPartialOf<String, GenTPartialOf<ForId>>, EitherTPartialOf<String, ForId>>(
            EitherT.monadGen(GenT.monadGen()),
            EitherT.genK(
                IO.monad(),
                GenT.monad(Id.monad()),
                GenT.genK(IO.monad(), Id.monad(), Id.genK(IO.monad())),
                GenT.monadGen(IO.monad()) { ascii().string(0..100) }
            ),
            GenT.genK(
                IO.monad(),
                EitherT.monad<String, ForId>(Id.monad()),
                EitherT.genK<IOPartialOf<Nothing>, ForId, String>(
                    IO.monad(),
                    Id.monad(),
                    Id.genK(IO.monad()),
                    GenT.monadGen(IO.monad()) { ascii().string(0..100) }
                )
            ),
            EitherT.eqK(GenT.eqK(Id.eqK(), zeroSeed), String.eq()),
            GenT.eqK(EitherT.eqK(Id.eqK(), String.eq()), zeroSeed)
        )
    )

    "WriterT: MonadGen laws"(
        MonadGenLaws.laws(
            WriterT.monadGen(GenT.monadGen(), String.monoid()),
            WriterT.genK<IOPartialOf<Nothing>, GenTPartialOf<ForId>, String>(
                IO.monad(),
                GenT.monad(Id.monad()),
                GenT.genK(IO.monad(), Id.monad(), Id.genK(IO.monad())),
                GenT.monadGen(IO.monad()) { ascii().string(0..100) }
            ),
            GenT.genK(
                IO.monad(),
                WriterT.monad(Id.monad(), String.monoid()),
                WriterT.genK<IOPartialOf<Nothing>, ForId, String>(
                    IO.monad(),
                    Id.monad(),
                    Id.genK(IO.monad()),
                    GenT.monadGen(IO.monad()) { ascii().string(0..100) }
                )
            ),
            WriterT.eqK(GenT.eqK(Id.eqK(), zeroSeed), String.eq()),
            GenT.eqK(WriterT.eqK(Id.eqK(), String.eq()), zeroSeed)
        )
    )
})
