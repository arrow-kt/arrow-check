package arrow.check.gen

import arrow.Kind
import arrow.check.gen.instances.alternative
import arrow.check.gen.instances.applicative
import arrow.check.gen.instances.birecursive
import arrow.check.gen.instances.mFunctor
import arrow.check.gen.instances.monad
import arrow.check.gen.instances.monadFilter
import arrow.check.gen.instances.monadTrans
import arrow.check.property.Size
import arrow.core.AndThen
import arrow.core.Const
import arrow.core.Either
import arrow.core.Eval
import arrow.core.ForId
import arrow.core.FunctionK
import arrow.core.Id
import arrow.core.Ior
import arrow.core.NonEmptyList
import arrow.core.None
import arrow.core.Option
import arrow.core.SequenceK
import arrow.core.Some
import arrow.core.Tuple2
import arrow.core.Tuple3
import arrow.core.Validated
import arrow.core.extensions.fx
import arrow.core.extensions.id.monad.monad
import arrow.core.extensions.list.traverse.sequence
import arrow.core.extensions.list.traverse.traverse
import arrow.core.extensions.listk.functorFilter.filterMap
import arrow.core.fix
import arrow.core.identity
import arrow.core.k
import arrow.core.left
import arrow.core.right
import arrow.core.some
import arrow.core.toMap
import arrow.core.toOption
import arrow.core.toT
import arrow.core.value
import arrow.mtl.OptionT
import arrow.mtl.OptionTPartialOf
import arrow.mtl.extensions.optiont.alternative.alternative
import arrow.mtl.extensions.optiont.applicative.applicative
import arrow.mtl.extensions.optiont.functor.functor
import arrow.mtl.extensions.optiont.monad.monad
import arrow.mtl.extensions.optiont.monadTrans.monadTrans
import arrow.mtl.fix
import arrow.mtl.typeclasses.unnest
import arrow.mtl.value
import arrow.syntax.collections.tail
import arrow.typeclasses.Alternative
import arrow.typeclasses.Applicative
import arrow.typeclasses.Functor
import arrow.typeclasses.Monad
import arrow.typeclasses.MonadFilter
import arrow.typeclasses.MonadSyntax
import arrow.typeclasses.Show
import kotlin.random.Random

// @higherkind boilerplate
class ForGenT private constructor() {
    companion object
}
typealias GenTOf<M, A> = arrow.Kind<GenTPartialOf<M>, A>
typealias GenTPartialOf<M> = arrow.Kind<ForGenT, M>

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <M, A> GenTOf<M, A>.fix(): GenT<M, A> =
    this as GenT<M, A>

typealias Gen<A> = GenT<ForId, A>

/**
 * Datatype that modesl creation of a value based on a seed and a size parameter
 */
class GenT<M, A>(val runGen: (Tuple2<RandSeed, Size>) -> Rose<OptionTPartialOf<M>, A>) : GenTOf<M, A> {

    fun <B> genMap(MF: Functor<M>, f: (A) -> B): GenT<M, B> =
        GenT(AndThen(runGen).andThen { r -> r.map(OptionT.functor(MF), f) })

    /**
     * This breaks applicative monad laws because they now behave different, but that
     *  is essential to good shrinking results. And tbh since we assume sameness by just size and same distribution in
     *  monad laws as well, we could consider this equal as well.
     */
    fun <B> genAp(MA: Monad<M>, ff: GenT<M, (A) -> B>): GenT<M, B> =
        GenT(AndThen(::runGWithSize).andThen { (res, sizeAndSeed) ->
            Rose.applicative(OptionT.applicative(MA)).run {
                res.zipTree(OptionT.monad(MA), Eval.later { ff.runGen(sizeAndSeed) }).map { (a, f) -> f(a) }.fix()
            }
        })

    fun <B> genFlatMap(MM: Monad<M>, f: (A) -> GenT<M, B>): GenT<M, B> =
        GenT(AndThen(::runGWithSize).andThen { (res, sizeAndSeed) ->
            res.flatMap(OptionT.monad(MM)) { f(it).runGen(sizeAndSeed) }
        })

    fun <B> mapTree(f: (Rose<OptionTPartialOf<M>, A>) -> Rose<OptionTPartialOf<M>, B>): GenT<M, B> =
        GenT(AndThen(runGen).andThen(f))

    internal fun runGWithSize(sz: Tuple2<RandSeed, Size>): Tuple2<Rose<OptionTPartialOf<M>, A>, Tuple2<RandSeed, Size>> {
        val (l, r) = sz.a.split()
        return runGen(r toT sz.b) toT (l toT sz.b)
    }

    companion object {
        fun <M, A> just(MA: Monad<M>, a: A): GenT<M, A> = GenT {
            Rose.just(OptionT.applicative(MA), a)
        }

        fun <A> just(a: A): Gen<A> = just(Id.monad(), a)
    }
}

fun <M> GenT.Companion.monadGen(MM: Monad<M>): MonadGen<GenTPartialOf<M>, M> = object : MonadGen<GenTPartialOf<M>, M> {
    override fun BM(): Monad<M> = MM
    override fun MM(): Monad<GenTPartialOf<M>> = GenT.monad(MM)
    override fun <A> GenT<M, A>.fromGenT(): Kind<GenTPartialOf<M>, A> = this
    override fun <A> Kind<GenTPartialOf<M>, A>.toGenT(): GenT<M, A> = fix()
    override fun <A, B> Kind<GenTPartialOf<M>, A>.map(f: (A) -> B): Kind<GenTPartialOf<M>, B> =
        fix().genMap(BM(), f)

    override fun <A, B> Kind<GenTPartialOf<M>, A>.ap(ff: Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(BM(), ff.fix())

    override fun <A, B> Kind<GenTPartialOf<M>, A>.flatMap(f: (A) -> Kind<GenTPartialOf<M>, B>): Kind<GenTPartialOf<M>, B> =
        MM().run { flatMap(f) }

    override fun <A> just(a: A): Kind<GenTPartialOf<M>, A> = MM().just(a)
    override fun <A, B> tailRecM(a: A, f: (A) -> Kind<GenTPartialOf<M>, Either<A, B>>): Kind<GenTPartialOf<M>, B> =
        MM().tailRecM(a, f)
}

// for convenience and to not throw on non-arrow users
fun GenT.Companion.monadGen(): MonadGen<GenTPartialOf<ForId>, ForId> = object : MonadGen<GenTPartialOf<ForId>, ForId> {
    override fun BM(): Monad<ForId> = Id.monad()
    override fun MM(): Monad<GenTPartialOf<ForId>> = GenT.monad(Id.monad())
    override fun <A> GenT<ForId, A>.fromGenT(): Kind<GenTPartialOf<ForId>, A> = this
    override fun <A> Kind<GenTPartialOf<ForId>, A>.toGenT(): GenT<ForId, A> = fix()
    override fun <A, B> Kind<GenTPartialOf<ForId>, A>.map(f: (A) -> B): Kind<GenTPartialOf<ForId>, B> =
        fix().genMap(BM(), f)

    override fun <A, B> Kind<GenTPartialOf<ForId>, A>.ap(ff: Kind<GenTPartialOf<ForId>, (A) -> B>): Kind<GenTPartialOf<ForId>, B> =
        fix().genAp(BM(), ff.fix())

    override fun <A, B> Kind<GenTPartialOf<ForId>, A>.flatMap(f: (A) -> Kind<GenTPartialOf<ForId>, B>): Kind<GenTPartialOf<ForId>, B> =
        MM().run { flatMap(f) }

    override fun <A> just(a: A): Kind<GenTPartialOf<ForId>, A> = MM().just(a)
    override fun <A, B> tailRecM(
        a: A,
        f: (A) -> Kind<GenTPartialOf<ForId>, Either<A, B>>
    ): Kind<GenTPartialOf<ForId>, B> =
        MM().tailRecM(a, f)
}

fun <M, A> GenT.Companion.monadGen(MM: Monad<M>, f: MonadGen<GenTPartialOf<M>, M>.() -> GenTOf<M, A>): GenT<M, A> =
    monadGen(MM).f().fix()

fun <A> GenT.Companion.monadGen(f: MonadGen<GenTPartialOf<ForId>, ForId>.() -> GenTOf<ForId, A>): Gen<A> =
    monadGen().f().fix()

fun <M, A> Gen<A>.generalize(MM: Monad<M>): GenT<M, A> = GenT { (r, s) ->
    Rose.mFunctor().run {
        runGen(r toT s).hoist(
            OptionT.monad(Id.monad()),
            object : FunctionK<OptionTPartialOf<ForId>, OptionTPartialOf<M>> {
                override fun <A> invoke(fa: Kind<OptionTPartialOf<ForId>, A>): Kind<OptionTPartialOf<M>, A> =
                    OptionT(
                        MM.just(fa.fix().value().value())
                    )
            }).fix()
    }
}

interface MonadGen<M, B> : Monad<M>, MonadFilter<M>, Alternative<M> {
    fun MM(): Monad<M>
    fun BM(): Monad<B>

    fun optionTM() = OptionT.monad(BM())

    override fun <A> empty(): Kind<M, A> = GenT.alternative(BM()).empty<A>().fix().fromGenT()
    override fun <A> Kind<M, A>.orElse(b: Kind<M, A>): Kind<M, A> =
        GenT.alternative(BM()).run { toGenT().orElse(b.toGenT()) }.fix().fromGenT()

    fun <A> GenT<B, A>.fromGenT(): Kind<M, A>
    fun <A> Kind<M, A>.toGenT(): GenT<B, A>

    // generate values without shrinking
    fun <A> generate(f: (RandSeed, Size) -> A): Kind<M, A> = GenT { (r, s) ->
        Rose.just(optionTM(), f(r, s))
    }.fromGenT()

    // ------------ shrinking
    // add shrinking capabilities, retaining existing shrinks
    fun <A> Kind<M, A>.shrink(f: (A) -> Sequence<A>): Kind<M, A> = GenT { (r, s) ->
        toGenT().runGen(r toT s).expand(optionTM(), f)
    }.fromGenT()

    // throw away shrinking results
    fun <A> Kind<M, A>.prune(): Kind<M, A> = GenT { (r, s) ->
        toGenT().runGen(r toT s).prune(optionTM(), 0)
    }.fromGenT()

    // ----------- Size
    fun <A> sized(f: (Size) -> Kind<M, A>): Kind<M, A> = MM().run {
        generate { _, s -> s }.flatMap(f)
    }

    fun <A> Kind<M, A>.resize(i: Size): Kind<M, A> = scale { i }

    fun <A> Kind<M, A>.scale(f: (Size) -> Size): Kind<M, A> = GenT { (r, s) ->
        val newSize = f(s)
        if (newSize.unSize < 0) throw IllegalArgumentException("GenT.scaled. Negative size")
        else toGenT().runGen(r toT newSize)
    }.fromGenT()

    fun <A> Kind<M, A>.small(): Kind<M, A> = scale(::golden)

    fun golden(s: Size): Size = Size((s.unSize * 0.61803398875).toInt())

    // ------- integral numbers
    fun long(range: Range<Long>): Kind<M, Long> =
        long_(range).shrink { it.shrinkTowards(range.origin) }

    fun long_(range: Range<Long>): Kind<M, Long> =
        generate { randSeed, s ->
            val (min, max) = range.bounds(s)
            if (min == max) min
            else randSeed.nextLong(min, max).a
        }

    fun long(range: LongRange): Kind<M, Long> = long(Range.constant(range.first, range.last))

    fun long_(range: LongRange): Kind<M, Long> = long_(Range.constant(range.first, range.last))

    fun int(range: Range<Int>): Kind<M, Int> = MM().run {
        long(range.map { it.toLong() }).map { it.toInt() }
    }

    fun int_(range: Range<Int>): Kind<M, Int> = MM().run {
        long_(range.map { it.toLong() }).map { it.toInt() }
    }

    fun int(range: IntRange): Kind<M, Int> = int(Range.constant(range.first, range.last))

    fun int_(range: IntRange): Kind<M, Int> = int_(Range.constant(range.first, range.last))

    fun short(range: Range<Short>): Kind<M, Short> = MM().run {
        long(range.map { it.toLong() }).map { it.toShort() }
    }

    fun short_(range: Range<Short>): Kind<M, Short> = MM().run {
        long_(range.map { it.toLong() }).map { it.toShort() }
    }

    fun byte(range: Range<Byte>): Kind<M, Byte> = MM().run {
        long(range.map { it.toLong() }).map { it.toByte() }
    }

    fun byte_(range: Range<Byte>): Kind<M, Byte> = MM().run {
        long_(range.map { it.toLong() }).map { it.toByte() }
    }

    // floating point numbers
    fun double(range: Range<Double>): Kind<M, Double> =
        double_(range).shrink { it.shrinkTowards(range.origin) }

    fun double_(range: Range<Double>): Kind<M, Double> =
        generate { randSeed, s ->
            val (min, max) = range.bounds(s)
            if (min == max) min
            else randSeed.nextDouble(min, max).a
        }

    fun double(range: ClosedFloatingPointRange<Double>): Kind<M, Double> =
        double(Range.constant(range.start, range.endInclusive))

    fun double_(range: ClosedFloatingPointRange<Double>): Kind<M, Double> =
        double_(Range.constant(range.start, range.endInclusive))

    fun float(range: Range<Float>): Kind<M, Float> = MM().run {
        double(range.map { it.toDouble() }).map { it.toFloat() }
    }

    fun float_(range: Range<Float>): Kind<M, Float> = MM().run {
        double_(range.map { it.toDouble() }).map { it.toFloat() }
    }

    fun float(range: ClosedFloatingPointRange<Float>): Kind<M, Float> =
        float(Range.constant(range.start, range.endInclusive))

    fun float_(range: ClosedFloatingPointRange<Float>): Kind<M, Float> =
        float_(Range.constant(range.start, range.endInclusive))

    // boolean
    fun boolean(): Kind<M, Boolean> =
        boolean_().shrink { if (it) sequenceOf(false) else emptySequence() }

    fun boolean_(): Kind<M, Boolean> = generate { randSeed, _ ->
        randSeed.nextInt(0, 2).a != 0
    }

    // chars TODO Arrow codegen bug when trying to generate @extension versions of this
    fun char(range: Range<Char>): Kind<M, Char> = MM().run {
        long(range.map { it.toLong() }).map { it.toChar() }
    }

    fun char_(range: Range<Char>): Kind<M, Char> = MM().run {
        long_(range.map { it.toLong() }).map { it.toChar() }
    }

    fun char(range: CharRange): Kind<M, Char> = char(Range.constant(range.first, range.last))
    fun char_(range: CharRange): Kind<M, Char> = char_(Range.constant(range.first, range.last))

    fun binit(): Kind<M, Char> = char('0'..'1')

    fun octit(): Kind<M, Char> = char('0'..'7')

    fun digit(): Kind<M, Char> = char('0'..'9')

    fun hexit(): Kind<M, Char> = choice(digit(), char('a'..'f'), char('A'..'F'))

    fun lower(): Kind<M, Char> = char('a'..'z')

    fun upper(): Kind<M, Char> = char('A'..'Z')

    fun alpha(): Kind<M, Char> = choice(lower(), upper())

    fun alphaNum(): Kind<M, Char> = choice(lower(), upper(), digit())

    fun ascii(): Kind<M, Char> = MM().run {
        int(0..127).map { it.toChar() }
    }

    fun latin1(): Kind<M, Char> = MM().run {
        int(0..255).map { it.toChar() }
    }

    fun unicode(): Kind<M, Char> = MM().run {
        val s1 = (55296 toT int(0..55295).map { it.toChar() })
        val s2 = (8190 toT int(57344..65533).map { it.toChar() })
        val s3 = (1048576 toT int(65536..1114111).map { it.toChar() })
        frequency(s1, s2, s3)
    }

    fun unicodeAll(): Kind<M, Char> = char(Char.MIN_VALUE..Char.MAX_VALUE)

    fun Kind<M, Char>.string(range: Range<Int>): Kind<M, String> =
        MM().run { list(range).map { it.joinToString("") } }

    fun Kind<M, Char>.string(range: IntRange): Kind<M, String> =
        string(Range.constant(range.first, range.last))

    // combinators
    fun <A> constant(a: A): Kind<M, A> = MM().just(a)

    fun <A> element(vararg els: A): Kind<M, A> =
        if (els.isEmpty()) throw IllegalArgumentException("Gen.Element used with no arguments")
        else MM().fx.monad {
            val i = !int(Range.constant(0, els.size - 1))
            els[i]
        }

    fun <A> choice(vararg gens: Kind<M, A>): Kind<M, A> =
        if (gens.isEmpty()) throw IllegalArgumentException("Gen.Choice used with no arguments")
        else MM().fx.monad {
            val i = !int(Range.constant(0, gens.size))
            !gens[i]
        }

    fun <A> frequency(vararg gens: Tuple2<Int, Kind<M, A>>): Kind<M, A> =
        if (gens.isEmpty()) throw IllegalArgumentException("Gen.Frequency used with no arguments")
        else MM().fx.monad {
            val total = gens.map { it.a }.sum()
            val n = !int(Range.constant(0, total))
            !gens.toList().pick(n)
        }

    private fun <A> List<Tuple2<Int, A>>.pick(n: Int): A =
        if (isEmpty()) throw IllegalArgumentException("Gen.Frequency.Pick used with no arguments")
        else first().let { (k, el) ->
            if (n <= k) el
            else tail().pick(n - k)
        }

    fun <A> recursive(
        chooseFn: (List<Kind<M, A>>) -> Kind<M, A>,
        nonRec: List<Kind<M, A>>,
        rec: () -> List<Kind<M, A>>
    ): Kind<M, A> =
        sized { s ->
            if (s.unSize <= 1) chooseFn(nonRec)
            else chooseFn(nonRec + rec().map { it.small() })
        }

    fun <A> discard(): Kind<M, A> = GenT { _ ->
        Rose<OptionTPartialOf<B>, A>(OptionT.none(BM()))
    }.fromGenT()

    fun <A> Kind<M, A>.ensure(p: (A) -> Boolean): Kind<M, A> =
        MM().fx.monad {
            val x = !this@ensure
            if (p(x)) x
            else !discard<A>()
        }

    override fun <A, C> Kind<M, A>.filterMap(f: (A) -> Option<C>): Kind<M, C> {
        fun t(k: Int): Kind<M, C> =
            if (k > 100) discard()
            else fx {
                val (x, gen) = this@filterMap.scale { Size(2 * k + it.unSize) }.freeze().bind()
                f(x).fold({ t(k + 1).bind() }, {
                    gen.toGenT()
                        .mapTree {
                            Rose.monadFilter(OptionT.alternative(BM()), OptionT.monad(BM())).run { it.filterMap(f) }
                                .fix()
                        }
                        .fromGenT().bind()
                })
            }
        return t(0)
    }

    fun <A> Kind<M, A>.option(): Kind<M, Option<A>> = sized { n ->
        frequency(
            2 toT MM().just(None),
            1 + n.unSize toT MM().run { this@option.map { it.some() } }
        )
    }

    fun <A> Kind<M, A>.list(range: Range<Int>): Kind<M, List<A>> = sized { s ->
        MM().run {
            fx.monad {
                val n = !int_(range)
                !this@list.toGenT().mapTree {
                    Rose.just(OptionT.monad(BM()), it)
                }.fromGenT().replicateSafe(this@run, n)
            }.toGenT().mapTree { r ->
                Rose(
                    optionTM().run {
                        r.runRose.flatMap {
                            it.res
                                .traverse(OptionT.monad(BM())) { it.runRose }
                                .map {
                                    it.fix().asSequence().interleave(OptionT.monad(BM()))
                                }
                        }
                    }
                )
            }.fromGenT()
                .map { it.toList() }
                .ensure { it.size >= range.lowerBound(s) }
        }
    }

    // TODO arrow pr? This is actually traverse but the current traverse is not stacksafe for stackunsafe monads...
    fun <F, A> Kind<F, A>.replicateSafe(AP: Applicative<F>, n: Int): Kind<F, List<A>> =
        if (n <= 0) AP.just(emptyList())
        else (0..n).toList().foldRight(Eval.now(AP.just(emptyList<A>())) as Eval<Kind<F, List<A>>>) { _, acc ->
            acc.map { AP.run { this@replicateSafe.ap(it.map { xs -> { a: A -> listOf(a) + xs } }) } }
        }.value()

    // This resulted in a NoSuchMethodError so I copied these methods here...
    // TODO check from time to time to see if this persists
    fun <A> Sequence<A>.splits(): Sequence<Tuple3<Sequence<A>, A, Sequence<A>>> =
        firstOrNull().toOption().fold({
            emptySequence()
        }, { x ->
            sequenceOf(Tuple3(emptySequence<A>(), x, drop(1)))
                // flatMap for added laziness
                .flatMap {
                    sequenceOf(it) + drop(1).splits().map { (a, b, c) ->
                        Tuple3(sequenceOf(x) + a, b, c)
                    }
                }
        })

    fun <M, A> Sequence<RoseF<A, Rose<M, A>>>.dropOne(MM: Monad<M>): Sequence<Rose<M, Sequence<A>>> =
        SequenceK.fx {
            val (xs, _, zs) = !splits().k()
            Rose(MM.just((xs + zs).interleave(MM)))
        }

    fun <M, A> Sequence<RoseF<A, Rose<M, A>>>.shrinkOne(MM: Monad<M>): Sequence<Rose<M, Sequence<A>>> =
        SequenceK.fx {
            val (xs, y, zs) = !splits().k()
            val y1 = !y.shrunk.k()
            Rose(
                MM.run {
                    y1.runRose.map { (xs + sequenceOf(it) + zs).interleave(MM) }
                }
            )
        }

    fun <M, A> Sequence<RoseF<A, Rose<M, A>>>.interleave(MM: Monad<M>): RoseF<Sequence<A>, Rose<M, Sequence<A>>> =
        RoseF(
            this.map { it.res },
            dropOne(MM) + shrinkOne(MM)
        )
    // --------------

    fun <A> Kind<M, A>.list(range: IntRange): Kind<M, List<A>> = list(Range.constant(range.first, range.last))

    fun <A> Kind<M, A>.set(range: Range<Int>): Kind<M, Set<A>> = MM().run {
        map { it toT Unit }.map(range).map { it.keys }
    }

    fun <A> Kind<M, A>.set(range: IntRange): Kind<M, Set<A>> = set(Range.constant(range.first, range.last))

    fun <K, A> Kind<M, Tuple2<K, A>>.map(range: Range<Int>): Kind<M, Map<K, A>> = sized { s ->
        MM().run {
            fx.monad {
                val k = !int_(range)
                !this@map.uniqueByKey(k)
            }.shrink { it.shrink() }
                .flatMap { it.sequence(MM()).map { it.fix() } }
                .map { it.toMap() }
                .ensure { it.size >= range.lowerBound(s) }
        }
    }

    fun <K, A> Kind<M, Tuple2<K, A>>.map(range: IntRange): Kind<M, Map<K, A>> = map(Range.constant(range.first, range.last))

    fun <K, A> Kind<M, Tuple2<K, A>>.uniqueByKey(n: Int): Kind<M, List<Kind<M, Tuple2<K, A>>>> {
        fun go(k: Int, map: Map<K, Kind<M, Tuple2<K, A>>>): Kind<M, List<Kind<M, Tuple2<K, A>>>> =
            if (k > 100) discard()
            else freeze().replicate(n).flatMap {
                val res = (map + it.map { it.bimap({ it.a }, ::identity) }.toMap())
                if (res.size >= n) MM().just(res.values.toList())
                else go(k + 1, res)
            }
        return go(0, emptyMap())
    }

    // arrow combinators
    fun <L, R> either(l: Kind<M, L>, r: Kind<M, R>): Kind<M, Either<L, R>> = sized { sz ->
        frequency(
            2 toT l.map { it.left() },
            1 + sz.unSize toT r.map { it.right() }
        )
    }

    fun <E, A> validated(l: Kind<M, E>, r: Kind<M, A>): Kind<M, Validated<E, A>> =
        either(l, r).map { Validated.fromEither(it) }

    fun <L, R> ior(l: Kind<M, L>, r: Kind<M, R>): Kind<M, Ior<L, R>> = sized { sz ->
        frequency(
            2 toT l.map { Ior.Left(it) },
            1 + (sz.unSize / 2) toT mapN(l, r) { (l, r) -> Ior.Both(l, r) },
            1 + sz.unSize toT r.map { Ior.Right(it) }
        )
    }

    fun <A> Kind<M, A>.id(): Kind<M, Id<A>> = map(::Id)

    fun <A, T> Kind<M, A>.const(): Kind<M, Const<A, T>> = map(::Const)

    fun <A> Kind<M, A>.nonEmptyList(range: Range<Int>): Kind<M, NonEmptyList<A>> =
        list(range).filterMap { NonEmptyList.fromList(it) }

    fun <A> Kind<M, A>.nonEmptyList(range: IntRange): Kind<M, NonEmptyList<A>> =
        nonEmptyList(Range.constant(range.first, range.last))

    // subterms
    fun <A> Kind<M, A>.freeze(): Kind<M, Tuple2<A, Kind<M, A>>> =
        GenT { (r, s) ->
            Rose.monad(optionTM()).fx.monad {
                val mx = !OptionT.monadTrans().run { toGenT().runGen(r toT s).runRose.fix().value().liftT(BM()) }.let {
                    Rose.monadTrans().run { it.liftT(OptionT.monad(BM())) }
                }
                mx.fold({
                    !Rose.alternative(OptionT.alternative(BM()), OptionT.monad(BM())).empty<Tuple2<A, Kind<M, A>>>()
                }, {
                    (it.res toT GenT { _ ->
                        Rose(OptionT.monad(BM()).just(it))
                    }.fromGenT())
                })
            }.fix()
        }.fromGenT()

    // invariant: List size is never changed!
    fun <A> List<Kind<M, A>>.genSubterms(): Kind<M, Subterms<A>> =
        MM().run {
            traverse(MM()) { it.freeze().map { it.b } }
                .map { Subterms.All(it.fix()) }
                .shrink<Subterms<Kind<M, A>>> { it.shrinkSubterms() }
                .flatMap {
                    when (it) {
                        is Subterms.One -> it.a.map { Subterms.One(it) }
                        is Subterms.All -> it.l
                            .sequence(MM())
                            .map { Subterms.All(it.fix()) }
                    }
                }
        }

    // invariant: size list in f is always equal to the size of this
    fun <A> List<Kind<M, A>>.subtermList(f: (List<A>) -> Kind<M, A>): Kind<M, A> =
        MM().run {
            this@subtermList.genSubterms().flatMap { it.fromSubterms(MM(), f) }
        }

    fun <A> Kind<M, A>.subtermM(f: (A) -> Kind<M, A>): Kind<M, A> =
        listOf(this).subtermList { f(it.first()) }

    fun <A> Kind<M, A>.subterm(f: (A) -> A): Kind<M, A> =
        subtermM { MM().just(f(it)) }

    fun <A> subtermM2(g1: Kind<M, A>, g2: Kind<M, A>, f: (A, A) -> Kind<M, A>): Kind<M, A> =
        MM().run { listOf(g1, g2).genSubterms().flatMap { it.fromSubterms(MM()) { f(it[0], it[1]) } } }

    fun <A> subterm2(g1: Kind<M, A>, g2: Kind<M, A>, f: (A, A) -> A): Kind<M, A> =
        subtermM2(g1, g2) { a1, a2 -> MM().just(f(a1, a2)) }

    fun <A> subtermM3(g1: Kind<M, A>, g2: Kind<M, A>, g3: Kind<M, A>, f: (A, A, A) -> Kind<M, A>): Kind<M, A> =
        MM().run { listOf(g1, g2, g3).genSubterms().flatMap { it.fromSubterms(MM()) { f(it[0], it[1], it[2]) } } }

    fun <A> subterm3(g1: Kind<M, A>, g2: Kind<M, A>, g3: Kind<M, A>, f: (A, A, A) -> A): Kind<M, A> =
        subtermM3(g1, g2, g3) { a1, a2, a3 -> MM().just(f(a1, a2, a3)) }

    // permutations
    fun <A> List<A>.subsequence(): Kind<M, List<A>> =
        MM().run {
            traverse(MM()) { a ->
                boolean_().map { if (it) a.some() else None }
            }.map { it.filterMap(::identity) as List<A> }
                .shrink { it.shrink() }
        }

    // typeclass overwrites for easier use
    fun <A> fx(f: suspend MonadSyntax<M>.() -> A): Kind<M, A> =
        MM().fx.monad(f)
}

sealed class Subterms<A> {
    data class One<A>(val a: A) : Subterms<A>()
    data class All<A>(val l: List<A>) : Subterms<A>()
}

fun <M, A> Subterms<A>.fromSubterms(AM: Applicative<M>, f: (List<A>) -> Kind<M, A>): Kind<M, A> = when (this) {
    is Subterms.One -> AM.just(a)
    is Subterms.All -> f(l)
}

fun <A> Subterms<A>.shrinkSubterms(): Sequence<Subterms<A>> = when (this) {
    is Subterms.One -> emptySequence()
    is Subterms.All -> l.asSequence().map { Subterms.One(it) }
}

// samples // No point for IO imo as these are debug only anyway
// if you need them in IO just wrap them in IO { ... }
fun <A> Gen<A>.sample(): A {
    tailrec fun loop(n: Int): A =
        if (n <= 0) throw IllegalStateException("Gen.Sample too many discards")
        else {
            val seed = RandSeed(Random.nextLong())
            val opt = this.runGen(seed toT Size(30)).runRose
                .value() // optionT
                .value() // id
            when (opt) {
                is None -> loop(n - 1)
                is Some -> opt.t.res
            }
        }
    return loop(100)
}

fun <A> Gen<A>.print(SA: Show<A> = Show.any()): Unit =
    printWith(Size(30), RandSeed(Random.nextLong()), SA)

fun <A> Gen<A>.printWith(size: Size, r: RandSeed, SA: Show<A> = Show.any()): Unit =
    this.runGen(r toT size).runRose
        .value() // optionT
        .value() // id
        .fold({
            println("Result:")
            println("<discard>")
        }, {
            println("Result:")
            println(SA.run { it.res.show() })
            println("Shrinks:")
            it.shrunk
                .forEach {
                    it.runRose
                        .value()
                        .value()
                        .map {
                            println(SA.run { it.res.show() })
                        }
                }
        })

fun <A> Gen<A>.printTree(SA: Show<A> = Show.any()): Unit =
    printTreeWith(Size(30), RandSeed(Random.nextLong()), SA)

// TODO I did write a prettyprinter library for propCheck so I should maybe use it? :)
//  Also implement this to render directly line by line so that infinite stuff still renders bit by bit
fun <A> Gen<A>.printTreeWith(size: Size, randSeed: RandSeed, SA: Show<A> = Show.any()) =
    runGen(randSeed toT size)
        .let {
            Rose.birecursive<OptionTPartialOf<ForId>, A>(OptionT.monad(Id.monad())).run {
                it.cata<List<String>> {
                    it.unnest()
                        .value()
                        .value()
                        .fold({
                            listOf("<discarded>")
                        }, { r ->
                            SA.run {
                                listOf(
                                    r.fix().res.show()
                                ) + r.fix().shrunk.toList()
                                    .let { l ->
                                        l.dropLast(1)
                                            .flatMap { ls ->
                                                when {
                                                    ls.isEmpty() -> emptyList()
                                                    else -> {
                                                        listOf(
                                                            "|-> ${ls.first()}"
                                                        ) + ls.tail()
                                                            .map { "|   $it" }
                                                    }
                                                }
                                            } + (
                                                if (l.isEmpty()) emptyList()
                                                else l.last().let { ls ->
                                                    when {
                                                        ls.isEmpty() -> emptyList()
                                                        else -> {
                                                            listOf(
                                                                "--> ${ls.first()}"
                                                            ) + ls.tail()
                                                                .map { "    $it" }
                                                        }
                                                    }
                                                })
                                    }
                            }
                        })
                }
            }
        }
        .joinToString("\n")
        .let(::println)
