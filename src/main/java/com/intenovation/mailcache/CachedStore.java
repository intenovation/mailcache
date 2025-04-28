package com.intenovation.mailcache;

import javax.mail.*;
import java.io.File;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A JavaMail Store implementation that provides caching capabilities
 * with distinct operation modes and change notification.
 */
public class CachedStore extends Store {
    private static final Logger LOGGER = Logger.getLogger(CachedStore.class.getName());

    private Store imapStore;
    private File baseCacheDirectory;  // The base cache directory
    private File userCacheDirectory;  // The user-specific cache directory
    private String username;          // The username for this store
    private CacheMode mode;
    private boolean connected = false;

    // Listener support
    private final List<MailCacheChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a new CachedStore with the specified session
     */
    public CachedStore(Session session, URLName urlname) {
        super(session, urlname);

        // Get the base cache directory from properties
        String cachePath = session.getProperty("mail.cache.directory");
        if (cachePath != null) {
            this.baseCacheDirectory = new File(cachePath);
        }

        // Get the operation mode from properties
        String modeStr = session.getProperty("mail.cache.mode");
        if (modeStr != null) {
            try {
                this.mode = CacheMode.valueOf(modeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                this.mode = CacheMode.ACCELERATED; // Default to accelerated mode
            }
        } else {
            this.mode = CacheMode.ACCELERATED; // Default to accelerated mode
        }

        // Initialize username if available in URLName
        if (urlname != null && urlname.getUsername() != null) {
            this.username = sanitizeUsername(urlname.getUsername());
            initializeUserDirectory();
        }
    }

    /**
     * Add a listener to be notified of changes
     * @param listener The listener to add
     */
    public void addChangeListener(MailCacheChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a change listener
     * @param listener The listener to remove
     */
    public void removeChangeListener(MailCacheChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notify all listeners of a change
     * @param event The change event
     */
    protected void fireChangeEvent(MailCacheChangeEvent event) {
        for (MailCacheChangeListener listener : listeners) {
            try {
                listener.mailCacheChanged(event);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    /**
     * Sanitize the username to make it suitable for use in a file path
     * @param username The username to sanitize
     * @return The sanitized username
     */
    private String sanitizeUsername(String username) {
        // Replace characters that are not allowed in file paths
        return username.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * Initialize the user-specific cache directory
     */
    private void initializeUserDirectory() {
        if (baseCacheDirectory != null && username != null) {
            String sanitizedUsername = sanitizeUsername(username);
            // Check if baseCacheDirectory already ends with the sanitized username
            String basePath = baseCacheDirectory.getAbsolutePath();
            String lastPathComponent = basePath.substring(basePath.lastIndexOf(File.separator) + 1);

            if (lastPathComponent.equals(sanitizedUsername)) {
                // The base directory already includes the username as the final path component
                userCacheDirectory = baseCacheDirectory;
            } else {
                // Add the username to the path
                userCacheDirectory = new File(baseCacheDirectory, sanitizedUsername);
            }

            // Log the user directory being used
            LOGGER.info("Using cache directory for user '" + username + "': " +
                    userCacheDirectory.getAbsolutePath());
        }
    }

    /**
     * Connect to the store with the specified credentials
     */
    @Override
    protected boolean protocolConnect(String host, int port, String user, String password)
            throws MessagingException {
        // Save the username and initialize user directory
        if (user != null && !user.isEmpty()) {
            this.username = sanitizeUsername(user);
            initializeUserDirectory();
        }

        // Ensure the base cache directory exists
        if (baseCacheDirectory != null && !baseCacheDirectory.exists()) {
            baseCacheDirectory.mkdirs();
        }

        // Ensure the user cache directory exists
        if (userCacheDirectory != null && !userCacheDirectory.exists()) {
            userCacheDirectory.mkdirs();
        }

        // Set the connection status for the cache
        connected = true;

        // Log what mode we're connecting in
        LOGGER.info("Connecting in " + mode + " mode for user: " + username);

        // For OFFLINE mode, we're done - no IMAP needed
        if (mode == CacheMode.OFFLINE) {
            // Notify listeners
            fireChangeEvent(new MailCacheChangeEvent(this,
                    MailCacheChangeEvent.ChangeType.STORE_OPENED, null));
            return true;
        }

        // For ONLINE, ACCELERATED, REFRESH and DESTRUCTIVE modes, initialize IMAP
        String imapHost = host;
        int imapPort = port;
        String imapUser = user;
        String imapPassword = password;
        boolean useSSL = true;  // Default to SSL

        // Override with session properties if available
        if (imapHost == null || imapHost.isEmpty()) {
            imapHost = session.getProperty("mail.imaps.host");
            // If not found, try non-SSL version
            if (imapHost == null || imapHost.isEmpty()) {
                imapHost = session.getProperty("mail.imap.host");
                useSSL = false;
            }
        }

        if (imapPort <= 0) {
            String portStr = useSSL ?
                    session.getProperty("mail.imaps.port") :
                    session.getProperty("mail.imap.port");
            if (portStr != null && !portStr.isEmpty()) {
                try {
                    imapPort = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    imapPort = useSSL ? 993 : 143;  // Default IMAPS or IMAP port
                }
            } else {
                imapPort = useSSL ? 993 : 143;  // Default IMAPS or IMAP port
            }
        }

        if (imapUser == null || imapUser.isEmpty()) {
            imapUser = useSSL ?
                    session.getProperty("mail.imaps.user") :
                    session.getProperty("mail.imap.user");
        }

        if (imapPassword == null || imapPassword.isEmpty()) {
            imapPassword = useSSL ?
                    session.getProperty("mail.imaps.password") :
                    session.getProperty("mail.imap.password");
        }

        // Log IMAP connection details (except password)
        LOGGER.info("IMAP connection details - Host: " + imapHost +
                ", Port: " + imapPort +
                ", User: " + imapUser +
                ", SSL: " + useSSL);

        // Check if we have all the required parameters
        boolean hasImapConfig = imapHost != null && !imapHost.isEmpty() &&
                imapUser != null && !imapUser.isEmpty() &&
                imapPassword != null && !imapPassword.isEmpty();

        if (!hasImapConfig) {
            LOGGER.warning("Missing required IMAP parameters: " +
                    (imapHost == null || imapHost.isEmpty() ? "Host " : "") +
                    (imapUser == null || imapUser.isEmpty() ? "User " : "") +
                    (imapPassword == null || imapPassword.isEmpty() ? "Password" : ""));

            // In ACCELERATED mode, we can continue with local cache
            if (mode == CacheMode.ACCELERATED) {
                LOGGER.info("Operating in ACCELERATED mode with cache-only (no IMAP connection)");
                // Notify listeners
                fireChangeEvent(new MailCacheChangeEvent(this,
                        MailCacheChangeEvent.ChangeType.STORE_OPENED, null));
                return true;
            } else {
                // In other modes, connection is required
                connected = false;
                throw new MessagingException("Missing required IMAP connection parameters");
            }
        }

        // Connect to IMAP
        try {
            // Get the appropriate store based on SSL setting
            imapStore = session.getStore(useSSL ? "imaps" : "imap");
            LOGGER.info("Created IMAP store: " + imapStore.getClass().getName());

            imapStore.connect(imapHost, imapPort, imapUser, imapPassword);
            LOGGER.info("Successfully connected to IMAP server: " + imapHost);

            // Notify listeners
            fireChangeEvent(new MailCacheChangeEvent(this,
                    MailCacheChangeEvent.ChangeType.STORE_OPENED, null));
            return true;
        } catch (MessagingException e) {
            // If we're in ACCELERATED mode, we can continue with local cache
            if (mode == CacheMode.ACCELERATED) {
                // Log a warning but continue
                LOGGER.log(Level.WARNING, "Could not connect to IMAP server. " +
                        "Operating in cache-only mode.", e);

                // Ensure imapStore is null so we don't attempt to use it
                imapStore = null;

                // Notify listeners
                fireChangeEvent(new MailCacheChangeEvent(this,
                        MailCacheChangeEvent.ChangeType.STORE_OPENED, null));
                return true;
            } else {
                // In other modes, connection is required
                connected = false;
                throw e;
            }
        }
    }

    /**
     * Get the default folder
     */
    @Override
    public CachedFolder getDefaultFolder() throws MessagingException {
        if (!connected) {
            throw new IllegalStateException("Store not connected");
        }

        CachedFolder folder = new CachedFolder(this, "", true);
        // Register for folder changes
        folder.addChangeListener(event -> fireChangeEvent(event));
        return folder;
    }

    /**
     * Get a folder by name
     */
    @Override
    public CachedFolder getFolder(String name) throws MessagingException {
        if (!connected) {
            throw new IllegalStateException("Store not connected");
        }

        CachedFolder folder = new CachedFolder(this, name, false);
        // Register for folder changes
        folder.addChangeListener(event -> fireChangeEvent(event));
        return folder;
    }

    /**
     * Get a folder by URLName
     */
    @Override
    public CachedFolder getFolder(URLName url) throws MessagingException {
        return getFolder(url.getFile());
    }

    /**
     * Get the current operation mode
     */
    public CacheMode getMode() {
        return mode;
    }

    /**
     * Set the operation mode
     */
    public void setMode(CacheMode mode) {
        CacheMode oldMode = this.mode;
        this.mode = mode;

        // Notify listeners of the mode change
        if (oldMode != mode) {
            fireChangeEvent(new MailCacheChangeEvent(this,
                    MailCacheChangeEvent.ChangeType.CACHE_MODE_CHANGED, mode));
        }
    }

    /**
     * Get the IMAP store (null in OFFLINE mode)
     */
    public Store getImapStore() {
        return imapStore;
    }

    /**
     * Get the base cache directory
     */
    public File getBaseCacheDirectory() {
        return baseCacheDirectory;
    }

    /**
     * Get the user-specific cache directory
     */
    public File getCacheDirectory() {
        // Return the user-specific directory if available
        // Otherwise, fall back to the base directory
        return userCacheDirectory != null ? userCacheDirectory : baseCacheDirectory;
    }

    /**
     * Get the username for this store
     */
    public String getUsername() {
        return username;
    }

    /**
     * Get the JavaMail session
     */
    public Session getSession() {
        return session;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() throws MessagingException {
        if (imapStore != null && imapStore.isConnected()) {
            imapStore.close();
        }
        connected = false;

        // Notify listeners
        fireChangeEvent(new MailCacheChangeEvent(this,
                MailCacheChangeEvent.ChangeType.STORE_CLOSED, null));
    }

    /**
     * Enhanced IMAP Store debugging utility
     * Add this to your CachedStore class
     */
    public void debugImapConnection() {
        LOGGER.info("=== IMAP Connection Debug ===");
        LOGGER.info("Current cache mode: " + getMode());
        LOGGER.info("Username: " + username);
        LOGGER.info("Base cache directory: " + (baseCacheDirectory != null ? baseCacheDirectory.getAbsolutePath() : "null"));
        LOGGER.info("User cache directory: " + (userCacheDirectory != null ? userCacheDirectory.getAbsolutePath() : "null"));

        // Check if store exists
        if (imapStore == null) {
            LOGGER.info("IMAP store is NULL - checking initialization");

            // Check session properties for IMAP configuration
            Session session = getSession();
            if (session != null) {
                LOGGER.info("Session properties related to IMAP:");
                String[] imapProps = {
                        "mail.store.protocol",
                        "mail.imaps.host",
                        "mail.imaps.port",
                        "mail.imaps.user",
                        "mail.imaps.ssl.enable",
                        "mail.imap.host",
                        "mail.imap.port",
                        "mail.imap.user",
                        "mail.imap.ssl.enable"
                };

                for (String prop : imapProps) {
                    String value = session.getProperty(prop);
                    // Don't log password
                    LOGGER.info("  " + prop + ": " + (value != null ? value : "not set"));
                }

                // Check for password property existence (don't log actual value)
                String imapsPass = session.getProperty("mail.imaps.password");
                String imapPass = session.getProperty("mail.imap.password");
                LOGGER.info("  mail.imaps.password: " + (imapsPass != null ? "[SET]" : "not set"));
                LOGGER.info("  mail.imap.password: " + (imapPass != null ? "[SET]" : "not set"));
            } else {
                LOGGER.info("Session is NULL");
            }

            // Try to initialize the IMAP store
            LOGGER.info("Attempting to initialize IMAP store...");
            try {
                if (session != null) {
                    String protocol = session.getProperty("mail.store.protocol");
                    if (protocol == null || protocol.equals("cache")) {
                        // Try both IMAPS and IMAP
                        try {
                            Store store = session.getStore("imaps");
                            LOGGER.info("Created IMAPS store: " + (store != null));
                        } catch (Exception e) {
                            LOGGER.info("Failed to create IMAPS store: " + e.getMessage());
                        }

                        try {
                            Store store = session.getStore("imap");
                            LOGGER.info("Created IMAP store: " + (store != null));
                        } catch (Exception e) {
                            LOGGER.info("Failed to create IMAP store: " + e.getMessage());
                        }
                    } else {
                        LOGGER.info("Current protocol is: " + protocol);
                    }
                }
            } catch (Exception e) {
                LOGGER.info("Error during IMAP store initialization: " + e.getMessage());
            }
        } else {
            // IMAP store exists, check connection
            LOGGER.info("IMAP store exists, checking connection");
            LOGGER.info("IMAP store class: " + imapStore.getClass().getName());
            LOGGER.info("IMAP store is connected: " + imapStore.isConnected());

            try {
                URLName url = imapStore.getURLName();
                if (url != null) {
                    LOGGER.info("IMAP URL: " +
                            url.getProtocol() + "://" +
                            url.getUsername() + "@" +
                            url.getHost() + ":" +
                            url.getPort());
                } else {
                    LOGGER.info("IMAP URL is null");
                }
            } catch (Exception e) {
                LOGGER.info("Error getting IMAP URL: " + e.getMessage());
            }
        }

        LOGGER.info("=== End IMAP Connection Debug ===");
    }
}