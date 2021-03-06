package scientifik.kmath.prob

import scientifik.kmath.chains.Chain
import scientifik.kmath.chains.ConstantChain
import scientifik.kmath.chains.pipe
import scientifik.kmath.chains.zip
import scientifik.kmath.operations.Space

class BasicSampler<T : Any>(val chainBuilder: (RandomGenerator) -> Chain<T>) : Sampler<T> {
    override fun sample(generator: RandomGenerator): Chain<T> = chainBuilder(generator)
}

class ConstantSampler<T : Any>(val value: T) : Sampler<T> {
    override fun sample(generator: RandomGenerator): Chain<T> = ConstantChain(value)
}

/**
 * A space for samplers. Allows to perform simple operations on distributions
 */
class SamplerSpace<T : Any>(val space: Space<T>) : Space<Sampler<T>> {

    override val zero: Sampler<T> = ConstantSampler(space.zero)

    override fun add(a: Sampler<T>, b: Sampler<T>): Sampler<T> = BasicSampler { generator ->
        a.sample(generator).zip(b.sample(generator)) { aValue, bValue -> space.run { aValue + bValue } }
    }

    override fun multiply(a: Sampler<T>, k: Number): Sampler<T> = BasicSampler { generator ->
        a.sample(generator).pipe { space.run { it * k.toDouble() } }
    }
}