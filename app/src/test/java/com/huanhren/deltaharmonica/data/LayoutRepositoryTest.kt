package com.huanhren.deltaharmonica.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.huanhren.deltaharmonica.model.DefaultLayouts
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LayoutRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedLayoutSurvivesStoreRecreation() = runBlocking {
        val file = temporary.newFile("layout.preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = LayoutRepository(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        val layout = DefaultLayouts.create(1_000L)
        try {
            assertNull(first.load())
            first.save(layout)
            assertEquals(layout, first.load())
        } finally { firstScope.coroutineContext.job.cancelAndJoin() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = LayoutRepository(PreferenceDataStoreFactory.create(scope = secondScope) { file })
            assertEquals(layout, second.load())
        } finally { secondScope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun damagedStoredJsonIsNotSilentlyReplaced() = runBlocking {
        val file = temporary.newFile("damaged.preferences_pb").also { it.delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val key = stringPreferencesKey("layout_json")
            store.edit { it[key] = "invalid-json" }
            val failure = runCatching { LayoutRepository(store).load() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("invalid-json", store.data.first()[key])
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
