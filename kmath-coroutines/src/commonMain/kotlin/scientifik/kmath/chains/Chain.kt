/*
 * Copyright  2018 Alexander Nozik.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package scientifik.kmath.chains

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow


/**
 * A not-necessary-Markov chain of some type
 * @param R - the chain element type
 */
interface Chain<out R> {
    /**
     * Generate next value, changing state if needed
     */
    suspend fun next(): R

    /**
     * Create a copy of current chain state. Consuming resulting chain does not affect initial chain
     */
    fun fork(): Chain<R>

    companion object

}

/**
 * Chain as a coroutine flow. The flow emit affects chain state and vice versa
 */
@FlowPreview
val <R> Chain<R>.flow: Flow<R>
    get() = kotlinx.coroutines.flow.flow { while (true) emit(next()) }

fun <T> Iterator<T>.asChain(): Chain<T> = SimpleChain { next() }
fun <T> Sequence<T>.asChain(): Chain<T> = iterator().asChain()

/**
 * A simple chain of independent tokens
 */
class SimpleChain<out R>(private val gen: suspend () -> R) : Chain<R> {
    override suspend fun next(): R = gen()
    override fun fork(): Chain<R> = this
}

/**
 * A stateless Markov chain
 */
class MarkovChain<out R : Any>(private val seed: suspend () -> R, private val gen: suspend (R) -> R) : Chain<R> {

    constructor(seedValue: R, gen: suspend (R) -> R) : this({ seedValue }, gen)

    private val value = atomic<R?>(null)

    override suspend fun next(): R {
        return value.updateAndGet { prev -> gen(prev ?: seed()) }!!
    }

    override fun fork(): Chain<R> {
        return MarkovChain(seed = { value.value ?: seed() }, gen = gen)
    }
}

/**
 * A chain with possibly mutable state. The state must not be changed outside the chain. Two chins should never share the state
 * @param S - the state of the chain
 * @param forkState - the function to copy current state without modifying it
 */
class StatefulChain<S, out R>(
    private val state: S,
    private val seed: S.() -> R,
    private val forkState: ((S) -> S),
    private val gen: suspend S.(R) -> R
) : Chain<R> {

    constructor(state: S, seedValue: R, forkState: ((S) -> S), gen: suspend S.(R) -> R) : this(
        state,
        { seedValue },
        forkState,
        gen
    )

    private val atomicValue = atomic<R?>(null)

    override suspend fun next(): R {
        return atomicValue.updateAndGet { prev -> state.gen(prev ?: state.seed()) }!!
    }

    override fun fork(): Chain<R> {
        return StatefulChain(forkState(state), seed, forkState, gen)
    }
}

/**
 * A chain that repeats the same value
 */
class ConstantChain<out T>(val value: T) : Chain<T> {
    override suspend fun next(): T = value

    override fun fork(): Chain<T> {
        return this
    }
}

/**
 * Map the chain result using suspended transformation. Initial chain result can no longer be safely consumed
 * since mapped chain consumes tokens. Accepts regular transformation function
 */
fun <T, R> Chain<T>.pipe(func: suspend (T) -> R): Chain<R> = object : Chain<R> {
    override suspend fun next(): R = func(this@pipe.next())
    override fun fork(): Chain<R> = this@pipe.fork().pipe(func)
}

/**
 * Map the whole chain
 */
fun <T, R> Chain<T>.map(mapper: suspend (Chain<T>) -> R): Chain<R> = object : Chain<R> {
    override suspend fun next(): R = mapper(this@map)
    override fun fork(): Chain<R> = this@map.fork().map(mapper)
}

fun <T, S, R> Chain<T>.mapWithState(state: S, stateFork: (S) -> S, mapper: suspend S.(Chain<T>) -> R): Chain<R> =
    object : Chain<R> {
        override suspend fun next(): R = state.mapper(this@mapWithState)
        override fun fork(): Chain<R> = this@mapWithState.fork().mapWithState(stateFork(state), stateFork, mapper)
    }

/**
 * Zip two chains together using given transformation
 */
fun <T, U, R> Chain<T>.zip(other: Chain<U>, block: suspend (T, U) -> R): Chain<R> = object : Chain<R> {
    override suspend fun next(): R = block(this@zip.next(), other.next())

    override fun fork(): Chain<R> = this@zip.fork().zip(other.fork(), block)
}