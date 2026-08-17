package com.ifmix.api.core.graphql.common.directive

import com.ifmix.api.core.common.http.RequestContext
import com.netflix.graphql.dgs.DgsDirective
import com.netflix.graphql.dgs.context.DgsContext
import graphql.schema.DataFetcher
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import org.springframework.stereotype.Component

/**
 * 自定义 @requirePermission directive。
 *
 * Schema 中使用：
 *   directive @requirePermission(permission: String!) on FIELD_DEFINITION
 *
 *   type Mutation {
 *     createTodo(input: CreateTodoInput!): Todo! @requirePermission(permission: "todo:write")
 *   }
 *
 * DGS 中通过实现 SchemaDirectiveWiring 并注册为 @DgsDirective bean。
 */
@Component
@DgsDirective(name = "requirePermission")
class RequirePermissionDirective : SchemaDirectiveWiring {

    override fun onField(
        environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>,
    ): GraphQLFieldDefinition {
        val requiredPermission = environment.getAppliedDirective("requirePermission")
            ?.getArgument("permission")
            ?.getValue<String>()
            ?: throw IllegalArgumentException("@requirePermission requires a 'permission' argument")

        val originalFetcher = environment.fieldDataFetcher

        val authFetcher = DataFetcher<Any> { dfe ->
            val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
            if (!ctx.permissions.contains(requiredPermission)) {
                throw PermissionDeniedException(
                    "Permission denied: requires '$requiredPermission'"
                )
            }
            originalFetcher.get(dfe)
        }

        // Use FieldCoordinates for broader compatibility (GraphQLFieldsContainer vs GraphQLObjectType)
        val fieldDef = environment.fieldDefinition
        val objectType = environment.fieldsContainer as? graphql.schema.GraphQLObjectType
            ?: throw IllegalStateException("Expected GraphQLObjectType but got ${environment.fieldsContainer?.javaClass}")
        environment.codeRegistry
            .dataFetcher(objectType, fieldDef, authFetcher)
        return fieldDef
    }
}

class PermissionDeniedException(message: String) : RuntimeException(message)
