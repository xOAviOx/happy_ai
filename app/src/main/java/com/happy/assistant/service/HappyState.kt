package com.happy.assistant.service

/**
 * The pipeline state machine from spec section 4.
 *
 * Only OFF and IDLE are reachable in Phase 0. The rest exist so the notification,
 * the log and the UI already speak the vocabulary the audio phases will use, and
 * so that "every exit path returns to IDLE" is a rule with somewhere to point.
 */
enum class HappyState(val label: String) {
    OFF("Stopped"),
    IDLE("Idle"),
    LISTENING_WAKE("Listening for the wake phrase"),
    CAPTURING("Listening to you"),
    PROCESSING("Thinking"),
    SPEAKING("Speaking"),
}
