package com.pulseshift.app.domain.terminal

import com.pulseshift.app.domain.terminal.TerminalCommand as Cmd

/**
 * Parses raw terminal text into [TerminalCommand]s.
 *
 * Commands are case-insensitive and internal whitespace is collapsed to single
 * spaces, but there is deliberately NO fuzzy matching: a misspelled or
 * out-of-range argument is a typed error, never a "closest match" execution —
 * especially for destructive operations.
 *
 * Ranges mirror the firmware contract (docs/ANDROID-TERMINAL.md and the USB
 * serial console):
 *  - RPM MULTIPLIER  0.1 .. 1000.0 (sent as u32 LE milli-units)
 *  - EFFECT          0 .. 5 (5 = unverified provisional CAN preview)
 *  - RPM TEST        0 .. 20000
 *  - STAGE           1 .. 3
 *  - DUMP PAGE       >= 1
 */
object TerminalCommandParser {

    sealed class ParseResult {
        data class Success(val command: Cmd) : ParseResult()
        data class Failure(val error: ParseError) : ParseResult()
    }

    sealed class ParseError {
        abstract val message: String

        data class UnknownCommand(val input: String) : ParseError() {
            override val message: String =
                "Unknown command: \"$input\". Type HELP for the command list."
        }

        data class MissingArgument(val syntax: String) : ParseError() {
            override val message: String = "Missing argument. Usage: $syntax"
        }

        data class InvalidArgument(
            val argument: String,
            val reason: String,
            val syntax: String,
        ) : ParseError() {
            override val message: String =
                "Invalid argument \"$argument\": $reason. Usage: $syntax"
        }
    }

    const val SYNTAX_RPM_MULTIPLIER = "RPM MULTIPLIER <0.1..1000.0>"
    const val SYNTAX_EFFECT = "EFFECT <0..5>"
    const val SYNTAX_RPM_TEST = "RPM TEST <0..20000>"
    const val SYNTAX_STAGE = "STAGE <1|2|3>"
    const val SYNTAX_DUMP_PAGE = "DUMP PAGE <n>"

    fun parse(raw: String): ParseResult {
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) {
            return ParseResult.Failure(ParseError.UnknownCommand(""))
        }

        return when (normalized.uppercase()) {
            "HELP", "?" -> success(Cmd.Help)
            "STATUS", "CAN STATUS" -> success(Cmd.Status)
            "BLE STATUS" -> success(Cmd.BleStatus)
            "RPM STATUS" -> success(Cmd.RpmStatus)
            "RPM MONITOR ON" -> success(Cmd.RpmMonitorOn)
            "RPM MONITOR OFF" -> success(Cmd.RpmMonitorOff)
            "RPM MONITOR" -> success(Cmd.RpmMonitorSample)
            "CONFIG STATUS" -> success(Cmd.ConfigStatus)
            "CONFIG SAVE" -> success(Cmd.ConfigSave)
            "CONFIG RESET" -> success(Cmd.ConfigReset)
            "RPM TEST OFF" -> success(Cmd.RpmTestOff)
            "CAPTURE STOP", "CAPTURE PAUSE" -> success(Cmd.CaptureStop)
            "CAPTURE RESUME", "CAPTURE START" -> success(Cmd.CaptureResume)
            "LOGS" -> success(Cmd.Logs)
            "DUMP", "DUMP NEXT" -> success(Cmd.DumpNext)
            "DUMP ALL" -> success(Cmd.DumpAll)
            "DUMP CANCEL" -> success(Cmd.DumpCancel)
            "RESTART" -> success(Cmd.Restart)
            "CLEAR DIAGNOSTICS" -> success(Cmd.ClearDiagnostics)
            "AUTH" -> success(Cmd.Auth)
            "STREAM ON", "STREAM OFF" -> success(Cmd.StreamUnsupported)
            "CLEAR", "CLEAR YES" -> success(Cmd.ClearWipeUnsupported)
            else -> parseWithArgument(normalized)
        }
    }

    private fun parseWithArgument(normalized: String): ParseResult {
        val upper = normalized.uppercase()
        return when {
            upper.startsWith("RPM MULTIPLIER") ->
                parseArg(normalized, "RPM MULTIPLIER", SYNTAX_RPM_MULTIPLIER) { arg ->
                    Cmd.RpmMultiplier(parseMultiplier(arg))
                }

            upper.startsWith("EFFECT") ->
                parseArg(normalized, "EFFECT", SYNTAX_EFFECT) { arg ->
                    Cmd.Effect(parseInt(arg, 0, 5, "effect id must be an integer 0..5 (5 = provisional preview)"))
                }

            upper.startsWith("RPM TEST") ->
                parseArg(normalized, "RPM TEST", SYNTAX_RPM_TEST) { arg ->
                    Cmd.RpmTest(parseInt(arg, 0, 20_000, "target RPM must be an integer 0..20000"))
                }

            upper.startsWith("STAGE") ->
                parseArg(normalized, "STAGE", SYNTAX_STAGE) { arg ->
                    Cmd.CaptureStage(parseInt(arg, 1, 3, "stage must be 1, 2 or 3"))
                }

            upper.startsWith("DUMP PAGE") ->
                parseArg(normalized, "DUMP PAGE", SYNTAX_DUMP_PAGE) { arg ->
                    Cmd.DumpPage(parseInt(arg, 1, Int.MAX_VALUE, "page must be an integer 1 or higher"))
                }

            else -> ParseResult.Failure(ParseError.UnknownCommand(normalized))
        }
    }

    private inline fun parseArg(
        normalized: String,
        keyword: String,
        syntax: String,
        build: (String) -> Cmd,
    ): ParseResult {
        val arg = argumentAfter(normalized, keyword)
            ?: return ParseResult.Failure(ParseError.MissingArgument(syntax))
        return try {
            ParseResult.Success(build(arg))
        } catch (e: IllegalArgumentException) {
            ParseResult.Failure(ParseError.InvalidArgument(arg, e.message ?: "invalid", syntax))
        }
    }

    /**
     * Argument text after a keyword prefix, or null when absent. Trailing
     * whitespace was already collapsed by normalization; an argument that is
     * empty or contains spaces after the keyword (e.g. "EFFECT 1 2") is
     * rejected by the validators, not silently truncated.
     */
    private fun argumentAfter(normalized: String, keyword: String): String? {
        if (!normalized.uppercase().startsWith(keyword)) return null
        val rest = normalized.substring(keyword.length)
        if (rest.isEmpty()) return null
        return rest.trim().ifEmpty { null }
    }

    /** Integer parser matching the firmware console: unsigned decimal only. */
    private fun parseInt(text: String, min: Int, max: Int, reason: String): Int {
        if (text.isEmpty() || !text.all { it in '0'..'9' }) {
            throw IllegalArgumentException(reason)
        }
        val value = text.toLongOrNull() ?: throw IllegalArgumentException(reason)
        if (value < min || value > max) throw IllegalArgumentException(reason)
        return value.toInt()
    }

    private fun parseMultiplier(text: String): Double {
        // Rejects signs, exponents and locale junk; decimal point only.
        val ok = text.isNotEmpty() && text.all { it in '0'..'9' || it == '.' } &&
            text.count { it == '.' } <= 1 && text.any { it in '0'..'9' }
        if (!ok) {
            throw IllegalArgumentException("multiplier must be a positive decimal number")
        }
        val value = text.toDoubleOrNull() ?: throw IllegalArgumentException("multiplier is not a valid number")
        if (value.isNaN() || value.isInfinite() || value < 0.1 || value > 1000.0) {
            throw IllegalArgumentException("multiplier must be between 0.1 and 1000.0")
        }
        return value
    }

    private fun success(command: Cmd) = ParseResult.Success(command)
}
