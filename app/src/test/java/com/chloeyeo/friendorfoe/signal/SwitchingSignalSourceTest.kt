package com.chloeyeo.friendorfoe.signal

import com.chloeyeo.friendorfoe.session.VerdictKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchingSignalSourceTest {
    private class StubUsb : SignalSource {
        override val kind = SignalKind.USB_GADGET
        var calls = 0
        override suspend fun challenge(req: ChallengeRequest): Flow<SignalEvent> {
            calls++
            return emptyFlow()
        }
    }

    private val req = ChallengeRequest(3, 7, 1_760_000_000L)

    private fun switching(choice: SignalChoice, connected: Boolean, mode: FakeMode = FakeMode.HONEST): Triple<SwitchingSignalSource, StubUsb, FakeSignalSource> {
        val usb = StubUsb()
        val fake = FakeSignalSource(delayMs = { 0L })
        return Triple(SwitchingSignalSource(usb, fake, { choice }, { mode }, { connected }), usb, fake)
    }

    @Test
    fun `AUTO uses the gadget when connected and the fake otherwise`() {
        val (a, usbA, fakeA) = switching(SignalChoice.AUTO, connected = true)
        assertSame(usbA, a.pick())
        val (b, _, fakeB) = switching(SignalChoice.AUTO, connected = false)
        assertSame(fakeB, b.pick())
        assertTrue(fakeA !== a.pick())
    }

    @Test
    fun `USB and FAKE are fixed choices`() {
        val (u, usb, _) = switching(SignalChoice.USB, connected = false)
        assertSame(usb, u.pick())
        val (f, _, fake) = switching(SignalChoice.FAKE, connected = true)
        assertSame(fake, f.pick())
    }

    @Test
    fun `the fake runs in the selected mode and kind follows the last challenge`() = runBlocking {
        val (s, usb, _) = switching(SignalChoice.FAKE, connected = false, mode = FakeMode.IMPOSTOR)
        val events = s.challenge(req).toList()
        val answer = events.filterIsInstance<SignalEvent.Answer>().single()
        assertEquals(VerdictKind.FOE, answer.verdict)
        assertEquals("bad tag", answer.reason)
        assertEquals(SignalKind.FAKE, s.kind)
        assertEquals(0, usb.calls)

        val (g, usbG, _) = switching(SignalChoice.USB, connected = true)
        g.challenge(req).toList()
        assertEquals(1, usbG.calls)
        assertEquals(SignalKind.USB_GADGET, g.kind)
    }

    @Test
    fun `the fake SILENT mode emits nothing`() = runBlocking {
        assertTrue(FakeSignalSource(FakeMode.SILENT).challenge(req).toList().isEmpty())
    }

    @Test
    fun `every fake mode but SILENT answers once with a 16-byte nonce`() = runBlocking {
        for (mode in FakeMode.entries - FakeMode.SILENT) {
            val events = FakeSignalSource(mode, delayMs = { 0L }).challenge(req).toList()
            val answer = events.filterIsInstance<SignalEvent.Answer>().single()
            assertEquals(mode.name, 16, answer.nonce.size)
            assertTrue(mode.name, events.first() is SignalEvent.Status)
        }
    }

    @Test
    fun `choices and modes parse case-insensitively`() {
        assertEquals(SignalChoice.USB, SignalChoice.parse(" usb "))
        assertEquals(FakeMode.REPLAY, FakeMode.parse("Replay"))
        assertNull(FakeMode.parse("maybe"))
        assertNull(SignalChoice.parse(null))
    }

    @Test
    fun `redaction keeps an 8-hex prefix only`() {
        val hex = "00112233445566778899aabbccddeeff"
        assertEquals("""{"nonce":"00112233…"}""", Redact.hexRuns("""{"nonce":"$hex"}"""))
        assertEquals("00112233…", Redact.nonce(Hex.decode(hex)))
        assertEquals("box 3 · 42 ms", Redact.hexRuns("box 3 · 42 ms"))
        assertEquals(81, Redact.line("x".repeat(200)).length)
    }
}
