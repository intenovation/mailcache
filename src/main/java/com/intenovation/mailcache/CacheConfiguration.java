package com.intenovation.mailcache;

/**
 * Configuration options for the mail cache
 */
public class CacheConfiguration {
    private boolean cacheAttachments = true;
    private boolean compressMessages = false;
    private boolean indexContent = true;
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