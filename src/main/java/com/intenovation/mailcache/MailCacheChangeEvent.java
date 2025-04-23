package com.intenovation.mailcache;

import java.util.EventObject;

/**
 * Event class for mail cache changes.
 */
public class MailCacheChangeEvent extends EventObject {
    public enum ChangeType {
        FOLDER_ADDED,
        FOLDER_REMOVED,
        FOLDER_UPDATED,
        MESSAGE_ADDED,
        MESSAGE_REMOVED,
        MESSAGE_UPDATED,
        CACHE_MODE_CHANGED,
        STORE_OPENED,
        STORE_CLOSED
    }

    private final ChangeType changeType;
    private final Object changedItem;

    /**
     * Create a new change event.
     * @param source The object that initiated the event
     * @param changeType The type of change that occurred
     * @param changedItem The item that changed (folder, message, etc.)
     */
    public MailCacheChangeEvent(Object source, ChangeType changeType, Object changedItem) {
        super(source);
        this.changeType = changeType;
        this.changedItem = changedItem;
    }

    /**
     * Get the type of change that occurred.
     * @return The change type
     */
    public ChangeType getChangeType() {
        return changeType;
    }

    /**
     * Get the item that changed.
     * @return The changed item
     */
    public Object getChangedItem() {
        return changedItem;
    }
}