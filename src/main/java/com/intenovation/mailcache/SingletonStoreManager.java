package com.intenovation.mailcache;

import javax.mail.MessagingException;
import javax.mail.Session;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SingletonStoreManager ensures only one CachedStore instance exists
 * per session configuration
 */
public class SingletonStoreManager {
    private static final Logger LOGGER = Logger.getLogger(SingletonStoreManager.class.getName());
    
    // Map of session config hash to store instance
    private static final Map<Integer, CachedStore> storeInstances = new ConcurrentHashMap<>();
    
    // Lock for synchronized operations
    private static final Object lock = new Object();
    
    /**
     * Get or create a CachedStore instance for the given session
     * 
     * @param session The JavaMail session
     * @return The CachedStore instance
     * @throws MessagingException If there's an error creating the store
     */
    public static CachedStore getStore(Session session) throws MessagingException {
        // Create a hash of the session properties for mapping
        int sessionHash = createSessionHash(session);
        
        // Check if we already have a store for this session config
        CachedStore store = storeInstances.get(sessionHash);
        
        if (store != null && store.isConnected()) {
            LOGGER.fine("Reusing existing connected CachedStore instance");
            return store;
        }
        
        // Create a new store if needed
        synchronized (lock) {
            // Check again in case another thread created it
            store = storeInstances.get(sessionHash);
            if (store != null && store.isConnected()) {
                return store;
            }
            
            // Create a new store
            LOGGER.info("Creating new CachedStore instance");
            store = (CachedStore) session.getStore();
            
            // Store it for future use
            storeInstances.put(sessionHash, store);
            return store;
        }
    }
    
    /**
     * Create a hash code for the session's relevant properties
     */
    private static int createSessionHash(Session session) {
        Properties props = session.getProperties();
        
        // Create a list of relevant properties for store identification
        String[] relevantProps = {
            "mail.store.protocol",
            "mail.cache.directory",
            "mail.cache.mode",
            "mail.imaps.host", 
            "mail.imaps.port",
            "mail.imaps.user",
            "mail.imap.host", 
            "mail.imap.port",
            "mail.imap.user"
        };
        
        // Build a string of these properties
        StringBuilder sb = new StringBuilder();
        for (String prop : relevantProps) {
            String value = props.getProperty(prop);
            if (value != null) {
                sb.append(prop).append('=').append(value).append(';');
            }
        }
        
        return sb.toString().hashCode();
    }
    
    /**
     * Close and remove all cached stores
     */
    public static void closeAllStores() {
        synchronized (lock) {
            for (CachedStore store : storeInstances.values()) {
                try {
                    if (store.isConnected()) {
                        store.close();
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error closing store", e);
                }
            }
            storeInstances.clear();
        }
    }
}