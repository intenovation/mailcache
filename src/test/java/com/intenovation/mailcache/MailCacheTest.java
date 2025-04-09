package com.intenovation.mailcache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.mail.MessagingException;
import javax.mail.Provider;
import javax.mail.Session;
import javax.mail.Store;
import java.io.File;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for MailCache helper class
 */
public class MailCacheTest {

    @TempDir
    File tempDir;
    
    @Test
    public void testCreateSession() {
        // Create a session
        Session session = MailCache.createSession(tempDir, CacheMode.ACCELERATED);
        
        // Verify the session properties
        Properties props = session.getProperties();
        assertEquals("cache", props.getProperty("mail.store.protocol"));
        assertEquals(tempDir.getAbsolutePath(), props.getProperty("mail.cache.directory"));
        assertEquals("ACCELERATED", props.getProperty("mail.cache.mode"));
        
        // Verify the provider was registered
        Provider[] providers = session.getProviders();
        boolean foundProvider = false;
        
        for (Provider provider : providers) {
            if ("cache".equals(provider.getProtocol()) && 
                Provider.Type.STORE.equals(provider.getType()) &&
                CachedStore.class.getName().equals(provider.getClassName())) {
                foundProvider = true;
                break;
            }
        }
        
        assertTrue(foundProvider, "Cache provider should be registered");
    }
    
    @Test
    public void testCreateSessionWithImapSettings() {
        // Create a session with IMAP settings
        Session session = MailCache.createSession(
                tempDir, 
                CacheMode.ONLINE,
                "imap.example.com",
                993,
                "user",
                "password",
                true);
        
        // Verify the session properties
        Properties props = session.getProperties();
        assertEquals("cache", props.getProperty("mail.store.protocol"));
        assertEquals(tempDir.getAbsolutePath(), props.getProperty("mail.cache.directory"));
        assertEquals("ONLINE", props.getProperty("mail.cache.mode"));
        
        // Verify the IMAP settings
        assertEquals("imap.example.com", props.getProperty("mail.imaps.host"));
        assertEquals("993", props.getProperty("mail.imaps.port"));
        assertEquals("user", props.getProperty("mail.imaps.user"));
        assertEquals("password", props.getProperty("mail.imaps.password"));
        assertEquals("true", props.getProperty("mail.imaps.ssl.enable"));
    }
    
    @Test
    public void testOpenStore() throws MessagingException {
        // Create and open a store
        CachedStore store = MailCache.openStore(tempDir, CacheMode.OFFLINE);
        
        // Verify the store is connected
        assertTrue(store.isConnected());
        
        // Verify the store properties
        assertEquals(tempDir.getAbsolutePath(), store.getCacheDirectory().getAbsolutePath());
        assertEquals(CacheMode.OFFLINE, store.getMode());
        
        // Clean up
        store.close();
    }
    
    @Test
    public void testFolderExistsInCache() {
        // Initially no folders exist
        assertFalse(MailCache.folderExistsInCache(tempDir, "INBOX"));
        
        // Create a folder
        File inboxDir = new File(tempDir, "INBOX");
        inboxDir.mkdirs();
        
        // Now it should exist
        assertTrue(MailCache.folderExistsInCache(tempDir, "INBOX"));
        
        // Test with a subfolder
        assertFalse(MailCache.folderExistsInCache(tempDir, "INBOX/Subfolder"));
        
        // Create the subfolder
        File subfolderDir = new File(inboxDir, "Subfolder");
        subfolderDir.mkdirs();
        
        // Now it should exist
        assertTrue(MailCache.folderExistsInCache(tempDir, "INBOX/Subfolder"));
    }
    
    @Test
    public void testClearFolderCache() {
        // Create a folder
        File inboxDir = new File(tempDir, "INBOX");
        inboxDir.mkdirs();
        
        // Create a subfolder
        File subfolderDir = new File(inboxDir, "Subfolder");
        subfolderDir.mkdirs();
        
        // Create a messages directory
        File messagesDir = new File(inboxDir, "messages");
        messagesDir.mkdirs();
        
        // Clear the folder
        boolean result = MailCache.clearFolderCache(tempDir, "INBOX");
        
        // Verify the result
        assertTrue(result);
        
        // Verify the folder was cleared
        assertFalse(inboxDir.exists());
    }
    
    @Test
    public void testClearCache() {
        // Create some folders
        File inboxDir = new File(tempDir, "INBOX");
        inboxDir.mkdirs();
        
        File sentDir = new File(tempDir, "Sent");
        sentDir.mkdirs();
        
        // Clear the cache
        boolean result = MailCache.clearCache(tempDir);
        
        // Verify the result
        assertTrue(result);
        
        // Verify the folders were cleared
        assertFalse(inboxDir.exists());
        assertFalse(sentDir.exists());
        
        // Verify the root directory still exists
        assertTrue(tempDir.exists());
    }
    
    @Test
    public void testGetCacheSize() {
        // Initially the cache is empty
        assertEquals(0, MailCache.getCacheSize(tempDir));
        
        // Create a folder
        File inboxDir = new File(tempDir, "INBOX");
        inboxDir.mkdirs();
        
        // Create a messages directory
        File messagesDir = new File(inboxDir, "messages");
        messagesDir.mkdirs();
        
        // Create a file
        File testFile = new File(messagesDir, "test.txt");
        try {
            testFile.createNewFile();
            java.nio.file.Files.write(testFile.toPath(), "Test content".getBytes());
        } catch (Exception e) {
            fail("Failed to create test file: " + e.getMessage());
        }
        
        // Get the cache size
        long size = MailCache.getCacheSize(tempDir);
        
        // Verify the result is greater than zero
        assertTrue(size > 0);
    }
}