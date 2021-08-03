package arrow.check

import arrow.check.gen.Gen
import arrow.check.gen.RandSeed
import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.PropertyName
import arrow.check.property.Size
import arrow.check.property.Test
import arrow.check.property.runProperty
import kotlin.random.Random

public suspend fun <A> checkProp(gen: Gen<Any?, A>, propConfig: PropertyConfig = PropertyConfig(), prop: suspend Test.(A) -> Unit): Boolean =
  checkProp(Property(gen, propConfig, prop))

public suspend fun <A> checkProp(config: Config, gen: Gen<Any?, A>, propConfig: PropertyConfig = PropertyConfig(), prop: suspend Test.(A) -> Unit): Boolean =
  checkProp(config, null, Property(gen, propConfig, prop))

public suspend fun <A> checkProp(name: PropertyName?, gen: Gen<Any?, A>, propConfig: PropertyConfig = PropertyConfig(), prop: suspend Test.(A) -> Unit): Boolean =
  checkProp(detectConfig(), name, Property(gen, propConfig, prop))

public suspend fun <A> checkProp(config: Config, name: PropertyName?, gen: Gen<Any?, A>, propConfig: PropertyConfig = PropertyConfig(), prop: suspend Test.(A) -> Unit): Boolean =
  checkProp(config, name, Property(gen, propConfig, prop))

public suspend fun <A> checkProp(prop: Property<A>): Boolean =
  checkProp(detectConfig(), null, prop)

public suspend fun <A> checkProp(name: PropertyName?, prop: Property<A>): Boolean =
  checkProp(detectConfig(), name, prop)

public suspend fun <A> checkProp(config: Config, prop: Property<A>): Boolean =
  checkProp(config, null, prop)

public suspend fun <A> checkProp(config: Config, name: PropertyName?, prop: Property<A>): Boolean =
  checkReport(config, name, prop).status != Result.Success

/**
 * Reproduce a test case using the given [RandSeed] and [Size].
 *
 * Unless *any* generator used in the test changed, this will reproduce a test exactly
 *
 * @see check for a function that performs the test with random inputs
 */
public suspend fun <A> recheck(size: Size, seed: RandSeed, prop: Property<A>): Unit =
  recheck(detectConfig(), size, seed, prop)

public suspend fun <A> recheck(
  size: Size,
  seed: RandSeed,
  gen: Gen<Any?, A>,
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend Test.(A) -> Unit
): Unit = recheck(size, seed, Property(gen, propertyConfig, c))

public suspend fun <A> recheck(
  config: Config,
  size: Size,
  seed: RandSeed,
  gen: Gen<Any?, A>,
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend Test.(A) -> Unit
): Unit = recheck(config, size, seed, Property(gen, propertyConfig, c))

public suspend fun <A> recheck(config: Config, size: Size, seed: RandSeed, prop: Property<A>): Unit {
  checkReport(seed, size, config, null, prop)
}

/**
 * Perform a property test and return a detailed report.
 *
 * @see check if you are only interested in whether or not the test is a successful
 */
// TODO Should checkReport do console output or just return the report?
public suspend fun <A> checkReport(name: PropertyName? = null, gen: Gen<Any?, A>, propConfig: PropertyConfig = PropertyConfig(), prop: suspend Test.(A) -> Unit): Report<Result> =
  checkReport(detectConfig(), name, Property(gen, propConfig, prop))

public suspend fun <A> checkReport(config: Config, name: PropertyName?, gen: Gen<Any?, A>, propConfig: PropertyConfig = PropertyConfig(), prop: suspend Test.(A) -> Unit): Report<Result> =
  checkReport(config, name, Property(gen, propConfig, prop))

public suspend fun <A> checkReport(name: PropertyName? = null, prop: Property<A>): Report<Result> =
  checkReport(detectConfig(), name, prop)

public suspend fun <A> checkReport(config: Config, name: PropertyName?, prop: Property<A>): Report<Result> =
  checkReport(RandSeed(Random.nextLong()), Size(0), config, name, prop)

public suspend fun <A> checkReport(
  seed: RandSeed,
  size: Size,
  config: Config,
  name: PropertyName?,
  prop: Property<A>
): Report<Result> {
  val report = runProperty(size, seed, prop.config, prop.gen, prop.prop) {
    // TODO Live update will come back once I finish concurrent output
  }
  println(report.renderResult(config.useColor, name))
  return report
}
