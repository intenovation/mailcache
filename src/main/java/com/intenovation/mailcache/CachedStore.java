package com.intenovation.mailcache;

import javax.mail.*;
import java.io.File;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A JavaMail Store implementation that provides caching capabilities
 * with three distinct operation modes.
 */
public class CachedStore extends Store {
    private static final Logger LOGGER = Logger.getLogger(CachedStore.class.getName());

    private Store imapStore;
    private File cacheDirectory;
    private CacheMode mode;
    private boolean connected = false;
    private CacheConfiguration config;

    /**
     * Creates a new CachedStore with the specified session
     */
    public CachedStore(Session session, URLName urlname) {
        super(session, urlname);

        // Get the cache directory from properties
        String cachePath = session.getProperty("mail.cache.directory");
        if (cachePath != null) {
            this.cacheDirectory = new File(cachePath);
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

        // Initialize default configuration
        this.config = new CacheConfiguration();
    }

    /**
     * Get the cache configuration
     */
    public CacheConfiguration getConfig() {
        return config;
    }

    /**
     * Set the cache configuration
     *
     * @param config The configuration to use
     */
    public void setConfig(CacheConfiguration config) {
        this.config = config;
    }

    /**
     * Connect to the store with the specified credentials
     */
    @Override
    protected boolean protocolConnect(String host, int port, String user, String password)
            throws MessagingException {
        // Always ensure cache directory exists
        if (cacheDirectory != null && !cacheDirectory.exists()) {
            cacheDirectory.mkdirs();
        }

        // Set the connection status for the cache
        connected = true;

        // For OFFLINE mode, we're done
        if (mode == CacheMode.OFFLINE) {
            return true;
        }

        // For ONLINE and ACCELERATED modes, get IMAP settings from session properties if not provided
        String imapHost = host;
        int imapPort = port;
        String imapUser = user;
        String imapPassword = password;
        boolean useSSL = true;  // Default to SSL

        // Override with session properties if available
        if (imapHost == null || imapHost.isEmpty()) {
            imapHost = session.getProperty("mail.imaps.host");
        }

        if (imapPort <= 0) {
            String portStr = session.getProperty("mail.imaps.port");
            if (portStr != null && !portStr.isEmpty()) {
                try {
                    imapPort = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    imapPort = 993;  // Default IMAPS port
                }
            } else {
                imapPort = 993;  // Default IMAPS port
            }
        }

        //if (imapUser == null || imapUser.isEmpty()) {
         //   imapUser = session.getProperty("mail.imaps.user");
        //}

        if (imapPassword == null || imapPassword.isEmpty()) {
            imapUser = session.getProperty("mail.imaps.user");
            imapPassword = session.getProperty("mail.imaps.password");
        }

        String sslStr = session.getProperty("mail.imaps.ssl.enable");
        if (sslStr != null) {
            useSSL = Boolean.parseBoolean(sslStr);
        }

        // Check if we have all the required parameters
        if (imapHost == null || imapHost.isEmpty() ||
                imapUser == null || imapUser.isEmpty() ||
                imapPassword == null || imapPassword.isEmpty()) {

            if (mode == CacheMode.ACCELERATED) {
                // In ACCELERATED mode, we can continue with local cache
                LOGGER.log(Level.WARNING, "Missing IMAP parameters. Operating in cache-only mode.");
                return true;
            } else {
                // In ONLINE or DESTRUCTIVE mode, connection is required
                connected = false;
                throw new MessagingException("Missing required IMAP connection parameters");
            }
        }

        // Connect to IMAP
        try {
            // Get the appropriate store based on SSL setting
            imapStore = session.getStore(useSSL ? "imaps" : "imap");
            imapStore.connect(imapHost, imapPort, imapUser, imapPassword);
            return true;
        } catch (MessagingException e) {
            // If we're in ACCELERATED mode, we can continue with local cache
            if (mode == CacheMode.ACCELERATED) {
                // Log a warning but continue
                LOGGER.log(Level.WARNING, "Could not connect to IMAP server. " +
                        "Operating in cache-only mode.", e);
                return true;
            } else {
                // In ONLINE mode, connection is required
                connected = false;
                throw e;
            }
        }
    }

    /**
     * Get the default folder
     */
    @Override
    public Folder getDefaultFolder() throws MessagingException {
        if (!connected) {
            throw new IllegalStateException("Store not connected");
        }

        return new CachedFolder(this, "", true);
    }

    /**
     * Get a folder by name
     */
    @Override
    public Folder getFolder(String name) throws MessagingException {
        if (!connected) {
            throw new IllegalStateException("Store not connected");
        }

        return new CachedFolder(this, name, false);
    }

    /**
     * Get a folder by URLName
     */
    @Override
    public Folder getFolder(URLName url) throws MessagingException {
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
        this.mode = mode;
    }

    /**
     * Get the IMAP store (null in OFFLINE mode)
     */
    public Store getImapStore() {
        return imapStore;
    }

    /**
     * Get the cache directory
     */
    public File getCacheDirectory() {
        return cacheDirectory;
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
    }
}