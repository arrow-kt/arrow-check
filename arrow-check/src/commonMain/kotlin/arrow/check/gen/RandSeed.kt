package arrow.check.gen

/**
 * TODO run a random number testsuite like dieharder over this!
 * This whole thing is copypasta simply to make SplittableRandom more accessible
 * re-implementation of SplittableRandom
 *  by http://gee.cs.oswego.edu/dl/papers/oopsla14.pdf
 *
 * Why? Because all other random generators hide the seed in a private field and
 *  I don't want to rely on reflection to get it!
 */
public class RandSeed private constructor(
  private val seed: Long,
  private val gamma: Long // Has to be odd!
) {

  override fun toString(): String = "RandSeed($seed, $gamma)"

  // See http://hackage.haskell.org/package/QuickCheck-2.13.2/docs/src/Test.QuickCheck.Random.html#integerVariant
  /**
   * Modify the random seed in a way that even small variations in [i] brings larger variants.
   */
  public fun variant(i: Long): RandSeed = when {
    i >= 1 -> gammaF(i)(this.split().first)
    else -> gammaF(1 - i)(this.split().second)
  }

  private fun gammaF(i: Long): (RandSeed) -> RandSeed = {
    ilog2(i).let { k ->
      encode(i, k, zeroes(k, it))
    }
  }

  private tailrec fun encode(n: Long, k: Int, r: RandSeed): RandSeed = when (k) {
    -1 -> r
    else -> when {
      n and (1L shl k) == 0L -> encode(n, k - 1, r.split().first)
      else -> encode(n, k - 1, r.split().second)
    }
  }

  private tailrec fun zeroes(n: Int, r: RandSeed): RandSeed = when (n) {
    0 -> r
    else -> zeroes(n - 1, r.split().first)
  }

  private fun ilog2(i: Long): Int {
    tailrec fun go(i: Long, acc: Int): Int = when (i) {
      1L -> acc
      else -> go(i / 2, acc + 1)
    }
    return go(i, 0)
  }

  /**
   * Generate a random long.
   */
  public fun nextLong(): Pair<Long, RandSeed> = nextSeed(seed, gamma).let { mix64(it) to RandSeed(it, gamma) }

  /**
   * Generate a random long within bounds (inclusive, exclusive).
   */
  public fun nextLong(origin: Long, bound: Long): Pair<Long, RandSeed> = when {
    origin >= bound -> throw IllegalArgumentException("Invalid bounds $origin $bound")
    else -> {
      nextSeed(seed, gamma).let {
        var r = mix64(it)
        var latestSeed = it
        val n = bound - origin
        val m = n - 1
        if (n and m == 0L)
          r = (r and m) + origin
        else if (n > 0) {
          var u = r.ushr(1)
          r = u.rem(n)
          while (u + m - u.rem(n) < 0) {
            r = u.rem(n)
            latestSeed = nextSeed(latestSeed, gamma)
            u = mix64(latestSeed).ushr(1)
          }
          r += origin
        } else {
          while (r < origin || r >= bound) {
            latestSeed = nextSeed(latestSeed, gamma)
            r = mix64(latestSeed)
          }
        }
        r to RandSeed(latestSeed, gamma)
      }
    }
  }

  /**
   * Generate a random integer
   */
  public fun nextInt(): Pair<Int, RandSeed> = nextSeed(seed, gamma).let { mix32(it) to RandSeed(it, gamma) }

  /**
   * Generate a random integer within bounds (inclusive, exclusive)
   */
  public fun nextInt(origin: Int, bound: Int): Pair<Int, RandSeed> = when {
    origin >= bound -> throw IllegalArgumentException("Invalid bounds $origin $bound")
    else -> {
      nextSeed(seed, gamma).let {
        var r = mix32(it)
        var latestSeed = it
        val n = bound - origin
        val m = n - 1
        if (n and m == 0)
          r = (r and m) + origin
        else if (n > 0) {
          var u = r.ushr(1)
          r = u.rem(n)
          while (u + m - u.rem(n) < 0) {
            r = u.rem(n)
            latestSeed = nextSeed(latestSeed, gamma)
            u = mix32(latestSeed).ushr(1)
          }
          r += origin
        } else {
          while (r < origin || r >= bound) {
            latestSeed = nextSeed(latestSeed, gamma)
            r = mix32(latestSeed)
          }
        }
        r to RandSeed(latestSeed, gamma)
      }
    }
  }

  /**
   * Generate a random double
   */
  public fun nextDouble(): Pair<Double, RandSeed> = nextSeed(seed, gamma).let {
    mix64(it).ushr(11) * DOUBLE_UNIT to RandSeed(it, gamma)
  }

  /**
   * Generate a random double within bounds (inclusive, exclusive)
   */
  public fun nextDouble(origin: Double, bound: Double): Pair<Double, RandSeed> {
    val (l, s) = nextLong()
    var r = l.ushr(11) * DOUBLE_UNIT
    if (origin < bound) {
      r = r * (bound - origin) + origin
      if (r >= bound)
      // correct for rounding
        r = (bound.toLong() - 1).toDouble()
      // Not sure if the above is equal to this java code
      // r = java.lang.Double.longBitsToDouble(java.lang.Double.doubleToLongBits(bound) - 1)
    }
    return r to s
  }

  /**
   * Split the seed into two new seeds
   */
  public fun split(): Pair<RandSeed, RandSeed> {
    val (l, seed) = nextLong()
    val nl = nextSeed(seed.seed, gamma)
    val nextGamma = mixGamma(nl)
    return RandSeed(nl, seed.gamma) to RandSeed(l, nextGamma)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is RandSeed) return false

    if (seed != other.seed) return false
    if (gamma != other.gamma) return false

    return true
  }

  override fun hashCode(): Int {
    var result = seed.hashCode()
    result = 31 * result + gamma.hashCode()
    return result
  }

  public companion object {

    public operator fun invoke(seed: Long, gamma: Long = GOLDEN_GAMMA): RandSeed =
      if (gamma.rem(2L) == 0L) throw IllegalArgumentException("Gamma has to be an odd value!")
      else RandSeed(seed, gamma)

    private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
    private const val DOUBLE_UNIT = 1.0 / ((1L).shl(53))

    private fun nextSeed(seed: Long, gamma: Long): Long = seed + gamma

    private fun mix64(z: Long): Long {
      val z1 = (z xor z.ushr(30)) * -0x40a7b892e31b1a47L
      val z2 = (z1 xor z1.ushr(27)) * -0x6b2fb644ecceee15L
      return z2 xor z2.ushr(31)
    }

    private fun mix32(z: Long): Int {
      val z1 = (z xor z.ushr(33)) * 0x62a9d9ed799705f5L
      return ((z1 xor z1.ushr(28)) * -0x34db2f5a3773ca4dL).ushr(32).toInt()
    }

    private fun mixGamma(z: Long): Long {
      val z1 = (z xor z.ushr(33)) * -0xae502812aa7333L // MurmurHash3 mix constants
      val z2 = (z1 xor z1.ushr(33)) * -0x3b314601e57a13adL
      val z3 = z2 xor z2.ushr(33) or 1L // force to be odd
      val n = bitCount(z3 xor z3.ushr(1)) // ensure enough transitions
      return if (n < 24) z3 xor -0x5555555555555556L else z3
    }

    // java.lang.Long.bitcount
    private fun bitCount(var0: Long): Int {
      var var0 = var0
      var0 -= var0.ushr(1) and 6148914691236517205L
      var0 = (var0 and 3689348814741910323L) + (var0.ushr(2) and 3689348814741910323L)
      var0 = var0 + var0.ushr(4) and 1085102592571150095L
      var0 += var0.ushr(8)
      var0 += var0.ushr(16)
      var0 += var0.ushr(32)
      return var0.toInt() and 127
    }
  }
}
