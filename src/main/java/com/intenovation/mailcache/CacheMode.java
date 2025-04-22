package com.intenovation.mailcache;

/**
 * Enum representing the different cache operation modes
 */
public enum CacheMode {
    /**
     * Offline mode - all operations use local cache, writing operations disabled
     */
    OFFLINE("Uses local cache only, no server connection. All operations are read-only."),

    /**
     * Accelerated mode - reading is local, writing happens both locally and on server
     */
    ACCELERATED("Reading uses cache, writing to both cache and server. Best balance of performance and functionality."),

    /**
     * Online mode - searching happens online, reading uses cache for speed, writing happens both locally and on server
     */
    ONLINE("Searching on server, reading from cache for speed. Good when real-time search results are needed."),

    /**
     * Refresh mode - always gets latest from server, overwrites cache completely, writing happens both locally and on server
     */
    REFRESH("Always gets latest from server, overwrites cache completely. Use when cache needs updating."),

    /**
     * Destructive mode - only mode that allows deleting messages and folders
     * Should be used with caution, same as ONLINE
     */
    DESTRUCTIVE("Only mode that allows deleting messages and folders. Use with caution for administrative tasks.");

    private final String description;

    CacheMode(String description) {
        this.description = description;
    }

    /**
     * Get the description of this cache mode
     *
     * @return The description text
     */
    public String getDescription() {
        return description;
    }
}