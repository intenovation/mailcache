package com.intenovation.mailcache;

/**
 * Enum representing the different cache operation modes
 */
public enum CacheMode {
    /**
     * Offline mode - all operations use local cache, writing operations disabled
     */
    OFFLINE("Uses local cache only, no server connection. All operations are read-only.", false, false, false, false),

    /**
     * Accelerated mode - reading is local, writing happens both locally and on server
     */
    ACCELERATED("Reading uses cache, writing to both cache and server. Best balance of performance and functionality.", false, false, true, false),

    /**
     * Online mode - searching happens online, reading uses cache for speed, writing happens both locally and on server
     */
    ONLINE("Searching on server, reading from cache for speed. Good when real-time search results are needed. should not initially read from server but do so if not available", false, true, true, false),

    /**
     * Refresh mode - always gets latest from server, overwrites cache completely, writing happens both locally and on server
     */
    REFRESH("Always gets latest from server, overwrites cache completely. Use when cache needs updating.", true, true, true, false),

    /**
     * Destructive mode - only mode that allows deleting messages and folders
     * Should be used with caution, same as ONLINE
     */
    DESTRUCTIVE("Only mode that allows deleting messages and folders. Use with caution for administrative tasks.", true, true, true, true);

    private final String description;
    private final boolean shouldReadFromServer;
    private final boolean shouldSearchOnServer;
    private final boolean writeAllowed;
    private final boolean deleteAllowed;

    /**
     * Constructs a cache mode with the specified properties
     *
     * @param description The description of this cache mode
     * @param shouldReadFromServer Whether reading should be done from server
     * @param shouldSearchOnServer Whether searching should be done on server
     * @param writeAllowed Whether write operations are allowed
     * @param deleteAllowed Whether delete operations are allowed
     */
    CacheMode(String description, boolean shouldReadFromServer, boolean shouldSearchOnServer,
              boolean writeAllowed, boolean deleteAllowed) {
        this.description = description;
        this.shouldReadFromServer = shouldReadFromServer;
        this.shouldSearchOnServer = shouldSearchOnServer;
        this.writeAllowed = writeAllowed;
        this.deleteAllowed = deleteAllowed;
    }

    /**
     * Get the description of this cache mode
     *
     * @return The description text
     */
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
}