package com.happy.assistant.router

/**
 * Everything Happy can be asked to do, as data.
 *
 * The router produces one of these and a handler consumes it. Keeping them
 * separate is what makes the router testable without a device: matching is pure
 * string work, and only the handlers touch Android.
 */
sealed interface Command {

    /** Torch. [on] null means toggle. */
    data class Torch(val on: Boolean?) : Command

    /** Media volume, one step at a time. */
    data class VolumeStep(val up: Boolean) : Command

    /** Media volume as a percentage of maximum. */
    data class VolumeSet(val percent: Int) : Command

    enum class Ringer { SILENT, VIBRATE, NORMAL }

    data class RingerMode(val mode: Ringer) : Command

    data class OpenApp(val query: String) : Command

    data object BatteryStatus : Command

    data object StorageStatus : Command

    data object TimeNow : Command

    data object DateToday : Command

    /** Twenty-four hour clock, already resolved. */
    data class SetAlarm(val hour: Int, val minute: Int) : Command

    data class SetTimer(val seconds: Int) : Command

    data class ContactNumber(val name: String) : Command

    // ---- Phase 5: calls and messaging ----

    data class CallContact(val name: String) : Command

    /** Redial whoever was on the phone last. */
    data object CallBack : Command

    data object AnswerCall : Command

    data object RejectCall : Command

    data class Speakerphone(val on: Boolean) : Command

    data class SendSms(val name: String, val message: String) : Command

    /** Read out what arrived while the phone was face down. */
    data object ReadNotifications : Command

    data object WhoCalled : Command

    /** Inline reply to whoever most recently messaged, via RemoteInput. */
    data class ReplyToNotification(val name: String, val message: String) : Command

    /** Nothing matched. Phase 7 hands these to the knowledge router. */
    data class Unmatched(val text: String) : Command
}
