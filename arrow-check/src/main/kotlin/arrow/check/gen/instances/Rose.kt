package arrow.check.gen.instances

import arrow.Kind
import arrow.Kind2
import arrow.check.gen.*
import arrow.check.gen.Rose.Companion.liftF
import arrow.check.gen.instances.rose.eq.eq
import arrow.check.gen.instances.rose.monadTrans.liftT
import arrow.check.gen.instances.rose.monadTrans.monadTrans
import arrow.check.gen.instances.rosef.eq.eq
import arrow.check.gen.instances.rosef.functor.functor
import arrow.core.*
import arrow.core.extensions.sequence.foldable.foldLeft
import arrow.core.extensions.sequence.foldable.foldRight
import arrow.core.extensions.sequence.traverse.traverse
import arrow.core.extensions.sequencek.eq.eq
import arrow.extension
import arrow.fx.IO
import arrow.fx.typeclasses.MonadIO
import arrow.mtl.typeclasses.*
import arrow.recursion.typeclasses.Birecursive
import arrow.typeclasses.*

@extension
interface RoseFFunctor<C> : Functor<RoseFPartialOf<C>> {
    override fun <A, B> Kind<RoseFPartialOf<C>, A>.map(f: (A) -> B): Kind<RoseFPartialOf<C>, B> =
        fix().map(f)
}

@extension
interface RoseFFoldable<C> : Foldable<RoseFPartialOf<C>> {
    override fun <A, B> Kind<RoseFPartialOf<C>, A>.foldLeft(b: B, f: (B, A) -> B): B =
        fix().shrunk.foldLeft(b, f)

    override fun <A, B> Kind<RoseFPartialOf<C>, A>.foldRight(lb: Eval<B>, f: (A, Eval<B>) -> Eval<B>): Eval<B> =
        fix().shrunk.foldRight(lb, f)
}

@extension
interface RoseFTraverse<C> : Traverse<RoseFPartialOf<C>>, RoseFFoldable<C> {
    override fun <G, A, B> Kind<RoseFPartialOf<C>, A>.traverse(
        AP: Applicative<G>,
        f: (A) -> Kind<G, B>
    ): Kind<G, Kind<RoseFPartialOf<C>, B>> = AP.run {
        fix().shrunk.traverse(AP, f).map {
            RoseF(fix().res, it.fix())
        }
    }
}

@extension
interface RoseFEq<A, C> : Eq<RoseF<A, C>> {
    fun EQA(): Eq<A>
    fun EQC(): Eq<C>
    override fun RoseF<A, C>.eqv(b: RoseF<A, C>): Boolean =
        EQA().run { res.eqv(b.res) } && SequenceK.eq(EQC()).run { shrunk.k().eqv(b.shrunk.k()) }
}

@extension
interface RoseFEqK<C> : EqK<RoseFPartialOf<C>> {
    fun EQC(): Eq<C>
    override fun <A> Kind<RoseFPartialOf<C>, A>.eqK(other: Kind<RoseFPartialOf<C>, A>, EQ: Eq<A>): Boolean =
        RoseF.eq(EQC(), EQ).run { fix().eqv(other.fix()) }
}

// --------------------- Rose extensions

@extension
interface RoseFunctor<M> : Functor<RosePartialOf<M>> {
    fun FM(): Functor<M>

    override fun <A, B> Kind<RosePartialOf<M>, A>.map(f: (A) -> B): Kind<RosePartialOf<M>, B> =
        fix().map(FM(), f)
}

@extension
interface RoseApplicative<M> : Applicative<RosePartialOf<M>> {
    fun MA(): Applicative<M>

    override fun <A> just(a: A): Kind<RosePartialOf<M>, A> =
        Rose.just(MA(), a)

    override fun <A, B> Kind<RosePartialOf<M>, A>.ap(ff: Kind<RosePartialOf<M>, (A) -> B>): Kind<RosePartialOf<M>, B> =
        fix().ap(MA(), ff.fix())

}

@extension
interface RoseMonad<M> : Monad<RosePartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A, B> Kind<RosePartialOf<M>, A>.flatMap(f: (A) -> Kind<RosePartialOf<M>, B>): Kind<RosePartialOf<M>, B> =
        fix().flatMap(MM()) { f(it).fix() }

    override fun <A> just(a: A): Kind<RosePartialOf<M>, A> =
        Rose.just(MM(), a)

    override fun <A, B> tailRecM(a: A, f: (A) -> Kind<RosePartialOf<M>, Either<A, B>>): Kind<RosePartialOf<M>, B> =
        Rose(
            MM().run {
                fun roseFRec(r: RoseF<Either<A, B>, Rose<M, Either<A, B>>>): Kind<M, RoseF<B, Rose<M, B>>> =
                    r.shrunk.map { Rose(it.runRose.flatMap(::roseFRec)) }.let { branches ->
                        r.res.fold({
                            tailRecM(RoseF(it, branches)) { (a, branches) ->
                                f(a).fix().runRose.map { (e, newBranches) ->
                                    e.fold({
                                        RoseF(it, branches + newBranches.map {
                                            Rose(it.runRose.flatMap(::roseFRec))
                                        }).left()
                                    }, {
                                        RoseF(it, branches).right()
                                    })
                                }
                            }
                        }, {
                            just(RoseF(it, branches))
                        })
                    }
                roseFRec(RoseF(a.left(), emptySequence()))
            }
        )
}

@extension
interface RoseAlternative<M> : Alternative<RosePartialOf<M>>,
    RoseApplicative<M> {
    fun AM(): Alternative<M>
    fun MM(): Monad<M>
    override fun MA(): Applicative<M> = MM()

    override fun <A> empty(): Kind<RosePartialOf<M>, A> = Rose.monadTrans().run { AM().empty<A>().liftT(MM()) }

    override fun <A> Kind<RosePartialOf<M>, A>.orElse(b: Kind<RosePartialOf<M>, A>): Kind<RosePartialOf<M>, A> =
        AM().run {
            Rose(fix().runRose.orElse(b.fix().runRose))
        }

    override fun <A> Kind<RosePartialOf<M>, A>.combineK(y: Kind<RosePartialOf<M>, A>): Kind<RosePartialOf<M>, A> =
        fix().orElse(y.fix())
}

@extension
interface RoseFunctorFilter<M> : FunctorFilter<RosePartialOf<M>>,
    RoseFunctor<M> {
    override fun FM(): Functor<M> = MM()
    fun MM(): Monad<M>
    fun AM(): Alternative<M>

    override fun <A, B> Kind<RosePartialOf<M>, A>.filterMap(f: (A) -> Option<B>): Kind<RosePartialOf<M>, B> =
        Rose(MM().fx.monad {
            val (x, xs) = fix().runRose.bind()
            f(x).fold({
                AM().empty<RoseF<B, Rose<M, B>>>()
                    .bind()
            }, { x1 ->
                RoseF(x1, xs.map { it.filterMap(f).fix() })
            })
        })
}

@extension
interface RoseMonadFilter<M> : MonadFilter<RosePartialOf<M>>,
    RoseFunctorFilter<M>, RoseMonad<M> {
    override fun AM(): Alternative<M>
    override fun MM(): Monad<M>

    override fun <A> empty(): Kind<RosePartialOf<M>, A> = Rose.monadTrans().run { AM().empty<A>().liftT(MM()) }
    override fun <A, B> Kind<RosePartialOf<M>, A>.map(f: (A) -> B): Kind<RosePartialOf<M>, B> =
        fix().map(MM(), f)

    override fun <A, B> Kind<RosePartialOf<M>, A>.filterMap(f: (A) -> Option<B>): Kind<RosePartialOf<M>, B> =
        Rose(MM().fx.monad {
            val (x, xs) = fix().runRose.bind()
            f(x).fold({
                AM().empty<RoseF<B, Rose<M, B>>>()
                    .bind()
            }, { x1 ->
                RoseF(x1, xs.map { it.filterMap(f).fix() })
            })
        })
}

@extension
interface RoseBirecursive<M, A>: Birecursive<Rose<M, A>, Nested<M, RoseFPartialOf<A>>> {
    fun MM(): Monad<M>
    override fun FF(): Functor<Nested<M, RoseFPartialOf<A>>> =
        ComposedFunctor(MM(), RoseF.functor())

    override fun Kind<Nested<M, RoseFPartialOf<A>>, Rose<M, A>>.embedT(): Rose<M, A> =
        Rose(MM().run { unnest().map { it.fix() } })

    override fun Rose<M, A>.projectT(): Kind<Nested<M, RoseFPartialOf<A>>, Rose<M, A>> =
        runRose.nest()
}

@extension
interface RoseMonadTrans : MonadTrans<ForRose> {
    override fun <G, A> Kind<G, A>.liftT(MF: Monad<G>): Kind2<ForRose, G, A> = liftF(MF, this)
}

@extension
interface RoseMonadIO<M> : MonadIO<RosePartialOf<M>>, RoseMonad<M> {
    override fun MM(): Monad<M> = MIO()
    fun MIO(): MonadIO<M>
    override fun <A> IO<A>.liftIO(): Kind<RosePartialOf<M>, A> = MIO().run {
        liftIO().liftT(this)
    }
}

@extension
interface RoseApplicativeError<M, E> : ApplicativeError<RosePartialOf<M>, E>, RoseApplicative<M> {
    override fun MA(): Applicative<M> = AE()
    fun AE(): ApplicativeError<M, E>

    override fun <A> raiseError(e: E): Kind<RosePartialOf<M>, A> = liftF(AE(), AE().raiseError(e))

    override fun <A> Kind<RosePartialOf<M>, A>.handleErrorWith(f: (E) -> Kind<RosePartialOf<M>, A>): Kind<RosePartialOf<M>, A> {
        fun RoseF<A, Rose<M, A>>.handleErrorRoseF(): RoseF<A, Rose<M, A>> = RoseF(res, shrunk.map { handleErrorWith(f).fix() })
        return AE().run {
            Rose(fix().runRose.handleErrorWith(f.andThen { it.fix().runRose }).map {
                it.handleErrorRoseF()
            })
        }
    }
}

@extension
interface RoseMonadError<M, E> : MonadError<RosePartialOf<M>, E>, RoseApplicativeError<M, E>, RoseMonad<M> {
    override fun AE(): ApplicativeError<M, E> = ME()
    override fun MM(): Monad<M> = ME()
    fun ME(): MonadError<M, E>

    override fun <A, B> Kind<RosePartialOf<M>, A>.ap(ff: Kind<RosePartialOf<M>, (A) -> B>): Kind<RosePartialOf<M>, B> =
        fix().ap(ME(), ff.fix())

    override fun <A> just(a: A): Kind<RosePartialOf<M>, A> = Rose.just(ME(), a)
}

@extension
interface RoseMonadReader<M, D> : MonadReader<RosePartialOf<M>, D>, RoseMonad<M> {
    override fun MM(): Monad<M> = MR()
    fun MR(): MonadReader<M, D>
    override fun ask(): Kind<RosePartialOf<M>, D> = liftF(MR(), MR().ask())
    override fun <A> Kind<RosePartialOf<M>, A>.local(f: (D) -> D): Kind<RosePartialOf<M>, A> =
        MR().run {
            Rose(fix().runRose.local(f).map {
                RoseF(it.res, it.shrunk.map { it.local(f).fix() })
            })
        }
}

@extension
interface RoseMonadWriter<M, W> : MonadWriter<RosePartialOf<M>, W>, RoseMonad<M> {
    override fun MM(): Monad<M> = MW()
    fun MW(): MonadWriter<M, W>
    override fun <A> Kind<RosePartialOf<M>, A>.listen(): Kind<RosePartialOf<M>, Tuple2<W, A>> =
        MW().run {
            Rose(fix().runRose.listen().map { (w, r) ->
                RoseF(w toT r.res, r.shrunk.map { it.listen().fix() })
            })
        }

    override fun <A> Kind<RosePartialOf<M>, Tuple2<(W) -> W, A>>.pass(): Kind<RosePartialOf<M>, A> =
        MW().run {
            Rose(fix().runRose.map {
                val (f, res) = it.res
                f toT RoseF(res, it.shrunk.map { it.pass().fix() })
            }.pass())
        }

    override fun <A> writer(aw: Tuple2<W, A>): Kind<RosePartialOf<M>, A> =
        MW().run { Rose(MW().writer(aw).map { RoseF(it, emptySequence<Rose<M, A>>()) }) }
}

@extension
interface RoseMonadState<M, S> : MonadState<RosePartialOf<M>, S>, RoseMonad<M> {
    override fun MM(): Monad<M> = MS()
    fun MS(): MonadState<M, S>
    override fun get(): Kind<RosePartialOf<M>, S> =
        MS().run { Rose(MS().get().map { RoseF(it, emptySequence<Rose<M, S>>()) }) }
    override fun set(s: S): Kind<RosePartialOf<M>, Unit> =
        MS().run { Rose(MS().set(s).map { RoseF(it, emptySequence<Rose<M, Unit>>()) }) }
}

@extension
interface RoseEq<M, A> : Eq<Rose<M, A>> {
    fun EQKM(): EqK<M>
    fun EQA(): Eq<A>
    override fun Rose<M, A>.eqv(b: Rose<M, A>): Boolean =
        EQKM().liftEq(RoseF.eq(EQA(), this@RoseEq)).run { runRose.eqv(b.runRose) }
}

@extension
interface RoseEqK<M> : EqK<RosePartialOf<M>> {
    fun EQKM(): EqK<M>
    override fun <A> Kind<RosePartialOf<M>, A>.eqK(other: Kind<RosePartialOf<M>, A>, EQ: Eq<A>): Boolean =
        Rose.eq(EQKM(), EQ).run { fix().eqv(other.fix()) }
}
