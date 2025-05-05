package com.intenovation.mailcache;

import com.intenovation.mailcache.CachedStore;
import com.intenovation.mailcache.MailCache;
import com.intenovation.mailcache.CacheMode;
import com.intenovation.passwordmanager.Password;
import com.intenovation.passwordmanager.PasswordChangeListener;
import com.intenovation.passwordmanager.PasswordManagerApp;
import com.intenovation.passwordmanager.PasswordType;

import javax.mail.MessagingException;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Integration between MailCache and PasswordManager that listens for
 * password changes and manages cached stores based on password configurations
 */
public class MailCacheManager implements PasswordChangeListener {
    private static final Logger LOGGER = Logger.getLogger(MailCacheManager.class.getName());

    // Constants for custom properties
    public static final String PROP_PORT = "mail.port";
    public static final String PROP_SSL = "mail.ssl";
    public static final String PROP_CACHE_MODE = "mail.cache.mode";
    public static final String PROP_CACHE_DIR = "mail.cache.directory";

    private PasswordManagerApp passwordManager;
    private File defaultCacheDir;
    private Map<String, CachedStore> openStores = new HashMap<>();

    /**
     * Create a new mail cache manager
     *
     * @param passwordManager The password manager to use
     * @param defaultCacheDir The default cache directory
     */
    public MailCacheManager(PasswordManagerApp passwordManager, File defaultCacheDir) {
        this.passwordManager = passwordManager;
        this.defaultCacheDir = defaultCacheDir;

        // Register as a listener for password changes
        passwordManager.addPasswordChangeListener(this);
    }

    /**
     * Initialize all IMAP accounts from the password manager
     *
     * @return Map of username to CachedStore
     */
    public Map<String, CachedStore> initializeAllStores() {
        List<Password> imapPasswords = PasswordManagerApp.getImapPasswords();

        for (Password password : imapPasswords) {
            try {
                // Get credentials and configuration from password object
                String username = password.getUsername();
                String plainPassword = password.getPlainTextPassword();
                String server = password.getUrl();

                // Get port (default to 993 for SSL)
                int port = password.getIntProperty(PROP_PORT, 993);

                // Get SSL setting (default to true)
                boolean useSSL = password.getBooleanProperty(PROP_SSL, true);
                
                // If no port was explicitly set, use standard defaults based on SSL
                if (password.getProperty(PROP_PORT) == null) {
                    port = useSSL ? 993 : 143;
                }

                // Get cache mode (default to ACCELERATED)
                CacheMode cacheMode = CacheMode.ACCELERATED;
                CacheMode storedMode = password.getEnumProperty(PROP_CACHE_MODE, CacheMode.class);
                if (storedMode != null) {
                    cacheMode = storedMode;
                }

                // Get cache directory (default to user directory within default cache dir)
                File cacheDir = new File(defaultCacheDir, username.replaceAll("[\\\\/:*?\"<>|]", "_"));
                String cacheDirStr = password.getProperty(PROP_CACHE_DIR);
                if (cacheDirStr != null && !cacheDirStr.isEmpty()) {
                    cacheDir = new File(cacheDirStr);
                }

                // Ensure cache directory exists
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }

                // Open the store
                CachedStore store = MailCache.openStore(
                        cacheDir,
                        cacheMode,
                        server,
                        port,
                        username,
                        plainPassword,
                        useSSL);

                if (store != null) {
                    openStores.put(username, store);
                    LOGGER.info("Opened store for " + username +
                            " in " + cacheMode + " mode");
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error opening store for " +
                        password.getUsername(), e);
            }
        }

        return new HashMap<>(openStores);
    }
    
    /**
     * Open a store in offline mode
     * 
     * @param username The username for the store
     * @return The opened store or null if opening failed
     */
    public CachedStore openOfflineStore(String username) {
        try {
            // Look up the password record
            Password password = null;
            List<Password> imapPasswords = PasswordManagerApp.getImapPasswords();
            
            for (Password p : imapPasswords) {
                if (p.getUsername().equals(username)) {
                    password = p;
                    break;
                }
            }
            
            if (password == null) {
                LOGGER.warning("No password record found for username: " + username);
                return null;
            }
            
            // Get cache directory
            File cacheDir = new File(defaultCacheDir, username.replaceAll("[\\\\/:*?\"<>|]", "_"));
            String cacheDirStr = password.getProperty(PROP_CACHE_DIR);
            if (cacheDirStr != null && !cacheDirStr.isEmpty()) {
                cacheDir = new File(cacheDirStr);
            }
            
            // Ensure cache directory exists
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            
            // Open the store in offline mode
            CachedStore store = MailCache.openOfflineStore(cacheDir, username);
            
            if (store != null) {
                openStores.put(username, store);
                LOGGER.info("Opened offline store for " + username);
            }
            
            return store;
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error opening offline store for " + username, e);
            return null;
        }
    }
    
    /**
     * Add a new IMAP account to the password manager with default settings
     * 
     * @param username The username
     * @param password The password
     * @param server The IMAP server URL
     * @param port The IMAP port (if null/0, will default based on SSL)
     * @param useSSL Whether to use SSL (if null, defaults to true)
     * @param cacheMode The cache mode to use (if null, defaults to ACCELERATED)
     * @param cacheDir The directory to use for caching (null for default)
     * @return true if successful, false otherwise
     */
    public boolean addImapAccount(String username, String password, 
                                String server, Integer port, Boolean useSSL,
                                CacheMode cacheMode, File cacheDir) {
        try {
            // Set defaults
            if (useSSL == null) {
                useSSL = true;
            }
            
            if (port == null || port == 0) {
                port = useSSL ? 993 : 143;
            }
            
            if (cacheMode == null) {
                cacheMode = CacheMode.ACCELERATED;
            }
            
            // Create custom properties map
            Map<String, String> properties = new HashMap<>();
            properties.put(PROP_PORT, String.valueOf(port));
            properties.put(PROP_SSL, String.valueOf(useSSL));
            
            if (cacheDir != null) {
                properties.put(PROP_CACHE_DIR, cacheDir.getAbsolutePath());
            }
            
            // Add the password with properties
            passwordManager.addPasswordWithProperties(
                    username, password, server, PasswordType.IMAP, properties);
            
            // Add the enum property separately
            List<Password> passwords = passwordManager.getPasswordConfig().getAllPasswords();
            for (Password p : passwords) {
                if (p.getUsername().equals(username) && 
                    p.getType() == PasswordType.IMAP && 
                    p.getUrl().equals(server)) {
                    
                    p.setEnumProperty(PROP_CACHE_MODE, cacheMode);
                    
                    // Save changes
                    passwordManager.getPasswordConfig().savePasswords(passwords);
                    break;
                }
            }
            
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error adding IMAP account", e);
            return false;
        }
    }
    
    /**
     * Add a new IMAP account with basic defaults
     * 
     * @param username The username
     * @param password The password
     * @param server The IMAP server URL
     * @return true if successful, false otherwise
     */
    public boolean addImapAccount(String username, String password, String server) {
        return addImapAccount(username, password, server, null, null, null, null);
    }
    
    /**
     * Update cache mode for an IMAP account
     * 
     * @param username The username
     * @param cacheMode The new cache mode
     * @return true if successful, false otherwise
     */
    public boolean updateCacheMode(String username, CacheMode cacheMode) {
        try {
            // Find the password record
            List<Password> passwords = passwordManager.getPasswordConfig().getAllPasswords();
            int index = -1;
            
            for (int i = 0; i < passwords.size(); i++) {
                Password p = passwords.get(i);
                if (p.getUsername().equals(username) && p.getType() == PasswordType.IMAP) {
                    index = i;
                    break;
                }
            }
            
            if (index == -1) {
                LOGGER.warning("Password record not found for username: " + username);
                return false;
            }
            
            // Update the enum property
            return passwordManager.updatePasswordEnum(index, PROP_CACHE_MODE, cacheMode);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error updating cache mode", e);
            return false;
        }
    }
    
    /**
     * Get a store by username
     * 
     * @param username The username to look up
     * @return The CachedStore or null if not found
     */
    public CachedStore getStore(String username) {
        return openStores.get(username);
    }
    
    /**
     * Get all open stores
     * 
     * @return Map of username to CachedStore
     */
    public Map<String, CachedStore> getAllStores() {
        return new HashMap<>(openStores);
    }
    
    /**
     * Close a specific store
     * 
     * @param username The username of the store to close
     */
    public void closeStore(String username) {
        try {
            CachedStore store = openStores.get(username);
            if (store != null) {
                store.close();
                openStores.remove(username);
                LOGGER.info("Closed store for " + username);
            }
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error closing store for " + username, e);
        }
    }
    
    /**
     * Close all open stores
     */
    public void closeAllStores() {
        for (String username : new HashMap<>(openStores).keySet()) {
            closeStore(username);
        }
    }
    
    /**
     * Handle password changes
     */
    @Override
    public void onPasswordChanged(Password password, ChangeType changeType) {
        // Only care about IMAP passwords
        if (password.getType() != PasswordType.IMAP) {
            return;
        }
        
        String username = password.getUsername();
        
        switch (changeType) {
            case ADD:
                // Try to open a new store for this password
                try {
                    // Check if already open
                    if (openStores.containsKey(username)) {
                        LOGGER.info("Store already open for " + username);
                        return;
                    }
                    
                    // Get plain password
                    String plainPassword = passwordManager.getPasswordConfig().getPlainPassword(password);
                    
                    // Get other settings from properties
                    String server = password.getUrl();
                    
                    int port = 993;
                    String portStr = password.getProperty(PROP_PORT);
                    if (portStr != null && !portStr.isEmpty()) {
                        try {
                            port = Integer.parseInt(portStr);
                        } catch (NumberFormatException e) {
                            // Use default port
                        }
                    }
                    
                    boolean useSSL = true;
                    String sslStr = password.getProperty(PROP_SSL);
                    if (sslStr != null && !sslStr.isEmpty()) {
                        useSSL = Boolean.parseBoolean(sslStr);
                    }
                    
                    // Handle defaults if properties aren't set
                    if (portStr == null || portStr.isEmpty()) {
                        port = useSSL ? 993 : 143;
                    }
                    
                    CacheMode cacheMode = CacheMode.ACCELERATED;
                    CacheMode storedMode = password.getEnumProperty(PROP_CACHE_MODE, CacheMode.class);
                    if (storedMode != null) {
                        cacheMode = storedMode;
                    }
                    
                    File cacheDir = new File(defaultCacheDir, username.replaceAll("[\\\\/:*?\"<>|]", "_"));
                    String cacheDirStr = password.getProperty(PROP_CACHE_DIR);
                    if (cacheDirStr != null && !cacheDirStr.isEmpty()) {
                        cacheDir = new File(cacheDirStr);
                    }
                    
                    // Ensure cache directory exists
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs();
                    }
                    
                    // Open the store
                    CachedStore store = MailCache.openStore(
                            cacheDir,
                            cacheMode,
                            server,
                            port,
                            username,
                            plainPassword,
                            useSSL);
                    
                    if (store != null) {
                        openStores.put(username, store);
                        LOGGER.info("Opened store for newly added " + username + 
                                " in " + cacheMode + " mode");
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error opening store for newly added " + 
                            username, e);
                }
                break;
                
            case MODIFY:
                // Check if we have this store open
                CachedStore existingStore = openStores.get(username);
                if (existingStore == null) {
                    LOGGER.fine("Store not open for modified " + username);
                    return;
                }
                
                // Check if cache mode changed
                CacheMode newCacheMode = password.getEnumProperty(PROP_CACHE_MODE, CacheMode.class);
                if (newCacheMode != null && existingStore.getMode() != newCacheMode) {
                    // Update the cache mode
                    existingStore.setMode(newCacheMode);
                    LOGGER.info("Updated cache mode for " + username + " to " + newCacheMode);
                }
                
                // Check if other critical properties changed - if so, need to reopen
                boolean needReopen = false;
                
                // Check if server URL changed
                if (!existingStore.getURLName().getHost().equals(password.getUrl())) {
                    needReopen = true;
                }
                
                // Check if port changed
                String portStr = password.getProperty(PROP_PORT);
                if (portStr != null && !portStr.isEmpty()) {
                    try {
                        int port = Integer.parseInt(portStr);
                        if (existingStore.getURLName().getPort() != port) {
                            needReopen = true;
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid port
                    }
                }
                
                // If need to reopen, close and reopen
                if (needReopen) {
                    LOGGER.info("Critical properties changed for " + username + ", reopening store");
                    try {
                        // Close first
                        existingStore.close();
                        openStores.remove(username);
                        
                        // Then reopen with new settings
                        String plainPassword = passwordManager.getPasswordConfig().getPlainPassword(password);
                        String server = password.getUrl();
                        
                        int port = 993;
                        if (portStr != null && !portStr.isEmpty()) {
                            try {
                                port = Integer.parseInt(portStr);
                            } catch (NumberFormatException e) {
                                // Use default port
                            }
                        }
                        
                        boolean useSSL = true;
                        String sslStr = password.getProperty(PROP_SSL);
                        if (sslStr != null && !sslStr.isEmpty()) {
                            useSSL = Boolean.parseBoolean(sslStr);
                        }
                        
                        CacheMode cacheMode = newCacheMode != null ? 
                                newCacheMode : CacheMode.ACCELERATED;
                        
                        File cacheDir = new File(defaultCacheDir, username.replaceAll("[\\\\/:*?\"<>|]", "_"));
                        String cacheDirStr = password.getProperty(PROP_CACHE_DIR);
                        if (cacheDirStr != null && !cacheDirStr.isEmpty()) {
                            cacheDir = new File(cacheDirStr);
                        }
                        
                        // Ensure cache directory exists
                        if (!cacheDir.exists()) {
                            cacheDir.mkdirs();
                        }
                        
                        // Open the store
                        CachedStore store = MailCache.openStore(
                                cacheDir,
                                cacheMode,
                                server,
                                port,
                                username,
                                plainPassword,
                                useSSL);
                        
                        if (store != null) {
                            openStores.put(username, store);
                            LOGGER.info("Reopened store for " + username + 
                                    " with new settings in " + cacheMode + " mode");
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error reopening store for " + 
                                username, e);
                    }
                }
                break;
                
            case DELETE:
                // Close the store if open
                closeStore(username);
                break;
        }
    }
}