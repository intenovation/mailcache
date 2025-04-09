package com.intenovation.mailcache;

import javax.mail.Provider;

/**
 * Factory for creating the JavaMail Provider for the cache implementation
 */
public class CacheProviderFactory {
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