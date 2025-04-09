package com.intenovation.mailcache;

import javax.mail.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manager for controlling and monitoring the mail cache
 */
public class CacheManager {
    private static final Logger LOGGER = Logger.getLogger(CacheManager.class.getName());
    private static final Map<CachedStore, CacheManager> INSTANCES = new ConcurrentHashMap<>();
    
    private final CachedStore store;
    private final Map<String, SyncStatus> syncStatus = new ConcurrentHashMap<>();
    
    /**
     * Create a new cache manager
     */
    private CacheManager(CachedStore store) {
        this.store = store;
    }
    
    /**
     * Get the cache manager for a store
     * 
     * @param store The store
     * @return The cache manager
     */
    public static synchronized CacheManager getInstance(CachedStore store) {
        return INSTANCES.computeIfAbsent(store, CacheManager::new);
    }
    
    /**
     * Synchronize a folder between the server and local cache
     * 
     * @param folderName The folder name
     * @return True if successful, false otherwise
     */
    public boolean synchronize(String folderName) {
        if (store.getMode() == CacheMode.OFFLINE) {
            LOGGER.warning("Cannot synchronize in OFFLINE mode");
            return false;
        }
        
        try {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                LOGGER.warning("Folder does not exist: " + folderName);
                return false;
            }
            
            // Record sync start time
            SyncStatus status = syncStatus.computeIfAbsent(folderName, k -> new SyncStatus());
            status.setStartTime(System.currentTimeMillis());
            
            folder.open(Folder.READ_ONLY);
            
            // Get messages
            Message[] messages = folder.getMessages();
            
            // Update sync status
            status.setEndTime(System.currentTimeMillis());
            status.setSyncedMessageCount(messages.length);
            status.setLastSyncSuccessful(true);
            
            folder.close(false);
            
            return true;
        } catch (MessagingException e) {
            LOGGER.log(Level.SEVERE, "Error synchronizing folder: " + folderName, e);
            
            // Update sync status
            SyncStatus status = syncStatus.computeIfAbsent(folderName, k -> new SyncStatus());
            status.setEndTime(System.currentTimeMillis());
            status.setLastSyncSuccessful(false);
            status.setLastError(e.getMessage());
            
            return false;
        }
    }
    
    /**
     * Get the synchronization status for a folder
     * 
     * @param folderName The folder name
     * @return The synchronization status
     */
    public SyncStatus getSyncStatus(String folderName) {
        return syncStatus.computeIfAbsent(folderName, k -> new SyncStatus());
    }
    
    /**
     * Clear the cache for a specific folder
     * 
     * @param folderName The folder name
     * @return True if successful, false otherwise
     */
    public boolean clearCache(String folderName) {
        File cacheDir = store.getCacheDirectory();
        return MailCache.clearFolderCache(cacheDir, folderName);
    }
    
    /**
     * Clear the entire cache
     * 
     * @return True if successful, false otherwise
     */
    public boolean clearCache() {
        File cacheDir = store.getCacheDirectory();
        return MailCache.clearCache(cacheDir);
    }
    
    /**
     * Purge messages older than the specified number of days
     * 
     * @param folderName The folder name
     * @param days The number of days
     * @param preserveFlagged Whether to preserve flagged messages
     * @return The number of messages purged
     */
    public int purgeOlderThan(String folderName, int days, boolean preserveFlagged) {
        if (days <= 0) {
            return 0;
        }
        
        File cacheDir = store.getCacheDirectory();
        File folderDir = new File(cacheDir, folderName.replace('/', File.separatorChar));
        if (!folderDir.exists()) {
            return 0;
        }
        
        // Calculate cutoff date
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -days);
        Date cutoffDate = cal.getTime();
        
        int purgedCount = 0;
        
        // Open the folder to get messages
        try {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                return 0;
            }
            
            folder.open(Folder.READ_ONLY);
            
            Message[] messages = folder.getMessages();
            for (Message message : messages) {
                try {
                    // Skip flagged messages if preserveFlagged is true
                    if (preserveFlagged && message.isSet(Flags.Flag.FLAGGED)) {
                        continue;
                    }
                    
                    // Compare message date with cutoff date
                    Date sentDate = message.getSentDate();
                    if (sentDate != null && sentDate.before(cutoffDate)) {
                        // Find the message directory
                        if (message instanceof CachedMessage) {
                            // Get the message ID
                            String[] headers = message.getHeader("Message-ID");
                            if (headers != null && headers.length > 0) {
                                String messageId = headers[0];
                                String safeId = sanitizeFileName(messageId);
                                
                                // Find the message directory
                                File messagesDir = new File(folderDir, "messages");
                                File messageDir = new File(messagesDir, safeId);
                                
                                // Delete the message directory
                                if (messageDir.exists()) {
                                    if (deleteDirectory(messageDir)) {
                                        purgedCount++;
                                    }
                                }
                            }
                        }
                    }
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error purging message", e);
                }
            }
            
            folder.close(false);
        } catch (MessagingException e) {
            LOGGER.log(Level.SEVERE, "Error purging folder: " + folderName, e);
        }
        
        return purgedCount;
    }
    
    /**
     * Get cache statistics
     * 
     * @return The cache statistics
     */
    public CacheStats getStatistics() {
        CacheStats stats = new CacheStats();
        
        File cacheDir = store.getCacheDirectory();
        stats.setTotalSize(MailCache.getCacheSize(cacheDir));
        
        // Count folders and messages
        int folderCount = 0;
        int messageCount = 0;
        
        if (cacheDir.exists()) {
            File[] folders = cacheDir.listFiles(File::isDirectory);
            if (folders != null) {
                folderCount = folders.length;
                
                for (File folder : folders) {
                    // Count messages in each folder
                    File messagesDir = new File(folder, "messages");
                    if (messagesDir.exists()) {
                        File[] messages = messagesDir.listFiles(File::isDirectory);
                        if (messages != null) {
                            messageCount += messages.length;
                        }
                    }
                }
            }
        }
        
        stats.setFolderCount(folderCount);
        stats.setMessageCount(messageCount);
        
        return stats;
    }
    
    /**
     * Sanitize a filename for safe filesystem storage
     */
    private String sanitizeFileName(String input) {
        if (input == null) {
            return "unknown_" + System.currentTimeMillis();
        }
        return input.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
    
    /**
     * Recursively delete a directory
     */
    private boolean deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return true;
        }
        
        // Delete all contents first
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    if (!file.delete()) {
                        LOGGER.log(Level.WARNING, "Failed to delete file: " + file.getAbsolutePath());
                    }
                } else if (file.isDirectory()) {
                    deleteDirectory(file);
                }
            }
        }
        
        // Delete the directory itself
        return dir.delete();
    }
    
    /**
     * Synchronization status for a folder
     */
    public static class SyncStatus {
        private long startTime = 0;
        private long endTime = 0;
        private int syncedMessageCount = 0;
        private boolean lastSyncSuccessful = false;
        private String lastError = null;
        
        /**
         * Get the start time of the last synchronization
         */
        public long getStartTime() {
            return startTime;
        }
        
        /**
         * Set the start time of the synchronization
         */
        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }
        
        /**
         * Get the end time of the last synchronization
         */
        public long getEndTime() {
            return endTime;
        }
        
        /**
         * Set the end time of the synchronization
         */
        public void setEndTime(long endTime) {
            this.endTime = endTime;
        }
        
        /**
         * Get the time of the last successful synchronization
         */
        public long getLastSyncTime() {
            return lastSyncSuccessful ? endTime : 0;
        }
        
        /**
         * Get the number of messages synchronized
         */
        public int getSyncedMessageCount() {
            return syncedMessageCount;
        }
        
        /**
         * Set the number of messages synchronized
         */
        public void setSyncedMessageCount(int syncedMessageCount) {
            this.syncedMessageCount = syncedMessageCount;
        }
        
        /**
         * Whether the last synchronization was successful
         */
        public boolean isLastSyncSuccessful() {
            return lastSyncSuccessful;
        }
        
        /**
         * Set whether the synchronization was successful
         */
        public void setLastSyncSuccessful(boolean lastSyncSuccessful) {
            this.lastSyncSuccessful = lastSyncSuccessful;
        }
        
        /**
         * Get the error message from the last failed synchronization
         */
        public String getLastError() {
            return lastError;
        }
        
        /**
         * Set the error message from a failed synchronization
         */
        public void setLastError(String lastError) {
            this.lastError = lastError;
        }
    }
    
    /**
     * Cache statistics
     */
    public static class CacheStats {
        private long totalSize = 0;
        private int folderCount = 0;
        private int messageCount = 0;
        
        /**
         * Get the total size of the cache in bytes
         */
        public long getTotalSize() {
            return totalSize;
        }
        
        /**
         * Set the total size of the cache
         */
        public void setTotalSize(long totalSize) {
            this.totalSize = totalSize;
        }
        
        /**
         * Get the number of folders in the cache
         */
        public int getFolderCount() {
            return folderCount;
        }
        
        /**
         * Set the number of folders in the cache
         */
        public void setFolderCount(int folderCount) {
            this.folderCount = folderCount;
        }
        
        /**
         * Get the number of messages in the cache
         */
        public int getMessageCount() {
            return messageCount;
        }
        
        /**
         * Set the number of messages in the cache
         */
        public void setMessageCount(int messageCount) {
            this.messageCount = messageCount;
        }
        
        /**
         * Get a formatted total size
         */
        public String getFormattedTotalSize() {
            if (totalSize < 1024) {
                return totalSize + " B";
            } else if (totalSize < 1024 * 1024) {
                return String.format("%.2f KB", totalSize / 1024.0);
            } else if (totalSize < 1024 * 1024 * 1024) {
                return String.format("%.2f MB", totalSize / (1024.0 * 1024));
            } else {
                return String.format("%.2f GB", totalSize / (1024.0 * 1024 * 1024));
            }
        }
    }
}