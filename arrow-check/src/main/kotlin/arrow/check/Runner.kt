package arrow.check

import arrow.check.gen.RandSeed
import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.PropertyName
import arrow.check.property.PropertyTest
import arrow.check.property.Size
import arrow.check.property.property
import arrow.check.property.runProperty
import arrow.core.Tuple2
import arrow.core.toT
import kotlin.random.Random

/**
 * Check an entire group of named properties.
 *
 * Fails if any property failed or gave up.
 *
 * @see check For a function that checks just one property
 */
suspend fun checkGroup(groupName: String, vararg props: Pair<String, Property>): Boolean =
  checkGroup(detectConfig(), groupName, *props)

suspend fun checkGroup(config: Config, groupName: String, vararg props: Pair<String, Property>): Boolean =
  checkGroup(config, groupName, *props.map { (n, p) -> n toT p }.toTypedArray())

suspend fun checkGroup(groupName: String, vararg props: Tuple2<String, Property>): Boolean =
  checkGroup(detectConfig(), groupName, *props)

suspend fun checkGroup(config: Config, groupName: String, vararg props: Tuple2<String, Property>): Boolean {
  println("━━━ $groupName ━━━")

  val summary =
    props.fold(Summary.monoid().empty().copy(waiting = PropertyCount(props.size))) { acc, (n, prop) ->
      val res = checkReport(config, PropertyName(n), prop)
      Summary.monoid().run {
        acc + empty().copy(waiting = PropertyCount(-1)) +
          when (res.status) {
            is Result.Failure -> empty().copy(failed = PropertyCount(1))
            is Result.Success -> empty().copy(successful = PropertyCount(1))
            is Result.GivenUp -> empty().copy(gaveUp = PropertyCount(1))
          }
      }
    }

  return summary.failed.unPropertyCount == 0 && summary.gaveUp.unPropertyCount == 0
}

/**
 * Perform a property test.
 *
 * @return Boolean indicating success or failure.
 *
 * @see recheck For a function that allows passing a [RandSeed] and [Size] to reproduce a test
 * @see checkNamed For a function that attaches a name to the property.
 * @see checkReport For a function that returns a detailed report rather than a boolean.
 */
suspend fun check(
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend PropertyTest.() -> Unit
): Boolean =
  check(property(propertyConfig, c))

suspend fun check(
  config: Config,
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend PropertyTest.() -> Unit
): Boolean =
  check(config, property(propertyConfig, c))

suspend fun check(prop: Property): Boolean =
  check(detectConfig(), prop)

suspend fun check(config: Config, prop: Property): Boolean = check(config, null, prop)

/**
 * Reproduce a test case using the given [RandSeed] and [Size].
 *
 * Unless *any* generator used in the test changed, this will reproduce a test exactly
 *
 * @see check for a function that performs the test with random inputs
 */
suspend fun recheck(size: Size, seed: RandSeed, prop: Property): Unit =
  recheck(detectConfig(), size, seed, prop)

suspend fun recheck(
  size: Size,
  seed: RandSeed,
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend PropertyTest.() -> Unit
): Unit = recheck(size, seed, property(propertyConfig, c))

suspend fun recheck(
  config: Config,
  size: Size,
  seed: RandSeed,
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend PropertyTest.() -> Unit
): Unit = recheck(config, size, seed, property(propertyConfig, c))

suspend fun recheck(config: Config, size: Size, seed: RandSeed, prop: Property): Unit {
  checkReport(seed, size, config, null, prop)
}

/**
 * Perform a property test using a named property.
 *
 * Basically just adds a name to the output.
 */
suspend fun checkNamed(
  name: String,
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend PropertyTest.() -> Unit
): Boolean = checkNamed(name, property(propertyConfig, c))

suspend fun checkNamed(name: String, prop: Property): Boolean =
  check(detectConfig(), PropertyName(name), prop)

suspend fun checkNamed(
  config: Config,
  name: String,
  propertyConfig: PropertyConfig = PropertyConfig(),
  c: suspend PropertyTest.() -> Unit
): Boolean = check(config, PropertyName(name), property(propertyConfig, c))

suspend fun checkNamed(config: Config, name: String, prop: Property): Boolean =
  check(config, PropertyName(name), prop)

suspend fun check(config: Config, name: PropertyName?, prop: Property): Boolean =
  checkReport(config, name, prop).status is Result.Success

/**
 * Perform a property test and return a detailed report.
 *
 * @see check if you are only interested in whether or not the test is a successful
 */
suspend fun checkReport(name: PropertyName?, prop: Property): Report<Result> =
  checkReport(RandSeed(Random.nextLong()), Size(0), detectConfig(), name, prop)

suspend fun checkReport(config: Config, name: PropertyName?, prop: Property): Report<Result> =
  checkReport(RandSeed(Random.nextLong()), Size(0), config, name, prop)

suspend fun checkReport(
  seed: RandSeed,
  size: Size,
  config: Config,
  name: PropertyName?,
  prop: Property
): Report<Result> {
  val report = runProperty(size, seed, prop.config, prop.prop) {
    // TODO Live update will come back once I finish concurrent output
  }
  println(report.renderResult(config.useColor, name))
  return report
}
