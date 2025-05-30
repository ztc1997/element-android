/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import timber.log.Timber

data class NotificationEventQueue(
        private val queue: MutableList<NotifiableEvent>,

        /**
         * An in memory FIFO cache of the seen events.
         * Acts as a notification debouncer to stop already dismissed push notifications from
         * displaying again when the /sync response is delayed.
         */
        private val seenEventIds: CircularCache<String>
) {

    fun markRedacted(eventIds: List<String>) {
        eventIds.forEach { redactedId ->
            queue.replace(redactedId) {
                when (it) {
                    is InviteNotifiableEvent -> it.copy(isRedacted = true)
                    is NotifiableMessageEvent -> it.copy(isRedacted = true)
                    is SimpleNotifiableEvent -> it.copy(isRedacted = true)
                }
            }
        }
    }

    fun syncRoomEvents(roomsLeft: Collection<String>, roomsJoined: Collection<String>) {
        if (roomsLeft.isNotEmpty() || roomsJoined.isNotEmpty()) {
            queue.removeAll {
                when (it) {
                    is NotifiableMessageEvent -> roomsLeft.contains(it.roomId)
                    is InviteNotifiableEvent -> roomsLeft.contains(it.roomId) || roomsJoined.contains(it.roomId)
                    else -> false
                }
            }
        }
    }

    fun isEmpty() = queue.isEmpty()

    fun clearAndAdd(events: List<NotifiableEvent>) {
        queue.clear()
        queue.addAll(events)
    }

    fun clear() {
        queue.clear()
    }

    fun add(notifiableEvent: NotifiableEvent) {
        val existing = findExistingById(notifiableEvent)
        val edited = findEdited(notifiableEvent)
        when {
            existing != null -> {
                if (existing.canBeReplaced) {
                    // Use the event coming from the event stream as it may contains more info than
                    // the fcm one (like type/content/clear text) (e.g when an encrypted message from
                    // FCM should be update with clear text after a sync)
                    // In this case the message has already been notified, and might have done some noise
                    // So we want the notification to be updated even if it has already been displayed
                    // Use setOnlyAlertOnce to ensure update notification does not interfere with sound
                    // from first notify invocation as outlined in:
                    // https://developer.android.com/training/notify-user/build-notification#Updating
                    replace(replace = existing, with = notifiableEvent)
                } else {
                    // keep the existing one, do not replace
                }
            }
            edited != null -> {
                // Replace the existing notification with the new content
                replace(replace = edited, with = notifiableEvent)
            }
            seenEventIds.contains(notifiableEvent.eventId) -> {
                // we've already seen the event, lets skip
                Timber.d("onNotifiableEventReceived(): skipping event, already seen")
            }
            else -> {
                seenEventIds.put(notifiableEvent.eventId)
                queue.add(notifiableEvent)
            }
        }
    }

    private fun findExistingById(notifiableEvent: NotifiableEvent): NotifiableEvent? {
        return queue.firstOrNull { it.eventId == notifiableEvent.eventId }
    }

    private fun findEdited(notifiableEvent: NotifiableEvent): NotifiableEvent? {
        return notifiableEvent.editedEventId?.let { editedId ->
            queue.firstOrNull {
                it.eventId == editedId || it.editedEventId == editedId
            }
        }
    }

    private fun replace(replace: NotifiableEvent, with: NotifiableEvent) {
        queue.remove(replace)
        queue.add(
                when (with) {
                    is InviteNotifiableEvent -> with.copy(isUpdated = true)
                    is NotifiableMessageEvent -> with.copy(isUpdated = true)
                    is SimpleNotifiableEvent -> with.copy(isUpdated = true)
                }
        )
    }

    fun clearMemberShipNotificationForRoom(roomId: String) {
        Timber.d("clearMemberShipOfRoom $roomId")
        queue.removeAll { it is InviteNotifiableEvent && it.roomId == roomId }
    }

    fun clearMessagesForRoom(roomId: String) {
        Timber.d("clearMessageEventOfRoom $roomId")
        queue.removeAll { it is NotifiableMessageEvent && it.roomId == roomId }
    }

    fun clearMessagesForThread(roomId: String, threadId: String) {
        Timber.d("clearMessageEventOfThread $roomId, $threadId")
        queue.removeAll { it is NotifiableMessageEvent && it.roomId == roomId && it.threadId == threadId }
    }

    fun rawEvents(): List<NotifiableEvent> = queue
}

private fun MutableList<NotifiableEvent>.replace(eventId: String, block: (NotifiableEvent) -> NotifiableEvent) {
    val indexToReplace = indexOfFirst { it.eventId == eventId }
    if (indexToReplace == -1) {
        return
    }
    set(indexToReplace, block(get(indexToReplace)))
}
