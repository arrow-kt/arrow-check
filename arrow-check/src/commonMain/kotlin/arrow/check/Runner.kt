package arrow.check

import arrow.check.gen.Gen
import arrow.check.gen.RandSeed
import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.PropertyName
import arrow.check.property.Size
import arrow.check.property.Test
import arrow.check.property.property
import arrow.check.property.runProperty
import kotlin.random.Random

/**
 * Reproduce a test case using the given [RandSeed] and [Size].
 *
 * Unless *any* generator used in the test changed, this will reproduce a test exactly
 *
 * @see check for a function that performs the test with random inputs
 */
suspend fun <A> recheck(size: Size, seed: RandSeed, gen: Gen<Any?, A>, prop: Property<A>): Unit =
  recheck(detectConfig(), size, seed, gen, prop)

suspend fun <A> recheck(
  size: Size,
  seed: RandSeed,
  gen: Gen<Any?, A>,
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend Test.(A) -> Unit
): Unit = recheck(size, seed, gen, property(propertyConfig, c))

suspend fun <A> recheck(
  config: Config,
  size: Size,
  seed: RandSeed,
  gen: Gen<Any?, A>,
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend Test.(A) -> Unit
): Unit = recheck(config, size, seed, gen, property(propertyConfig, c))

suspend fun <A> recheck(config: Config, size: Size, seed: RandSeed, gen: Gen<Any?, A>, prop: Property<A>): Unit {
  checkReport(seed, size, config, null, gen, prop)
}

/**
 * Perform a property test and return a detailed report.
 *
 * @see check if you are only interested in whether or not the test is a successful
 */
suspend fun <A> checkReport(gen: Gen<Any?, A>, prop: Property<A>): Report<Result> =
  checkReport(null, gen, prop)

suspend fun <A> checkReport(name: PropertyName?, gen: Gen<Any?, A>, prop: Property<A>): Report<Result> =
  checkReport(detectConfig(), name, gen, prop)

suspend fun <A> checkReport(config: Config, name: PropertyName?, gen: Gen<Any?, A>, prop: Property<A>): Report<Result> =
  checkReport(RandSeed(Random.nextLong()), Size(0), config, name, gen, prop)

suspend fun <A> checkReport(
  seed: RandSeed,
  size: Size,
  config: Config,
  name: PropertyName?,
  gen: Gen<Any?, A>,
  prop: Property<A>
): Report<Result> {
  val report = runProperty(size, seed, prop.config, gen, prop.prop) {
    // TODO Live update will come back once I finish concurrent output
  }
  println(report.renderResult(config.useColor, name))
  return report
}
