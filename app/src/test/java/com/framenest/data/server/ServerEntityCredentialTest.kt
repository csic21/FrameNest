package com.framenest.data.server

import com.framenest.core.model.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ServerEntityCredentialTest {

    @Test
    fun entity_hasCredentialAlias_notPasswordField() {
        val fields = ServerEntity::class.java.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertTrue("credentialAlias must be persisted", "credentialAlias" in fields)
        assertFalse("password must never be a Room field", "password" in fields)
        assertFalse("passwd must never be a Room field", "passwd" in fields)
    }

    @Test
    fun model_roundTrip_keepsAliasOnly() {
        val model = SavedServer(
            id = "id-1",
            name = "NAS",
            host = "192.168.1.10",
            port = 445,
            username = "user",
            domain = "HOME",
            credentialAlias = "cred_abc",
            defaultShare = "media",
        )
        val entity = ServerEntity.fromModel(model, createdAtMs = 1L, updatedAtMs = 2L)
        assertEquals("cred_abc", entity.credentialAlias)
        assertEquals(model, entity.toModel())
    }

    @Test
    fun inMemoryCredentialStore_isolatesSecretsFromEntity() {
        val store = InMemoryCredentialStore()
        val alias = store.createAlias()
        store.savePassword(alias, "s3cret".toCharArray())

        val entity = ServerEntity(
            id = "1",
            name = "n",
            host = "h",
            port = 445,
            username = "u",
            domain = null,
            credentialAlias = alias,
            defaultShare = null,
            createdAtMs = 0,
            updatedAtMs = 0,
        )
        // Entity serialization surface must not embed the password string.
        assertFalse(entity.toString().contains("s3cret"))
        assertEquals("s3cret", String(store.getPassword(alias)!!))
    }
}
