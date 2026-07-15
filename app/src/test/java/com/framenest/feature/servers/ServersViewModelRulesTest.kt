package com.framenest.feature.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServersViewModelRulesTest {
    @Test
    fun parseServerPort_acceptsOnlyExplicitValidNumber() {
        assertEquals(445, parseServerPort(" 445 "))
        assertNull(parseServerPort(""))
        assertNull(parseServerPort("144x"))
        assertNull(parseServerPort("0"))
        assertNull(parseServerPort("65536"))
    }

    @Test
    fun editingAnyFieldInvalidatesPreviousConnectionTest() {
        val tested = ServerEditorState(
            host = "old-host",
            isTesting = true,
            testMessage = "连接成功",
            testSucceeded = true,
            formError = "old error",
        )

        val edited = applyServerEditorChange(tested) { it.copy(host = "new-host") }

        assertEquals("new-host", edited.host)
        assertEquals(false, edited.isTesting)
        assertNull(edited.testMessage)
        assertEquals(false, edited.testSucceeded)
        assertNull(edited.formError)
    }
}
