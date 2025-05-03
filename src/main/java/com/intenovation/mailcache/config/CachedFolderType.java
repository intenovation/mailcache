package com.intenovation.mailcache.config;

import com.intenovation.appfw.config.ConfigItemType;
import com.intenovation.mailcache.CachedFolder;
import com.intenovation.mailcache.CachedStore;
import com.intenovation.mailcache.MailCache;
import javax.mail.MessagingException;

/**
 * Configuration item type for CachedFolder.
 * Format: username:folderPath
 */
public class CachedFolderType implements ConfigItemType {
    
    public static final String TYPE_ID = "CACHED_FOLDER";
    
    @Override
    public String getTypeId() {
        return TYPE_ID;
    }
    
    @Override
    public String getDisplayName() {
        return "Cached Mail Folder";
    }
    
    @Override
    public boolean isValidValue(Object value) {
        return value == null || value instanceof CachedFolder;
    }
    
    @Override
    public Object parseValue(String stringValue) {
        if (stringValue == null || stringValue.isEmpty()) {
            return null;
        }
        
        // Format: username:folderPath
        String[] parts = stringValue.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid format. Expected 'username:folderPath'");
        }
        
        String username = parts[0];
        String folderPath = parts[1];
        
        // Get store from MailCache
        CachedStore store = MailCache.getStoreByUsername(username);
        if (store == null) {
            throw new IllegalArgumentException("No cached store found for username: " + username);
        }
        
        try {
            if (!store.isConnected()) {
                store.connect();
            }
            return store.getFolder(folderPath);
        } catch (MessagingException e) {
            throw new IllegalArgumentException("Cannot get folder: " + folderPath + " for user: " + username, e);
        }
    }
    
    @Override
    public String formatValue(Object value) {
        if (value instanceof CachedFolder) {
            CachedFolder folder = (CachedFolder) value;
            String username = folder.getStore().getUsername();
            String folderPath = folder.getFullName();
            return username + ":" + folderPath;
        }
        return "";
    }
    
    @Override
    public Object getDefaultValue() {
        return null;
    }
}