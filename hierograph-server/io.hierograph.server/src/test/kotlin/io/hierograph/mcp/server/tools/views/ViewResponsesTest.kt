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
package io.hierograph.mcp.server.tools.views

import io.hierograph.server.views.ViewFinding
import io.hierograph.server.views.ViewFinding.Code
import io.hierograph.server.views.ViewReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ViewResponsesTest {

    private fun finding(code: Code, i: Int) = ViewFinding(code.severity, code, "g$i", "finding $i")

    @Test
    fun `the capped findings list errors first, so a flood of warnings cannot hide them`() {
        val warnings = (1..60).map { finding(Code.EMPTY_GROUP, it) }
        val report = ViewReport(warnings + finding(Code.MEMBERS_WITH_DIFFERENT_PARENTS, 61))

        @Suppress("UNCHECKED_CAST")
        val findings = ViewResponses.report(report)["findings"] as List<Map<String, Any?>>

        assertThat(findings).hasSize(ViewResponses.MAX_FINDINGS)
        assertThat(findings.first()["code"]).isEqualTo("MEMBERS_WITH_DIFFERENT_PARENTS")
        assertThat(findings.drop(1)).allMatch { it["severity"] == "warning" }
        assertThat(ViewResponses.report(report)["finding_count"]).isEqualTo(61)
    }
}
