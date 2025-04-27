package com.intenovation.mailcache;

import javax.mail.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper class for creating and accessing the mail cache
 * Manages multiple cached stores for different users
 */
public class MailCache {
    private static final Logger LOGGER = Logger.getLogger(MailCache.class.getName());

    // Store a map of username to CachedStore
    private static final Map<String, CachedStore> storeMap = new ConcurrentHashMap<>();

    // Store configuration hashes to detect changes
    private static final Map<String, Integer> configHashes = new ConcurrentHashMap<>();

    // Listener support
    private static final List<MailCacheChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Create a new cached mail session
     *
     * @param cacheDir The directory to use for caching
     * @return A new Session configured for caching
     */
    private static Session createOfflineSession(File cacheDir) {
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", "cache");
        props.setProperty("mail.cache.directory", cacheDir.getAbsolutePath());
        props.setProperty("mail.cache.mode", CacheMode.OFFLINE.name());

        Session session = Session.getInstance(props);

        // Register our provider
        session.addProvider(getCachedStoreProvider());

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
    private static Session createSession(File cacheDir, CacheMode mode,
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
        session.addProvider(getCachedStoreProvider());

        return session;
    }

    /**
     * Calculate a configuration hash for a set of connection parameters
     *
     * @param imapHost The IMAP server hostname
     * @param imapPort The IMAP server port
     * @param username The username for authentication
     * @param password The password for authentication
     * @param useSSL Whether to use SSL for IMAP
     * @return A hash code representing this configuration
     */
    private static int calculateConfigHash(String imapHost, int imapPort,
                                           String username, String password,
                                           boolean useSSL) {
        return Objects.hash(imapHost, imapPort, username, password, useSSL);
    }

    /**
     * Add a listener to be notified of MailCache changes
     *
     * @param listener The listener to add
     */
    public static void addChangeListener(MailCacheChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a change listener
     *
     * @param listener The listener to remove
     */
    public static void removeChangeListener(MailCacheChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notify all listeners of a change
     *
     * @param event The change event
     */
    private static void fireChangeEvent(MailCacheChangeEvent event) {
        for (MailCacheChangeListener listener : listeners) {
            try {
                listener.mailCacheChanged(event);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener", e);
            }
        }
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

        // Calculate the config hash for this connection
        int newConfigHash = calculateConfigHash(imapHost, imapPort, username, password, useSSL);

        // Check if we already have a store for this user with the same configuration
        CachedStore existingStore = storeMap.get(username);
        if (existingStore != null) {
            Integer oldConfigHash = configHashes.get(username);
            if (oldConfigHash != null && oldConfigHash == newConfigHash && existingStore.isConnected()) {
                LOGGER.fine("Returning existing store for user: " + username);
                return existingStore;
            } else {
                // Configuration changed or store is disconnected, close the old store
                LOGGER.info("Configuration changed for user: " + username + ", creating new store");
                try {
                    existingStore.close();
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error closing existing store for user: " + username, e);
                    // Continue with creating a new store
                }
            }
        }

        Session session = createSession(cacheDir, mode, imapHost, imapPort,
                username, password, useSSL);

        CachedStore newStore = (CachedStore) session.getStore();

        // Register for store events
        newStore.addChangeListener(event -> fireChangeEvent(event));

        // Pass all parameters to ensure they're available in protocolConnect
        newStore.connect(imapHost, imapPort, username, password);

        if (newStore.isConnected()) {
            // Store the new store and its configuration hash
            storeMap.put(username, newStore);
            configHashes.put(username, newConfigHash);

            LOGGER.info("Successfully connected store for user: " + username +
                    " in directory: " + newStore.getCacheDirectory().getAbsolutePath());

            // Notify listeners that a new store was added
            fireChangeEvent(new MailCacheChangeEvent(MailCache.class,
                    MailCacheChangeEvent.ChangeType.STORE_OPENED, newStore));

            return newStore;
        }

        return null;
    }

    /**
     * Open a cached store with IMAP connectivity
     * This version allows explicit control of the base cache directory and user-specific paths
     *
     * @param baseCacheDir The base directory to use for caching multiple users
     * @param mode The cache operation mode
     * @param imapHost The IMAP server hostname
     * @param imapPort The IMAP server port
     * @param username The username for authentication
     * @param password The password for authentication
     * @param useSSL Whether to use SSL for IMAP
     * @return A connected CachedStore
     * @throws MessagingException If there is an error connecting
     */
    public static CachedStore openMultiUserStore(File baseCacheDir, CacheMode mode,
                                                 String imapHost, int imapPort,
                                                 String username, String password,
                                                 boolean useSSL)
            throws MessagingException {

        // Calculate the config hash for this connection
        int newConfigHash = calculateConfigHash(imapHost, imapPort, username, password, useSSL);

        // Check if we already have a store for this user with the same configuration
        CachedStore existingStore = storeMap.get(username);
        if (existingStore != null) {
            Integer oldConfigHash = configHashes.get(username);
            if (oldConfigHash != null && oldConfigHash == newConfigHash && existingStore.isConnected()) {
                LOGGER.fine("Returning existing store for user: " + username);
                return existingStore;
            } else {
                // Configuration changed or store is disconnected, close the old store
                LOGGER.info("Configuration changed for user: " + username + ", creating new store");
                try {
                    existingStore.close();
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error closing existing store for user: " + username, e);
                    // Continue with creating a new store
                }
            }
        }

        // Create the base cache directory if it doesn't exist
        if (!baseCacheDir.exists()) {
            baseCacheDir.mkdirs();
        }

        Session session = createSession(baseCacheDir, mode, imapHost, imapPort,
                username, password, useSSL);

        CachedStore newStore = (CachedStore) session.getStore();

        // Register for store events
        newStore.addChangeListener(event -> fireChangeEvent(event));

        // Pass all parameters to ensure they're available in protocolConnect
        newStore.connect(imapHost, imapPort, username, password);

        if (newStore.isConnected()) {
            // Store the new store and its configuration hash
            storeMap.put(username, newStore);
            configHashes.put(username, newConfigHash);

            LOGGER.info("Successfully connected store for user: " + username +
                    " in directory: " + newStore.getCacheDirectory().getAbsolutePath());

            // Notify listeners that a new store was added
            fireChangeEvent(new MailCacheChangeEvent(MailCache.class,
                    MailCacheChangeEvent.ChangeType.STORE_OPENED, newStore));

            return newStore;
        }

        return null;
    }

    /**
     * Get a list of all active cached stores
     *
     * @return A collection of all cached stores
     */
    public static Collection<CachedStore> getAllStores() {
        return Collections.unmodifiableCollection(storeMap.values());
    }

    /**
     * Get a specific store by username
     *
     * @param username The username to look up
     * @return The CachedStore for this user, or null if not found
     */
    public static CachedStore getStoreByUsername(String username) {
        return storeMap.get(username);
    }

    /**
     * Get a mapping of all usernames to their stores
     *
     * @return An unmodifiable map of username to CachedStore
     */
    public static Map<String, CachedStore> getAllStoresByUsername() {
        return Collections.unmodifiableMap(storeMap);
    }

    private static final Provider CACHED_STORE_PROVIDER = new Provider(
            Provider.Type.STORE,
            "cache",
            CachedStore.class.getName(),
            "Intenovation",
            "1.0"
    );

    /**
     * Get the provider for the CachedStore
     *
     * @return The JavaMail Provider
     */
    public static Provider getCachedStoreProvider() {
        return CACHED_STORE_PROVIDER;
    }

    /**
     * Close all currently open stores
     *
     * @throws MessagingException If there is an error closing any store
     */
    public static void closeAllStores() throws MessagingException {
        List<MessagingException> exceptions = new ArrayList<>();

        for (Map.Entry<String, CachedStore> entry : storeMap.entrySet()) {
            String username = entry.getKey();
            CachedStore store = entry.getValue();

            try {
                if (store.isConnected()) {
                    store.close();
                    LOGGER.info("Closed store for user: " + username);

                    // Notify listeners
                    fireChangeEvent(new MailCacheChangeEvent(MailCache.class,
                            MailCacheChangeEvent.ChangeType.STORE_CLOSED, store));
                }
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error closing store for user: " + username, e);
                exceptions.add(e);
            }
        }

        // Clear the maps
        storeMap.clear();
        configHashes.clear();

        // If there were any exceptions, throw a combined exception
        if (!exceptions.isEmpty()) {
            MessagingException combinedException = new MessagingException("Error closing one or more stores");
            for (MessagingException e : exceptions) {
                combinedException.addSuppressed(e);
            }
            throw combinedException;
        }
    }

    /**
     * Close a specific store by username
     *
     * @param username The username of the store to close
     * @throws MessagingException If there is an error closing the store
     */
    public static void closeStore(String username) throws MessagingException {
        CachedStore store = storeMap.get(username);
        if (store != null) {
            try {
                if (store.isConnected()) {
                    store.close();
                    LOGGER.info("Closed store for user: " + username);

                    // Notify listeners
                    fireChangeEvent(new MailCacheChangeEvent(MailCache.class,
                            MailCacheChangeEvent.ChangeType.STORE_CLOSED, store));
                }
            } finally {
                // Remove from maps even if close failed
                storeMap.remove(username);
                configHashes.remove(username);
            }
        }
    }

    /**
     * Create an offline session and store for viewing cached messages
     * without server connectivity
     *
     * @param cacheDir The directory containing the cache
     * @param username The username for the offline store
     * @return A connected offline CachedStore
     * @throws MessagingException If there is an error creating the store
     */
    public static CachedStore openOfflineStore(File cacheDir, String username)
            throws MessagingException {
        // Check if we already have this offline store
        CachedStore existingStore = storeMap.get(username);
        if (existingStore != null && existingStore.getMode() == CacheMode.OFFLINE && existingStore.isConnected()) {
            return existingStore;
        }

        // Create an offline session
        Session session = createOfflineSession(cacheDir);

        // Create and connect the store
        CachedStore offlineStore = (CachedStore) session.getStore();

        // For offline stores, we just need to call connect() without parameters
        offlineStore.connect();

        if (offlineStore.isConnected()) {
            // Store in our map
            storeMap.put(username, offlineStore);
            // Use a special hash for offline stores
            configHashes.put(username, -1);

            LOGGER.info("Created offline store for user: " + username);

            // Notify listeners
            fireChangeEvent(new MailCacheChangeEvent(MailCache.class,
                    MailCacheChangeEvent.ChangeType.STORE_OPENED, offlineStore));

            return offlineStore;
        }

        return null;
    }
}