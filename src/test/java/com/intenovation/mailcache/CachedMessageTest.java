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
import java.io.InputStream;
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
    MimeMessage imapMessage;

    private File messageDir;
    private CachedMessage cachedMessage;
    private Properties props;
    private Session session;

    @BeforeEach
    public void setUp() throws MessagingException, IOException {
        MockitoAnnotations.openMocks(this);

        // Use real Session instead of mocking
        props = new Properties();
        props.setProperty("mail.store.protocol", "cache");
        session = Session.getInstance(props);

        // Setup the folder and store
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

        // Set up the IMAP message - must use when/thenReturn for each method
        when(imapMessage.getHeader("Message-ID")).thenReturn(new String[]{"<test_message@example.com>"});
        when(imapMessage.getSubject()).thenReturn("Test Subject");
        when(imapMessage.getFrom()).thenReturn(new Address[]{new InternetAddress("sender@example.com")});
        when(imapMessage.getSentDate()).thenReturn(new Date());
        when(imapMessage.getContent()).thenReturn("This is the IMAP message content");
        when(imapMessage.getFlags()).thenReturn(new Flags());

        // Create files for testing from local cache
        // Create message properties
        File propsFile = new File(messageDir, "message.properties");
        Properties msgProps = new Properties();
        msgProps.setProperty("Subject", "Test Subject");
        msgProps.setProperty("From", "sender@example.com");
        msgProps.setProperty("Date", new Date().toString());
        msgProps.setProperty("Message-ID", "<test_message@example.com>");

        try (FileWriter writer = new FileWriter(propsFile)) {
            msgProps.store(writer, "Test Message");
        }

        // Create message content
        File contentFile = new File(messageDir, "content.txt");
        try (FileWriter writer = new FileWriter(contentFile)) {
            writer.write("This is the message content");
        }

        // Create flags file
        File flagsFile = new File(messageDir, "flags.txt");
        try (FileWriter writer = new FileWriter(flagsFile)) {
            writer.write("SEEN\n");
            writer.write("FLAGGED\n");
        }
    }

    @Test
    public void testCreateFromLocalCache() throws MessagingException {
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);

        // Verify the message was created
        assertNotNull(cachedMessage);
    }

    @Test
    public void testGetSubjectFromCache() throws MessagingException {
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);

        // Get the subject
        String subject = cachedMessage.getSubject();

        // Verify the result
        assertEquals("Test Subject", subject);
    }

    @Test
    public void testGetSubjectFromImapInOnlineMode() throws MessagingException, IOException {
        // We'll use a spy on a MimeMessage with real session instead of a mock
        MimeMessage realMessage = new MimeMessage(session);
        realMessage.setSubject("Test Subject");
        MimeMessage spyMessage = spy(realMessage);

        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);

        // Create a cached message from an IMAP message using spy
        // Override saveToCache to do nothing to avoid errors
        doNothing().when(spyMessage).writeTo(any());

        // Create message directory for the spy message
        File spyMessageDir = new File(messageDir.getParentFile(), "spy_message");
        spyMessageDir.mkdirs();

        // Create the CachedMessage with our spy
        cachedMessage = new CachedMessage(cachedFolder, spyMessage);

        // Get the subject
        String subject = cachedMessage.getSubject();

        // Verify the result
        assertEquals("Test Subject", subject);

        // Verify the message was accessed
        verify(spyMessage, atLeastOnce()).getSubject();
    }

    @Test
    public void testGetFromAddressFromCache() throws MessagingException {
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
    public void testGetContentFromCache() throws MessagingException, IOException {
        // Create a cached message from the local cache
        cachedMessage = new CachedMessage(cachedFolder, messageDir);

        // Get the content
        Object content = cachedMessage.getContent();

        // Verify the result
        assertEquals("This is the message content", content);
    }

    @Test
    public void testGetFlags() throws MessagingException {
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
    public void testGetFlagsFromImapInOnlineMode() throws MessagingException, IOException {
        // We'll use a spy on a MimeMessage with real session instead of a mock
        MimeMessage realMessage = new MimeMessage(session);
        Flags imapFlags = new Flags();
        imapFlags.add(Flags.Flag.SEEN);
        realMessage.setFlags(imapFlags, true);
        MimeMessage spyMessage = spy(realMessage);

        // Set the mode to ONLINE
        when(cachedStore.getMode()).thenReturn(CacheMode.ONLINE);

        // Override saveToCache to do nothing to avoid errors
        doNothing().when(spyMessage).writeTo(any());

        // Create message directory for the spy message
        File spyMessageDir = new File(messageDir.getParentFile(), "spy_message");
        spyMessageDir.mkdirs();

        // Create the CachedMessage with our spy
        cachedMessage = new CachedMessage(cachedFolder, spyMessage);

        // Create spy on the CachedMessage to avoid actually accessing flags multiple times
        CachedMessage spyCachedMessage = spy(cachedMessage);

        // Get the flags
        Flags flags = spyCachedMessage.getFlags();

        // Verify the result
        assertTrue(flags.contains(Flags.Flag.SEEN));

        // We can't verify exact number of calls because saveToCache is called during construction
        verify(spyMessage, atLeastOnce()).getFlags();
    }

    @Test
    public void testGetInputStream() throws MessagingException, IOException {
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
}