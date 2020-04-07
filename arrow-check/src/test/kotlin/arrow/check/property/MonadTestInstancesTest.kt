package arrow.check.property

import arrow.check.PropertySpec
import arrow.check.gen.RandSeed
import arrow.check.laws.MonadTestLaws
import arrow.check.laws.eqK
import arrow.check.property.instances.monadTest
import arrow.core.ForId
import arrow.core.Id
import arrow.core.extensions.eq
import arrow.core.extensions.id.eqK.eqK
import arrow.core.extensions.id.monad.monad
import arrow.core.extensions.monoid
import arrow.core.toT
import arrow.mtl.EitherT
import arrow.mtl.Kleisli
import arrow.mtl.OptionT
import arrow.mtl.StateT
import arrow.mtl.WriterT
import arrow.mtl.extensions.eithert.eqK.eqK
import arrow.mtl.extensions.optiont.eqK.eqK
import arrow.mtl.extensions.writert.eqK.eqK
import arrow.mtl.test.eq.eqK

class MonadTestInstancesTest : PropertySpec({
    "TestT: MonadTest laws"(MonadTestLaws.laws(TestT.monadTest(Id.monad()), TestT.eqK(Id.eqK())))

    "PropertyT: MonadTest laws"(
        MonadTestLaws.laws(PropertyT.monadTest(Id.monad()), PropertyT.eqK(Id.eqK(), RandSeed(0) toT Size(0)))
    )

    "OptionT: MonadTest laws"(
        MonadTestLaws.laws(OptionT.monadTest(TestT.monadTest(Id.monad())), OptionT.eqK(TestT.eqK(Id.eqK())))
    )

    "EitherT: MonadTest laws"(
        MonadTestLaws.laws(
            EitherT.monadTest<String, TestTPartialOf<ForId>>(TestT.monadTest(Id.monad())),
            EitherT.eqK(TestT.eqK(Id.eqK()), String.eq())
        )
    )

    "Kleisli: MonadTest laws"(
        MonadTestLaws.laws(
            Kleisli.monadTest<Int, TestTPartialOf<ForId>>(TestT.monadTest(Id.monad())),
            Kleisli.eqK(TestT.eqK(Id.eqK()), 1)
        )
    )

    "WriterT: MonadTest laws"(
        MonadTestLaws.laws(
            WriterT.monadTest(TestT.monadTest(Id.monad()), String.monoid()),
            WriterT.eqK(TestT.eqK(Id.eqK()), String.eq())
        )
    )

    "StateT: MonadTest laws"(
        MonadTestLaws.laws(
            StateT.monadTest<Int, TestTPartialOf<ForId>>(TestT.monadTest(Id.monad())),
            StateT.eqK(TestT.eqK(Id.eqK()), Int.eq(), 1)
        )
    )
})
