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

    private static CachedStore store = null;
    private static int configHash = 0;
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
        if (store!= null&& configHash==(imapHost+ imapPort+ username+ password).hashCode()){
            return  store;
        }
        Session session = createSession(cacheDir, mode, imapHost, imapPort,
                username, password, useSSL);
        CachedStore newstore =  (CachedStore) session.getStore();
        // Pass all parameters to ensure they're available in protocolConnect
        newstore.connect(imapHost, imapPort, username, password);
        if (newstore.isConnected()) {
            store = newstore;
            configHash=(imapHost+ imapPort+ username+ password).hashCode();
            return store;
        }
        return  null;
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


}