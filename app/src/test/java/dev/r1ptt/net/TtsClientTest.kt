package dev.r1ptt.net

import dev.r1ptt.data.Config
import dev.r1ptt.data.Endpoint
import dev.r1ptt.data.Tts
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsClientTest {
    @Test
    fun elevenLabsStreamsRawPcmFromTheVoiceId() {
        val cfg = Config(tts = Tts(endpoint = Endpoint("https://api.elevenlabs.io/v1", apiKey = "xi", model = "eleven_flash_v2_5"), voice = "JBFqnCBsd6RMkjVDRZzb"))
        val req = TtsClient { cfg }.call("Claude Code on cerebro: Needs decision").request()
        assertEquals("https://api.elevenlabs.io/v1/text-to-speech/JBFqnCBsd6RMkjVDRZzb/stream?output_format=pcm_24000", req.url.toString())
        assertEquals("xi", req.header("xi-api-key"))
        val body = JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8())
        assertEquals("Claude Code on cerebro: Needs decision", body.getString("text"))
        assertEquals("eleven_flash_v2_5", body.getString("model_id"))
    }

    @Test
    fun openAiStillUsesAudioSpeech() {
        val req = TtsClient { Config(openaiKey = "sk") }.call("hi").request()
        assertEquals("https://api.openai.com/v1/audio/speech", req.url.toString())
        assertEquals("Bearer sk", req.header("Authorization"))
    }
}
