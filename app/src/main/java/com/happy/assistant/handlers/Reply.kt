package com.happy.assistant.handlers

import com.happy.assistant.router.ContactResolver

/**
 * What Happy says back, and whether it is expecting an answer.
 *
 * A question with nowhere to send the reply is worse than no question at all:
 * Happy asks "do you mean Rohit or Mohit", stops listening, and the answer lands
 * as a fresh unmatched command. [pending] is what keeps the thread of the
 * conversation - the service captures once more and hands the answer back to the
 * handler that asked.
 */
data class Reply(val speak: String, val pending: Pending? = null)

/** What to do once it is clear which person was meant. */
sealed interface ContactAction {
    data object ReadNumber : ContactAction
    data object Call : ContactAction
    data class Sms(val message: String) : ContactAction
    data class WhatsApp(val message: String) : ContactAction
}

sealed interface Pending {
    /**
     * Several contacts matched equally well. Carries the action to perform once
     * the user picks, so disambiguation works identically for reading a number,
     * placing a call and sending a text.
     */
    data class ChooseContact(
        val options: List<ContactResolver.Contact>,
        val then: ContactAction,
    ) : Pending

    /**
     * A text is written and waiting on a yes.
     *
     * Sending is not recoverable and recognition mishears, so the message is read
     * back before it goes anywhere. This is the one place Happy insists on
     * confirmation.
     */
    data class ConfirmSms(
        val to: ContactResolver.Contact,
        val message: String,
    ) : Pending
}
