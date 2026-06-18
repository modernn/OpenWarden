package com.openwarden.child

/**
 * Durable state a [CommandGate] reads + mutates (ADR-030 D5). Implemented by [ReplayFloorStore]
 * against the same TEE/StrongBox-bound EncryptedSharedPreferences as the policy/heartbeat floors;
 * a fake backs the unit tests.
 */
interface CommandStore {
    /** The child's own stable id, for audience binding (shared with the policy/heartbeat path). */
    fun childDeviceId(): String

    /** Highest command `issued_at` admitted, or null if none — the shared lock/unlock replay floor. */
    fun commandFloor(): Long?

    /** The current durable lock state surfaced by `GET /state.is_locked`. */
    fun isLocked(): Boolean

    /**
     * Atomically (one durable commit) advance the command replay floor to [issuedAt] AND set the
     * lock flag to [locked]. Fail-closed: throws on a failed/divergent write, leaving prior state
     * intact (the command is then treated as not-admitted). One commit so a crash can never leave
     * the floor advanced (command consumed) but the lock flag unchanged, or vice versa.
     */
    fun admitCommand(issuedAt: Long, locked: Boolean)
}

/**
 * Verify + admit a [SignedCommand] (ADR-030). Composes the pure [CommandAdmission] decision with the
 * durable [CommandStore], mirroring [ContactClock.admitHeartbeat]: on a valid, fresh, audience- and
 * type-matched command it advances the replay floor and sets the lock flag in ONE durable commit,
 * then returns. Fail-closed: any rejection leaves all state untouched.
 *
 * The DPM side effect of a lock (`PolicyEnforcer.lockNow()`) is the caller's concern — this gate
 * owns only the authenticated, replay-safe state transition.
 */
class CommandGate(
    private val store: CommandStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun admit(
        cmd: SignedCommand,
        expectedType: String,
        pinnedParentPubkey: ByteArray?,
    ): CommandAdmission.Outcome {
        val outcome = CommandAdmission.decide(
            cmd = cmd,
            expectedType = expectedType,
            myChildDeviceId = store.childDeviceId(),
            pinnedParentPubkey = pinnedParentPubkey,
            commandFloor = store.commandFloor(),
            nowMs = clock(),
        )
        if (outcome is CommandAdmission.Outcome.Accept) {
            // Floor advance + lock-flag set are one atomic durable commit (see CommandStore.admitCommand).
            store.admitCommand(cmd.issued_at, locked = outcome.type == SignedCommand.TYPE_LOCK)
        }
        return outcome
    }
}
