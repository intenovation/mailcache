package com.intenovation.mailcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.mail.*;
import java.io.File;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test cases for CachedFolder
 */
public class CachedFolderTest {

    @TempDir
    File tempDir;

    @Mock
    CachedStore cachedStore;

    @Mock
    Store imapStore;

    @Mock
    Folder imapFolder;

    @Mock
    Session session;

    private CachedFolder cachedFolder;
    private Properties props;

    @BeforeEach
    public void setUp() throws MessagingException {
        MockitoAnnotations.openMocks(this);

        // Set up properties
        props = new Properties();
        props.setProperty("mail.store.protocol", "cache");

        // Mock session to return properties
        when(session.getProperties()).thenReturn(props);

        // Mock the store to return the cache directory
        when(cachedStore.getCacheDirectory()).thenReturn(tempDir);

        // Mock the store to return the IMAP store
        when(cachedStore.getImapStore()).thenReturn(imapStore);

        // Mock the store to return the session
        when(cachedStore.getSession()).thenReturn(session);

        // Mock the mode
        when(cachedStore.getMode()).thenReturn(CacheMode.ACCELERATED);

        // Mock the IMAP store to return a folder
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);

        // Create the cached folder
        cachedFolder = new CachedFolder(cachedStore, "INBOX", true);
    }

    @Test
    public void testGetName() {
        assertEquals("INBOX", cachedFolder.getName());
    }

    @Test
    public void testGetFullName() {
        assertEquals("INBOX", cachedFolder.getFullName());
    }

    @Test
    public void testGetParent() throws MessagingException {
        // Mock the default folder
        Folder defaultFolder = mock(Folder.class);
        when(cachedStore.getDefaultFolder()).thenReturn(defaultFolder);

        // Get the parent of INBOX - should be the default folder
        Folder parent = cachedFolder.getParent();

        // Verify the result
        assertEquals(defaultFolder, parent);

        // Now test a subfolder
        CachedFolder subfolder = new CachedFolder(cachedStore, "INBOX/Subfolder", true);

        // Mock getFolder to return our INBOX folder
        when(cachedStore.getFolder("INBOX")).thenReturn(cachedFolder);

        // Get the parent of the subfolder
        parent = subfolder.getParent();

        // Verify the result
        assertNotNull(parent);
        assertEquals("INBOX", parent.getFullName());
    }

    @Test
    public void testExists() throws MessagingException {
        // Initially the folder doesn't exist in the filesystem
        assertFalse(cachedFolder.exists());

        // Create the folder directory
        File folderDir = new File(tempDir, "INBOX");
        folderDir.mkdirs();

        // Now it should exist
        assertTrue(cachedFolder.exists());

        // Test with ONLINE mode and IMAP folder
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);
        when(imapFolder.exists()).thenReturn(true);

        // Should still exist
        assertTrue(cachedFolder.exists());
    }

    @Test
    public void testList() throws MessagingException {
        // Create some test folders
        File inboxDir = new File(tempDir, "INBOX");
        inboxDir.mkdirs();

        File subfolder1Dir = new File(inboxDir, "Subfolder1");
        subfolder1Dir.mkdirs();

        File subfolder2Dir = new File(inboxDir, "Subfolder2");
        subfolder2Dir.mkdirs();

        // Create messages directory (should be skipped)
        File messagesDir = new File(inboxDir, "messages");
        messagesDir.mkdirs();

        // List the folders
        Folder[] folders = cachedFolder.list();

        // Verify the result
        assertEquals(2, folders.length);

        // Verify the folder names
        boolean hasSubfolder1 = false;
        boolean hasSubfolder2 = false;

        for (Folder folder : folders) {
            if (folder.getFullName().equals("INBOX/Subfolder1")) {
                hasSubfolder1 = true;
            } else if (folder.getFullName().equals("INBOX/Subfolder2")) {
                hasSubfolder2 = true;
            }
        }

        assertTrue(hasSubfolder1);
        assertTrue(hasSubfolder2);
    }

    @Test
    public void testListWithPattern() throws MessagingException {
        // Create some test folders
        File inboxDir = new File(tempDir, "INBOX");
        inboxDir.mkdirs();

        File subfolder1Dir = new File(inboxDir, "Subfolder1");
        subfolder1Dir.mkdirs();

        File subfolder2Dir = new File(inboxDir, "Subfolder2");
        subfolder2Dir.mkdirs();

        // Create messages directory (should be skipped)
        File messagesDir = new File(inboxDir, "messages");
        messagesDir.mkdirs();

        // List the folders with a pattern
        Folder[] folders = cachedFolder.list("Sub*");

        // Verify the result - note: the pattern is ignored in our implementation
        assertEquals(2, folders.length);
    }

    @Test
    public void testGetFolder() throws MessagingException {
        // Get a subfolder
        Folder subfolder = cachedFolder.getFolder("Subfolder");

        // Verify the result
        assertNotNull(subfolder);
        assertTrue(subfolder instanceof CachedFolder);
        assertEquals("INBOX/Subfolder", subfolder.getFullName());
    }

    @Test
    public void testGetSeparator() throws MessagingException {
        assertEquals('/', cachedFolder.getSeparator());
    }

    @Test
    public void testGetType() throws MessagingException {
        assertEquals(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS, cachedFolder.getType());
    }

    @Test
    public void testCreateOfflineMode() throws MessagingException {
        // Set the mode to OFFLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.OFFLINE);

        // Create the folder
        boolean result = cachedFolder.create(Folder.HOLDS_MESSAGES);

        // Verify the result
        assertTrue(result);

        // Verify the directory was created
        File folderDir = new File(tempDir, "INBOX");
        assertTrue(folderDir.exists());

        // Verify the messages directory was created
        File messagesDir = new File(folderDir, "messages");
        assertTrue(messagesDir.exists());

        // Verify the IMAP folder was not created
        verify(imapFolder, never()).create(anyInt());
    }

    @Test
    public void testCreateOnlineMode() throws MessagingException {
        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);

        // Mock the IMAP folder to return success
        when(imapFolder.create(anyInt())).thenReturn(true);

        // Create the folder
        boolean result = cachedFolder.create(Folder.HOLDS_MESSAGES);

        // Verify the result
        assertTrue(result);

        // Verify the directory was created
        File folderDir = new File(tempDir, "INBOX");
        assertTrue(folderDir.exists());

        // Verify the messages directory was created
        File messagesDir = new File(folderDir, "messages");
        assertTrue(messagesDir.exists());

        // Verify the IMAP folder was created
        verify(imapFolder).create(Folder.HOLDS_MESSAGES);
    }

    @Test
    public void testDeleteOfflineMode() throws MessagingException {
        // Set the mode to OFFLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.OFFLINE);

        // Delete should throw an exception in OFFLINE mode
        assertThrows(MessagingException.class, () -> cachedFolder.delete(false));
    }

    @Test
    public void testDeleteOnlineMode() throws MessagingException {
        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);

        // Mock the IMAP folder to return success
        when(imapFolder.delete(anyBoolean())).thenReturn(true);

        // Create the folder directory
        File folderDir = new File(tempDir, "INBOX");
        folderDir.mkdirs();

        // Delete the folder
        boolean result = cachedFolder.delete(false);

        // Verify the result
        assertTrue(result);

        // Verify the IMAP folder was deleted
        verify(imapFolder).delete(false);
    }

    @Test
    public void testOpenAndClose() throws MessagingException {
        // Initially the folder is not open
        assertFalse(cachedFolder.isOpen());

        // Open the folder
        cachedFolder.open(Folder.READ_ONLY);

        // Verify the folder is now open
        assertTrue(cachedFolder.isOpen());

        // Close the folder
        cachedFolder.close(false);

        // Verify the folder is now closed
        assertFalse(cachedFolder.isOpen());
    }

    @Test
    public void testOpenAndCloseOnlineMode() throws MessagingException {
        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);

        // Open the folder
        cachedFolder.open(Folder.READ_ONLY);

        // Verify the IMAP folder was opened
        verify(imapFolder).open(Folder.READ_ONLY);

        // Close the folder
        cachedFolder.close(false);

        // Verify the IMAP folder was closed
        verify(imapFolder).close(false);
    }

    @Test
    public void testGetMessageCount() throws MessagingException {
        // Create the folder directory
        File folderDir = new File(tempDir, "INBOX");
        folderDir.mkdirs();

        // Create the messages directory
        File messagesDir = new File(folderDir, "messages");
        messagesDir.mkdirs();

        // Create some message directories
        new File(messagesDir, "msg1").mkdirs();
        new File(messagesDir, "msg2").mkdirs();

        // Open the folder
        cachedFolder.open(Folder.READ_ONLY);

        // Get the message count
        int count = cachedFolder.getMessageCount();

        // Verify the result - depends on how we implement counting
        // In our implementation we count subdirectories in the messages directory
        assertEquals(2, count);
    }

    @Test
    public void testGetMessages() throws MessagingException {
        // Create the folder directory
        File folderDir = new File(tempDir, "INBOX");
        folderDir.mkdirs();

        // Create the messages directory
        File messagesDir = new File(folderDir, "messages");
        messagesDir.mkdirs();

        // Create some message directories
        new File(messagesDir, "msg1").mkdirs();
        new File(messagesDir, "msg2").mkdirs();

        // Open the folder
        cachedFolder.open(Folder.READ_ONLY);

        // Get all messages
        Message[] messages = cachedFolder.getMessages();

        // Verify the result - will depend on how we handle loading messages
        // This is a simplified test that just checks we get the expected number
        assertEquals(2, messages.length);
    }
}