package com.intenovation.mailcache.config;

import com.intenovation.appfw.config.ConfigItemType;
import com.intenovation.mailcache.CachedFolder;
import com.intenovation.mailcache.CachedMessage;
import com.intenovation.mailcache.CachedStore;
import com.intenovation.mailcache.MailCache;
import javax.mail.Message;
import javax.mail.MessagingException;
import java.io.File;

/**
 * Configuration item type for CachedMessage.
 * Format: username:folderPath:messageId or username:folderPath:messageDir
 */
public class CachedMessageType implements ConfigItemType {
    
    public static final String TYPE_ID = "CACHED_MESSAGE";
    
    @Override
    public String getTypeId() {
        return TYPE_ID;
    }
    
    @Override
    public String getDisplayName() {
        return "Cached Mail Message";
    }
    
    @Override
    public boolean isValidValue(Object value) {
        return value == null || value instanceof CachedMessage;
    }
    
    @Override
    public Object parseValue(String stringValue) {
        if (stringValue == null || stringValue.isEmpty()) {
            return null;
        }
        
        // Format: username:folderPath:messageId or username:folderPath:messageDir
        String[] parts = stringValue.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid format. Expected 'username:folderPath:messageId'");
        }
        
        String username = parts[0];
        String folderPath = parts[1];
        String messageIdentifier = parts[2];
        
        // Get store from MailCache
        CachedStore store = MailCache.getStoreByUsername(username);
        if (store == null) {
            throw new IllegalArgumentException("No cached store found for username: " + username);
        }
        
        try {
            if (!store.isConnected()) {
                store.connect();
            }
            
            CachedFolder folder = store.getFolder(folderPath);
            if (!folder.exists()) {
                throw new IllegalArgumentException("Folder does not exist: " + folderPath);
            }
            
            // Try to interpret as messageDir path first
            File messagesDir = new File(folder.getCacheDir(), "messages");
            File messageDir = new File(messagesDir, messageIdentifier);
            
            if (messageDir.exists() && messageDir.isDirectory()) {
                return new CachedMessage(folder, messageDir);
            }
            
            // Otherwise try to find by Message-ID
            folder.open(javax.mail.Folder.READ_ONLY);
            try {
                Message[] messages = folder.search(
                    new javax.mail.search.HeaderTerm("Message-ID", messageIdentifier)
                );
                
                if (messages.length > 0) {
                    return messages[0];
                }
                
                throw new IllegalArgumentException("Message not found with ID: " + messageIdentifier);
            } finally {
                folder.close(false);
            }
            
        } catch (MessagingException e) {
            throw new IllegalArgumentException("Cannot get message: " + messageIdentifier, e);
        }
    }
    
    @Override
    public String formatValue(Object value) {
        if (value instanceof CachedMessage) {
            CachedMessage message = (CachedMessage) value;
            String username = message.getFolder().getStore().getUsername();
            String folderPath = message.getFolder().getFullName();
            
            // Try to get message ID first
            try {
                String[] messageIds = message.getHeader("Message-ID");
                if (messageIds != null && messageIds.length > 0) {
                    return username + ":" + folderPath + ":" + messageIds[0];
                }
            } catch (MessagingException e) {
                // Fall back to directory name
            }
            
            // Use directory name if available
            File messageDir = message.getMessageDirectory();
            if (messageDir != null) {
                return username + ":" + folderPath + ":" + messageDir.getName();
            }
            
            return username + ":" + folderPath + ":unknown";
        }
        return "";
    }
    
    @Override
    public Object getDefaultValue() {
        return null;
    }
}