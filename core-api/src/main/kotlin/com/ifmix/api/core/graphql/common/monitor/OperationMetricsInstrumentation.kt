package com.ifmix.api.core.graphql.common.monitor

import com.netflix.graphql.dgs.context.DgsContext
import graphql.ExecutionResult
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.concurrent.CompletableFuture

/**
 * Per-operation metrics instrumentation for GraphQL.
 *
 * Metrics exposed:
 * - graphql.operation.count (Counter) — tags: bff, operation, result
 * - graphql.operation.duration (Timer) — tags: bff, operation
 *
 * Operation name 从 TrustedDocumentFilter 注入的 request attribute 取（可信来源）；
 * 开发模式下 fallback 到 GraphQL operation name。
 */
@Component
class OperationMetricsInstrumentation(
    private val meterRegistry: MeterRegistry,
) : SimplePerformantInstrumentation() {

    override fun beginExecution(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?,
    ): InstrumentationContext<ExecutionResult>? {
        val startTime = System.nanoTime()
        return object : InstrumentationContext<ExecutionResult> {
            override fun onDispatched() {}

            override fun onCompleted(result: ExecutionResult?, t: Throwable?) {
                val elapsed = System.nanoTime() - startTime
                val operationName = getOperationName(parameters)
                val bff = getBff()

                if (operationName != null) {
                    val hasErrors = result?.errors?.isNotEmpty() == true || t != null
                    val resultTag = if (hasErrors) "error" else "success"

                    meterRegistry.counter(
                        "graphql.operation.count",
                        "bff", bff,
                        "operation", operationName,
                        "result", resultTag,
                    ).increment()

                    Timer.builder("graphql.operation.duration")
                        .tag("bff", bff)
                        .tag("operation", operationName)
                        .register(meterRegistry)
                        .record(elapsed, java.util.concurrent.TimeUnit.NANOSECONDS)
                }
            }
        }
    }

    private fun getOperationName(parameters: InstrumentationExecutionParameters): String? {
        // Trusted operation name（来自 allowlist，不可篡改）
        val trustedName = getCurrentRequest()?.getAttribute("trusted.operation.name") as? String
        if (trustedName != null) return trustedName
        // Fallback: GraphQL operation name（开发模式）
        return parameters.operation
    }

    private fun getBff(): String {
        val uri = getCurrentRequest()?.requestURI
        return if (uri?.startsWith("/admin/") == true) "admin" else "customer"
    }

    private fun getCurrentRequest(): HttpServletRequest? {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        return attrs?.request
    }
}
