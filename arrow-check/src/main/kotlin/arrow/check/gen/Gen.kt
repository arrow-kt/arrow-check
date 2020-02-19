package arrow.check.gen

import arrow.Kind
import arrow.check.gen.instances.*
import arrow.check.property.*
import arrow.core.*
import arrow.core.extensions.eval.monad.monad
import arrow.core.extensions.fx
import arrow.core.extensions.id.functor.functor
import arrow.core.extensions.id.monad.monad
import arrow.core.extensions.list.traverse.sequence
import arrow.core.extensions.list.traverse.traverse
import arrow.core.extensions.listk.functorFilter.filterMap
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
import arrow.typeclasses.*
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
 * Datatype that models creation of a value based on a seed and a size parameter. In general you should use this through
 *  the [MonadGen] instance, but it can sometimes be useful to work on this level.
 *
 * A [GenT] is a monad-transformer which given a [RandSeed] and [Size] generates a [Rose]-tree of values in the context [M].
 * This also makes it deterministic: Given the same [RandSeed] and [Size] you will always get the same [Rose]-tree of values.
 *
 * The [Rose] represents a generated value, at the root node, and 0 or more "smaller" shrunk values at the branches. Performing
 *  any operation over this datatype propagates these changes all the way, which means any invariants encoded (by filtering/mapping etc)
 *  also automatically apply for all shrunk values.
 *
 * Additionally the values at each node are optional (indicated by using [OptionT]). Missing values are later treated as discarded.
 */
class GenT<M, A>(val runGen: (Tuple2<RandSeed, Size>) -> Rose<OptionTPartialOf<M>, A>) : GenTOf<M, A> {

    fun <B> genMap(MF: Functor<M>, f: (A) -> B): GenT<M, B> =
        GenT(AndThen(runGen).andThen { r -> r.map(OptionT.functor(MF), f) })

    /**
     * This breaks applicative monad laws because they now behave different, but that
     *  is essential to good shrinking results. And tbh since we assume sameness by just size and same distribution in
     *  monad laws as well, we could consider this equal as well.
     */
    fun <B> genAp(MA: Monad<M>, ff: GenT<M, (A) -> B>): GenT<M, B> = GenT(AndThen(::runGWithSize).andThen { (res, sizeAndSeed) ->
        Rose.applicative(OptionT.applicative(MA)).run { res.zipTree(OptionT.applicative(MA)) { ff.runGen(sizeAndSeed) }.map { (a, f) -> f(a) }.fix() }
    })

    fun <B> genFlatMap(MM: Monad<M>, f: (A) -> GenT<M, B>): GenT<M, B> = GenT(AndThen(::runGWithSize).andThen { (res, sizeAndSeed) ->
        res.flatMap(OptionT.monad(MM)) { f(it).runGen(sizeAndSeed) }
    })

    /**
     * Apply a function to the [Rose]-tree that this generator produces
     */
    fun <B> mapTree(f: (Rose<OptionTPartialOf<M>, A>) -> Rose<OptionTPartialOf<M>, B>): GenT<M, B> = GenT(AndThen(runGen).andThen(f))

    /**
     * Used to implement tailRecM from [Monad].
     * Splits the [RandSeed], applies one to the current generator and returns the result together with the other split and size.
     */
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

/**
 * Obtain a [MonadGen] instance for [GenT] given any [Monad] [M].
 * If all you want is to define a single generator, take a look at the other [monadGen] overloads which take a constructor
 *  function that has access to the [MonadGen] instance through receiver scope.
 *
 * Manual implementation because @extension does not work properly.
 */
fun <M> GenT.Companion.monadGen(MM: Monad<M>): MonadGen<GenTPartialOf<M>, M> = object : MonadGen<GenTPartialOf<M>, M> {
    override fun BM(): Monad<M> = MM
    override fun MM(): Monad<GenTPartialOf<M>> = GenT.monad(MM)
    override fun <A> GenT<M, A>.fromGenT(): Kind<GenTPartialOf<M>, A> = this
    override fun <A> Kind<GenTPartialOf<M>, A>.toGenT(): GenT<M, A> = fix()
    override fun <A, B> Kind<GenTPartialOf<M>, A>.map(f: (A) -> B): Kind<GenTPartialOf<M>, B> =
        fix().genMap(BM(), f)

    override fun <A, B> Kind<GenTPartialOf<M>, A>.ap(ff: Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(BM(), ff.fix())

    override fun <A, B> Kind<GenTPartialOf<M>, A>.lazyAp(ff: () -> Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(BM(), ff().fix())

    override fun <A, B> Kind<GenTPartialOf<M>, A>.flatMap(f: (A) -> Kind<GenTPartialOf<M>, B>): Kind<GenTPartialOf<M>, B> =
        MM().run { flatMap(f) }

    override fun <A> just(a: A): Kind<GenTPartialOf<M>, A> = MM().just(a)
    override fun <A, B> tailRecM(a: A, f: (A) -> Kind<GenTPartialOf<M>, Either<A, B>>): Kind<GenTPartialOf<M>, B> =
        MM().tailRecM(a, f)

    override fun <A> empty(): Kind<GenTPartialOf<M>, A> = GenT.alternative(BM()).empty()
    override fun <A> Kind<GenTPartialOf<M>, A>.orElse(b: Kind<GenTPartialOf<M>, A>): Kind<GenTPartialOf<M>, A> =
        GenT.alternative(BM()).run {
            orElse(b.fix())
        }
}

/**
 * Obtain a [MonadGen] instance for [GenT] with the [Monad] fixed to [Id].
 *
 * Be aware that generators made with this instance are not stacksafe. It will work for almost anything that is non-recursive.
 *
 * To get a stacksafe variant just call [monadGen] and pass it a stacksafe [Monad] instance, like from [Eval] or [IO].
 * If you define a generator that is recursive or has long flatMap/ap chains consider making it polymorphic over [M] because
 *  then you can use it in [forAllT] and arrow-check will use a [Monad] which is stacksafe.
 */
fun GenT.Companion.monadGen(): MonadGen<GenTPartialOf<ForId>, ForId> = monadGen(Id.monad())

/**
 * Define a generator with receiver access to [MonadGen]. This is a useful shortcut over `monadGen(MM).run { ... }.fix()`
 */
fun <M, A> GenT.Companion.monadGen(MM: Monad<M>, f: MonadGen<GenTPartialOf<M>, M>.() -> GenTOf<M, A>): GenT<M, A> =
    monadGen(MM).f().fix()

/**
 * Define a generator with receiver access to [MonadGen]. This is a useful shortcut over `monadGen().run { ... }.fix()`.
 *
 * As this uses the shortcut for the [MonadGen] instance for [GenT] with [Id], the same caveats apply: Stacksafety is not
 *  guaranteed.
 */
fun <A> GenT.Companion.monadGen(f: MonadGen<GenTPartialOf<ForId>, ForId>.() -> GenTOf<ForId, A>): Gen<A> =
    monadGen().f().fix()

/**
 * Turn a [Gen] into a [GenT] for any [M]. This is mainly used to integrate generators with no effects with those that use
 *  effects. This never changes the behaviour of a generator.
 */
fun <M, A> Gen<A>.generalize(MM: Monad<M>): GenT<M, A> = GenT { (r, s) ->
    Rose.mFunctor().run {
        runGen(r toT s).hoist(OptionT.monad(Id.monad()), object : FunctionK<OptionTPartialOf<ForId>, OptionTPartialOf<M>> {
            override fun <A> invoke(fa: Kind<OptionTPartialOf<ForId>, A>): Kind<OptionTPartialOf<M>, A> =
                OptionT(
                    MM.just(fa.fix().value().value())
                )
        }).fix()
    }
}

/**
 * Typeclass which models generating values while in context [M].
 *
 * This is parameterized to both [M] and [B]. [M] refers to the [Monad] in which the generator is written, usually some
 *  variant of [GenT]. [B] refers to the inner [Monad] of [GenT]. This distinction arises from the necessity for some generators
 *  to operate on the [Rose]-tree directly which usually requires proof that the inner [Monad] is indeed a [Monad] (usually by parameter).
 *
 * There are only two methods one needs to define for a valid [MonadGen] instance: [fromGenT] and [toGenT]. This can be quite
 *  tricky for most types, especially monad-transformers, which is why there are several helpers defined [MFunctor] and [MonadTransDistributive].
 *
 * Using this typeclass happens implicitly for the most part because the [forAll] family of functions (and [monadGen]) provide it in the receiver scope
 *  making direct use if it mostly unnecessary.
 *
 * The one good usecase for using it directly is to define extension functions on it for custom generators:
 * ```kotlin
 * // This generator will also be in scope inside forAll and similar methods!
 * fun <M, B> MonadGen<M, B>.myGen(): Kind<M, Foo>
 * ```
 */
interface MonadGen<M, B> : Monad<M>, MonadFilter<M>, Alternative<M> {
    fun MM(): Monad<M>
    fun BM(): Monad<B>

    fun roseM() = OptionT.monad(BM())

    fun <A> GenT<B, A>.fromGenT(): Kind<M, A>
    fun <A> Kind<M, A>.toGenT(): GenT<B, A>

    /**
     * Create a generator from a constructor function that does not produce any shrink results.
     *
     * If you want a constant generator take a look at [constant] instead.
     * If you want to choose from a series of constants, take a look at [element].
     */
    fun <A> generate(f: (RandSeed, Size) -> A): Kind<M, A> = GenT { (r, s) ->
        Rose.just(roseM(), f(r, s))
    }.fromGenT()

    // ------------ shrinking

    /**
     * Extend the current [Rose]-tree by providing a function that from a value at a node produces more branches.
     * This is purely add on and does not remove existing branches.
     */
    fun <A> Kind<M, A>.shrink(f: (A) -> Sequence<A>): Kind<M, A> = GenT { (r, s) ->
        toGenT().runGen(r toT s).expand(roseM(), f)
    }.fromGenT()

    /**
     * Throw away branches from the [Rose]-tree to effectively disable shrinking from this generator
     */
    fun <A> Kind<M, A>.prune(): Kind<M, A> = GenT { (r, s) ->
        toGenT().runGen(r toT s).prune(roseM(), 0)
    }.fromGenT()

    // ----------- Size

    /**
     * Provide a generator with the current [Size]. The main use for this function is to generate the bounds of a [Range] and to
     *  handle termination with [recursive] (each iteration get's a smaller size until it falls below a threshold).
     *
     * ```kotlin
     * sized { s ->
     *   frequency(
     *     // increase the likelyhood of normal values as testing goes on
     *     1 + s toT int(Range.constant(0, -100, 100)),
     *     // throw in some outliers from time to time ^^
     *     3 toT elements(Int.MIN_VALUE, INT.MAX_VALUE, 0, -1, 1)
     *   )
     * }
     * ```
     */
    fun <A> sized(f: (Size) -> Kind<M, A>): Kind<M, A> = MM().run {
        generate { _, s -> s }.flatMap(f)
    }

    /**
     * Change the size of a generator
     */
    fun <A> Kind<M, A>.resize(i: Size): Kind<M, A> = scale { i }

    /**
     * Change the size of a generator by applying a function to the current size. This should never return in a
     *  negative [Size].
     *
     * TODO maybe that should be an invariant of [Size] itself rather than a failure condition here.
     */
    fun <A> Kind<M, A>.scale(f: (Size) -> Size): Kind<M, A> = GenT { (r, s) ->
        val newSize = f(s)
        if (newSize.unSize < 0) throw IllegalArgumentException("GenT.scaled. Negative size")
        else toGenT().runGen(r toT newSize)
    }.fromGenT()

    /**
     * Scale a generator by using the golden ratio
     */
    fun <A> Kind<M, A>.small(): Kind<M, A> = scale(::golden)

    fun golden(s: Size): Size = Size((s.unSize * 0.61803398875).toInt())

    // ------- integral numbers

    /**
     * Generate a [Long] in a given [Range] with shrinking.
     */
    fun long(range: Range<Long>): Kind<M, Long> = long_(range).shrink { it.shrinkTowards(range.origin) }

    /**
     * Generate a [Long] in a given [Range] without shrinking
     */
    fun long_(range: Range<Long>): Kind<M, Long> =
        generate { randSeed, s ->
            val (min, max) = range.bounds(s)
            if (min == max) min
            else randSeed.nextLong(min, max).a
        }

    /**
     * Generate a [Long] in a given [LongRange]. Will use the first and last elements of that range as constants.
     * This generator will shrink.
     */
    fun long(range: LongRange): Kind<M, Long> = long(Range.constant(range.first, range.last))

    /**
     * Generate a [Long] in a given [LongRange]. Will use the first and last elements of that range as constants.
     * This generator will not shrink.
     */
    fun long_(range: LongRange): Kind<M, Long> = long_(Range.constant(range.first, range.last))

    /**
     * Generate an [Int] in a given [Range] with shrinking.
     */
    fun int(range: Range<Int>): Kind<M, Int> = MM().run {
        long(range.map { it.toLong() }).map { it.toInt() }
    }

    /**
     * Generate an [Int] in a given [Range] without shrinking
     */
    fun int_(range: Range<Int>): Kind<M, Int> = MM().run {
        long_(range.map { it.toLong() }).map { it.toInt() }
    }

    /**
     * Generate a [Int] in a given [IntRange]. Will use the first and last elements of that range as constants.
     * This generator will shrink.
     */
    fun int(range: IntRange): Kind<M, Int> = int(Range.constant(range.first, range.last))

    /**
     * Generate a [Int] in a given [IntRange]. Will use the first and last elements of that range as constants.
     * This generator will not shrink.
     */
    fun int_(range: IntRange): Kind<M, Int> = int_(Range.constant(range.first, range.last))

    /**
     * Generate a [Short] in a given [Range] with shrinking.
     */
    fun short(range: Range<Short>): Kind<M, Short> = MM().run {
        long(range.map { it.toLong() }).map { it.toShort() }
    }

    /**
     * Generate a [Short] in a given [Range] without shrinking
     */
    fun short_(range: Range<Short>): Kind<M, Short> = MM().run {
        long_(range.map { it.toLong() }).map { it.toShort() }
    }

    /**
     * Generate a [Byte] in a given [Range] with shrinking.
     */
    fun byte(range: Range<Byte>): Kind<M, Byte> = MM().run {
        long(range.map { it.toLong() }).map { it.toByte() }
    }

    /**
     * Generate a [Byte] in a given [Range] without shrinking
     */
    fun byte_(range: Range<Byte>): Kind<M, Byte> = MM().run {
        long_(range.map { it.toLong() }).map { it.toByte() }
    }

    // floating point numbers

    /**
     * Generate a [Double] from a given [Range] with shrinking.
     */
    fun double(range: Range<Double>): Kind<M, Double> =
        double_(range).shrink { it.shrinkTowards(range.origin) }

    /**
     * Generate a [Double] from a given [Range] without shrinking.
     */
    fun double_(range: Range<Double>): Kind<M, Double> =
        generate { randSeed, s ->
            val (min, max) = range.bounds(s)
            if (min == max) min
            else randSeed.nextDouble(min, max).a
        }

    /**
     * Generate a [Double] in a given [ClosedFloatingPointRange]. Will use the first and last elements of that range as constants.
     * This generator will shrink.
     */
    fun double(range: ClosedFloatingPointRange<Double>): Kind<M, Double> =
        double(Range.constant(range.start, range.endInclusive))

    /**
     * Generate a [Double] in a given [ClosedFloatingPointRange]. Will use the first and last elements of that range as constants.
     * This generator will not shrink.
     */
    fun double_(range: ClosedFloatingPointRange<Double>): Kind<M, Double> =
        double_(Range.constant(range.start, range.endInclusive))

    /**
     * Generate a [Float] from a given [Range] with shrinking.
     */
    fun float(range: Range<Float>): Kind<M, Float> = MM().run {
        double(range.map { it.toDouble() }).map { it.toFloat() }
    }

    /**
     * Generate a [Float] from a given [Range] without shrinking.
     */
    fun float_(range: Range<Float>): Kind<M, Float> = MM().run {
        double_(range.map { it.toDouble() }).map { it.toFloat() }
    }

    /**
     * Generate a [Float] in a given [ClosedFloatingPointRange]. Will use the first and last elements of that range as constants.
     * This generator will shrink.
     */
    fun float(range: ClosedFloatingPointRange<Float>): Kind<M, Float> =
        float(Range.constant(range.start, range.endInclusive))

    /**
     * Generate a [Double] in a given [ClosedFloatingPointRange]. Will use the first and last elements of that range as constants.
     * This generator will not shrink.
     */
    fun float_(range: ClosedFloatingPointRange<Float>): Kind<M, Float> =
        float_(Range.constant(range.start, range.endInclusive))

    // boolean

    /**
     * Generate a [Boolean] value. This will attempt to shrink the [Boolean] to `false` if it was `true`, otherwise it
     *  produces no shrinks.
     */
    fun boolean(): Kind<M, Boolean> =
        boolean_().shrink { if (it) sequenceOf(false) else emptySequence() }

    /**
     * Generate a [Boolean] value without shrinking.
     */
    fun boolean_(): Kind<M, Boolean> = generate { randSeed, _ ->
        randSeed.nextInt(0, 2).a != 0
    }

    // chars TODO Arrow codegen bug when trying to generate @extension versions of this

    /**
     * Generate a [Char] from a given [Range] with shrinking.
     */
    fun char(range: Range<Char>): Kind<M, Char> = MM().run {
        long(range.map { it.toLong() }).map { it.toChar() }
    }

    /**
     * Generate a [Char] from a given [Range] without shrinking.
     */
    fun char_(range: Range<Char>): Kind<M, Char> = MM().run {
        long_(range.map { it.toLong() }).map { it.toChar() }
    }

    /**
     * Generate a [Char] in a given [CharRange]. Will use the first and last elements of that range as constants.
     * This generator will shrink.
     */
    fun char(range: CharRange): Kind<M, Char> = char(Range.constant(range.first, range.last))

    /**
     * Generate a [Char] in a given [CharRange]. Will use the first and last elements of that range as constants.
     * This generator will not shrink.
     */
    fun char_(range: CharRange): Kind<M, Char> = char_(Range.constant(range.first, range.last))

    /**
     * Generate a [Char] that is either `0` or `1`
     */
    fun binit(): Kind<M, Char> = char('0'..'1')

    /**
     * Generate a [Char] between `0` and `7`
     */
    fun octit(): Kind<M, Char> = char('0'..'7')

    /**
     * Generate a [Char] between `0` and `9`
     */
    fun digit(): Kind<M, Char> = char('0'..'9')

    /**
     * Generate a [Char] from the ranges `0-9`, `a-f` and `A-F`.
     */
    fun hexit(): Kind<M, Char> = choice(digit(), char('a'..'f'), char('A'..'F'))

    /**
     * Generate a [Char] between `a` and `z`
     */
    fun lower(): Kind<M, Char> = char('a'..'z')

    /**
     * Generate a [Char] between `A` and `Z`
     */
    fun upper(): Kind<M, Char> = char('A'..'Z')

    /**
     * Combination of [lower] and [upper]. Which essentially means a lower or uppercase char from `a` to `z`.
     */
    fun alpha(): Kind<M, Char> = choice(lower(), upper())

    /**
     * Combination of [alpha] and [digit].
     */
    fun alphaNum(): Kind<M, Char> = choice(lower(), upper(), digit())

    /**
     * Generate random chars within the ascii range.
     */
    fun ascii(): Kind<M, Char> = MM().run {
        int(0..127).map { it.toChar() }
    }

    /**
     * Generate random chars within the latin1 range.
     */
    fun latin1(): Kind<M, Char> = MM().run {
        int(0..255).map { it.toChar() }
    }

    /**
     * Generate random chars within the unicode range excluding some invalid characters.
     */
    fun unicode(): Kind<M, Char> = MM().run {
        val s1 = (55296 toT int(0..55295).map { it.toChar() })
        val s2 = (8190 toT int(57344..65533).map { it.toChar() })
        val s3 = (1048576 toT int(65536..1114111).map { it.toChar() })
        frequency(s1, s2, s3)
    }

    /**
     * Generate random chars from the entire unicode range.
     */
    fun unicodeAll(): Kind<M, Char> = char(Char.MIN_VALUE..Char.MAX_VALUE)

    /**
     * Turn a generator for [Char] into a generator of [String].
     *
     * The [Range] defines the length bounds of the [String] and the generator for [Char] defines what [Char]'s will appear in
     *  the [String].
     *
     * Note: This uses [list] internally. This means the same caveats apply. See [list] for more information!
     */
    fun Kind<M, Char>.string(range: Range<Int>): Kind<M, String> =
        MM().run { list(range).map { it.joinToString("") } }

    /**
     * Turn a generator for [Char] into a generator of [String].
     *
     * The [IntRange] defines the length bounds of the [String] and the generator for [Char] defines what [Char]'s will appear in
     *  the [String].
     *
     * Note: This uses [list] internally. This means the same caveats apply. See [list] for more information!
     */
    fun Kind<M, Char>.string(range: IntRange): Kind<M, String> =
        string(Range.constant(range.first, range.last))

    // combinators

    /**
     * Create a constant generator which always generators the given value.
     *
     * This also produces no shrinks.
     */
    fun <A> constant(a: A): Kind<M, A> = MM().just(a)

    /**
     * Choose from a (non-empty) list of [A]'s.
     *
     * For single arguments see [constant].
     * For choice between multiple generators rather than values, see [choice] and [frequency].
     */
    fun <A> element(vararg els: A): Kind<M, A> =
        if (els.isEmpty()) throw IllegalArgumentException("Gen.Element used with no arguments")
        else MM().fx.monad {
            val i = !int(Range.constant(0, els.size - 1))
            els[i]
        }

    /**
     * Choose from a (non-empty) list of generators and apply that generator.
     *
     * For weighed choice see [frequency].
     * For choice between values rather than generators see [element].
     */
    fun <A> choice(vararg gens: Kind<M, A>): Kind<M, A> =
        if (gens.isEmpty()) throw IllegalArgumentException("Gen.Choice used with no arguments")
        else MM().fx.monad {
            val i = !int(Range.constant(0, gens.size))
            !gens[i]
        }

    /**
     * Choose from a (non-empty) list of generators and apply that generator.
     *
     * This in contrast to [choice] also takes weight for each generator and the choices will be distributed accordingly.
     */
    fun <A> frequency(vararg gens: Tuple2<Int, Kind<M, A>>): Kind<M, A> =
        if (gens.isEmpty()) throw IllegalArgumentException("Gen.Frequency used with no arguments")
        else MM().fx.monad {
            val total = gens.map { it.a }.sum()
            val n = !int(Range.constant(0, total))
            !gens.toList().pick(n)
        }

    /**
     * Drop elements and decrement n until it is in range.
     *
     * Invariant: The list should never be empty
     */
    private tailrec fun <A> List<Tuple2<Int, A>>.pick(n: Int): A =
        if (isEmpty()) throw IllegalArgumentException("Gen.Frequency.Pick used with no arguments")
        else {
            val (k, el) = first()
            if (n <= k) el
            else tail().pick(n - k)
        }

    /**
     * Helper to implement recursive generators.
     *
     * It takes a list of terminal cases and a (lazy) list of recursive cases. It then chooses between the terminal and
     *  recursive cases using [choice] and in case it happens to choose a recursive case it will scale the size parameter.
     *  Once the size parameter is smaller or equal to 1 it will only choose between terminal cases.
     *
     * The recursive generators are wrapped in functions because they may be recursive on construction and hence overflow
     *  before this generator even does work. This allows the generator to, for example, refer to itself as the recursive case.
     *
     * Note: Using this with the [Id] monad as in [forAll] might cause stackoverflows. In case that happens use [forAllT] or a
     *  stacksafe [Monad] like [Eval] or [IO] instead.
     */
    fun <A> recursive(
        nonRec: List<Kind<M, A>>,
        rec: () -> List<Kind<M, A>>
    ): Kind<M, A> =
        sized { s ->
            if (s.unSize <= 1) choice(*nonRec.toTypedArray())
            else choice(*(nonRec + rec().map { it.small() }).toTypedArray())
        }

    /**
     * Create a generator which never produces a value. Using this inside a test will cause the runner to discard the
     *  generated data and start over. Discarding too many values can cause tests to fail.
     *
     * For most cases prefer [filter] or [filterMap] instead.
     */
    fun <A> discard(): Kind<M, A> = GenT { _ ->
        Rose<OptionTPartialOf<B>, A>(OptionT.none(BM()))
    }.fromGenT()

    /**
     * Discard a generator if the generated value fails to hold a condition.
     *
     * This uses [discard], so the same caveats apply.
     */
    fun <A> Kind<M, A>.ensure(p: (A) -> Boolean): Kind<M, A> =
        MM().fx.monad {
            val x = !this@ensure
            if (p(x)) x
            else !discard<A>()
        }

    /**
     * Apply a function to the generated value and remove it until it produces a [Some].
     *
     * This differs from [ensure] in two ways:
     * - It maps from [A] to [B] rather than just being a [Boolean] condition
     * - In case it [f] returns [None] the generator retries rather than calling [discard]. It has a safety threshold of
     *  100 retries after which it will eventually call [discard]. Each retry also increases the size to have a wider search range
     *
     * For a version of [ensure] that behaves like this see [filter] which any [MonadGen] implements.
     */
    override fun <A, C> Kind<M, A>.filterMap(f: (A) -> Option<C>): Kind<M, C> {
        fun t(k: Int): Kind<M, C> =
            if (k > 100) discard()
            else fx {
                val (x, gen) = this@filterMap.scale { Size(2 * k + it.unSize) }.freeze().bind()
                f(x).fold({ t(k + 1).bind() }, {
                    gen.toGenT()
                        .mapTree { Rose.monadFilter(OptionT.alternative(BM()), OptionT.monad(BM())).run { it.filterMap(f) }.fix() }
                        .fromGenT().bind()
                })
            }
        return t(0)
    }

    /**
     * Generate list of values from a generator.
     *
     * The [Range] determines the size of the resulting list.
     *
     * At first glance this seems to be as easy as `int_(range).flatMap { this@list.replicate(it) }` but this will yield
     *  very poor shrinking as the generator will never attempt to shrink the list size for example. Using `int` in place
     *  of `int_` will shrink the size but it will still be suboptimal because of the use of `flatMap` which is the real issue here.
     *
     * Instead this generator manually shrinks by first peeking at the result (without changing the generator state) using [freeze].
     * Then it will use [interleave], which is also used to zip [Rose]-tree's together to produce a better shrink tree for a generated value.
     *
     * Lastly it will discard any lists which have been shrunk to sizes outside of the range's lowerbound.
     *
     * Note: This can cause stackoverflow's when used with the [Id] monad and larger ranges. Use a stacksafe monad like [Eval], [IO] etc. instead.
     * If you access it through [forAll] just use [forAllT] and arrow-check will provide a stacksafe monad.
     */
    fun <A> Kind<M, A>.list(range: Range<Int>): Kind<M, List<A>> = sized { s ->
        MM().run {
            fx.monad {
                val n = !int_(range)
                !this@list.toGenT().mapTree {
                    Rose.just(OptionT.monad(BM()), it)
                }.fromGenT().replicateSafe(this@run, n)
            }.toGenT().mapTree { r ->
                Rose(
                    roseM().run {
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

    // TODO Replace with traverse after the lazyAp pr to use eval is in!
    // This is actually traverse but the current traverse is not stacksafe for stackunsafe monads...
    private fun <F, A> Kind<F, A>.replicateSafe(AP: Applicative<F>, n: Int): Kind<F, List<A>> =
        if (n <= 0) AP.just(emptyList())
        else (0..n).toList().foldRight(Eval.now(AP.just(emptyList<A>())) as Eval<Kind<F, List<A>>>) { _, acc ->
            acc.map { AP.run { this@replicateSafe.ap(it.map { xs -> { a: A -> listOf(a) + xs } }) } }
        }.value()

    // This resulted in a NoSuchMethodError so I copied these methods here...
    // TODO check from time to time to see if this persists
    private fun <A> Sequence<A>.splits(): Sequence<Tuple3<Sequence<A>, A, Sequence<A>>> =
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

    private fun <M, A> Sequence<RoseF<A, Rose<M, A>>>.dropOne(MM: Monad<M>): Sequence<Rose<M, Sequence<A>>> =
        SequenceK.fx {
            val (xs, _, zs) = !splits().k()
            Rose(MM.just((xs + zs).interleave(MM)))
        }

    private fun <M, A> Sequence<RoseF<A, Rose<M, A>>>.shrinkOne(MM: Monad<M>): Sequence<Rose<M, Sequence<A>>> =
        SequenceK.fx {
            val (xs, y, zs) = !splits().k()
            val y1 = !y.shrunk.k()
            Rose(
                MM.run {
                    y1.runRose.map { (xs + sequenceOf(it) + zs).interleave(MM) }
                }
            )
        }

    private fun <M, A> Sequence<RoseF<A, Rose<M, A>>>.interleave(MM: Monad<M>): RoseF<Sequence<A>, Rose<M, Sequence<A>>> =
        RoseF(
            this.map { it.res },
            dropOne(MM) + shrinkOne(MM)
        )
    // --------------

    /**
     * Generate list of values from a generator.
     *
     * The [IntRange] determines the size of the resulting list.
     *
     * At first glance this seems to be as easy as `int_(range).flatMap { this@list.replicate(it) }` but this will yield
     *  very poor shrinking as the generator will never attempt to shrink the list size for example. Using `int` in place
     *  of `int_` will shrink the size but it will still be suboptimal because of the use of `flatMap` which is the real issue here.
     *
     * Instead this generator manually shrinks by first peeking at the result (without changing the generator state) using [freeze].
     * Then it will use [interleave], which is also used to zip [Rose]-tree's together to produce a better shrink tree for a generated value.
     *
     * Lastly it will discard any lists which have been shrunk to sizes outside of the range's lowerbound.
     *
     * Note: This can cause stackoverflow's when used with the [Id] monad and larger ranges. Use a stacksafe monad like [Eval], [IO] etc. instead.
     * If you access it through [forAll] just use [forAllT] and arrow-check will provide a stacksafe monad.
     */
    fun <A> Kind<M, A>.list(range: IntRange): Kind<M, List<A>> = list(Range.constant(range.first, range.last))

    /**
     * Generate a [Set] of unique values from a generator.
     *
     * The [Range] parameter determines the size of the [Set].
     *
     * Note: This internally uses [list] so the same caveat's apply!
     */
    fun <A> Kind<M, A>.set(range: Range<Int>): Kind<M, Set<A>> = MM().run {
        map { it toT Unit }.map(range).map { it.keys }
    }

    /**
     * Generate a [Map] of unique values from a generator of keys and values.
     *
     * The [Range] parameter determines the size of the [Map].
     *
     * Note: This internally uses [list] so the same caveat's apply!
     */
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

    /**
     * Generates a unique list of values from a generator of keys and values
     *
     * The parameter determines the list size. If this generator fails to generate enough unique values after 100 attempts it will discard.
     *
     * Note: This internally uses [list] so the same caveat's apply!
     */
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

    /**
     * Generate an [Option] from a generator.
     *
     * Note there also exists a similar method called [optional] which comes from [Alternative]. That will have the effect
     *  of generating a value and wrapping it in [Some] if it has not been discarded and [None] if it has.
     *
     * This uses [frequency] and [sized] to have the generator return [None] more frequently in the beginning, but later on
     *  will mainly produce [Some].
     */
    fun <A> Kind<M, A>.option(): Kind<M, Option<A>> = sized { n ->
        frequency(
            2 toT MM().just(None),
            1 + n.unSize toT MM().run { this@option.map { it.some() } }
        )
    }

    /**
     * Generate an [Either] from a generator for the left and one for the right side.
     *
     * This uses [frequency] and [sized] to have the generator return [Left] more frequently in the beginning, but later on
     *  will mainly produce [Right].
     */
    fun <L, R> either(l: Kind<M, L>, r: Kind<M, R>): Kind<M, Either<L, R>> = sized { sz ->
        frequency(
            2 toT l.map(::Left),
            1 + sz.unSize toT r.map(::Right)
        )
    }

    /**
     * Generate a [Validated] from a generator for the left and one for the right side.
     *
     * This uses [frequency] and [sized] to have the generator return [Invalid] more frequently in the beginning, but later on
     *  will mainly produce [Valid].
     *
     * `validated(l, r) == either(l, r).map { Validated.fromEither(it) }`
     */
    fun <E, A> validated(l: Kind<M, E>, r: Kind<M, A>): Kind<M, Validated<E, A>> =
        either(l, r).map { Validated.fromEither(it) }

    /**
     * Generate an [Ior] from a generator for the left and one for the right side.
     *
     * This uses [frequency] and [sized] to have the generator return [Ior.Left] and [Ior.Both] more frequently in the beginning, but later on
     *  transition to mainly produce [Ior.Right].
     */
    fun <L, R> ior(l: Kind<M, L>, r: Kind<M, R>): Kind<M, Ior<L, R>> = sized { sz ->
        frequency(
            2 toT l.map { Ior.Left(it) },
            1 + (sz.unSize / 2) toT mapN(l, r) { (l, r) -> Ior.Both(l, r) },
            1 + sz.unSize toT r.map { Ior.Right(it) }
        )
    }

    /**
     * Wrap the result of a generator in [Id]
     */
    fun <A> Kind<M, A>.id(): Kind<M, Id<A>> = map(::Id)

    /**
     * Wrap the result of a generator in [Const]
     */
    fun <A, T> Kind<M, A>.const(): Kind<M, Const<A, T>> = map(::Const)

    /**
     * Generate a non-empty list from a generator of values.
     *
     * This uses [list] internally so the same caveats apply.
     */
    fun <A> Kind<M, A>.nonEmptyList(range: Range<Int>): Kind<M, NonEmptyList<A>> =
        list(range).filterMap { NonEmptyList.fromList(it) }

    // subterms

    /**
     * Return a generator which contains its own initial result and a constant generator for that result.
     *
     * This is very useful if one wants to add shrinking, but needs access to the generated value. This technique is
     *  used inside [list] to produce a better shrink result.
     *
     * The generator returned will not do any shrinking on it's own as this methods purpose usually is to add that manually.
     */
    fun <A> Kind<M, A>.freeze(): Kind<M, Tuple2<A, Kind<M, A>>> =
        GenT { (r, s) ->
            Rose.monad(roseM()).fx.monad {
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

    /**
     * Helper to implement [subTerm1], [subterm2] ...
     *
     * This basically throws away the shrink tree's for every generator in the list and wraps the results of each
     *  in [Subterms.All]. This is then shrunk to [Subterms.One] in the branches of the [Rose].
     *
     * Invariant: List size is never changed!
     */
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

    /**
     * Generate a value from its n-subterms and add the ability to shrink itself to one subterm.
     *
     * This can be used to in types like `sealed class Exp { data class Add(val l: Exp, val r: Exp); ... }` to have `Add` shrink not
     *  only its left and right parts but also itself to a potentially smaller version of `Exp`.
     *
     * In the case a [Subterms] has been shrunk to [Subterms.One] it will behave as a constant generator that returns that value and never call [f].
     *
     * Invariant: size list in f is always equal to the size of this
     */
    fun <A> List<Kind<M, A>>.subtermList(f: (List<A>) -> Kind<M, A>): Kind<M, A> =
        MM().run {
            this@subtermList.genSubterms().flatMap { it.fromSubterms(MM(), f) }
        }

    /**
     * Generate a value from its one subterm.
     *
     * For more information look at [subtermList]
     */
    fun <A> Kind<M, A>.subtermM(f: (A) -> Kind<M, A>): Kind<M, A> =
        listOf(this).subtermList { f(it.first()) }

    /**
     * Generate a value from its one subterm.
     *
     * For more information look at [subtermList]
     */
    fun <A> Kind<M, A>.subterm(f: (A) -> A): Kind<M, A> =
        subtermM { MM().just(f(it)) }

    /**
     * Generate a value from its two subterms.
     *
     * For more information look at [subtermList]
     */
    fun <A> subtermM2(g1: Kind<M, A>, g2: Kind<M, A>, f: (A, A) -> Kind<M, A>): Kind<M, A> =
        MM().run { listOf(g1, g2).subtermList { f(it[0], it[1]) } }

    /**
     * Generate a value from its two subterms.
     *
     * For more information look at [subtermList]
     */
    fun <A> subterm2(g1: Kind<M, A>, g2: Kind<M, A>, f: (A, A) -> A): Kind<M, A> =
        subtermM2(g1, g2) { a1, a2 -> MM().just(f(a1, a2)) }

    /**
     * Generate a value from its three subterms.
     *
     * For more information look at [subtermList]
     */
    fun <A> subtermM3(g1: Kind<M, A>, g2: Kind<M, A>, g3: Kind<M, A>, f: (A, A, A) -> Kind<M, A>): Kind<M, A> =
        MM().run { listOf(g1, g2, g3).subtermList { f(it[0], it[1], it[2]) } }

    /**
     * Generate a value from its three subterms.
     *
     * For more information look at [subtermList]
     */
    fun <A> subterm3(g1: Kind<M, A>, g2: Kind<M, A>, g3: Kind<M, A>, f: (A, A, A) -> A): Kind<M, A> =
        subtermM3(g1, g2, g3) { a1, a2, a3 -> MM().just(f(a1, a2, a3)) }

    // permutations

    /**
     * Generate lists which are subsequences of a given list.
     */
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

/**
 * A value [A] is one that can be built from either a singular value [A] or a list of [A]'s.
 *
 * An example of this is the following sealed class:
 * ```kotlin
 * sealed class Exp {
 *   data class Add(val l: Exp, val r: Exp): Exp()
 *   data class IntLit(val i: Int): Exp()
 *   <...>
 * }
 * ```
 * Here a `Exp` can be constructed either by combining two subterms to form one `Add` or by simply creating `IntLit`.
 *
 * When using [MonadGen.subtermList], or it's derivations [MonadGen.subterm2] etc, this means the shrinker will not only
 *  shrink the left and right part of an `Add` expression, but also `Add` itself to smaller components like `IntLit`
 */
sealed class Subterms<A> {
    data class One<A>(val a: A) : Subterms<A>()
    data class All<A>(val l: List<A>) : Subterms<A>()
}

/**
 * Helper function to implement [MonadGen.subtermList].
 */
internal fun <M, A> Subterms<A>.fromSubterms(AM: Applicative<M>, f: (List<A>) -> Kind<M, A>): Kind<M, A> = when (this) {
    is Subterms.One -> AM.just(a)
    is Subterms.All -> f(l)
}

/**
 * Helper function to implement [MonadGen.genSubterms].
 */
internal fun <A> Subterms<A>.shrinkSubterms(): Sequence<Subterms<A>> = when (this) {
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
