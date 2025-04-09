package com.intenovation.mailcache;

import javax.mail.*;
import java.io.File;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper class for creating and accessing the mail cache
 */
public class MailCache {
    private static final Logger LOGGER = Logger.getLogger(MailCache.class.getName());

    /**
     * Create a new cached mail session
     *
     * @param cacheDir The directory to use for caching
     * @param mode The cache operation mode
     * @return A new Session configured for caching
     */
    public static Session createSession(File cacheDir, CacheMode mode) {
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", "cache");
        props.setProperty("mail.cache.directory", cacheDir.getAbsolutePath());
        props.setProperty("mail.cache.mode", mode.name());

        Session session = Session.getInstance(props);

        // Register our provider
        session.addProvider(new Provider(
                Provider.Type.STORE,
                "cache",
                CachedStore.class.getName(),
                "Intenovation",
                "1.0"
        ));

        return session;
    }

    /**
     * Create a new cached mail session with IMAP settings
     *
     * @param cacheDir The directory to use for caching
     * @param mode The cache operation mode
     * @param imapHost The IMAP server hostname
     * @param imapPort The IMAP server port
     * @param username The username for authentication
     * @param password The password for authentication
     * @param useSSL Whether to use SSL for IMAP
     * @return A new Session configured for caching with IMAP
     */
    public static Session createSession(File cacheDir, CacheMode mode,
                                        String imapHost, int imapPort,
                                        String username, String password,
                                        boolean useSSL) {
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", "cache");
        props.setProperty("mail.cache.directory", cacheDir.getAbsolutePath());
        props.setProperty("mail.cache.mode", mode.name());

        // IMAP settings
        props.setProperty("mail.imaps.host", imapHost);
        props.setProperty("mail.imaps.port", String.valueOf(imapPort));
        props.setProperty("mail.imaps.user", username);
        props.setProperty("mail.imaps.password", password);
        props.setProperty("mail.imaps.ssl.enable", String.valueOf(useSSL));

        Session session = Session.getInstance(props);

        // Register our provider
        session.addProvider(new Provider(
                Provider.Type.STORE,
                "cache",
                CachedStore.class.getName(),
                "Intenovation",
                "1.0"
        ));

        return session;
    }

    /**
     * Open a cached store
     *
     * @param cacheDir The directory to use for caching
     * @param mode The cache operation mode
     * @return A connected CachedStore
     * @throws MessagingException If there is an error connecting
     */
    public static CachedStore openStore(File cacheDir, CacheMode mode)
            throws MessagingException {
        Session session = createSession(cacheDir, mode);
        Store store = session.getStore();
        store.connect();
        return (CachedStore) store;
    }

    /**
     * Open a cached store with IMAP connectivity
     *
     * @param cacheDir The directory to use for caching
     * @param mode The cache operation mode
     * @param imapHost The IMAP server hostname
     * @param imapPort The IMAP server port
     * @param username The username for authentication
     * @param password The password for authentication
     * @param useSSL Whether to use SSL for IMAP
     * @return A connected CachedStore
     * @throws MessagingException If there is an error connecting
     */
    public static CachedStore openStore(File cacheDir, CacheMode mode,
                                        String imapHost, int imapPort,
                                        String username, String password,
                                        boolean useSSL)
            throws MessagingException {
        Session session = createSession(cacheDir, mode, imapHost, imapPort,
                username, password, useSSL);
        Store store = session.getStore();
        store.connect(imapHost, username, password);
        return (CachedStore) store;
    }

    /**
     * Check if a folder exists in the cache
     *
     * @param cacheDir The cache directory
     * @param folderName The folder name to check
     * @return true if the folder exists in the cache
     */
    public static boolean folderExistsInCache(File cacheDir, String folderName) {
        File folderDir = new File(cacheDir, folderName.replace('/', File.separatorChar));
        return folderDir.exists() && folderDir.isDirectory();
    }

    /**
     * Clear the cache for a specific folder
     *
     * @param cacheDir The cache directory
     * @param folderName The folder name to clear
     * @return true if the folder was successfully cleared
     */
    public static boolean clearFolderCache(File cacheDir, String folderName) {
        File folderDir = new File(cacheDir, folderName.replace('/', File.separatorChar));
        if (folderDir.exists() && folderDir.isDirectory()) {
            return deleteDirectory(folderDir);
        }
        return false;
    }

    /**
     * Clear the entire cache
     *
     * @param cacheDir The cache directory
     * @return true if the cache was successfully cleared
     */
    public static boolean clearCache(File cacheDir) {
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            // Only delete the contents, not the directory itself
            File[] contents = cacheDir.listFiles();
            if (contents != null) {
                for (File file : contents) {
                    deleteDirectory(file);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Get the size of the cache
     *
     * @param cacheDir The cache directory
     * @return The size of the cache in bytes
     */
    public static long getCacheSize(File cacheDir) {
        return getDirectorySize(cacheDir);
    }

    /**
     * Get the size of a directory
     */
    private static long getDirectorySize(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }

        long size = 0;

        // Add size of all files
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getDirectorySize(file);
                }
            }
        }

        return size;
    }

    /**
     * Recursively delete a directory
     */
    private static boolean deleteDirectory(File dir) {
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
}