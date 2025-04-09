package com.intenovation.mailcache;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;

/**
 * A JavaMail Message implementation that supports caching
 */
public class CachedMessage extends MimeMessage {
    private static final Logger LOGGER = Logger.getLogger(CachedMessage.class.getName());

    private Message imapMessage;
    private File messageDir;
    private boolean contentLoaded = false;
    private CachedFolder folder;
    private Properties messageProperties;
    private String content;
    private Flags flags;

    /**
     * Create a new CachedMessage from an IMAP message
     */
    public CachedMessage(CachedFolder folder, Message imapMessage)
            throws MessagingException {
        super(((CachedStore)folder.getStore()).getSession());
        this.folder = folder;
        this.imapMessage = imapMessage;
        this.flags = new Flags();
        this.messageProperties = new Properties();

        // Create message directory in cache
        File messagesDir = new File(folder.getCacheDir(), "messages");
        if (!messagesDir.exists()) {
            messagesDir.mkdirs();
        }

        String messageId = getMessageId();
        if (messageId == null) {
            messageId = "msg_" + System.currentTimeMillis() + "_" +
                    Math.abs(imapMessage.getSubject().hashCode());
        }

        this.messageDir = new File(messagesDir, sanitizeFileName(messageId));
        if (!this.messageDir.exists()) {
            this.messageDir.mkdirs();

            // Save to cache
            saveToCache();
        }
    }

    /**
     * Create a cached message from local cache
     */
    public CachedMessage(CachedFolder folder, File messageDir)
            throws MessagingException {
        super(((CachedStore)folder.getStore()).getSession());
        this.folder = folder;
        this.messageDir = messageDir;
        this.flags = new Flags();
        this.messageProperties = new Properties();

        // Load from cache
        loadFromCache();
    }

    private String getMessageId() {
        try {
            if (imapMessage != null) {
                String[] headers = imapMessage.getHeader("Message-ID");
                return headers != null && headers.length > 0 ? headers[0] : null;
            }

            // Try to get from properties
            if (messageProperties != null) {
                return messageProperties.getProperty("Message-ID");
            }
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error getting Message-ID", e);
        }
        return null;
    }

    private String sanitizeFileName(String input) {
        if (input == null) {
            return "unknown_" + System.currentTimeMillis();
        }
        return input.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void loadFromCache() throws MessagingException {
        if (messageDir == null || !messageDir.exists()) {
            throw new MessagingException("Message directory does not exist");
        }

        try {
            // Load serialized message if available
            File mboxFile = new File(messageDir, "message.mbox");
            if (mboxFile.exists()) {
                try (FileInputStream fis = new FileInputStream(mboxFile)) {
                    parse(fis);
                    contentLoaded = true;
                    return;
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error loading serialized message", e);
                    // Continue with other methods
                }
            }

            // Load properties
            File propsFile = new File(messageDir, "message.properties");
            if (propsFile.exists()) {
                try (FileInputStream fis = new FileInputStream(propsFile)) {
                    messageProperties.load(fis);
                }
            }

            // Load content
            File contentFile = new File(messageDir, "content.txt");
            if (contentFile.exists()) {
                try {
                    content = new String(java.nio.file.Files.readAllBytes(
                            contentFile.toPath()), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error loading message content", e);
                }
            }

            // Load flags
            File flagsFile = new File(messageDir, "flags.txt");
            if (flagsFile.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new FileReader(flagsFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("SEEN".equals(line)) {
                            flags.add(Flags.Flag.SEEN);
                        } else if ("ANSWERED".equals(line)) {
                            flags.add(Flags.Flag.ANSWERED);
                        } else if ("DELETED".equals(line)) {
                            flags.add(Flags.Flag.DELETED);
                        } else if ("FLAGGED".equals(line)) {
                            flags.add(Flags.Flag.FLAGGED);
                        } else if ("DRAFT".equals(line)) {
                            flags.add(Flags.Flag.DRAFT);
                        } else if ("RECENT".equals(line)) {
                            flags.add(Flags.Flag.RECENT);
                        } else if (line.startsWith("USER:")) {
                            flags.add(line.substring(5));
                        }
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error loading message flags", e);
                }
            }

            contentLoaded = true;
        } catch (Exception e) {
            throw new MessagingException("Error loading message from cache", e);
        }
    }

    private void saveToCache() throws MessagingException {
        if (messageDir == null) {
            return;
        }

        if (!messageDir.exists()) {
            messageDir.mkdirs();
        }

        try {
            // First try to save as serialized message
            if (imapMessage instanceof MimeMessage) {
                try (FileOutputStream fos = new FileOutputStream(
                        new File(messageDir, "message.mbox"))) {
                    ((MimeMessage) imapMessage).writeTo(fos);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error saving serialized message", e);
                    // Continue with other methods
                }
            }

            // Save properties
            try {
                // Extract properties from message
                messageProperties.setProperty("Date",
                        imapMessage.getSentDate() != null ?
                                imapMessage.getSentDate().toString() : "");

                messageProperties.setProperty("Subject",
                        imapMessage.getSubject() != null ?
                                imapMessage.getSubject() : "");

                Address[] from = imapMessage.getFrom();
                if (from != null && from.length > 0) {
                    messageProperties.setProperty("From", from[0].toString());
                }

                String[] headers = imapMessage.getHeader("Message-ID");
                if (headers != null && headers.length > 0) {
                    messageProperties.setProperty("Message-ID", headers[0]);
                }

                // Save properties
                try (FileOutputStream fos = new FileOutputStream(
                        new File(messageDir, "message.properties"))) {
                    messageProperties.store(fos, "Mail Message Properties");
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error saving message properties", e);
            }

            // Save content
            try {
                Object msgContent = imapMessage.getContent();
                if (msgContent instanceof String) {
                    content = (String) msgContent;
                    try (FileWriter writer = new FileWriter(
                            new File(messageDir, "content.txt"))) {
                        writer.write(content);
                    }
                } else if (msgContent instanceof Multipart) {
                    // Process multipart content
                    // This would be implemented to extract text and save attachments
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error saving message content", e);
            }

            // Save flags
            try {
                Flags msgFlags = imapMessage.getFlags();
                try (FileWriter writer = new FileWriter(
                        new File(messageDir, "flags.txt"))) {
                    if (msgFlags.contains(Flags.Flag.SEEN)) {
                        writer.write("SEEN\n");
                    }
                    if (msgFlags.contains(Flags.Flag.ANSWERED)) {
                        writer.write("ANSWERED\n");
                    }
                    if (msgFlags.contains(Flags.Flag.DELETED)) {
                        writer.write("DELETED\n");
                    }
                    if (msgFlags.contains(Flags.Flag.FLAGGED)) {
                        writer.write("FLAGGED\n");
                    }
                    if (msgFlags.contains(Flags.Flag.DRAFT)) {
                        writer.write("DRAFT\n");
                    }
                    if (msgFlags.contains(Flags.Flag.RECENT)) {
                        writer.write("RECENT\n");
                    }
                    String[] userFlags = msgFlags.getUserFlags();
                    for (String flag : userFlags) {
                        writer.write("USER:" + flag + "\n");
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error saving message flags", e);
            }

            contentLoaded = true;
        } catch (Exception e) {
            throw new MessagingException("Error saving message to cache", e);
        }
    }

    @Override
    public String getSubject() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (imapMessage != null && store.getMode() == CacheMode.ONLINE) {
            return imapMessage.getSubject();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (messageProperties != null) {
            return messageProperties.getProperty("Subject");
        }

        return null;
    }

    @Override
    public Address[] getFrom() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (imapMessage != null && store.getMode() == CacheMode.ONLINE) {
            return imapMessage.getFrom();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (messageProperties != null) {
            String from = messageProperties.getProperty("From");
            if (from != null && !from.isEmpty()) {
                try {
                    return new Address[]{new InternetAddress(from)};
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error parsing From address", e);
                }
            }
        }

        return null;
    }

    @Override
    public Date getSentDate() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (imapMessage != null && store.getMode() == CacheMode.ONLINE) {
            return imapMessage.getSentDate();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (messageProperties != null) {
            String dateStr = messageProperties.getProperty("Date");
            if (dateStr != null && !dateStr.isEmpty()) {
                try {
                    return new Date(dateStr);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error parsing date", e);
                }
            }
        }

        return null;
    }

    @Override
    public Object getContent() throws MessagingException, IOException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (imapMessage != null && store.getMode() == CacheMode.ONLINE) {
            return imapMessage.getContent();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        return content != null ? content : "";
    }

    @Override
    public void setFlags(Flags flag, boolean set) throws MessagingException {
        // In OFFLINE mode, cannot modify
        CachedStore store = (CachedStore)folder.getStore();
        if (store.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot modify messages in OFFLINE mode");
        }

        // For other modes, update flags
        if (imapMessage != null) {
            imapMessage.setFlags(flag, set);
        }

        // Update flags in memory
        if (set) {
            flags.add(flag);
        } else {
            flags.remove(flag);
        }

        if (store.getMode() == CacheMode.ACCELERATED) {
            // Also update in cache
            try {
                // Save flags
                try (FileWriter writer = new FileWriter(
                        new File(messageDir, "flags.txt"))) {
                    if (flags.contains(Flags.Flag.SEEN)) {
                        writer.write("SEEN\n");
                    }
                    if (flags.contains(Flags.Flag.ANSWERED)) {
                        writer.write("ANSWERED\n");
                    }
                    if (flags.contains(Flags.Flag.DELETED)) {
                        writer.write("DELETED\n");
                    }
                    if (flags.contains(Flags.Flag.FLAGGED)) {
                        writer.write("FLAGGED\n");
                    }
                    if (flags.contains(Flags.Flag.DRAFT)) {
                        writer.write("DRAFT\n");
                    }
                    if (flags.contains(Flags.Flag.RECENT)) {
                        writer.write("RECENT\n");
                    }
                    String[] userFlags = flags.getUserFlags();
                    for (String userFlag : userFlags) {
                        writer.write("USER:" + userFlag + "\n");
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error updating flags in cache", e);
            }
        }
    }

    @Override
    public Flags getFlags() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (imapMessage != null && store.getMode() == CacheMode.ONLINE) {
            return imapMessage.getFlags();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        return flags;
    }

    @Override
    public boolean isSet(Flags.Flag flag) throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (imapMessage != null && store.getMode() == CacheMode.ONLINE) {
            return imapMessage.isSet(flag);
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        return flags.contains(flag);
    }

    @Override
    public int getSize() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (imapMessage != null && store.getMode() == CacheMode.ONLINE) {
            return imapMessage.getSize();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        return content != null ? content.length() : 0;
    }

    @Override
    public InputStream getInputStream() throws IOException, MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (imapMessage != null && store.getMode() == CacheMode.ONLINE) {
            return imapMessage.getInputStream();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (content != null) {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        // Check if we have a mbox file
        File mboxFile = new File(messageDir, "message.mbox");
        if (mboxFile.exists()) {
            return new FileInputStream(mboxFile);
        }

        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void writeTo(OutputStream os) throws IOException, MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (imapMessage != null && store.getMode() == CacheMode.ONLINE) {
            if (imapMessage instanceof MimeMessage) {
                ((MimeMessage)imapMessage).writeTo(os);
                return;
            }
        }

        // Check if we have a mbox file
        File mboxFile = new File(messageDir, "message.mbox");
        if (mboxFile.exists()) {
            try (FileInputStream fis = new FileInputStream(mboxFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
            return;
        }

        // Otherwise, need to build a MimeMessage from properties and content
        super.writeTo(os);
    }
}