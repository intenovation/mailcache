package com.intenovation.mailcache;

/**
 * Enum representing the different cache operation modes
 */
public enum CacheMode {
    /**
     * Offline mode - all operations use local cache, writing operations disabled
     */
    OFFLINE,

    /**
     * Accelerated mode - reading is local, writing happens both locally and on server
     */
    ACCELERATED,

    /**
     * Online mode - searching happens online, reading uses cache for speed
     */
    ONLINE
}