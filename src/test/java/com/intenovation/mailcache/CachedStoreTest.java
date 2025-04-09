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
 * Test cases for CachedStore
 */
public class CachedStoreTest {

    @TempDir
    File tempDir;

    @Mock
    Session session;

    @Mock
    Store imapStore;

    @Mock
    Folder imapFolder;

    private CachedStore cachedStore;
    private Properties props;

    @BeforeEach
    public void setUp() throws MessagingException {
        MockitoAnnotations.openMocks(this);

        // Set up properties for the session
        props = new Properties();
        props.setProperty("mail.cache.directory", tempDir.getAbsolutePath());
        props.setProperty("mail.cache.mode", "ACCELERATED");

        // Mock the session to return the properties
        when(session.getProperty("mail.cache.directory")).thenReturn(tempDir.getAbsolutePath());
        when(session.getProperty("mail.cache.mode")).thenReturn("ACCELERATED");

        // This is crucial - mock getProperties() to return our props object
        when(session.getProperties()).thenReturn(props);

        // Mock the session to return the IMAP store
        when(session.getStore("imaps")).thenReturn(imapStore);

        // Create the cached store
        cachedStore = new CachedStore(session, null);
    }

    @Test
    public void testGetCacheDirectory() {
        assertEquals(tempDir.getAbsolutePath(), cachedStore.getCacheDirectory().getAbsolutePath());
    }

    @Test
    public void testGetMode() {
        assertEquals(CacheMode.ACCELERATED, cachedStore.getMode());
    }

    @Test
    public void testSetMode() {
        cachedStore.setMode(CacheMode.ONLINE);
        assertEquals(CacheMode.ONLINE, cachedStore.getMode());
    }

    @Test
    public void testProtocolConnectOfflineMode() throws MessagingException {
        // Set the mode to OFFLINE
        cachedStore.setMode(CacheMode.OFFLINE);

        // Connect to the store
        boolean result = cachedStore.protocolConnect("host", 993, "user", "password");

        // Verify the result
        assertTrue(result);

        // Verify that the IMAP store was not connected
        verify(imapStore, never()).connect(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    public void testProtocolConnectOnlineMode() throws MessagingException {
        // Set the mode to ONLINE
        cachedStore.setMode(CacheMode.ONLINE);

        // Connect to the store
        boolean result = cachedStore.protocolConnect("host", 993, "user", "password");

        // Verify the result
        assertTrue(result);

        // Verify that the IMAP store was connected
        verify(imapStore).connect("host", 993, "user", "password");
    }

    @Test
    public void testProtocolConnectAcceleratedMode() throws MessagingException {
        // Set the mode to ACCELERATED
        cachedStore.setMode(CacheMode.ACCELERATED);

        // Connect to the store
        boolean result = cachedStore.protocolConnect("host", 993, "user", "password");

        // Verify the result
        assertTrue(result);

        // Verify that the IMAP store was connected
        verify(imapStore).connect("host", 993, "user", "password");
    }

    @Test
    public void testProtocolConnectOnlineModeConnectionFails() throws MessagingException {
        // Set the mode to ONLINE
        cachedStore.setMode(CacheMode.ONLINE);

        // Make the connection fail
        doThrow(new MessagingException("Connection failed")).when(imapStore)
                .connect(anyString(), anyInt(), anyString(), anyString());

        // Connect to the store should throw an exception
        assertThrows(MessagingException.class, () ->
                cachedStore.protocolConnect("host", 993, "user", "password"));
    }

    @Test
    public void testProtocolConnectAcceleratedModeConnectionFails() throws MessagingException {
        // Set the mode to ACCELERATED
        cachedStore.setMode(CacheMode.ACCELERATED);

        // Make the connection fail
        doThrow(new MessagingException("Connection failed")).when(imapStore)
                .connect(anyString(), anyInt(), anyString(), anyString());

        // Connect to the store should still succeed in ACCELERATED mode
        boolean result = cachedStore.protocolConnect("host", 993, "user", "password");

        // Verify the result
        assertTrue(result);
    }

    @Test
    public void testGetDefaultFolder() throws MessagingException {
        // Connect to the store
        cachedStore.protocolConnect("host", 993, "user", "password");

        // Get the default folder
        Folder folder = cachedStore.getDefaultFolder();

        // Verify the result
        assertNotNull(folder);
        assertTrue(folder instanceof CachedFolder);
        assertEquals("", folder.getFullName());
    }

    @Test
    public void testGetFolder() throws MessagingException {
        // Connect to the store
        cachedStore.protocolConnect("host", 993, "user", "password");

        // Mock the IMAP store to return a folder
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);

        // Get a folder
        Folder folder = cachedStore.getFolder("INBOX");

        // Verify the result
        assertNotNull(folder);
        assertTrue(folder instanceof CachedFolder);
        assertEquals("INBOX", folder.getFullName());
    }

    @Test
    public void testGetFolderWithURLName() throws MessagingException {
        // Connect to the store
        cachedStore.protocolConnect("host", 993, "user", "password");

        // Mock the IMAP store to return a folder
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);

        // Get a folder with URLName
        URLName urlName = new URLName("imap://user@host/INBOX");
        Folder folder = cachedStore.getFolder(urlName);

        // Verify the result
        assertNotNull(folder);
        assertTrue(folder instanceof CachedFolder);
        assertEquals("INBOX", folder.getFullName());
    }

    @Test
    public void testIsConnected() throws MessagingException {
        // Initially not connected
        assertFalse(cachedStore.isConnected());

        // Connect to the store
        cachedStore.protocolConnect("host", 993, "user", "password");

        // Now connected
        assertTrue(cachedStore.isConnected());
    }

    @Test
    public void testClose() throws MessagingException {
        // Connect to the store
        cachedStore.protocolConnect("host", 993, "user", "password");

        // Mock the IMAP store to be connected
        when(imapStore.isConnected()).thenReturn(true);

        // Close the store
        cachedStore.close();

        // Verify the IMAP store was closed
        verify(imapStore).close();

        // Verify the store is now closed
        assertFalse(cachedStore.isConnected());
    }
}