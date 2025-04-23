package com.intenovation.mailcache;

/**
 * Interface for listeners that want to be notified of changes in the mail cache.
 */
public interface MailCacheChangeListener {
    /**
     * Called when a change occurs in the mail cache.
     * @param event The change event containing details of the change
     */
    void mailCacheChanged(MailCacheChangeEvent event);
}