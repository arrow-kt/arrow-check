package arrow.check.gen.instances

import arrow.Kind
import arrow.Kind2
import arrow.check.gen.*
import arrow.core.AndThen
import arrow.core.FunctionK
import arrow.mtl.ForOptionT
import arrow.mtl.OptionT
import arrow.mtl.OptionTPartialOf
import arrow.mtl.extensions.OptionTMonad
import arrow.mtl.extensions.optiont.alternative.alternative
import arrow.mtl.extensions.optiont.monad.monad
import arrow.mtl.extensions.optiont.monadTrans.monadTrans
import arrow.mtl.fix
import arrow.mtl.typeclasses.MonadTrans
import arrow.typeclasses.Monad

// Pray for arrow meta to do those instance lookups in the future // this is a 4-5 lines per instance in haskell^^

/**
 * Try doing this for a monad without all these helpers^^ As verbose as it is, this is actually quite the nice solution.
 */
fun <M, B> OptionT.Companion.monadGen(MG: MonadGen<M, B>): MonadGen<OptionTPartialOf<M>, OptionTPartialOf<B>> =
    object : OptionTMonadGen<M, B> {
        override fun MG(): MonadGen<M, B> = MG
    }

interface OptionTMonadGen<M, B> : MonadGen<OptionTPartialOf<M>, OptionTPartialOf<B>>, OptionTMonad<M> {
    fun MG(): MonadGen<M, B>

    override fun MF(): Monad<M> = MG().MM()
    override fun BM(): Monad<OptionTPartialOf<B>> = OptionT.monad(MG().BM())
    override fun MM(): Monad<OptionTPartialOf<M>> = OptionT.monad(MG().MM())

    override fun <A> GenT<OptionTPartialOf<B>, A>.fromGenT(): Kind<OptionTPartialOf<M>, A> = GenT.monadTransDistributive().run {
        distributeT(OptionT.monadTrans(), OptionT.mFunctor(), MG().BM(), object : MonadTransDistributive.MonadFromMonadTrans<ForOptionT> {
            override fun <M> monad(MM: Monad<M>): Monad<Kind<ForOptionT, M>> = OptionT.monad(MM)
        }).fix().let {
            OptionT.mFunctor().run {
                it.hoist(GenT.monad(MG().BM()), object : FunctionK<GenTPartialOf<B>, M> {
                    override fun <A> invoke(fa: Kind<GenTPartialOf<B>, A>): Kind<M, A> = MG().run { fa.fix().fromGenT() }
                })
            }
        }
    }
    override fun <A> Kind<OptionTPartialOf<M>, A>.toGenT(): GenT<OptionTPartialOf<B>, A> = OptionT.mFunctor().run {
        hoist(MF(), object : FunctionK<M, GenTPartialOf<B>> {
            override fun <A> invoke(fa: Kind<M, A>): Kind<GenTPartialOf<B>, A> = MG().run { fa.toGenT() }
        }).let {
            OptionT.monadTransDistributive().run {
                it.distributeT(GenT.monadTrans(), GenT.mFunctor(), MG().BM(), object : MonadTransDistributive.MonadFromMonadTrans<ForGenT> {
                    override fun <M> monad(MM: Monad<M>): Monad<Kind<ForGenT, M>> = GenT.monad(MM)
                }).fix()
            }
        }
    }

    override fun <A> empty(): Kind<OptionTPartialOf<M>, A> = OptionT.alternative(MF()).empty()
    override fun <A> Kind<OptionTPartialOf<M>, A>.orElse(b: Kind<OptionTPartialOf<M>, A>): Kind<OptionTPartialOf<M>, A> =
        OptionT.alternative(MF()).run { fix().orElse(b) }
}

// TODO StateT etc as soon as they have MonadTrans instances!

// --------------------------- Utilities for implementing MonadGen which is by no means trivial
/**
 * Map over the inner monad rather than the contained type
 *
 * TODO This should probably be in another library...
 */
interface MFunctor<F> {
    fun <M, N, A> Kind2<F, M, A>.hoist(MM: Monad<M>, f: FunctionK<M, N>): Kind2<F, N, A>
}

// @extension
interface OptionTMFunctor : MFunctor<ForOptionT> {
    override fun <M, N, A> Kind2<ForOptionT, M, A>.hoist(MM: Monad<M>, f: FunctionK<M, N>): Kind2<ForOptionT, N, A> =
        OptionT(f(fix().value()))
}

fun OptionT.Companion.mFunctor(): MFunctor<ForOptionT> = object : OptionTMFunctor {}

// @extension
interface RoseMFunctor : MFunctor<ForRose> {
    override fun <M, N, A> Kind2<ForRose, M, A>.hoist(MM: Monad<M>, f: FunctionK<M, N>): Kind2<ForRose, N, A> =
        Rose(MM.run { f.invoke(fix().runRose.map { RoseF(it.res, it.shrunk.map { it.hoist(MM, f).fix() }) }) })
}

fun Rose.Companion.mFunctor(): MFunctor<ForRose> = object : RoseMFunctor {}

// @extension
interface GenTMFunctor : MFunctor<ForGenT> {
    override fun <M, N, A> Kind2<ForGenT, M, A>.hoist(MM: Monad<M>, f: FunctionK<M, N>): Kind2<ForGenT, N, A> =
        GenT(AndThen(fix().runGen).andThen {
            Rose.mFunctor().run {
                it.hoist(OptionT.monad(MM), object : FunctionK<OptionTPartialOf<M>, OptionTPartialOf<N>> {
                    override fun <A> invoke(fa: Kind<OptionTPartialOf<M>, A>): Kind<OptionTPartialOf<N>, A> =
                        OptionT.mFunctor().run { fa.fix().hoist(MM, f) }
                }).fix()
            }
        })
}

fun GenT.Companion.mFunctor(): MFunctor<ForGenT> = object : GenTMFunctor {}

// TODO add MFunctor instances for EitherT, WriterT, StateT, Kleisli, TestT, GenT as soon as the argument reorder is in

/**
 * Distribute a monad transformer through another monad.
 */
interface MonadTransDistributive<G> {

    /**
     * Distributing an effect through a structure is the dual of traverse. This just lifts that process to distributing
     *  a transformer instead of an effect.
     *
     * If you are trying to understand it I'd recommend looking at an instance and doing every step one by one and inspecting
     *  the type returned (by using something like .let {} to let intellij display it nicely.
     */
    fun <F, M, A> Kind2<G, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadFromMonadTrans<F>
    ): Kind2<F, Kind<G, M>, A>

    interface MonadFromMonadTrans<F> {
        fun <M> monad(MM: Monad<M>): Monad<Kind<F, M>>
    }
}

// @extension
interface OptionTMonadTransDistributive : MonadTransDistributive<ForOptionT> {
    override fun <F, M, A> Kind2<ForOptionT, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<ForOptionT, M>, A> =
        MFunc.run {
            fix().value().hoist(MM, object : FunctionK<M, OptionTPartialOf<M>> {
                override fun <A> invoke(fa: Kind<M, A>): Kind<OptionTPartialOf<M>, A> =
                    OptionT.monadTrans().run { fa.liftT(MM) }
            }).let {
                MT.monad(OptionT.monad(MM)).run {
                    it.flatMap { MTF.run { OptionT(MM.just(it)).liftT(OptionT.monad(MM)) } }
                }
            }
        }
}

fun OptionT.Companion.monadTransDistributive(): MonadTransDistributive<ForOptionT> = object : OptionTMonadTransDistributive {}

// @extension
interface RoseMonadTransDistributive : MonadTransDistributive<ForRose> {
    override fun <F, M, A> Kind2<ForRose, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<ForRose, M>, A> = MFunc.run {
        fix().runRose.hoist(MM, object : FunctionK<M, RosePartialOf<M>> {
            override fun <A> invoke(fa: Kind<M, A>): Kind<RosePartialOf<M>, A> = Rose.monadTrans().run { fa.liftT(MM) }
        }).let {
            MT.monad(Rose.monad(MM)).run {
                MTF.run {
                    it.flatMap {
                        Rose(
                            MM.just(
                                RoseF(
                                    MT.monad(Rose.monad(MM)).just(it.res),
                                    it.shrunk.map { Rose.just(MM, it.distributeT(MTF, MFunc, MM, MT)) })
                            )
                        ).liftT(Rose.monad(MM)).flatten()
                    }
                }
            }
        }
    }
}

fun Rose.Companion.monadTransDistributive(): MonadTransDistributive<ForRose> = object : RoseMonadTransDistributive {}

// @extension
interface GenTMonadTransDistributive : MonadTransDistributive<ForGenT> {

    override fun <F, M, A> Kind2<ForGenT, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<ForGenT, M>, A> = GenT(AndThen(fix().runGen).andThen {
        Rose.mFunctor().run {
            it.hoist(
                OptionT.monad(MT.monad(MM)),
                object : FunctionK<OptionTPartialOf<Kind<F, M>>, Kind<F, OptionTPartialOf<M>>> {
                    override fun <A> invoke(fa: Kind<OptionTPartialOf<Kind<F, M>>, A>): Kind<Kind<F, OptionTPartialOf<M>>, A> =
                        OptionT.monadTransDistributive().run {
                            fa.distributeT(MTF, MFunc, MM, MT)
                        }
                }).let {
                it.fix().let {

                }
                Rose.monadTransDistributive().run {
                    it.distributeT(
                        MTF,
                        MFunc,
                        OptionT.monad(MM),
                        MT
                    ).let {
                        MFunc.run {
                            it.hoist(
                                Rose.monad(OptionT.monad(MM)),
                                object : FunctionK<RosePartialOf<OptionTPartialOf<M>>, GenTPartialOf<M>> {
                                    override fun <A> invoke(fa: Kind<RosePartialOf<OptionTPartialOf<M>>, A>): Kind<GenTPartialOf<M>, A> =
                                        GenT { _ -> fa.fix() }
                                }).let {
                                Rose.just(OptionT.monad(MM), it)
                            }
                        }
                    }
                }
            }
        }
    }).let {
        MTF.run {
            MT.monad(GenT.monad(MM)).run {
                it.liftT(GenT.monad(MM)).flatten()
            }
        }
    }
}

fun GenT.Companion.monadTransDistributive(): MonadTransDistributive<ForGenT> = object : GenTMonadTransDistributive {}
