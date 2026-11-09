/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.server.graphservice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class SaturationBreakerTest {

    private fun breaker(now: AtomicLong, window: Long = 100, max: Int = 3, cooldown: Long = 10) =
        SaturationBreaker(windowMs = window, maxRebuildsInWindow = max, cooldownMs = cooldown, clock = now::get)

    @Test
    fun `stays clear below the threshold and trips at it`() {
        val now = AtomicLong(0)
        val breaker = breaker(now, max = 3)

        assertThat(breaker.pauseDelayMs()).`as`("no rebuilds yet").isNull()
        breaker.recordRebuild()
        breaker.recordRebuild()
        assertThat(breaker.pauseDelayMs()).`as`("2 < 3 in window").isNull()

        breaker.recordRebuild() // now 3 in the window
        assertThat(breaker.pauseDelayMs()).`as`("3 >= 3 trips").isEqualTo(10L)
    }

    @Test
    fun `resumes once older completions age out of the window`() {
        val now = AtomicLong(0)
        val breaker = breaker(now, window = 100, max = 3, cooldown = 10)

        repeat(3) { breaker.recordRebuild() } // three completions at t=0
        assertThat(breaker.pauseDelayMs()).isEqualTo(10L)

        now.set(50)
        assertThat(breaker.pauseDelayMs()).`as`("still within the 100ms window").isEqualTo(10L)

        now.set(101) // the t=0 completions are now older than the window
        assertThat(breaker.pauseDelayMs()).`as`("window cleared, resume").isNull()
    }

    @Test
    fun `disabled when max-rebuilds is not positive`() {
        val now = AtomicLong(0)
        val breaker = breaker(now, max = 0)

        repeat(100) { breaker.recordRebuild() }
        assertThat(breaker.pauseDelayMs()).`as`("disabled: never trips").isNull()
    }

    @Test
    fun `only completions inside the trailing window count toward the threshold`() {
        val now = AtomicLong(0)
        val breaker = breaker(now, window = 100, max = 3, cooldown = 10)

        breaker.recordRebuild()          // t=0
        now.set(80); breaker.recordRebuild() // t=80
        now.set(90); breaker.recordRebuild() // t=90

        now.set(150) // t=0 has aged out (>100ms old); only t=80 and t=90 remain → 2 < 3
        assertThat(breaker.pauseDelayMs()).isNull()

        breaker.recordRebuild() // t=150 → three within [50,150]
        assertThat(breaker.pauseDelayMs()).isEqualTo(10L)
    }
}
