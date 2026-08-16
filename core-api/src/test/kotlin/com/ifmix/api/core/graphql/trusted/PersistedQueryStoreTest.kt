package com.ifmix.api.core.graphql.trusted

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.ifmix.api.core.graphql.common.trusted.ClasspathPersistedQueryStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PersistedQueryStoreTest {

    private lateinit var store: ClasspathPersistedQueryStore

    @BeforeEach
    fun setup() {
        store = ClasspathPersistedQueryStore("classpath:graphql/persisted-queries/")
        // init() is called by Spring at startup; call manually here for test
        store.init()
    }

    @Test
    fun `get returns entry for known hash and customer bff`() {
        val entry = store.get("a1b2c3d4e5f6", "customer")
        assertThat(entry).isNotNull()
        assertThat(entry!!.name).isEqualTo("todo_get")
    }

    @Test
    fun `get returns null for unknown hash`() {
        val entry = store.get("unknownhash", "customer")
        assertThat(entry).isNull()
    }

    @Test
    fun `get returns null for hash not in customer allowlist when queried with admin bff`() {
        val entry = store.get("a1b2c3d4e5f6", "admin")
        assertThat(entry).isNull()
    }

    @Test
    fun `get returns admin entry for admin bff`() {
        val entry = store.get("ad1234567890", "admin")
        assertThat(entry).isNotNull()
        assertThat(entry!!.name).isEqualTo("todo_list")
    }

    @Test
    fun `getByName returns entry for known name and customer bff`() {
        val entry = store.getByName("todo_get", "customer")
        assertThat(entry).isNotNull()
        assertThat(entry!!.name).isEqualTo("todo_get")
        assertThat(entry.query).contains("query todo_get")
    }

    @Test
    fun `getByName returns null for unknown name`() {
        val entry = store.getByName("UnknownOp", "customer")
        assertThat(entry).isNull()
    }

    @Test
    fun `getByName returns entry for admin bff`() {
        val entry = store.getByName("todo_list", "admin")
        assertThat(entry).isNotNull()
        assertThat(entry!!.name).isEqualTo("todo_list")
    }

    @Test
    fun `getByName finds same entry as get for known hash`() {
        val byHash = store.get("f6e5d4c3b2a1", "customer")
        val byName = store.getByName("todo_list", "customer")
        assertThat(byName).isNotNull()
        assertThat(byName!!.name).isEqualTo(byHash!!.name)
        assertThat(byName.query).isEqualTo(byHash.query)
    }
}
