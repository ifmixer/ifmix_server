package com.ifmix.api.core.graphql.router

import com.netflix.graphql.dgs.DgsQueryExecutor
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Dual-endpoint GraphQL router. */
@RestController
class GraphQLRouterController(
    private val dgsQueryExecutor: DgsQueryExecutor,
) {

    @PostMapping(
        "/customer/graphql",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun customerGraphql(@RequestBody body: Map<String, Any?>): ResponseEntity<String> =
        executeGraphQL(body)

    @PostMapping(
        "/admin/graphql",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun adminGraphql(@RequestBody body: Map<String, Any?>): ResponseEntity<String> =
        executeGraphQL(body)

    private fun executeGraphQL(body: Map<String, Any?>): ResponseEntity<String> {
        val query = body["query"] as? String ?: ""
        @Suppress("UNCHECKED_CAST")
        val variables = body["variables"] as? Map<String, Any> ?: emptyMap()
        val operationName = body["operationName"] as? String

        val result = dgsQueryExecutor.execute(query, variables, operationName)

        val json = result.toSpecification().toString()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(json)
    }
}
