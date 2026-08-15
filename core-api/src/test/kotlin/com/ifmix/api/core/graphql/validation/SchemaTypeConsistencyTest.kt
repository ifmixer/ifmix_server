package com.ifmix.api.core.graphql.validation

import assertk.assertThat
import assertk.assertions.contains
import com.ifmix.api.core.graphql.common.type.CollectionItemConnection
import com.ifmix.api.core.graphql.common.type.CollectionItemType
import com.ifmix.api.core.graphql.common.type.CollectionType
import com.ifmix.api.core.graphql.common.type.OperationResult
import com.ifmix.api.core.graphql.common.type.PresignDownloadResult
import com.ifmix.api.core.graphql.common.type.PresignUploadResult
import com.ifmix.api.core.graphql.common.type.ScanConnection
import com.ifmix.api.core.graphql.common.type.ScanRecordType
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

    @Test
    fun `ScanRecordType fields match schema ScanRecord type`() {
        // userDisplayName and userNotes are Kotlin-only fields not yet in the schema
        assertTypeFieldsMatch("ScanRecord", ScanRecordType::class, ignoredFields = setOf("userDisplayName", "userNotes"))
    }

    @Test
    fun `ScanConnection fields match schema ScanConnection type`() {
        assertTypeFieldsMatch("ScanConnection", ScanConnection::class)
    }

    @Test
    fun `PresignUploadResult fields match schema PresignUploadResult type`() {
        assertTypeFieldsMatch("PresignUploadResult", PresignUploadResult::class)
    }

    @Test
    fun `PresignDownloadResult fields match schema PresignDownloadResult type`() {
        assertTypeFieldsMatch("PresignDownloadResult", PresignDownloadResult::class)
    }

    @Test
    fun `CollectionType fields match schema Collection type`() {
        assertTypeFieldsMatch("Collection", CollectionType::class)
    }

    @Test
    fun `CollectionItemType fields match schema CollectionItemType type`() {
        assertTypeFieldsMatch("CollectionItemType", CollectionItemType::class)
    }

    @Test
    fun `CollectionItemConnection fields match schema CollectionItemConnection type`() {
        assertTypeFieldsMatch("CollectionItemConnection", CollectionItemConnection::class)
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
