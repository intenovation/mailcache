package com.intenovation.mailcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test cases for CachedMessage
 */
public class CachedMessageTest {

    @TempDir
    File tempDir;
    
    @Mock
    CachedStore cachedStore;
    
    @Mock
    CachedFolder cachedFolder;
    
    @Mock
    Message imapMessage;
    
    @Mock
    Session session;
    
    private File messageDir;
    private CachedMessage cachedMessage;
    
    @BeforeEach
    public void setUp() throws MessagingException {
        MockitoAnnotations.openMocks(this);
        
        // Set up the folder and store
        when(cachedFolder.getStore()).thenReturn(cachedStore);
        when(cachedStore.getSession()).thenReturn(session);
        when(cachedStore.getMode()).thenReturn(CacheMode.ACCELERATED);
        
        // Set up the cache directory
        File folderDir = new File(tempDir, "INBOX");
        folderDir.mkdirs();
        
        File messagesDir = new File(folderDir, "messages");
        messagesDir.mkdirs();
        
        messageDir = new File(messagesDir, "test_message");
        messageDir.mkdirs();
        
        // Mock the folder to return the cache directory
        when(cachedFolder.getCacheDir()).thenReturn(folderDir);
        
        // Set up the message
        when(imapMessage.getHeader("Message-ID")).thenReturn(new String[]{"<test_message@example.com>"});
        when(imapMessage.getSubject()).thenReturn("Test Subject");
        when(imapMessage.getFrom()).thenReturn(new Address[]{new InternetAddress("sender@example.com")});
        when(imapMessage.getSentDate()).thenReturn(new Date());
    }
    
    @Test
    public void testCreateFromImapMessage() throws MessagingException {
        // Create a cached message from an IMAP message
        cachedMessage = new CachedMessage(cachedFolder, imapMessage);
        
        // Verify the message was created
        assertNotNull(cachedMessage);
    }
    
    @Test
    public void testCreateFromLocalCache() throws MessagingException, IOException {
        // Create message properties
        File propsFile = new File(messageDir, "message.properties");
        Properties props = new Properties();
        props.setProperty("Subject", "Test Subject");
        props.setProperty("From", "sender@example.com");
        props.setProperty("Date", new Date().toString());
        props.setProperty("Message-ID", "<test_message@example.com>");
        
        try (FileWriter writer = new FileWriter(propsFile)) {
            props.store(writer, "Test Message");
        }
        
        // Create message content
        File contentFile = new File(messageDir, "content.txt");
        try (FileWriter writer = new FileWriter(contentFile)) {
            writer.write("This is the message content");
        }
        
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);
        
        // Verify the message was created
        assertNotNull(cachedMessage);
    }
    
    @Test
    public void testGetSubjectFromCache() throws MessagingException, IOException {
        // Create message properties
        File propsFile = new File(messageDir, "message.properties");
        Properties props = new Properties();
        props.setProperty("Subject", "Test Subject");
        
        try (FileWriter writer = new FileWriter(propsFile)) {
            props.store(writer, "Test Message");
        }
        
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);
        
        // Get the subject
        String subject = cachedMessage.getSubject();
        
        // Verify the result
        assertEquals("Test Subject", subject);
    }
    
    @Test
    public void testGetSubjectFromImapInOnlineMode() throws MessagingException {
        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);
        
        // Create a cached message from an IMAP message
        cachedMessage = new CachedMessage(cachedFolder, imapMessage);
        
        // Get the subject
        String subject = cachedMessage.getSubject();
        
        // Verify the result
        assertEquals("Test Subject", subject);
        
        // Verify the IMAP message was accessed
        verify(imapMessage).getSubject();
    }
    
    @Test
    public void testGetFromAddressFromCache() throws MessagingException, IOException {
        // Create message properties
        File propsFile = new File(messageDir, "message.properties");
        Properties props = new Properties();
        props.setProperty("From", "sender@example.com");
        
        try (FileWriter writer = new FileWriter(propsFile)) {
            props.store(writer, "Test Message");
        }
        
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);
        
        // Get the from address
        Address[] from = cachedMessage.getFrom();
        
        // Verify the result
        assertNotNull(from);
        assertEquals(1, from.length);
        assertEquals("sender@example.com", from[0].toString());
    }
    
    @Test
    public void testGetFromAddressFromImapInOnlineMode() throws MessagingException {
        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);
        
        // Create a cached message from an IMAP message
        cachedMessage = new CachedMessage(cachedFolder, imapMessage);
        
        // Get the from address
        Address[] from = cachedMessage.getFrom();
        
        // Verify the result
        assertNotNull(from);
        assertEquals(1, from.length);
        assertEquals("sender@example.com", from[0].toString());
        
        // Verify the IMAP message was accessed
        verify(imapMessage).getFrom();
    }
    
    @Test
    public void testGetContentFromCache() throws MessagingException, IOException {
        // Create message content
        File contentFile = new File(messageDir, "content.txt");
        try (FileWriter writer = new FileWriter(contentFile)) {
            writer.write("This is the message content");
        }
        
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);
        
        // Get the content
        Object content = cachedMessage.getContent();
        
        // Verify the result
        assertEquals("This is the message content", content);
    }
    
    @Test
    public void testGetContentFromImapInOnlineMode() throws MessagingException, IOException {
        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);
        
        // Mock the IMAP message to return content
        when(imapMessage.getContent()).thenReturn("This is the IMAP message content");
        
        // Create a cached message from an IMAP message
        cachedMessage = new CachedMessage(cachedFolder, imapMessage);
        
        // Get the content
        Object content = cachedMessage.getContent();
        
        // Verify the result
        assertEquals("This is the IMAP message content", content);
        
        // Verify the IMAP message was accessed
        verify(imapMessage).getContent();
    }
    
    @Test
    public void testSetFlagsOfflineMode() throws MessagingException {
        // Set the mode to OFFLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.OFFLINE);
        
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);
        
        // Set a flag should throw an exception
        assertThrows(MessagingException.class, () -> 
            cachedMessage.setFlags(new Flags(Flags.Flag.SEEN), true));
    }
    
    @Test
    public void testSetFlagsOnlineMode() throws MessagingException {
        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);
        
        // Create a cached message from an IMAP message
        cachedMessage = new CachedMessage(cachedFolder, imapMessage);
        
        // Set a flag
        cachedMessage.setFlags(new Flags(Flags.Flag.SEEN), true);
        
        // Verify the IMAP message flag was set
        verify(imapMessage).setFlags(any(Flags.class), eq(true));
    }
    
    @Test
    public void testGetFlags() throws MessagingException, IOException {
        // Create flags file
        File flagsFile = new File(messageDir, "flags.txt");
        try (FileWriter writer = new FileWriter(flagsFile)) {
            writer.write("SEEN\n");
            writer.write("FLAGGED\n");
        }
        
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);
        
        // Get the flags
        Flags flags = cachedMessage.getFlags();
        
        // Verify the result
        assertTrue(flags.contains(Flags.Flag.SEEN));
        assertTrue(flags.contains(Flags.Flag.FLAGGED));
        assertFalse(flags.contains(Flags.Flag.DELETED));
    }
    
    @Test
    public void testGetFlagsFromImapInOnlineMode() throws MessagingException {
        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);
        
        // Mock the IMAP message to return flags
        Flags imapFlags = new Flags();
        imapFlags.add(Flags.Flag.SEEN);
        when(imapMessage.getFlags()).thenReturn(imapFlags);
        
        // Create a cached message from an IMAP message
        cachedMessage = new CachedMessage(cachedFolder, imapMessage);
        
        // Get the flags
        Flags flags = cachedMessage.getFlags();
        
        // Verify the result
        assertTrue(flags.contains(Flags.Flag.SEEN));
        assertFalse(flags.contains(Flags.Flag.FLAGGED));
        
        // Verify the IMAP message was accessed
        verify(imapMessage).getFlags();
    }
    
    @Test
    public void testGetInputStream() throws MessagingException, IOException {
        // Create message content
        File contentFile = new File(messageDir, "content.txt");
        try (FileWriter writer = new FileWriter(contentFile)) {
            writer.write("This is the message content");
        }
        
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);
        
        // Get the input stream
        InputStream is = cachedMessage.getInputStream();
        
        // Read the content
        byte[] buffer = new byte[1024];
        int bytesRead = is.read(buffer);
        String content = new String(buffer, 0, bytesRead);
        
        // Verify the result
        assertEquals("This is the message content", content);
    }
    
    @Test
    public void testGetInputStreamFromImapInOnlineMode() throws MessagingException, IOException {
        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);
        
        // Mock the IMAP message to return an input stream
        ByteArrayInputStream bais = new ByteArrayInputStream("This is the IMAP message content".getBytes());
        when(imapMessage.getInputStream()).thenReturn(bais);
        
        // Create a cached message from an IMAP message
        cachedMessage = new CachedMessage(cachedFolder, imapMessage);
        
        // Get the input stream
        InputStream is = cachedMessage.getInputStream();
        
        // Read the content
        byte[] buffer = new byte[1024];
        int bytesRead = is.read(buffer);
        String content = new String(buffer, 0, bytesRead);
        
        // Verify the result
        assertEquals("This is the IMAP message content", content);
        
        // Verify the IMAP message was accessed
        verify(imapMessage).getInputStream();
    }
}