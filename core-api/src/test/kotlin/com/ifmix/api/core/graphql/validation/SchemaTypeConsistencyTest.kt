package com.ifmix.api.core.graphql.validation

import assertk.assertThat
import assertk.assertions.contains
import com.ifmix.api.core.graphql.common.type.OperationResult
import com.ifmix.api.core.graphql.common.type.TodoConnection
import com.ifmix.api.core.graphql.common.type.TodoItemType
import com.ifmix.api.core.graphql.common.type.TodoType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.language.ObjectTypeDefinition
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * 校验 schema.graphqls 中定义的类型字段与 Kotlin type class 的属性一致。
 * 如果不一致，此测试将失败，提示哪个字段缺失。
 */
class SchemaTypeConsistencyTest {

    private val registry: TypeDefinitionRegistry by lazy {
        val schemaResource = this::class.java.classLoader.getResource("schema/schema.graphqls")!!
        SchemaParser().parse(schemaResource.readText())
    }

    @Test
    fun `TodoType fields match schema Todo type`() {
        assertTypeFieldsMatch("Todo", TodoType::class, ignoredFields = setOf("items"))
    }

    @Test
    fun `TodoItemType fields match schema TodoItem type`() {
        assertTypeFieldsMatch("TodoItem", TodoItemType::class)
    }

    @Test
    fun `TodoConnection fields match schema TodoConnection type`() {
        assertTypeFieldsMatch("TodoConnection", TodoConnection::class)
    }

    @Test
    fun `OperationResult fields match schema OperationResult type`() {
        assertTypeFieldsMatch("OperationResult", OperationResult::class)
    }

    private fun assertTypeFieldsMatch(
        schemaTypeName: String,
        kotlinClass: KClass<*>,
        ignoredFields: Set<String> = emptySet(),
    ) {
        val typeDef = registry.getTypeOrNull(schemaTypeName) as? ObjectTypeDefinition
            ?: throw AssertionError("Schema type '$schemaTypeName' not found in schema.graphqls")

        val schemaFields = typeDef.fieldDefinitions
            .map { it.name }
            .filter { it !in ignoredFields }
            .toSet()

        val kotlinFields = kotlinClass.memberProperties
            .map { it.name }
            .toSet()

        for (field in schemaFields) {
            assertThat(kotlinFields).contains(field)
        }
    }
}
