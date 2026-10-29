package dev.r1ptt.herdr

import dev.r1ptt.herdr.model.ReceiptStatus
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HerdrClientTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @After fun stop() = server.shutdown()

    private fun serve(handle: (RecordedRequest) -> MockResponse): HerdrClient {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handle(request).also { requests += request }
        }
        server.start()
        return HerdrClient(server.url("/").toString(), "tok")
    }

    private fun receipt(key: String, status: String) = MockResponse().setBody(JSONObject().put("receipt", JSONObject()
        .put("action_id", "act_1").put("idempotency_key", key).put("kind", "message").put("status", status)).toString())

    @Test fun `a lost acknowledgement is recovered from the receipt, never re-sent`() {
        val client = serve { r ->
            if (r.method == "POST") MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else receipt(r.requestUrl!!.queryParameter("idempotency_key")!!, "accepted")
        }
        val result = client.message("m1:p1", "hello", 1)
        assertEquals(ReceiptStatus.ACCEPTED, result.status)
        assertEquals(1, requests.count { it.method == "POST" })
        val post = requests.first { it.method == "POST" }
        assertNotNull(post.getHeader("Idempotency-Key"))
        assertEquals(post.getHeader("Idempotency-Key"), result.key)
    }

    @Test fun `when the receipt cannot be read either, the outcome is unknown`() {
        val client = serve { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val result = client.stop("s", 1)
        assertEquals(ReceiptStatus.UNKNOWN, result.status)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun `a gateway error after the bridge accepted is settled from the receipt`() {
        val client = serve { r ->
            if (r.method == "POST") MockResponse().setResponseCode(502).setBody("Bad Gateway")
            else receipt(r.requestUrl!!.queryParameter("idempotency_key")!!, "accepted")
        }
        assertEquals(ReceiptStatus.ACCEPTED, client.delegate("s", "write tests", 1).status)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun `a refusal before dispatch is a rejected receipt with its code`() {
        val client = serve { MockResponse().setResponseCode(404).setBody("""{"error":{"code":"session_not_found","message":"No such session."}}""") }
        val result = client.message("gone", "hi", 1)
        assertEquals(ReceiptStatus.REJECTED, result.status)
        assertEquals("session_not_found", result.code)
    }

    @Test fun `machine-qualified ids are encoded once in the path`() {
        val client = serve { receipt("k", "accepted") }
        client.stop("local:w1 p/2", 3)
        assertEquals("/v1/sessions/local:w1%20p%2F2/actions/stop", requests.single().path)
        assertEquals("Bearer tok", requests.single().getHeader("Authorization"))
    }

    @Test fun `insight reads model, context and the work still running`() {
        val client = serve { MockResponse().setBody("""{"insight":{"session_id":"local/w1:p1","harness":"codex","model":"gpt-6-luna","effort":"high",
            "context":{"used":120688,"window":258400},
            "subagents":[{"id":"a","description":"Review the diff","type":"reviewer","state":"working","started_at":"t","ended_at":null}],
            "tasks":[{"id":"b","command":"npm test","state":"done","started_at":"t","ended_at":"t"}],
            "last_reply":null,"limit":{"message":"usage limit reached","at":"t"},"read_at":"t"}}""") }
        val i = client.insight("local/w1:p1")!!
        assertEquals("gpt-6-luna", i.model)
        assertEquals(258400, i.contextWindow)
        assertEquals(listOf(true), i.subagents.map { it.running })
        assertEquals(listOf(false), i.tasks.map { it.running })
        assertEquals("usage limit reached", i.limit)
    }

    @Test fun `an unreadable harness has no insight`() {
        assertEquals(null, serve { MockResponse().setBody("""{"insight":null}""") }.insight("s"))
    }
}
