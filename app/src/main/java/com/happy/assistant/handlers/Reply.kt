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

sealed interface Pending {
    /** Several contacts matched equally well and the user must pick one. */
    data class ChooseContact(val options: List<ContactResolver.Contact>) : Pending
}
