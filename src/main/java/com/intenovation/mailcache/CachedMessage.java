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

        // Generate formatted directory name using YYYY-MM-DD_Subject format
        String dirName = formatMessageDirName(imapMessage);

        this.messageDir = new File(messagesDir, dirName);
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

    /**
     * Get the local directory where this message is stored
     * This allows adding extra files to the message directory
     *
     * @return The File representing the message directory
     */
    public File getMessageDirectory() {
        return messageDir;
    }

    /**
     * Formats a message directory name based on date and subject
     * Format: YYYY-MM-DD_Subject
     */
    private String formatMessageDirName(Message message) throws MessagingException {
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd");
        String prefix = "";

        // Get date
        Date sentDate = message.getSentDate();
        if (sentDate != null) {
            prefix = dateFormat.format(sentDate) + "_";
        } else {
            // Use current date if no sent date
            prefix = dateFormat.format(new Date()) + "_";
        }

        // Get subject
        String subject = message.getSubject();
        if (subject == null || subject.isEmpty()) {
            subject = "NoSubject_" + System.currentTimeMillis();
        }

        // Sanitize subject for file system
        String sanitizedSubject = subject.replaceAll("[\\\\/:*?\"<>|]", "_");

        // Limit length to avoid too long file names
        if (sanitizedSubject.length() > 100) {
            sanitizedSubject = sanitizedSubject.substring(0, 100);
        }

        return prefix + sanitizedSubject;
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
                    Multipart multipart = (Multipart) msgContent;
                    StringBuilder textContent = new StringBuilder();

                    // Extract text content and save attachments
                    for (int i = 0; i < multipart.getCount(); i++) {
                        BodyPart part = multipart.getBodyPart(i);

                        String disposition = part.getDisposition();

                        // Check if this part is an attachment
                        if (disposition != null &&
                                (disposition.equalsIgnoreCase(Part.ATTACHMENT) ||
                                        disposition.equalsIgnoreCase(Part.INLINE))) {

                            // Only save attachments if configured to do so
                            CachedStore store = (CachedStore) folder.getStore();
                            //if (store.getConfig().isCacheAttachments()) {
                                saveAttachment(part);
                            //}
                        } else {
                            // This part is likely the message body
                            Object partContent = part.getContent();
                            if (partContent instanceof String) {
                                textContent.append((String) partContent);
                                textContent.append("\n");
                            }
                        }
                    }

                    // Save the text content
                    content = textContent.toString();
                    try (FileWriter writer = new FileWriter(
                            new File(messageDir, "content.txt"))) {
                        writer.write(content);
                    }
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

    /**
     * Save an attachment from a message part
     */
    private void saveAttachment(BodyPart part) throws MessagingException, IOException {
        String fileName = part.getFileName();
        if (fileName == null) {
            // Generate a name if none is provided
            fileName = "attachment_" + System.currentTimeMillis();
        }

        // Sanitize the filename
        fileName = sanitizeFileName(fileName);

        // Create attachments directory if it doesn't exist
        File attachmentsDir = new File(messageDir, "attachments");
        if (!attachmentsDir.exists()) {
            attachmentsDir.mkdirs();
        }

        // Only save attachments if configured to do so
        CachedStore store = (CachedStore) folder.getStore();
        if (store.getConfig().isCacheAttachments()) {
            // Save the attachment
            File attachmentFile = new File(attachmentsDir, fileName);
            try (InputStream is = part.getInputStream();
                 FileOutputStream fos = new FileOutputStream(attachmentFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
        } else {
            // Just log that we're skipping the attachment
            LOGGER.log(Level.FINE, "Skipping attachment " + fileName + " as per configuration");
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

        // Prevent setting DELETED flag unless in DESTRUCTIVE mode
        if (set && flag.contains(Flags.Flag.DELETED) && store.getMode() != CacheMode.DESTRUCTIVE) {
            throw new MessagingException("Cannot delete messages unless in DESTRUCTIVE mode");
        }

        // For other modes, update flags on server first
        if (imapMessage != null) {
            try {
                imapMessage.setFlags(flag, set);
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error updating flags on server: " + e.getMessage(), e);
                throw e; // Don't continue if server operation failed
            }
        }

        // Update flags in memory only after server update was successful
        if (set) {
            flags.add(flag);
        } else {
            flags.remove(flag);
        }

        // Always update in cache for all modes except OFFLINE
        if (store.getMode() != CacheMode.OFFLINE) {
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

    /**
     * Add an additional file to the message directory
     *
     * @param filename The name of the file to create/update
     * @param content The content to write to the file
     * @throws IOException If there is an error writing the file
     */
    public void addAdditionalFile(String filename, String content) throws IOException {
        if (messageDir == null || !messageDir.exists()) {
            throw new IOException("Message directory does not exist");
        }

        // Sanitize filename to prevent directory traversal
        String sanitizedFilename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        // Create extras directory if it doesn't exist
        File extrasDir = new File(messageDir, "extras");
        if (!extrasDir.exists()) {
            extrasDir.mkdirs();
        }

        // Write the file
        File file = new File(extrasDir, sanitizedFilename);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    /**
     * Get the content of an additional file
     *
     * @param filename The name of the file to read
     * @return The content of the file, or null if the file doesn't exist
     * @throws IOException If there is an error reading the file
     */
    public String getAdditionalFileContent(String filename) throws IOException {
        if (messageDir == null || !messageDir.exists()) {
            throw new IOException("Message directory does not exist");
        }

        // Sanitize filename to prevent directory traversal
        String sanitizedFilename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        File extrasDir = new File(messageDir, "extras");
        File file = new File(extrasDir, sanitizedFilename);

        if (!file.exists() || !file.isFile()) {
            return null;
        }

        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Get a list of all additional files
     *
     * @return An array of file names
     */
    public String[] listAdditionalFiles() {
        if (messageDir == null || !messageDir.exists()) {
            return new String[0];
        }

        File extrasDir = new File(messageDir, "extras");
        if (!extrasDir.exists() || !extrasDir.isDirectory()) {
            return new String[0];
        }

        File[] files = extrasDir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return new String[0];
        }

        String[] filenames = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            filenames[i] = files[i].getName();
        }

        return filenames;
    }

    /**
     * Get a list of all attachments
     *
     * @return An array of attachment file names
     */
    public String[] listAttachments() {
        if (messageDir == null || !messageDir.exists()) {
            return new String[0];
        }

        File attachmentsDir = new File(messageDir, "attachments");
        if (!attachmentsDir.exists() || !attachmentsDir.isDirectory()) {
            return new String[0];
        }

        File[] files = attachmentsDir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return new String[0];
        }

        String[] filenames = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            filenames[i] = files[i].getName();
        }

        return filenames;
    }

    /**
     * Get an attachment as an input stream
     *
     * @param filename The name of the attachment
     * @return An InputStream to read the attachment, or null if it doesn't exist
     * @throws IOException If there is an error opening the attachment
     */
    public InputStream getAttachmentStream(String filename) throws IOException {
        if (messageDir == null || !messageDir.exists()) {
            return null;
        }

        // Sanitize filename to prevent directory traversal
        String sanitizedFilename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        File attachmentsDir = new File(messageDir, "attachments");
        File file = new File(attachmentsDir, sanitizedFilename);

        if (!file.exists() || !file.isFile()) {
            return null;
        }

        return new FileInputStream(file);
    }

    /**
     * Save an attachment to an external file
     *
     * @param attachmentName The name of the attachment in the message
     * @param destinationFile The file to save it to
     * @return true if successful, false otherwise
     * @throws IOException If there is an error reading or writing the attachment
     */
    public boolean saveAttachmentToFile(String attachmentName, File destinationFile) throws IOException {
        InputStream attachmentStream = getAttachmentStream(attachmentName);
        if (attachmentStream == null) {
            return false;
        }

        try (FileOutputStream fos = new FileOutputStream(destinationFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = attachmentStream.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        } finally {
            attachmentStream.close();
        }

        return true;
    }
}