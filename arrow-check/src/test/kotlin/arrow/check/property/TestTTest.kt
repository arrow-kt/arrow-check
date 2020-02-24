package arrow.check.property

import arrow.Kind
import arrow.check.PropertySpec
import arrow.check.property.instances.monadTest
import arrow.core.ForId
import arrow.core.Id
import arrow.core.extensions.id.eqK.eqK
import arrow.core.extensions.id.monad.monad
import arrow.fx.IO
import arrow.fx.extensions.io.monad.monad

// TODO when https://github.com/arrow-kt/arrow/pull/1981 is merged
class TestTLawsTest : PropertySpec({
    "MonadTest laws"(laws(TestT.monadTest(Id.monad()), TestT.genK(IO.monad(), Id.monad(), Id.genK(IO.monad())), TestT.eqK(Id.eqK())))
})