package com.pulseshift.app

import com.pulseshift.app.domain.terminal.TerminalCommand
import com.pulseshift.app.domain.terminal.TerminalCommandParser
import com.pulseshift.app.domain.terminal.TerminalCommandParser.ParseError
import com.pulseshift.app.domain.terminal.TerminalCommandParser.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalParserTest {

    private fun parse(raw: String): TerminalCommand = when (val r = TerminalCommandParser.parse(raw)) {
        is ParseResult.Success -> r.command
        is ParseResult.Failure -> throw AssertionError("Expected success for \"$raw\", got ${r.error.message}")
    }

    private fun parseError(raw: String): ParseError = when (val r = TerminalCommandParser.parse(raw)) {
        is ParseResult.Success -> throw AssertionError("Expected failure for \"$raw\", got ${r.command}")
        is ParseResult.Failure -> r.error
    }

    // --- Fixed commands, case/whitespace tolerance ---

    @Test
    fun `fixed commands parse case-insensitively with normalized whitespace`() {
        assertEquals(TerminalCommand.Help, parse("help"))
        assertEquals(TerminalCommand.Help, parse("  HELP  "))
        assertEquals(TerminalCommand.Help, parse("?"))
        assertEquals(TerminalCommand.Status, parse("status"))
        assertEquals(TerminalCommand.Status, parse("  can   status "))
        assertEquals(TerminalCommand.BleStatus, parse("ble status"))
        assertEquals(TerminalCommand.RpmStatus, parse("RPM STATUS"))
        assertEquals(TerminalCommand.ConfigStatus, parse("config status"))
        assertEquals(TerminalCommand.ConfigSave, parse("config SAVE"))
        assertEquals(TerminalCommand.ConfigReset, parse("CONFIG reset"))
        assertEquals(TerminalCommand.Logs, parse("logs"))
        assertEquals(TerminalCommand.DumpNext, parse("dump"))
        assertEquals(TerminalCommand.DumpNext, parse("dump NEXT"))
        assertEquals(TerminalCommand.DumpAll, parse("DUMP ALL"))
        assertEquals(TerminalCommand.DumpCancel, parse("dump cancel"))
        assertEquals(TerminalCommand.Restart, parse("restart"))
        assertEquals(TerminalCommand.ClearDiagnostics, parse("clear diagnostics"))
        assertEquals(TerminalCommand.Auth, parse("auth"))
        assertEquals(TerminalCommand.RpmMonitorOn, parse("rpm monitor on"))
        assertEquals(TerminalCommand.RpmMonitorOff, parse("RPM MONITOR OFF"))
        assertEquals(TerminalCommand.RpmMonitorSample, parse("RPM MONITOR"))
        assertEquals(TerminalCommand.RpmTestOff, parse("rpm test off"))
        assertEquals(TerminalCommand.CaptureStop, parse("capture stop"))
        assertEquals(TerminalCommand.CaptureStop, parse("CAPTURE PAUSE"))
        assertEquals(TerminalCommand.CaptureResume, parse("capture resume"))
        assertEquals(TerminalCommand.CaptureResume, parse("CAPTURE START"))
        assertEquals(TerminalCommand.StreamUnsupported, parse("stream on"))
        assertEquals(TerminalCommand.StreamUnsupported, parse("STREAM OFF"))
        assertEquals(TerminalCommand.ClearWipeUnsupported, parse("clear"))
        assertEquals(TerminalCommand.ClearWipeUnsupported, parse("clear yes"))
    }

    // --- RPM MULTIPLIER ---

    @Test
    fun `rpm multiplier accepts decimal range 0_1 to 1000`() {
        assertEquals(TerminalCommand.RpmMultiplier(42.5), parse("RPM MULTIPLIER 42.5"))
        assertEquals(TerminalCommand.RpmMultiplier(40.0), parse("rpm multiplier 40"))
        assertEquals(TerminalCommand.RpmMultiplier(1000.0), parse("RPM MULTIPLIER 1000.0"))
        assertEquals(TerminalCommand.RpmMultiplier(0.1), parse("RPM MULTIPLIER 0.1"))
    }

    @Test
    fun `rpm multiplier rejects out-of-range and malformed values`() {
        assertTrue(parseError("RPM MULTIPLIER 0") is ParseError.InvalidArgument)
        assertTrue(parseError("RPM MULTIPLIER 1000.1") is ParseError.InvalidArgument)
        assertTrue(parseError("RPM MULTIPLIER -40") is ParseError.InvalidArgument)
        assertTrue(parseError("RPM MULTIPLIER abc") is ParseError.InvalidArgument)
        assertTrue(parseError("RPM MULTIPLIER 1e3") is ParseError.InvalidArgument)
        assertTrue(parseError("RPM MULTIPLIER 40,5") is ParseError.InvalidArgument)
        assertTrue(parseError("RPM MULTIPLIER 40 20") is ParseError.InvalidArgument)
        assertTrue(parseError("RPM MULTIPLIER") is ParseError.MissingArgument)
    }

    // --- EFFECT ---

    @Test
    fun `effect accepts 0 to 5`() {
        assertEquals(TerminalCommand.Effect(0), parse("effect 0"))
        assertEquals(TerminalCommand.Effect(4), parse("EFFECT 4"))
        assertEquals(TerminalCommand.Effect(5), parse("EFFECT 5"))
    }

    @Test
    fun `effect rejects 6, negatives and junk`() {
        assertTrue(parseError("EFFECT 6") is ParseError.InvalidArgument)
        assertTrue(parseError("EFFECT -1") is ParseError.InvalidArgument)
        assertTrue(parseError("EFFECT x") is ParseError.InvalidArgument)
        assertTrue(parseError("EFFECT") is ParseError.MissingArgument)
    }

    // --- RPM TEST ---

    @Test
    fun `rpm test accepts 0 to 20000`() {
        assertEquals(TerminalCommand.RpmTest(4500), parse("RPM TEST 4500"))
        assertEquals(TerminalCommand.RpmTest(20000), parse("rpm test 20000"))
        assertEquals(TerminalCommand.RpmTest(0), parse("RPM TEST 0"))
    }

    @Test
    fun `rpm test rejects 20001 and junk`() {
        assertTrue(parseError("RPM TEST 20001") is ParseError.InvalidArgument)
        assertTrue(parseError("RPM TEST 45x") is ParseError.InvalidArgument)
        assertTrue(parseError("RPM TEST") is ParseError.MissingArgument)
    }

    // --- STAGE ---

    @Test
    fun `stage accepts 1 2 3 only`() {
        assertEquals(TerminalCommand.CaptureStage(1), parse("stage 1"))
        assertEquals(TerminalCommand.CaptureStage(2), parse("STAGE 2"))
        assertEquals(TerminalCommand.CaptureStage(3), parse("stage 3"))
        assertTrue(parseError("STAGE 0") is ParseError.InvalidArgument)
        assertTrue(parseError("STAGE 4") is ParseError.InvalidArgument)
        assertTrue(parseError("STAGE") is ParseError.MissingArgument)
    }

    // --- DUMP PAGE ---

    @Test
    fun `dump page accepts n of 1 or higher`() {
        assertEquals(TerminalCommand.DumpPage(1), parse("dump page 1"))
        assertEquals(TerminalCommand.DumpPage(42), parse("DUMP PAGE 42"))
        assertTrue(parseError("DUMP PAGE 0") is ParseError.InvalidArgument)
        assertTrue(parseError("DUMP PAGE") is ParseError.MissingArgument)
    }

    // --- Errors ---

    @Test
    fun `unknown and empty input are typed errors`() {
        assertTrue(parseError("FROBNICATE") is ParseError.UnknownCommand)
        assertTrue(parseError("") is ParseError.UnknownCommand)
        assertTrue(parseError("   ") is ParseError.UnknownCommand)
        assertTrue(parseError("rpm multiplierx 40") is ParseError.InvalidArgument)
    }

    // --- Guard-rail metadata ---

    @Test
    fun `destructive commands require confirmation`() {
        assertTrue(parse("CONFIG RESET").requiresConfirmation)
        assertTrue(parse("RESTART").requiresConfirmation)
        assertTrue(parse("CLEAR DIAGNOSTICS").requiresConfirmation)
        assertFalse(parse("CONFIG SAVE").requiresConfirmation)
        assertFalse(parse("RPM TEST 4500").requiresConfirmation)
        assertFalse(parse("STATUS").requiresConfirmation)
    }

    @Test
    fun `transport classification matches contract`() {
        assertEquals(TerminalCommand.Transport.LOCAL_ONLY, parse("HELP").transport)
        assertEquals(TerminalCommand.Transport.LOCAL_ONLY, parse("AUTH").transport)
        assertEquals(TerminalCommand.Transport.LOCAL_ONLY, parse("STREAM ON").transport)
        assertEquals(TerminalCommand.Transport.LOCAL_ONLY, parse("CLEAR YES").transport)
        assertEquals(TerminalCommand.Transport.BLE, parse("STATUS").transport)
        assertEquals(TerminalCommand.Transport.BLE, parse("RPM STATUS").transport)
        assertEquals(TerminalCommand.Transport.BLE, parse("CONFIG SAVE").transport)
        assertEquals(TerminalCommand.Transport.BLE, parse("STAGE 2").transport)
        assertEquals(TerminalCommand.Transport.BLE, parse("DUMP ALL").transport)
    }
}
