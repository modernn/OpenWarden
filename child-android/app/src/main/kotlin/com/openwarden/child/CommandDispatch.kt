package com.openwarden.child

/**
 * Pure HTTP-shaping for the signed-command endpoints (ADR-030) — no Ktor, no Android. Extracted from
 * [ApiServer.handleCommand] so the wire-level fail-closed behaviour (unparseable body, durable-write
 * failure, outcome→status mapping) and the fail-closed lock-state read are unit-testable without
 * standing up the embedded server or the encrypted store.
 */
object CommandDispatch {
    /** An HTTP status code + JSON body, independent of the server engine. */
    data class Response(
        val status: Int,
        val body: Map<String, Any>,
    )

    /**
     * Map a parsed-or-null [SignedCommand] through [admit] to a [Response] (fail-closed):
     *  - a null command (the caller failed to parse the body) ⇒ 400 MALFORMED;
     *  - [admit] throwing (a fail-closed durable-write failure inside the gate) ⇒ 400 REJECTED — the
     *    command never persisted, so it is NOT acked;
     *  - [CommandAdmission.Outcome.Reject] ⇒ 400 REJECTED with the reason;
     *  - [CommandAdmission.Outcome.Accept] ⇒ 200 with the lock/unlock status.
     *
     * [admit] is the gate seam `(cmd, expectedType, pinnedKey) -> Outcome`; the lock side effect
     * (`lockNow`) is the caller's concern and is keyed off the returned [Response] / accepted outcome.
     */
    fun dispatch(
        cmd: SignedCommand?,
        expectedType: String,
        pinnedParentPubkey: ByteArray?,
        admit: (SignedCommand, String, ByteArray?) -> CommandAdmission.Outcome,
    ): Response {
        if (cmd == null) return Response(400, mapOf("error" to "MALFORMED"))
        val outcome =
            runCatching { admit(cmd, expectedType, pinnedParentPubkey) }
                .getOrElse {
                    return Response(400, mapOf("error" to "REJECTED", "reason" to "command not durably admitted"))
                }
        return when (outcome) {
            is CommandAdmission.Outcome.Accept -> {
                Response(200, mapOf("status" to if (outcome.type == SignedCommand.TYPE_LOCK) "locked" else "unlocked"))
            }

            is CommandAdmission.Outcome.Reject -> {
                Response(400, mapOf("error" to "REJECTED", "reason" to outcome.reason))
            }
        }
    }

    /**
     * Fail-closed lock-state read for `GET /state` (ADR-030 D5 + the fail-closed non-negotiable): if
     * the durable store cannot be read (StrongBox/keystore error, corruption), ASSUME locked rather
     * than report a falsely-unlocked device to the parent. Unreadable ⇒ `true`, never `false`.
     */
    fun isLockedFailClosed(read: () -> Boolean): Boolean = runCatching { read() }.getOrDefault(true)
}
