package com.intenovation.mailcache;

/**
 * Configuration options for the mail cache
 */
public class CacheConfiguration {
    private boolean cacheAttachments = true;
    private long maxMessageSize = -1; // No limit by default
    private int retentionDays = 30;
    private boolean compressMessages = false;
    private boolean indexContent = true;
    private long maxCacheSize = -1; // No limit by default
    private boolean syncFlags = true;
    
    /**
     * Create a new configuration with default values
     */
    public CacheConfiguration() {
        // Default values are set in field initializers
    }
    
    /**
     * Whether to cache message attachments
     */
    public boolean isCacheAttachments() {
        return cacheAttachments;
    }
    
    /**
     * Set whether to cache message attachments
     * 
     * @param cacheAttachments true to cache attachments, false to skip them
     * @return this configuration instance for chaining
     */
    public CacheConfiguration setCacheAttachments(boolean cacheAttachments) {
        this.cacheAttachments = cacheAttachments;
        return this;
    }
    
    /**
     * Get the maximum message size to cache (in bytes)
     * A value of -1 means no limit
     */
    public long getMaxMessageSize() {
        return maxMessageSize;
    }
    
    /**
     * Set the maximum message size to cache (in bytes)
     * A value of -1 means no limit
     * 
     * @param maxMessageSize The maximum size in bytes
     * @return this configuration instance for chaining
     */
    public CacheConfiguration setMaxMessageSize(long maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
        return this;
    }
    
    /**
     * Get the number of days to retain messages in the cache
     * A value of -1 means no expiration
     */
    public int getRetentionDays() {
        return retentionDays;
    }
    
    /**
     * Set the number of days to retain messages in the cache
     * A value of -1 means no expiration
     * 
     * @param retentionDays The number of days
     * @return this configuration instance for chaining
     */
    public CacheConfiguration setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
        return this;
    }
    
    /**
     * Whether to compress cached messages
     */
    public boolean isCompressMessages() {
        return compressMessages;
    }
    
    /**
     * Set whether to compress cached messages
     * 
     * @param compressMessages true to compress messages, false otherwise
     * @return this configuration instance for chaining
     */
    public CacheConfiguration setCompressMessages(boolean compressMessages) {
        this.compressMessages = compressMessages;
        return this;
    }
    
    /**
     * Whether to index message content for faster searching
     */
    public boolean isIndexContent() {
        return indexContent;
    }
    
    /**
     * Set whether to index message content for faster searching
     * 
     * @param indexContent true to index content, false otherwise
     * @return this configuration instance for chaining
     */
    public CacheConfiguration setIndexContent(boolean indexContent) {
        this.indexContent = indexContent;
        return this;
    }
    
    /**
     * Get the maximum cache size (in bytes)
     * A value of -1 means no limit
     */
    public long getMaxCacheSize() {
        return maxCacheSize;
    }
    
    /**
     * Set the maximum cache size (in bytes)
     * A value of -1 means no limit
     * 
     * @param maxCacheSize The maximum size in bytes
     * @return this configuration instance for chaining
     */
    public CacheConfiguration setMaxCacheSize(long maxCacheSize) {
        this.maxCacheSize = maxCacheSize;
        return this;
    }
    
    /**
     * Whether to synchronize message flags between server and cache
     */
    public boolean isSyncFlags() {
        return syncFlags;
    }
    
    /**
     * Set whether to synchronize message flags between server and cache
     * 
     * @param syncFlags true to sync flags, false otherwise
     * @return this configuration instance for chaining
     */
    public CacheConfiguration setSyncFlags(boolean syncFlags) {
        this.syncFlags = syncFlags;
        return this;
    }
}