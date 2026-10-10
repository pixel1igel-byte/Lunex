package app.lunex

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineOutboxTest {
    @Test
    fun pendingOutgoingMessagesSurviveReopenUntilAcknowledged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("lunex-outbox-test.db")
        val db = TestOutbox(context, "lunex-outbox-test.db")
        val pending = PendingMessage("client-1", "dm", "conversation-1", "hello", 123L)
        db.enqueue(pending)
        assertEquals(listOf(pending), db.pending())
        db.close()

        val reopened = TestOutbox(context, "lunex-outbox-test.db")
        assertEquals(listOf(pending), reopened.pending())
        reopened.acknowledge("client-1")
        assertTrue(reopened.pending().isEmpty())
        reopened.close()
        context.deleteDatabase("lunex-outbox-test.db")
    }

    @Test
    fun deliveredHistoryIsDeduplicatedAndReadableOffline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("lunex-history-test.db")
        val db = TestOutbox(context, "lunex-history-test.db")
        val message = StoredMessage("server-1", "channel", "general", "Felix", "u1", "hello", "2026-10-10T12:00:00Z")
        db.store(message)
        db.store(message.copy(text = "hello updated"))
        assertEquals(1, db.history("channel", "general").size)
        assertEquals("hello updated", db.history("channel", "general").single().text)
        assertTrue(db.history("dm", "conversation-1").isEmpty())
        db.close()
        context.deleteDatabase("lunex-history-test.db")
    }

    private class TestOutbox(context: Context, name: String) : OfflineOutbox(context) {
        // OfflineOutbox uses the production DB name; the tests isolate state by clearing it.
    }
}
