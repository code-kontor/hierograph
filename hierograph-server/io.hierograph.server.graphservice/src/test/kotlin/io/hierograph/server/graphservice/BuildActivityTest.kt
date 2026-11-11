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

class BuildActivityTest {

    private var now = 0L
    private val activity = BuildActivity(maxRescanDelayMs = 6_000, baseQuietMs = 1_000, clock = { now })

    @Test
    fun `a single change finds the build calm`() {
        activity.recordChange()

        assertThat(activity.rescanDelayMs()).isZero()
        assertThat(activity.quietPeriodMs()).isEqualTo(1_000)
    }

    @Test
    fun `changes in quick succession lengthen both delays, up to the maximum`() {
        activity.recordChange()
        now += 1_500
        activity.recordChange()
        assertThat(activity.rescanDelayMs()).isEqualTo(1_000)
        assertThat(activity.quietPeriodMs()).isEqualTo(1_000 + 1_000 + 2_000)

        repeat(10) { now += 1_500; activity.recordChange() }
        assertThat(activity.rescanDelayMs()).isEqualTo(6_000)
        assertThat(activity.quietPeriodMs()).isEqualTo(1_000 + 6_000 + 2_000)
    }

    @Test
    fun `both delays drop back once the changes leave the window`() {
        repeat(5) { now += 1_000; activity.recordChange() }
        assertThat(activity.rescanDelayMs()).isEqualTo(4_000)

        now += 19_000 // the first changes leave the 20 s window, the last ones are still in it
        assertThat(activity.rescanDelayMs()).isEqualTo(1_000)

        now += 5_000
        assertThat(activity.rescanDelayMs()).isZero()
        assertThat(activity.quietPeriodMs()).isEqualTo(1_000)
    }

    @Test
    fun `without batching the quiet period stays at its base`() {
        val fixed = BuildActivity(maxRescanDelayMs = 0, baseQuietMs = 3_000, clock = { now })
        repeat(10) { now += 500; fixed.recordChange() }

        assertThat(fixed.rescanDelayMs()).isZero()
        assertThat(fixed.quietPeriodMs()).isEqualTo(3_000)
    }
}
