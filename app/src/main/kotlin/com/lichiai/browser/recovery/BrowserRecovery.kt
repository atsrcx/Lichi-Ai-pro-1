package com.lichiai.browser.recovery

import com.lichiai.browser.api.BrowserCapabilityAPI
import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.events.BrowserEvent
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.perception.PagePerceptionSnapshot
import com.lichiai.browser.verifier.BrowserVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecoveryAttemptResult(
    val recovered: Boolean,
    val summary: String
)

/**
 * Isolated recovery engine for Browser Agent.
 * Handles transient network dropouts, unclickable DOM candidates, and timeouts.
 * Uses condition-based waits instead of fixed synchronization delays.
 */
class BrowserRecovery(
    private val capabilityApi: BrowserCapabilityAPI,
    private val eventBus: BrowserEventBus
) {

    suspend fun attemptRecovery(
        failedAction: String,
        arguments: Map<String, String>,
        context: BrowserTaskContext,
        attemptNumber: Int
    ): RecoveryAttemptResult {
        if (attemptNumber > 2) {
            return RecoveryAttemptResult(false, "Exceeded maximum recovery attempts.")
        }

        eventBus.emit(BrowserEvent.RecoveryStarted(attemptNumber, "Retrying action '$failedAction'"))

        return when (failedAction) {
            "navigate" -> {
                val url = arguments["url"] ?: return RecoveryAttemptResult(false, "No URL to reload")
                val ok = capabilityApi.navigate(url)
                if (ok) {
                    com.lichiai.browser.runtime.BrowserConditionWaiter.waitForPageReady(timeoutMs = 5000) { capabilityApi.getPageContext().activeEngine }
                    val ctx = capabilityApi.getPageContext()
                    val verified = com.lichiai.browser.verifier.BrowserVerifier.verifyNavigation(url, ctx)
                    if (verified.passed) {
                        RecoveryAttemptResult(true, "Recovered by reloading URL")
                    } else {
                        RecoveryAttemptResult(false, "Reload navigation unconfirmed: ${verified.detail}")
                    }
                } else {
                    RecoveryAttemptResult(false, "Reload request failed")
                }
            }

            "search" -> {
                val q = arguments["query"] ?: return RecoveryAttemptResult(false, "No query")
                val ok = capabilityApi.search(q, "duckduckgo")
                if (ok) {
                    com.lichiai.browser.runtime.BrowserConditionWaiter.waitForDomStable(settleMs = 200L, timeoutMs = 3000) { capabilityApi.getPageContext().activeEngine }
                    val ctx = capabilityApi.getPageContext()
                    val verified = com.lichiai.browser.verifier.BrowserVerifier.verifySearchResults(q, ctx)
                    if (verified.passed) {
                        RecoveryAttemptResult(true, "Recovered using alternate search engine (DuckDuckGo)")
                    } else {
                        RecoveryAttemptResult(false, "Search recovery failed: search results page not confirmed")
                    }
                } else {
                    RecoveryAttemptResult(false, "Alternate search engine request failed")
                }
            }

            "clickCandidate" -> {
                val idx = arguments["index"]?.toIntOrNull() ?: 1
                val candidate = context.extractedCandidates.firstOrNull { it.index == idx }
                if (candidate != null && candidate.title.isNotBlank()) {
                    val ok = capabilityApi.clickSelector(candidate.title.take(30))
                    if (ok) {
                        com.lichiai.browser.runtime.BrowserConditionWaiter.waitForDomStable(settleMs = 200L, timeoutMs = 3000) { capabilityApi.getPageContext().activeEngine }
                        val ctx = capabilityApi.getPageContext()
                        val verified = com.lichiai.browser.verifier.BrowserVerifier.verifyClick(context.currentUrl, ctx)
                        if (verified.passed) {
                            RecoveryAttemptResult(true, "Recovered by clicking candidate text")
                        } else {
                            RecoveryAttemptResult(false, "Candidate click unverified")
                        }
                    } else {
                        capabilityApi.reload()
                        com.lichiai.browser.runtime.BrowserConditionWaiter.waitForPageReady(timeoutMs = 5000) { capabilityApi.getPageContext().activeEngine }
                        val ok2 = capabilityApi.clickCandidate(idx)
                        if (ok2) {
                            com.lichiai.browser.runtime.BrowserConditionWaiter.waitForDomStable(settleMs = 200L, timeoutMs = 3000) { capabilityApi.getPageContext().activeEngine }
                            val ctx2 = capabilityApi.getPageContext()
                            val verified2 = com.lichiai.browser.verifier.BrowserVerifier.verifyClick(context.currentUrl, ctx2)
                            if (verified2.passed) {
                                RecoveryAttemptResult(true, "Recovered after page reload")
                            } else {
                                RecoveryAttemptResult(false, "Retry after reload unconfirmed")
                            }
                        } else {
                            RecoveryAttemptResult(false, "Retry failed")
                        }
                    }
                } else {
                    capabilityApi.reload()
                    com.lichiai.browser.runtime.BrowserConditionWaiter.waitForPageReady(timeoutMs = 5000) { capabilityApi.getPageContext().activeEngine }
                    val ok3 = capabilityApi.clickCandidate(idx)
                    if (ok3) {
                        com.lichiai.browser.runtime.BrowserConditionWaiter.waitForDomStable(settleMs = 200L, timeoutMs = 3000) { capabilityApi.getPageContext().activeEngine }
                        val ctx3 = capabilityApi.getPageContext()
                        val verified3 = com.lichiai.browser.verifier.BrowserVerifier.verifyClick(context.currentUrl, ctx3)
                        if (verified3.passed) {
                            RecoveryAttemptResult(true, "Recovered after page reload")
                        } else {
                            RecoveryAttemptResult(false, "Retry after reload unconfirmed")
                        }
                    } else {
                        RecoveryAttemptResult(false, "Retry failed")
                    }
                }
            }

            else -> {
                RecoveryAttemptResult(false, "No deterministic recovery strategy available for '$failedAction'")
            }
        }
    }
}