package com.intenovation.mailcache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the mailcache system
 * These tests verify that the components work together correctly
 */
public class IntegrationTest {

    @TempDir
    File tempDir;
    
    /**
     * Test the offline mode workflow
     */
    @Test
    public void testOfflineMode() throws Exception {
        // Create a cache directory
        File cacheDir = new File(tempDir, "cache");
        cacheDir.mkdirs();
        
        // Initialize the mailcache system in OFFLINE mode
        CachedStore store = MailCache.openStore(cacheDir, CacheMode.OFFLINE);
        
        // Verify the store is connected
        assertTrue(store.isConnected());
        
        // Get the default folder
        Folder defaultFolder = store.getDefaultFolder();
        assertNotNull(defaultFolder);
        
        // Verify no IMAP store is available
        assertNull(store.getImapStore());
        
        // Create the INBOX folder
        Folder inbox = store.getFolder("INBOX");
        inbox.create(Folder.HOLDS_MESSAGES);
        
        // Create a subfolder
        Folder subfolder = inbox.getFolder("Subfolder");
        subfolder.create(Folder.HOLDS_MESSAGES);
        
        // Verify the folders were created
        assertTrue(inbox.exists());
        assertTrue(subfolder.exists());
        
        // Open the INBOX
        inbox.open(Folder.READ_ONLY);
        
        // Verify no messages in the inbox
        assertEquals(0, inbox.getMessageCount());
        
        // Close the folder
        inbox.close(false);
        
        // Test that writing operations fail in OFFLINE mode
        inbox.open(Folder.READ_WRITE);
        
        // Create a test message
        Session session = store.getSession();
        MimeMessage message = new MimeMessage(session);
        message.setSubject("Test Subject");
        message.setFrom(new InternetAddress("from@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("to@example.com"));
        message.setText("This is the message content");
        message.setSentDate(new Date());
        
        // Attempt to append should throw an exception
        assertThrows(MessagingException.class, () -> inbox.appendMessages(new Message[]{message}));
        
        // Close the inbox and store
        inbox.close(false);
        store.close();
    }
    
    /**
     * Test the accelerated mode workflow without an actual IMAP server
     * This simulates the behavior when network is unavailable
     */
    @Test
    public void testAcceleratedModeOffline() throws Exception {
        // Create a cache directory
        File cacheDir = new File(tempDir, "cache");
        cacheDir.mkdirs();
        
        // Use fake IMAP settings - will fail to connect but continue in cache-only mode
        Session session = MailCache.createSession(
                cacheDir,
                CacheMode.ACCELERATED,
                "no-such-server.example.com",
                993,
                "user",
                "password",
                true);
        
        // Open the store - this will fail to connect to IMAP but should still work
        Store store = session.getStore();
        store.connect();
        
        // Verify the store is a CachedStore
        assertTrue(store instanceof CachedStore);
        CachedStore cachedStore = (CachedStore) store;
        
        // Verify it's connected in accelerated mode
        assertTrue(cachedStore.isConnected());
        assertEquals(CacheMode.ACCELERATED, cachedStore.getMode());
        
        // Create the INBOX folder
        Folder inbox = store.getFolder("INBOX");
        inbox.create(Folder.HOLDS_MESSAGES);
        
        // Create a subfolder
        Folder subfolder = inbox.getFolder("Subfolder");
        subfolder.create(Folder.HOLDS_MESSAGES);
        
        // Verify the folders were created
        assertTrue(inbox.exists());
        assertTrue(subfolder.exists());
        
        // Open the inbox
        inbox.open(Folder.READ_WRITE);
        
        // Create a test message
        MimeMessage message = new MimeMessage(session);
        message.setSubject("Test Subject");
        message.setFrom(new InternetAddress("from@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("to@example.com"));
        message.setText("This is the message content");
        message.setSentDate(new Date());
        
        // Append the message - this should work even without IMAP
        inbox.appendMessages(new Message[]{message});
        
        // Close and reopen the inbox
        inbox.close(false);
        inbox.open(Folder.READ_ONLY);
        
        // Verify the message was added
        assertEquals(1, inbox.getMessageCount());
        
        // Get the message and verify its content
        Message storedMessage = inbox.getMessage(1);
        assertNotNull(storedMessage);
        assertEquals("Test Subject", storedMessage.getSubject());
        
        // Close the inbox and store
        inbox.close(false);
        store.close();
    }
    
    /**
     * Test the cache manager functionality
     */
    @Test
    public void testCacheManager() throws Exception {
        // Create a cache directory
        File cacheDir = new File(tempDir, "cache");
        cacheDir.mkdirs();
        
        // Initialize the mailcache system in OFFLINE mode
        CachedStore store = MailCache.openStore(cacheDir, CacheMode.OFFLINE);
        
        // Create the INBOX folder
        Folder inbox = store.getFolder("INBOX");
        inbox.create(Folder.HOLDS_MESSAGES);
        
        // Create a subfolder
        Folder subfolder = inbox.getFolder("Subfolder");
        subfolder.create(Folder.HOLDS_MESSAGES);
        
        // Get the cache manager
        CacheManager manager = CacheManager.getInstance(store);
        
        // Get statistics
        CacheManager.CacheStats stats = manager.getStatistics();
        
        // Verify the statistics
        assertEquals(2, stats.getFolderCount()); // INBOX and Subfolder
        assertEquals(0, stats.getMessageCount());
        
        // Clear the subfolder cache
        boolean result = manager.clearCache("INBOX/Subfolder");
        assertTrue(result);
        
        // Verify the subfolder was deleted
        assertFalse(subfolder.exists());
        
        // Get updated statistics
        stats = manager.getStatistics();
        
        // Verify the folder count decreased
        assertEquals(1, stats.getFolderCount()); // Just INBOX now
        
        // Clear all cache
        result = manager.clearCache();
        assertTrue(result);
        
        // Verify all folders were deleted
        assertFalse(inbox.exists());
        
        // Close the store
        store.close();
    }
    
    /**
     * Test switching between modes
     */
    @Test
    public void testSwitchingModes() throws Exception {
        // Create a cache directory
        File cacheDir = new File(tempDir, "cache");
        cacheDir.mkdirs();
        
        // Initialize the mailcache system in OFFLINE mode
        CachedStore store = MailCache.openStore(cacheDir, CacheMode.OFFLINE);
        
        // Create the INBOX folder
        Folder inbox = store.getFolder("INBOX");
        inbox.create(Folder.HOLDS_MESSAGES);
        
        // Try to append a message - should fail in OFFLINE mode
        inbox.open(Folder.READ_WRITE);
        Session session = store.getSession();
        MimeMessage message = new MimeMessage(session);
        message.setSubject("Test Subject");
        message.setFrom(new InternetAddress("from@example.com"));
        message.setText("This is the message content");
        
        assertThrows(MessagingException.class, () -> inbox.appendMessages(new Message[]{message}));
        
        inbox.close(false);
        
        // Switch to ACCELERATED mode
        store.setMode(CacheMode.ACCELERATED);
        
        // Now try to append a message - should work in ACCELERATED mode
        inbox.open(Folder.READ_WRITE);
        
        // This will work even without an actual IMAP server since we're in accelerated mode
        inbox.appendMessages(new Message[]{message});
        
        // Verify the message was added
        assertEquals(1, inbox.getMessageCount());
        
        // Switch to ONLINE mode
        store.setMode(CacheMode.ONLINE);
        
        // In ONLINE mode, operations would require an IMAP server
        // We can't test this fully without a mock IMAP server
        
        // Close the inbox and store
        inbox.close(false);
        store.close();
    }
}