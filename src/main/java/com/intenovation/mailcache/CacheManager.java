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
     * Clear the cache for a specific folder - disabled unless in DESTRUCTIVE mode
     *
     * @param folderName The folder name
     * @return True if successful, false otherwise
     * @throws MessagingException If not in DESTRUCTIVE mode
     */
    public boolean clearCache(String folderName) throws MessagingException {
        // Only allow in DESTRUCTIVE mode
        if (store.getMode() != CacheMode.DESTRUCTIVE) {
            throw new MessagingException("Cannot clear cache unless in DESTRUCTIVE mode");
        }

        File cacheDir = store.getCacheDirectory();
        return MailCache.clearFolderCache(cacheDir, folderName);
    }

    /**
     * Clear the entire cache - disabled unless in DESTRUCTIVE mode
     *
     * @return True if successful, false otherwise
     * @throws MessagingException If not in DESTRUCTIVE mode
     */
    public boolean clearCache() throws MessagingException {
        // Only allow in DESTRUCTIVE mode
        if (store.getMode() != CacheMode.DESTRUCTIVE) {
            throw new MessagingException("Cannot clear cache unless in DESTRUCTIVE mode");
        }

        File cacheDir = store.getCacheDirectory();
        return MailCache.clearCache(cacheDir);
    }

    /**
     * Purge messages older than the specified number of days - only archive in DESTRUCTIVE mode
     *
     * @param folderName The folder name
     * @param days The number of days
     * @param preserveFlagged Whether to preserve flagged messages
     * @return The number of messages archived
     * @throws MessagingException If not in DESTRUCTIVE mode
     */
    public int purgeOlderThan(String folderName, int days, boolean preserveFlagged) throws MessagingException {
        // Only allow in DESTRUCTIVE mode, and even then just archive
        if (store.getMode() != CacheMode.DESTRUCTIVE) {
            throw new MessagingException("Cannot purge messages unless in DESTRUCTIVE mode");
        }

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

        int archivedCount = 0;

        // Create archive directory
        File archiveDir = new File(folderDir, "archived_messages");
        if (!archiveDir.exists()) {
            archiveDir.mkdirs();
        }

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
                            CachedMessage cachedMsg = (CachedMessage) message;
                            File messageDir = cachedMsg.getMessageDirectory();

                            if (messageDir != null && messageDir.exists()) {
                                // Move to archive instead of deleting
                                File archiveDest = new File(archiveDir, messageDir.getName());
                                if (messageDir.renameTo(archiveDest)) {
                                    archivedCount++;
                                }
                            }
                        }
                    }
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error archiving message", e);
                }
            }

            folder.close(false);
        } catch (MessagingException e) {
            LOGGER.log(Level.SEVERE, "Error archiving folder: " + folderName, e);
        }

        return archivedCount;
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
        int archivedCount = 0;

        if (cacheDir.exists()) {
            // Count normal folders (skip special directories)
            File[] folders = cacheDir.listFiles(file ->
                    file.isDirectory() &&
                            !file.getName().equals("archived_folders") &&
                            !file.getName().startsWith(".")
            );

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

                    // Count archived messages
                    File archivedDir = new File(folder, "archived_messages");
                    if (archivedDir.exists()) {
                        File[] archived = archivedDir.listFiles(File::isDirectory);
                        if (archived != null) {
                            archivedCount += archived.length;
                        }
                    }
                }
            }
        }

        stats.setFolderCount(folderCount);
        stats.setMessageCount(messageCount);
        stats.setArchivedMessageCount(archivedCount);

        return stats;
    }

    /**
     * Move messages from archive back to the active folder
     *
     * @param folderName The folder name
     * @param messageIds Array of message IDs to restore
     * @return Number of messages restored
     */
    public int restoreArchivedMessages(String folderName, String[] messageIds) {
        File cacheDir = store.getCacheDirectory();
        File folderDir = new File(cacheDir, folderName.replace('/', File.separatorChar));
        if (!folderDir.exists()) {
            return 0;
        }

        File archiveDir = new File(folderDir, "archived_messages");
        File messagesDir = new File(folderDir, "messages");

        if (!archiveDir.exists() || !archiveDir.isDirectory()) {
            return 0;
        }

        if (!messagesDir.exists()) {
            messagesDir.mkdirs();
        }

        int restoredCount = 0;

        for (String messageId : messageIds) {
            // Find message directory in archive that matches the ID
            File[] messageDirs = archiveDir.listFiles(file ->
                    file.isDirectory() && file.getName().contains(messageId));

            if (messageDirs != null && messageDirs.length > 0) {
                for (File messageDir : messageDirs) {
                    File destDir = new File(messagesDir, messageDir.getName());
                    if (messageDir.renameTo(destDir)) {
                        restoredCount++;
                    }
                }
            }
        }

        return restoredCount;
    }

    /**
     * List archived messages for a folder
     *
     * @param folderName The folder name
     * @return Array of archived message directory names
     */
    public String[] listArchivedMessages(String folderName) {
        File cacheDir = store.getCacheDirectory();
        File folderDir = new File(cacheDir, folderName.replace('/', File.separatorChar));
        if (!folderDir.exists()) {
            return new String[0];
        }

        File archiveDir = new File(folderDir, "archived_messages");
        if (!archiveDir.exists() || !archiveDir.isDirectory()) {
            return new String[0];
        }

        File[] messageDirs = archiveDir.listFiles(File::isDirectory);
        if (messageDirs == null || messageDirs.length == 0) {
            return new String[0];
        }

        String[] messageNames = new String[messageDirs.length];
        for (int i = 0; i < messageDirs.length; i++) {
            messageNames[i] = messageDirs[i].getName();
        }

        return messageNames;
    }

    /**
     * Backup the entire cache to a separate location
     *
     * @param backupDir The directory to backup to
     * @return true if the backup was successful, false otherwise
     */
    public boolean backupCache(File backupDir) {
        File cacheDir = store.getCacheDirectory();
        if (cacheDir == null || !cacheDir.exists()) {
            return false;
        }

        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        try {
            // Create a timestamp-based backup directory
            String timestamp = String.valueOf(System.currentTimeMillis());
            File timestampDir = new File(backupDir, "cache_backup_" + timestamp);
            timestampDir.mkdirs();

            // Copy each folder
            File[] folders = cacheDir.listFiles(File::isDirectory);
            if (folders != null) {
                for (File folder : folders) {
                    File destFolder = new File(timestampDir, folder.getName());
                    copyDirectory(folder, destFolder);
                }
            }

            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error backing up cache", e);
            return false;
        }
    }

    /**
     * Restore a folder from a backup
     *
     * @param backupDir The backup directory
     * @param folderName The folder name to restore
     * @return true if the restore was successful, false otherwise
     */
    public boolean restoreFromBackup(File backupDir, String folderName) {
        // First find the latest backup
        File[] backupDirs = backupDir.listFiles(file ->
                file.isDirectory() && file.getName().startsWith("cache_backup_"));

        if (backupDirs == null || backupDirs.length == 0) {
            return false;
        }

        // Find the latest backup
        Arrays.sort(backupDirs, Comparator.comparing(File::getName).reversed());
        File latestBackup = backupDirs[0];

        // Find the folder in the backup
        File backupFolder = new File(latestBackup, folderName.replace('/', File.separatorChar));
        if (!backupFolder.exists() || !backupFolder.isDirectory()) {
            return false;
        }

        // Create the destination folder
        File cacheDir = store.getCacheDirectory();
        File destFolder = new File(cacheDir, folderName.replace('/', File.separatorChar));

        // If the folder already exists, archive it first
        if (destFolder.exists()) {
            File archiveDir = new File(cacheDir, "archived_folders");
            if (!archiveDir.exists()) {
                archiveDir.mkdirs();
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            File archiveFolder = new File(archiveDir, folderName.replace('/', '_') + "_" + timestamp);

            if (!destFolder.renameTo(archiveFolder)) {
                return false;
            }
        }

        // Create the parent directories if needed
        if (!destFolder.getParentFile().exists()) {
            destFolder.getParentFile().mkdirs();
        }

        // Copy the folder from backup
        copyDirectory(backupFolder, destFolder);

        return true;
    }

    /**
     * Utility method to copy a directory
     */
    private void copyDirectory(File sourceDir, File destDir) {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        File[] files = sourceDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            File destFile = new File(destDir, file.getName());
            if (file.isDirectory()) {
                copyDirectory(file, destFile);
            } else {
                try {
                    java.nio.file.Files.copy(file.toPath(), destFile.toPath());
                } catch (java.io.IOException e) {
                    LOGGER.log(Level.WARNING, "Error copying file: " + file, e);
                }
            }
        }
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

        /**
         * Get the duration of the last synchronization in milliseconds
         */
        public long getSyncDuration() {
            return endTime - startTime;
        }

        /**
         * Get a formatted representation of the sync duration
         */
        public String getFormattedSyncDuration() {
            long duration = getSyncDuration();
            if (duration < 1000) {
                return duration + " ms";
            } else if (duration < 60000) {
                return String.format("%.2f seconds", duration / 1000.0);
            } else {
                return String.format("%.2f minutes", duration / 60000.0);
            }
        }
    }

    /**
     * Cache statistics
     */
    public static class CacheStats {
        private long totalSize = 0;
        private int folderCount = 0;
        private int messageCount = 0;
        private int archivedMessageCount = 0;

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
         * Get the number of archived messages
         */
        public int getArchivedMessageCount() {
            return archivedMessageCount;
        }

        /**
         * Set the number of archived messages
         */
        public void setArchivedMessageCount(int archivedMessageCount) {
            this.archivedMessageCount = archivedMessageCount;
        }

        /**
         * Get the total number of messages (active + archived)
         */
        public int getTotalMessageCount() {
            return messageCount + archivedMessageCount;
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

        /**
         * Get average message size
         */
        public double getAverageMessageSize() {
            int total = getTotalMessageCount();
            return total > 0 ? (double) totalSize / total : 0;
        }

        /**
         * Get a formatted average message size
         */
        public String getFormattedAverageMessageSize() {
            double avgSize = getAverageMessageSize();

            if (avgSize < 1024) {
                return String.format("%.2f B", avgSize);
            } else if (avgSize < 1024 * 1024) {
                return String.format("%.2f KB", avgSize / 1024.0);
            } else {
                return String.format("%.2f MB", avgSize / (1024.0 * 1024));
            }
        }
    }
}