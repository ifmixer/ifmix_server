package com.ifmix.api.core.infra.graphql

import com.ifmix.api.core.entity.feedback.Feedback
import com.ifmix.api.core.entity.iap.Subscription
import com.ifmix.api.core.entity.scan.ScanCollection
import com.ifmix.api.core.entity.scan.ScanCollectionItem
import com.ifmix.api.core.entity.scan.ScanRecord
import com.ifmix.api.core.entity.storage.UploadRecord
import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
import graphql.schema.DataFetchingFieldSelectionSet
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.fetcher.impl.FetcherImpl
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

/**
 * 从 GraphQL selection set 构建 Jimmer Fetcher，实现按需字段查询。
 */
@Component
class FetcherBuilder {

    fun <E : Any> build(
        entityType: KClass<E>,
        selectionSet: DataFetchingFieldSelectionSet,
    ): Fetcher<E> {
        @Suppress("UNCHECKED_CAST")
        return when (entityType) {
            Todo::class -> buildTodo(selectionSet) as Fetcher<E>
            TodoItem::class -> buildTodoItem(selectionSet) as Fetcher<E>
            ScanRecord::class -> buildScanRecord(selectionSet) as Fetcher<E>
            ScanCollection::class -> buildScanCollection(selectionSet) as Fetcher<E>
            ScanCollectionItem::class -> buildScanCollectionItem(selectionSet) as Fetcher<E>
            UploadRecord::class -> buildUploadRecord(selectionSet) as Fetcher<E>
            Feedback::class -> buildFeedback(selectionSet) as Fetcher<E>
            Subscription::class -> buildSubscription(selectionSet) as Fetcher<E>
            else -> throw IllegalArgumentException("Unsupported entity type: ${entityType.simpleName}")
        }
    }

    /**
     * 从父字段的 selection set 中提取子字段 fetcher。
     */
    fun <E : Any> buildFromField(
        entityType: KClass<E>,
        parentSelectionSet: DataFetchingFieldSelectionSet,
        fieldName: String,
    ): Fetcher<E>? {
        val field = parentSelectionSet.fields.find { it.name == fieldName } ?: return null
        val subSet = field.selectionSet ?: return null
        return build(entityType, subSet)
    }

    /**
     * 构建最小 fetcher（只包含 id），用于没有嵌套 select 时作为 fallback。
     */
    fun <E : Any> minimalFetcher(entityType: KClass<E>): Fetcher<E> {
        @Suppress("UNCHECKED_CAST")
        return when (entityType) {
            Todo::class -> FetcherImpl(Todo::class.java) as Fetcher<E>
            TodoItem::class -> FetcherImpl(TodoItem::class.java) as Fetcher<E>
            ScanRecord::class -> FetcherImpl(ScanRecord::class.java) as Fetcher<E>
            ScanCollection::class -> FetcherImpl(ScanCollection::class.java) as Fetcher<E>
            ScanCollectionItem::class -> FetcherImpl(ScanCollectionItem::class.java) as Fetcher<E>
            UploadRecord::class -> FetcherImpl(UploadRecord::class.java) as Fetcher<E>
            Feedback::class -> FetcherImpl(Feedback::class.java) as Fetcher<E>
            Subscription::class -> FetcherImpl(Subscription::class.java) as Fetcher<E>
            else -> throw IllegalArgumentException("Unsupported entity type: ${entityType.simpleName}")
        }
    }

    // ===== Per-entity fetcher builders =====

    private fun buildTodo(selectionSet: DataFetchingFieldSelectionSet): Fetcher<Todo> {
        var f: Fetcher<Todo> = FetcherImpl(Todo::class.java)
        selectionSet.fields.forEach { field ->
            f = when (field.name) {
                "id" -> f.add("id")
                "title" -> f.add("title")
                "done" -> f.add("done")
                "note" -> f.add("note")
                "meta" -> f.add("meta")
                "createdAt" -> f.add("createdAt")
                "updatedAt" -> f.add("updatedAt")
                "items" -> {
                    val sub = field.selectionSet
                    if (sub != null) f.add("items", buildTodoItem(sub)) else f
                }
                else -> f
            }
        }
        return f
    }

    private fun buildTodoItem(selectionSet: DataFetchingFieldSelectionSet): Fetcher<TodoItem> {
        var f: Fetcher<TodoItem> = FetcherImpl(TodoItem::class.java)
        selectionSet.fields.forEach { field ->
            f = when (field.name) {
                "id" -> f.add("id")
                "content" -> f.add("content")
                "done" -> f.add("done")
                "createdAt" -> f.add("createdAt")
                "updatedAt" -> f.add("updatedAt")
                else -> f
            }
        }
        return f
    }

    private fun buildScanRecord(selectionSet: DataFetchingFieldSelectionSet): Fetcher<ScanRecord> {
        var f: Fetcher<ScanRecord> = FetcherImpl(ScanRecord::class.java)
        selectionSet.fields.forEach { field ->
            f = when (field.name) {
                "id" -> f.add("id")
                "images" -> f.add("images")
                "result" -> f.add("result")
                "status" -> f.add("status")
                "clientIp" -> f.add("clientIp")
                "lang" -> f.add("lang")
                "country" -> f.add("country")
                "currency" -> f.add("currency")
                "userDisplayName" -> f.add("userDisplayName")
                "userNotes" -> f.add("userNotes")
                "collected" -> f.add("collected")
                "createdAt" -> f.add("createdAt")
                "updatedAt" -> f.add("updatedAt")
                else -> f
            }
        }
        return f
    }

    private fun buildScanCollection(selectionSet: DataFetchingFieldSelectionSet): Fetcher<ScanCollection> {
        var f: Fetcher<ScanCollection> = FetcherImpl(ScanCollection::class.java)
        selectionSet.fields.forEach { field ->
            f = when (field.name) {
                "id" -> f.add("id")
                "isDefault" -> f.add("isDefault")
                "createdAt" -> f.add("createdAt")
                else -> f
            }
        }
        return f
    }

    private fun buildScanCollectionItem(selectionSet: DataFetchingFieldSelectionSet): Fetcher<ScanCollectionItem> {
        var f: Fetcher<ScanCollectionItem> = FetcherImpl(ScanCollectionItem::class.java)
        selectionSet.fields.forEach { field ->
            f = when (field.name) {
                "id" -> f.add("id")
                "createdAt" -> f.add("createdAt")
                "scanRecord" -> {
                    val sub = field.selectionSet
                    if (sub != null) f.add("scanRecord", buildScanRecord(sub)) else f
                }
                else -> f
            }
        }
        return f
    }

    private fun buildUploadRecord(selectionSet: DataFetchingFieldSelectionSet): Fetcher<UploadRecord> {
        var f: Fetcher<UploadRecord> = FetcherImpl(UploadRecord::class.java)
        selectionSet.fields.forEach { field ->
            if (field.name == "id") f = f.add("id")
        }
        return f
    }

    private fun buildFeedback(selectionSet: DataFetchingFieldSelectionSet): Fetcher<Feedback> {
        var f: Fetcher<Feedback> = FetcherImpl(Feedback::class.java)
        selectionSet.fields.forEach { field ->
            if (field.name == "id") f = f.add("id")
        }
        return f
    }

    private fun buildSubscription(selectionSet: DataFetchingFieldSelectionSet): Fetcher<Subscription> {
        var f: Fetcher<Subscription> = FetcherImpl(Subscription::class.java)
        selectionSet.fields.forEach { field ->
            if (field.name == "id") f = f.add("id")
        }
        return f
    }
}
