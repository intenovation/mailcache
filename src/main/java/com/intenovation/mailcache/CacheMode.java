package com.intenovation.mailcache;

import com.intenovation.appfw.config.DescribedEnum;

/**
 * Enum representing the different cache operation modes,
 * implementing DescribedEnum for better integration with configuration systems.
 */
public enum CacheMode implements DescribedEnum {
    /**
     * Offline mode - all operations use local cache, writing operations disabled
     */
    OFFLINE("Uses local cache only, no server connection. All operations are read-only.", false, false, false, false, false),

    /**
     * Accelerated mode - reading is local, writing happens both locally and on server
     */
    ACCELERATED("Reading uses cache, writing to both cache and server. Best balance of performance and functionality.", false, false, true, false, true),

    /**
     * Online mode - searching happens online, reading uses cache for speed, writing happens both locally and on server
     */
    ONLINE("Searching on server, reading from cache for speed. Good when real-time search results are needed. should not initially read from server but do so if not available", false, true, true, false, true),

    /**
     * Refresh mode - always gets latest from server, overwrites cache completely, writing happens both locally and on server
     */
    REFRESH("Always gets latest from server, overwrites cache completely. Use when cache needs updating.", true, true, true, false, false),

    /**
     * Destructive mode - only mode that allows deleting messages and folders
     * Should be used with caution, same as ONLINE
     */
    DESTRUCTIVE("Only mode that allows deleting messages and folders. Use with caution for administrative tasks.", true, true, true, true, true);

    private final String description;
    private final boolean shouldReadFromServer;
    private final boolean shouldSearchOnServer;
    private final boolean writeAllowed;
    private final boolean deleteAllowed;
    private final boolean shouldReadFromServerAfterCacheMiss;

    /**
     * Constructs a cache mode with the specified properties
     *
     * @param description The description of this cache mode
     * @param shouldReadFromServer Whether reading should be done from server
     * @param shouldSearchOnServer Whether searching should be done on server
     * @param writeAllowed Whether write operations are allowed
     * @param deleteAllowed Whether delete operations are allowed
     * @param shouldReadFromServerAfterCacheMiss Whether to try reading from server after cache miss
     */
    CacheMode(String description, boolean shouldReadFromServer, boolean shouldSearchOnServer,
              boolean writeAllowed, boolean deleteAllowed, boolean shouldReadFromServerAfterCacheMiss) {
        this.description = description;
        this.shouldReadFromServer = shouldReadFromServer;
        this.shouldSearchOnServer = shouldSearchOnServer;
        this.writeAllowed = writeAllowed;
        this.deleteAllowed = deleteAllowed;
        this.shouldReadFromServerAfterCacheMiss = shouldReadFromServerAfterCacheMiss;
    }

    /**
     * Get the description of this cache mode
     *
     * @return The description text
     */
    @Override
    public String getDescription() {
        return description;
    }

    /**
     * Checks if reading operations should use the server
     *
     * @return true if reading should come from server, false for local cache only
     */
    public boolean shouldReadFromServer() {
        return shouldReadFromServer;
    }

    /**
     * Checks if search operations should use the server
     *
     * @return true if searching should happen on server, false for local cache only
     */
    public boolean shouldSearchOnServer() {
        return shouldSearchOnServer;
    }

    /**
     * Checks if write operations are allowed in this mode
     *
     * @return true if writing is allowed, false otherwise
     */
    public boolean isWriteAllowed() {
        return writeAllowed;
    }

    /**
     * Checks if delete operations are allowed in this mode
     *
     * @return true if deletion is allowed, false otherwise
     */
    public boolean isDeleteAllowed() {
        return deleteAllowed;
    }

    /**
     * Checks if reading from server should be attempted after a cache miss
     * This is true for ACCELERATED and ONLINE modes to provide fallback behavior
     *
     * @return true if server should be checked after cache miss, false otherwise
     */
    public boolean shouldReadFromServerAfterCacheMiss() {
        return shouldReadFromServerAfterCacheMiss;
    }
}