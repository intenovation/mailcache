package com.intenovation.mailcache;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;

/**
 * A JavaMail Message implementation that supports caching
 * and notifies listeners of changes.
 */
public class CachedMessage extends MimeMessage {
    private static final Logger LOGGER = Logger.getLogger(CachedMessage.class.getName());

    // Property key constants
    public static final String PROP_MESSAGE_ID = "message.id";
    public static final String PROP_MESSAGE_ID_FOLDER = "message.id.folder";
    public static final String PROP_ORIGINAL_MESSAGE_ID = "original.message.id";
    public static final String PROP_SUBJECT = "subject";
    public static final String PROP_FROM = "from";
    public static final String PROP_REPLY_TO = "reply.to";
    public static final String PROP_TO = "to";
    public static final String PROP_CC = "cc";
    public static final String PROP_SENT_DATE = "sent.date";
    public static final String PROP_RECEIVED_DATE = "received.date";
    public static final String PROP_SIZE_BYTES = "size.bytes";
    public static final String PROP_FOLDER_NAME_FORMAT = "folder.name.format";
    public static final String PROP_ORIGINAL_FOLDER_NAME = "original.folder.name";
    public static final String PROP_CONTENT_TYPE = "content.type";
    public static final String PROP_HAS_TEXT_CONTENT = "has.text.content";
    public static final String PROP_HAS_HTML_CONTENT = "has.html.content";
    public static final String PROP_PREFERRED_CONTENT_TYPE = "preferred.content.type";

    // File name constants
    public static final String FILE_MESSAGE_PROPERTIES = "message.properties";
    public static final String FILE_CONTENT_TXT = "content.txt";
    public static final String FILE_CONTENT_HTML = "content.html";
    public static final String FILE_FLAGS_TXT = "flags.txt";
    public static final String DIR_ATTACHMENTS = "attachments";
    public static final String DIR_EXTRAS = "extras";

    private Message imapMessage;
    private File messageDir;
    private boolean contentLoaded = false;
    private CachedFolder folder;
    private Properties messageProperties;
    private String textContent;
    private String htmlContent;
    private boolean hasHtmlContent = false;
    private boolean hasTextContent = false;
    private Flags flags;
    private Date sentDate;
    private Date receivedDate;
    private String subject;
    private String from;
    private String[] to;
    private String[] cc;
    private String replyTo;

    // Listener support
    private final List<MailCacheChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Add a change listener to this message
     */
    public void addChangeListener(MailCacheChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a change listener from this message
     */
    public void removeChangeListener(MailCacheChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Fire a change event to all listeners
     */
    protected void fireChangeEvent(MailCacheChangeEvent event) {
        for (MailCacheChangeListener listener : listeners) {
            try {
                listener.mailCacheChanged(event);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    /**
     * Create a new CachedMessage from an IMAP message
     *
     * @param folder The folder containing this message
     * @param imapMessage The IMAP message to cache
     * @param overwrite Whether to overwrite existing cache if it exists
     */
    public CachedMessage(CachedFolder folder, Message imapMessage, boolean overwrite)
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
        String dirName = MassageDirName.formatMessageDirName(imapMessage);

        this.messageDir = new File(messagesDir, dirName);

        // If the directory exists and we're overwriting, delete it first
        if (overwrite && this.messageDir.exists()) {
            // Delete the directory contents recursively
            deleteRecursive(this.messageDir);
        }

        if (!this.messageDir.exists()) {
            this.messageDir.mkdirs();
        }

        // Save to cache
        saveToCache();

        // Notify listeners
        fireChangeEvent(new MailCacheChangeEvent(this,
                MailCacheChangeEvent.ChangeType.MESSAGE_ADDED, this));
    }

    // Keep the original constructor for backward compatibility
    public CachedMessage(CachedFolder folder, Message imapMessage)
            throws MessagingException {
        this(folder, imapMessage, false);
    }

    // Helper method to recursively delete a directory
    private boolean deleteRecursive(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteRecursive(file);
                }
            }
        }
        return dir.delete();
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
     * Get the underlying IMAP message if available, or attempt to find it on the server
     * When a message is found on the server, it is also cached for future access - implementing
     * the "lazy loading" pattern for ONLINE mode.
     *
     * @return The IMAP message or null if not available or not found
     */
    public Message getImapMessage() {
        // If we already have the IMAP message, return it
        if (imapMessage != null) {
            return imapMessage;
        }

        // Check if we should try to get the message from the server
        CachedStore store = folder.getStore();
        CacheMode mode = store.getMode();

        // If we're in OFFLINE mode or server operations not allowed, don't try to find the message
        if (mode == CacheMode.OFFLINE || !mode.shouldReadFromServerAfterCacheMiss()) {
            return null;
        }

        // Try to find the message on the server
        try {
            // Get the folder from the server
            Folder imapFolder = folder.getImapFolder();
            if (imapFolder == null || !imapFolder.isOpen()) {
                return null;
            }

            // Get the Message-ID from properties
            String messageId = messageProperties.getProperty(PROP_MESSAGE_ID);
            boolean messageCached = false;

            // First try to find by Message-ID
            if (messageId != null && !messageId.isEmpty()) {
                Message[] messages = imapFolder.search(new javax.mail.search.HeaderTerm("Message-ID", messageId));
                if (messages != null && messages.length > 0) {
                    // Found the message, store the reference for future use
                    imapMessage = messages[0];
                    messageCached = true;
                    LOGGER.info("Found message on server by Message-ID: " + messageId);
                }
            }

            // If we couldn't find it by Message-ID, try other criteria
            if (imapMessage == null) {
                String subject = messageProperties.getProperty(PROP_SUBJECT);
                String sentDateStr = messageProperties.getProperty(PROP_SENT_DATE);
                if (subject != null && sentDateStr != null) {
                    try {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        Date sentDate = sdf.parse(sentDateStr);

                        // Get all messages in the folder
                        Message[] allMessages = imapFolder.getMessages();
                        for (Message msg : allMessages) {
                            // Check if subject and date match
                            if (subject.equals(msg.getSubject()) &&
                                    sentDate.equals(msg.getSentDate())) {
                                // Found a likely match
                                imapMessage = msg;
                                messageCached = true;
                                LOGGER.info("Found message on server by subject/date: " + subject);
                                break;
                            }
                        }
                    } catch (java.text.ParseException e) {
                        LOGGER.log(Level.WARNING, "Error parsing sent date: " + sentDateStr, e);
                    }
                }
            }

            // If we found the message on the server but it wasn't in our cache yet
            // (ONLINE mode with server search), save it to cache
            if (imapMessage != null && !messageCached) {
                LOGGER.info("Lazy loading: Message found on server but not in cache. Saving to cache.");

                // Ensure we have a proper message directory
                if (messageDir == null) {
                    // Generate a directory name for the message
                    String dirName = MassageDirName.formatMessageDirName(imapMessage);
                    File messagesDir = new File(folder.getCacheDir(), "messages");
                    if (!messagesDir.exists()) {
                        messagesDir.mkdirs();
                    }
                    this.messageDir = new File(messagesDir, dirName);
                    if (!this.messageDir.exists()) {
                        this.messageDir.mkdirs();
                    }
                }

                // Save the message content to cache
                saveToCache();

                // Notify listeners that a new message was cached
                fireChangeEvent(new MailCacheChangeEvent(this,
                        MailCacheChangeEvent.ChangeType.MESSAGE_ADDED, this));
            }

            return imapMessage;
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error finding IMAP message: " + e.getMessage(), e);
        }

        // Couldn't find the message on the server
        return null;
    }

    /**
     * Get the folder this message belongs to
     *
     * @return The folder
     */
    public CachedFolder getFolder() {
        return folder;
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
     * Checks if content is HTML based on MIME type and content
     *
     * @param part The message part to check
     * @param content The content of the part
     * @return true if the content is HTML, false otherwise
     */
    private boolean isHtmlContent(Part part, Object content) throws MessagingException {
        try {
            // Check MIME type first
            if (part.isMimeType("text/html")) {
                return true;
            }

            // Check if it's text/plain but contains HTML tags
            if (part.isMimeType("text/plain") && content instanceof String) {
                String strContent = (String) content;
                return strContent.toLowerCase().contains("<html");
            }
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error checking HTML content", e);
        }

        return false;
    }

    private String getMessageId() {
        try {
            if (imapMessage != null) {
                String[] headers = imapMessage.getHeader("Message-ID");
                return headers != null && headers.length > 0 ? headers[0] : null;
            }

            // Try to get from properties
            if (messageProperties != null) {
                return messageProperties.getProperty(PROP_MESSAGE_ID);
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
            // Load properties
            File propsFile = new File(messageDir, FILE_MESSAGE_PROPERTIES);
            if (propsFile.exists()) {
                try (FileInputStream fis = new FileInputStream(propsFile)) {
                    messageProperties.load(fis);

                    // Parse dates from properties
                    String sentDateStr = messageProperties.getProperty(PROP_SENT_DATE);
                    if (sentDateStr != null && !sentDateStr.isEmpty()) {
                        try {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                            sentDate = sdf.parse(sentDateStr);
                        } catch (java.text.ParseException e) {
                            LOGGER.log(Level.WARNING, "Error parsing sent date: " + sentDateStr, e);
                        }
                    }

                    String receivedDateStr = messageProperties.getProperty(PROP_RECEIVED_DATE);
                    if (receivedDateStr != null && !receivedDateStr.isEmpty()) {
                        try {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                            receivedDate = sdf.parse(receivedDateStr);
                        } catch (java.text.ParseException e) {
                            LOGGER.log(Level.WARNING, "Error parsing received date: " + receivedDateStr, e);
                        }
                    }

                    // Cache other commonly used properties
                    subject = messageProperties.getProperty(PROP_SUBJECT);
                    from = messageProperties.getProperty(PROP_FROM);

                    // Check for content availability
                    hasHtmlContent = "true".equals(messageProperties.getProperty(PROP_HAS_HTML_CONTENT, "false"));
                    hasTextContent = "true".equals(messageProperties.getProperty(PROP_HAS_TEXT_CONTENT, "false"));
                }
            }

            // Load HTML content if available
            File htmlContentFile = new File(messageDir, FILE_CONTENT_HTML);
            if (htmlContentFile.exists()) {
                try {
                    htmlContent = new String(java.nio.file.Files.readAllBytes(
                            htmlContentFile.toPath()), StandardCharsets.UTF_8);
                    hasHtmlContent = true;
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error loading HTML message content", e);
                }
            }

            // Load text content if available
            File txtContentFile = new File(messageDir, FILE_CONTENT_TXT);
            if (txtContentFile.exists()) {
                try {
                    textContent = new String(java.nio.file.Files.readAllBytes(
                            txtContentFile.toPath()), StandardCharsets.UTF_8);
                    hasTextContent = true;
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error loading text message content", e);
                }
            }

            // Load flags
            File flagsFile = new File(messageDir, FILE_FLAGS_TXT);
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
            // Save properties first for better referencing
            try {
                // Extract properties from message
                // Format dates in the consistent format
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                // Save sent date
                Date msgSentDate = imapMessage.getSentDate();
                if (msgSentDate != null) {
                    messageProperties.setProperty(PROP_SENT_DATE, sdf.format(msgSentDate));
                    this.sentDate = msgSentDate;
                }

                // Save received date
                Date msgReceivedDate = imapMessage.getReceivedDate();
                if (msgReceivedDate != null) {
                    messageProperties.setProperty(PROP_RECEIVED_DATE, sdf.format(msgReceivedDate));
                    this.receivedDate = msgReceivedDate;
                }

                // Save subject
                String msgSubject = imapMessage.getSubject();
                this.subject = msgSubject != null ? msgSubject : "";
                messageProperties.setProperty(PROP_SUBJECT, this.subject);

                // Save from address
                Address[] from = imapMessage.getFrom();
                if (from != null && from.length > 0) {
                    this.from = from[0].toString();
                    messageProperties.setProperty(PROP_FROM, this.from);
                }

                // Save reply-to addresses
                Address[] replyTo = imapMessage.getReplyTo();
                if (replyTo != null && replyTo.length > 0) {
                    StringBuilder replyToStr = new StringBuilder();
                    for (int i = 0; i < replyTo.length; i++) {
                        if (i > 0) replyToStr.append(", ");
                        replyToStr.append(replyTo[i].toString());
                    }
                    this.replyTo = replyToStr.toString();
                    messageProperties.setProperty(PROP_REPLY_TO, this.replyTo);
                }

                // Save recipient addresses
                Address[] recipients = imapMessage.getRecipients(Message.RecipientType.TO);
                if (recipients != null && recipients.length > 0) {
                    StringBuilder toStr = new StringBuilder();
                    for (int i = 0; i < recipients.length; i++) {
                        if (i > 0) toStr.append(", ");
                        toStr.append(recipients[i].toString());
                    }
                    messageProperties.setProperty(PROP_TO, toStr.toString());
                }

                // Save CC addresses
                Address[] ccRecipients = imapMessage.getRecipients(Message.RecipientType.CC);
                if (ccRecipients != null && ccRecipients.length > 0) {
                    StringBuilder ccStr = new StringBuilder();
                    for (int i = 0; i < ccRecipients.length; i++) {
                        if (i > 0) ccStr.append(", ");
                        ccStr.append(ccRecipients[i].toString());
                    }
                    messageProperties.setProperty(PROP_CC, ccStr.toString());
                }

                // Save Message-ID
                String[] headers = imapMessage.getHeader("Message-ID");
                if (headers != null && headers.length > 0) {
                    messageProperties.setProperty(PROP_MESSAGE_ID, headers[0]);
                    // Also save the folder-safe version
                    messageProperties.setProperty(PROP_MESSAGE_ID_FOLDER, sanitizeFileName(headers[0]));
                }

                // Save message size
                int size = imapMessage.getSize();
                if (size > 0) {
                    messageProperties.setProperty(PROP_SIZE_BYTES, String.valueOf(size));
                }

                // Save folder name for reference
                messageProperties.setProperty(PROP_ORIGINAL_FOLDER_NAME, folder.getFullName());

                // Save properties
                try (FileOutputStream fos = new FileOutputStream(
                        new File(messageDir, FILE_MESSAGE_PROPERTIES))) {
                    messageProperties.store(fos, "Mail Message Properties");
                }
            } catch (IOException e) {
                e.printStackTrace();
                LOGGER.log(Level.WARNING, "Error saving message properties", e);
            }

            // Save content
            try {
                Object msgContent = imapMessage.getContent();
                StringBuilder collectedTextContent = new StringBuilder();
                StringBuilder collectedHtmlContent = new StringBuilder();
                boolean foundText = false;
                boolean foundHtml = false;

                if (msgContent instanceof String) {
                    // Check if it's HTML content
                    boolean isHtml = isHtmlContent(imapMessage, msgContent);

                    if (isHtml) {
                        collectedHtmlContent.append((String) msgContent);
                        foundHtml = true;
                    } else {
                        collectedTextContent.append((String) msgContent);
                        foundText = true;
                    }
                } else if (msgContent instanceof Multipart) {
                    // Process multipart content
                    Multipart multipart = (Multipart) msgContent;

                    // Extract text content and save attachments
                    for (int i = 0; i < multipart.getCount(); i++) {
                        BodyPart part = multipart.getBodyPart(i);
                        String disposition = part.getDisposition();

                        // Check if this part is an attachment
                        if (disposition != null &&
                                (disposition.equalsIgnoreCase(Part.ATTACHMENT) ||
                                        disposition.equalsIgnoreCase(Part.INLINE))) {

                            // Save attachments
                            saveAttachment(part);

                        } else {
                            // This part is likely the message body
                            Object partContent = part.getContent();
                            if (partContent instanceof String) {
                                if (isHtmlContent(part, partContent)) {
                                    // HTML content
                                    collectedHtmlContent.append((String) partContent);
                                    foundHtml = true;
                                } else {
                                    // Plain text content
                                    collectedTextContent.append((String) partContent);
                                    collectedTextContent.append("\n");
                                    foundText = true;
                                }
                            } else if (partContent instanceof Multipart) {
                                // Handle nested multipart content recursively
                                processMimePartContent(part, collectedTextContent, collectedHtmlContent);
                            }
                        }
                    }
                }

                // Now save both formats if found
                if (foundHtml) {
                    htmlContent = collectedHtmlContent.toString();
                    try (FileWriter writer = new FileWriter(
                            new File(messageDir, FILE_CONTENT_HTML))) {
                        writer.write(htmlContent);
                    }
                    hasHtmlContent = true;
                    messageProperties.setProperty(PROP_HAS_HTML_CONTENT, "true");
                } else {
                    hasHtmlContent = false;
                    messageProperties.setProperty(PROP_HAS_HTML_CONTENT, "false");
                }

                if (foundText) {
                    textContent = collectedTextContent.toString();
                    try (FileWriter writer = new FileWriter(
                            new File(messageDir, FILE_CONTENT_TXT))) {
                        writer.write(textContent);
                    }
                    hasTextContent = true;
                    messageProperties.setProperty(PROP_HAS_TEXT_CONTENT, "true");
                } else {
                    hasTextContent = false;
                    messageProperties.setProperty(PROP_HAS_TEXT_CONTENT, "false");
                }

                // Set preferred content type - HTML preferred if available
                if (foundHtml) {
                    messageProperties.setProperty(PROP_PREFERRED_CONTENT_TYPE, "text/html");
                } else if (foundText) {
                    messageProperties.setProperty(PROP_PREFERRED_CONTENT_TYPE, "text/plain");
                }

                // Update properties file with content availability info
                try (FileOutputStream fos = new FileOutputStream(
                        new File(messageDir, FILE_MESSAGE_PROPERTIES))) {
                    messageProperties.store(fos, "Mail Message Properties");
                }

            } catch (IOException e) {
                e.printStackTrace();
                LOGGER.log(Level.WARNING, "Error saving message content", e);
            }

            // Save flags
            try {
                Flags msgFlags = imapMessage.getFlags();
                try (FileWriter writer = new FileWriter(
                        new File(messageDir, FILE_FLAGS_TXT))) {
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

                // Update our internal flags object
                this.flags = (Flags) msgFlags.clone();

            } catch (IOException e) {
                e.printStackTrace();
                LOGGER.log(Level.WARNING, "Error saving message flags", e);
            }

            contentLoaded = true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new MessagingException("Error saving message to cache", e);
        }
    }

    /**
     * Process a MIME part recursively to extract both text and HTML content
     */
    private void processMimePartContent(Part part, StringBuilder textContent, StringBuilder htmlContent)
            throws MessagingException, IOException {

        Object content = part.getContent();

        if (content instanceof String) {
            if (isHtmlContent(part, content)) {
                htmlContent.append((String) content);
            } else {
                textContent.append((String) content);
                textContent.append("\n");
            }
        } else if (content instanceof Multipart) {
            Multipart mp = (Multipart) content;
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String disposition = bp.getDisposition();

                // Skip attachments in content processing
                if (disposition != null &&
                        (disposition.equalsIgnoreCase(Part.ATTACHMENT) ||
                                disposition.equalsIgnoreCase(Part.INLINE))) {
                    continue;
                }

                processMimePartContent(bp, textContent, htmlContent);
            }
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
        File attachmentsDir = new File(messageDir, DIR_ATTACHMENTS);
        if (!attachmentsDir.exists()) {
            attachmentsDir.mkdirs();
        }

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
    }

    /**
     * Determines if server or local operations should be used for reading
     * based on the current CacheMode and availability of the IMAP message
     *
     * @return true if operations should use server data, false for local cache
     */
    private boolean shouldUseServerForReading() {
        // Get the current mode
        CachedStore store = (CachedStore) folder.getStore();
        CacheMode mode = store.getMode();

        // If mode directly states to read from server, do so if we have an IMAP message
        if (mode.shouldReadFromServer()) {
            return imapMessage != null;
        }

        // Otherwise, we don't use the server for initial reading
        return false;
    }

    /**
     * Handle a cache miss by attempting to fetch the message from server.
     * If successful, loads properties and content from the server message.
     *
     * @param methodName The name of the method that had the cache miss, for logging
     * @return true if the message was successfully retrieved and loaded, false otherwise
     * @throws MessagingException If there is an error loading the message data
     */
    private boolean handleCacheMiss(String methodName) throws MessagingException {
        CachedStore store = (CachedStore) folder.getStore();
        CacheMode mode = store.getMode();

        // Check if we can try the server after a cache miss
        if (mode.shouldReadFromServerAfterCacheMiss()) {
            LOGGER.fine("Cache miss in " + methodName + " - attempting to fetch from server");

            // Try to get/find the IMAP message
            Message msg = getImapMessage();
            if (msg != null) {
                LOGGER.info("Successfully fetched message from server after cache miss in " + methodName);

                // Store the reference
                this.imapMessage = msg;

                // Load message properties from the server message
                try {
                    // Extract and store key properties
                    if (this.sentDate == null) {
                        this.sentDate = msg.getSentDate();
                    }
                    if (this.subject == null) {
                        this.subject = msg.getSubject();
                    }

                    // Get from address
                    Address[] fromAddresses = msg.getFrom();
                    if (fromAddresses != null && fromAddresses.length > 0) {
                        this.from = fromAddresses[0].toString();
                    }

                    // Get Message-ID header
                    String[] headers = msg.getHeader("Message-ID");
                    if (headers != null && headers.length > 0) {
                        messageProperties.setProperty(PROP_MESSAGE_ID, headers[0]);
                    }

                    // Save full content to cache
                    try {
                        saveToCache();
                        contentLoaded = true;
                    } catch (MessagingException e) {
                        LOGGER.log(Level.WARNING, "Error saving message content to cache after server fetch", e);
                    }

                    return true;
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error extracting message properties from server after cache miss", e);
                }
            } else {
                LOGGER.warning("Failed to fetch message from server after cache miss in " + methodName);
            }
        }

        return false;
    }


    @Override
    public String getSubject() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.getSubject();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (subject != null) {
            return subject;
        }

        // Handle cache miss if needed
        if (handleCacheMiss("getSubject")) {
            try {
                return imapMessage.getSubject();
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting subject from server after cache miss", e);
            }
        }

        return null;
    }

    @Override
    public Address[] getFrom() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.getFrom();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (from != null && !from.isEmpty()) {
            try {
                return new Address[]{new InternetAddress(from)};
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error parsing From address", e);
            }
        }

        // Handle cache miss if needed
        if (handleCacheMiss("getFrom")) {
            try {
                return imapMessage.getFrom();
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting From address from server after cache miss", e);
            }
        }

        return null;
    }

    @Override
    public Date getSentDate() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.getSentDate();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (sentDate != null) {
            return sentDate;
        }

        // Handle cache miss if needed
        if (handleCacheMiss("getSentDate")) {
            try {
                return imapMessage.getSentDate();
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting sent date from server after cache miss", e);
            }
        }

        return null;
    }

    @Override
    public Object getContent() throws MessagingException, IOException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.getContent();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        // Return the preferred content type (HTML if available, otherwise text)
        if (hasHtmlContent) {
            return htmlContent;
        } else if (hasTextContent) {
            return textContent;
        }

        // Handle cache miss if needed
        if (handleCacheMiss("getContent")) {
            try {
                return imapMessage.getContent();
            } catch (MessagingException | IOException e) {
                LOGGER.log(Level.WARNING, "Error getting content from server after cache miss", e);
            }
        }

        // Empty string is better than null for content
        return "";
    }

    /**
     * Get the HTML content if available
     *
     * @return HTML content or null if not available
     * @throws MessagingException If there is an error accessing the content
     */
    public String getHtmlContent() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            try {
                Object content = imapMessage.getContent();
                if (content instanceof String) {
                    if (isHtmlContent(imapMessage, content)) {
                        return (String) content;
                    }
                } else if (content instanceof Multipart) {
                    StringBuilder html = new StringBuilder();
                    processMimePartContent(imapMessage, new StringBuilder(), html);
                    if (html.length() > 0) {
                        return html.toString();
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error getting HTML content from IMAP", e);
            }
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (hasHtmlContent) {
            return htmlContent;
        }

        // Handle cache miss if needed
        if (handleCacheMiss("getHtmlContent")) {
            try {
                Object content = imapMessage.getContent();
                if (content instanceof String) {
                    if (isHtmlContent(imapMessage, content)) {
                        String htmlStr = (String) content;
                        // Cache the HTML content
                        cacheHtmlContent(htmlStr);
                        return htmlStr;
                    }
                } else if (content instanceof Multipart) {
                    StringBuilder html = new StringBuilder();
                    processMimePartContent(imapMessage, new StringBuilder(), html);
                    if (html.length() > 0) {
                        String htmlStr = html.toString();
                        // Cache the HTML content
                        cacheHtmlContent(htmlStr);
                        return htmlStr;
                    }
                }
            } catch (IOException | MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting HTML content from server after cache miss", e);
            }
        }

        return null;
    }

    /**
     * Helper method to cache only HTML content
     */
    private void cacheHtmlContent(String htmlContent) {
        if (messageDir == null || !messageDir.exists()) {
            return;
        }

        try {
            // Save HTML content
            if (htmlContent != null && !htmlContent.isEmpty()) {
                try (FileWriter writer = new FileWriter(
                        new File(messageDir, FILE_CONTENT_HTML))) {
                    writer.write(htmlContent);
                }
                this.htmlContent = htmlContent;
                this.hasHtmlContent = true;
                messageProperties.setProperty(PROP_HAS_HTML_CONTENT, "true");

                // Update properties file
                try (FileOutputStream fos = new FileOutputStream(
                        new File(messageDir, FILE_MESSAGE_PROPERTIES))) {
                    messageProperties.store(fos, "Mail Message Properties");
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error caching HTML content", e);
        }
    }

    /**
     * Get the text content if available
     * If text content is not available but HTML content is,
     * converts HTML to text using Jsoup and caches the result
     *
     * @return Text content, never null
     * @throws MessagingException If there is an error accessing the content
     */
    public String getTextContent() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            try {
                Object content = imapMessage.getContent();
                if (content instanceof String) {
                    if (!isHtmlContent(imapMessage, content)) {
                        return (String) content;
                    } else {
                        // Convert HTML to text using Jsoup
                        String htmlStr = (String) content;
                        String textStr = Jsoup.parse(htmlStr).wholeText();

                        // Store both versions in cache
                        cacheHtmlAndTextContent(htmlStr, textStr);

                        return textStr;
                    }
                } else if (content instanceof Multipart) {
                    StringBuilder text = new StringBuilder();
                    StringBuilder html = new StringBuilder();
                    processMimePartContent(imapMessage, text, html);

                    if (text.length() > 0) {
                        return text.toString();
                    } else if (html.length() > 0) {
                        // Convert HTML to text using Jsoup
                        String htmlStr = html.toString();
                        String textStr = Jsoup.parse(htmlStr).wholeText();

                        // Store both versions in cache
                        cacheHtmlAndTextContent(htmlStr, textStr);

                        return textStr;
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error getting text content from IMAP", e);
            }
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        // Check if we have text content
        if (hasTextContent && textContent != null) {
            // Check if textContent contains HTML (possible in legacy messages)
            if (textContent.toLowerCase().contains("<html") ||
                    textContent.toLowerCase().contains("<body")) {
                try {
                    // This is actually HTML content stored in the wrong file
                    // Move it to HTML content and convert to text
                    String htmlStr = textContent;
                    String textStr = Jsoup.parse(htmlStr).wholeText();

                    // Update cache files
                    cacheHtmlAndTextContent(htmlStr, textStr);

                    return textStr;
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error converting HTML to text", e);
                }
            }
            return textContent;
        } else if (hasHtmlContent && htmlContent != null) {
            try {
                // Convert HTML to text using Jsoup
                String textStr = Jsoup.parse(htmlContent).wholeText();

                // Cache the text content
                cacheTextContent(textStr);

                return textStr;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error converting HTML to text", e);
                // Fall back to empty string
            }
        }

        // Handle cache miss if needed
        if (handleCacheMiss("getTextContent")) {
            try {
                Object content = imapMessage.getContent();
                if (content instanceof String) {
                    if (!isHtmlContent(imapMessage, content)) {
                        String textStr = (String) content;
                        // Cache the text content
                        cacheTextContent(textStr);
                        return textStr;
                    } else {
                        // Convert HTML to text using Jsoup
                        String htmlStr = (String) content;
                        String textStr = Jsoup.parse(htmlStr).wholeText();
                        // Store both versions in cache
                        cacheHtmlAndTextContent(htmlStr, textStr);
                        return textStr;
                    }
                } else if (content instanceof Multipart) {
                    StringBuilder text = new StringBuilder();
                    StringBuilder html = new StringBuilder();
                    processMimePartContent(imapMessage, text, html);

                    if (text.length() > 0) {
                        String textStr = text.toString();
                        cacheTextContent(textStr);
                        return textStr;
                    } else if (html.length() > 0) {
                        String htmlStr = html.toString();
                        String textStr = Jsoup.parse(htmlStr).wholeText();
                        cacheHtmlAndTextContent(htmlStr, textStr);
                        return textStr;
                    }
                }
            } catch (IOException | MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting text content from server after cache miss", e);
            }
        }

        // Return empty string instead of null
        return "";
    }

    /**
     * Helper method to cache both HTML and text content
     */
    private void cacheHtmlAndTextContent(String htmlContent, String textContent) {
        if (messageDir == null || !messageDir.exists()) {
            return;
        }

        try {
            // Save HTML content
            if (htmlContent != null && !htmlContent.isEmpty()) {
                try (FileWriter writer = new FileWriter(
                        new File(messageDir, FILE_CONTENT_HTML))) {
                    writer.write(htmlContent);
                }
                this.htmlContent = htmlContent;
                this.hasHtmlContent = true;
                messageProperties.setProperty(PROP_HAS_HTML_CONTENT, "true");
            }

            // Save text content
            if (textContent != null && !textContent.isEmpty()) {
                try (FileWriter writer = new FileWriter(
                        new File(messageDir, FILE_CONTENT_TXT))) {
                    writer.write(textContent);
                }
                this.textContent = textContent;
                this.hasTextContent = true;
                messageProperties.setProperty(PROP_HAS_TEXT_CONTENT, "true");
            }

            // Update properties file
            try (FileOutputStream fos = new FileOutputStream(
                    new File(messageDir, FILE_MESSAGE_PROPERTIES))) {
                messageProperties.store(fos, "Mail Message Properties");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error caching content", e);
        }
    }

    /**
     * Helper method to cache only text content
     */
    private void cacheTextContent(String textContent) {
        if (messageDir == null || !messageDir.exists()) {
            return;
        }

        try {
            // Save text content
            if (textContent != null && !textContent.isEmpty()) {
                try (FileWriter writer = new FileWriter(
                        new File(messageDir, FILE_CONTENT_TXT))) {
                    writer.write(textContent);
                }
                this.textContent = textContent;
                this.hasTextContent = true;
                messageProperties.setProperty(PROP_HAS_TEXT_CONTENT, "true");

                // Update properties file
                try (FileOutputStream fos = new FileOutputStream(
                        new File(messageDir, FILE_MESSAGE_PROPERTIES))) {
                    messageProperties.store(fos, "Mail Message Properties");
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error caching text content", e);
        }
    }

    /**
     * Check if the message has HTML content
     *
     * @return true if HTML content is available
     * @throws MessagingException If there is an error checking content
     */
    public boolean hasHtmlContent() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            try {
                Object msgContent = imapMessage.getContent();
                if (msgContent instanceof String) {
                    return isHtmlContent(imapMessage, msgContent);
                } else if (msgContent instanceof Multipart) {
                    StringBuilder html = new StringBuilder();
                    processMimePartContent(imapMessage, new StringBuilder(), html);
                    return html.length() > 0;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error checking HTML content from IMAP", e);
            }
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (hasHtmlContent) {
            return true;
        }

        // Handle cache miss if needed
        if (handleCacheMiss("hasHtmlContent")) {
            try {
                Object msgContent = imapMessage.getContent();
                if (msgContent instanceof String) {
                    boolean html = isHtmlContent(imapMessage, msgContent);
                    // Update cache if it's HTML
                    if (html) {
                        cacheHtmlContent((String) msgContent);
                    }
                    return html;
                } else if (msgContent instanceof Multipart) {
                    StringBuilder html = new StringBuilder();
                    processMimePartContent(imapMessage, new StringBuilder(), html);
                    boolean hasHtml = html.length() > 0;
                    if (hasHtml) {
                        cacheHtmlContent(html.toString());
                    }
                    return hasHtml;
                }
            } catch (IOException | MessagingException e) {
                LOGGER.log(Level.WARNING, "Error checking HTML content from server after cache miss", e);
            }
        }

        return false;
    }

    /**
     * Check if the message has text content
     *
     * @return true if text content is available
     * @throws MessagingException If there is an error checking content
     */
    public boolean hasTextContent() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            try {
                Object msgContent = imapMessage.getContent();
                if (msgContent instanceof String) {
                    return !isHtmlContent(imapMessage, msgContent);
                } else if (msgContent instanceof Multipart) {
                    StringBuilder text = new StringBuilder();
                    processMimePartContent(imapMessage, text, new StringBuilder());
                    return text.length() > 0;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error checking text content from IMAP", e);
            }
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (hasTextContent) {
            return true;
        }

        // Handle cache miss if needed
        if (handleCacheMiss("hasTextContent")) {
            try {
                Object msgContent = imapMessage.getContent();
                if (msgContent instanceof String) {
                    boolean isText = !isHtmlContent(imapMessage, msgContent);
                    if (isText) {
                        cacheTextContent((String) msgContent);
                    }
                    return isText;
                } else if (msgContent instanceof Multipart) {
                    StringBuilder text = new StringBuilder();
                    processMimePartContent(imapMessage, text, new StringBuilder());
                    boolean hasText = text.length() > 0;
                    if (hasText) {
                        cacheTextContent(text.toString());
                    }
                    return hasText;
                }
            } catch (IOException | MessagingException e) {
                LOGGER.log(Level.WARNING, "Error checking text content from server after cache miss", e);
            }
        }

        return false;
    }

    /**
     * Check if the content is HTML based on the preferred content type
     *
     * @return true if the preferred content is HTML, false otherwise
     * @throws MessagingException If there is an error checking content type
     */
    public boolean isHtmlContent() throws MessagingException {
        // HTML is preferred if both are available
        return hasHtmlContent();
    }

    @Override
    public void setFlags(Flags flag, boolean set) throws MessagingException {
        // Get the current mode
        CachedStore store = (CachedStore) folder.getStore();
        CacheMode mode = store.getMode();

        // In OFFLINE mode, cannot modify
        if (mode == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot modify messages in OFFLINE mode");
        }

        // Prevent setting DELETED flag unless in DESTRUCTIVE mode
        if (set && flag.contains(Flags.Flag.DELETED) && !mode.isDeleteAllowed()) {
            throw new MessagingException("Cannot delete messages unless in DESTRUCTIVE mode");
        }

        // For other modes, update flags on server first
        if (imapMessage != null) {
            try {
                imapMessage.setFlags(flag, set);
                LOGGER.fine("Updated flags on server: " + flag + " -> " + set);
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error updating flags on server: " + e.getMessage(), e);
                throw e; // Don't continue if server operation failed
            }
        } else {
            // In ONLINE mode, we need to try to find the message first
            if (mode.shouldSearchOnServer()) {
                Message msg = getImapMessage();
                if (msg != null) {
                    try {
                        msg.setFlags(flag, set);
                        LOGGER.fine("Updated flags on server (after lazy loading): " + flag + " -> " + set);
                    } catch (MessagingException e) {
                        LOGGER.log(Level.WARNING, "Error updating flags on server after lazy loading: " + e.getMessage(), e);
                        throw e;
                    }
                } else {
                    throw new MessagingException("Cannot update flags - message not found on server");
                }
            }
        }

        // Update flags in memory only after server update was successful
        if (set) {
            flags.add(flag);
        } else {
            flags.remove(flag);
        }

        // Always update in cache for all modes except OFFLINE
        if (mode != CacheMode.OFFLINE) {
            try {
                // Save flags
                try (FileWriter writer = new FileWriter(
                        new File(messageDir, FILE_FLAGS_TXT))) {
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

                // Notify listeners of the change
                fireChangeEvent(new MailCacheChangeEvent(this,
                        MailCacheChangeEvent.ChangeType.MESSAGE_UPDATED, this));

            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error updating flags in cache", e);
            }
        }
    }

    @Override
    public Flags getFlags() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.getFlags();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (flags != null) {
            return flags;
        }

        // Handle cache miss if needed
        if (handleCacheMiss("getFlags")) {
            try {
                Flags serverFlags = imapMessage.getFlags();
                // Update local cache
                this.flags = (Flags) serverFlags.clone();

                // Save to disk
                try (FileWriter writer = new FileWriter(
                        new File(messageDir, FILE_FLAGS_TXT))) {
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
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error writing flags to cache after server fetch", e);
                }

                return serverFlags;
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting flags from server after cache miss", e);
            }
        }

        // Return default flags
        return new Flags();
    }

    @Override
    public boolean isSet(Flags.Flag flag) throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.isSet(flag);
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (flags != null) {
            return flags.contains(flag);
        }

        // Handle cache miss if needed
        if (handleCacheMiss("isSet")) {
            try {
                return imapMessage.isSet(flag);
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error checking flag on server after cache miss", e);
            }
        }

        return false;
    }

    @Override
    public int getSize() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.getSize();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        // Get size from properties if available
        String sizeStr = messageProperties.getProperty(PROP_SIZE_BYTES);
        if (sizeStr != null && !sizeStr.isEmpty()) {
            try {
                return Integer.parseInt(sizeStr);
            } catch (NumberFormatException e) {
                LOGGER.log(Level.FINE, "Error parsing size property", e);
            }
        }

        // Return size of preferred content
        if (hasHtmlContent) {
            return htmlContent != null ? htmlContent.length() : 0;
        } else if (hasTextContent) {
            return textContent != null ? textContent.length() : 0;
        }

        // Handle cache miss if needed
        if (handleCacheMiss("getSize")) {
            try {
                return imapMessage.getSize();
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting size from server after cache miss", e);
            }
        }

        return 0;
    }

    @Override
    public InputStream getInputStream() throws IOException, MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.getInputStream();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        // Return preferred content as input stream
        if (hasHtmlContent) {
            return htmlContent != null ?
                    new ByteArrayInputStream(htmlContent.getBytes(StandardCharsets.UTF_8)) :
                    new ByteArrayInputStream(new byte[0]);
        } else if (hasTextContent) {
            return textContent != null ?
                    new ByteArrayInputStream(textContent.getBytes(StandardCharsets.UTF_8)) :
                    new ByteArrayInputStream(new byte[0]);
        }

        // Handle cache miss if needed
        if (handleCacheMiss("getInputStream")) {
            try {
                // Get input stream from server
                InputStream is = imapMessage.getInputStream();

                // For small messages, we can cache the content synchronously
                if (imapMessage.getSize() < 1024 * 1024) { // 1MB threshold
                    // Read content from stream into a buffer
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }

                    // Convert to string and determine content type
                    String content = baos.toString(StandardCharsets.UTF_8.name());
                    boolean isHtml = content.toLowerCase().contains("<html") ||
                            content.toLowerCase().contains("<!doctype html");

                    // Cache according to content type
                    if (isHtml) {
                        cacheHtmlContent(content);
                    } else {
                        cacheTextContent(content);
                    }

                    // Return a new stream from the cached content
                    return new ByteArrayInputStream(baos.toByteArray());
                }

                // For large messages, just return the stream directly without caching
                return is;
            } catch (IOException | MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting input stream from server after cache miss", e);
            }
        }

        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void writeTo(OutputStream os) throws IOException, MessagingException {
        // If we have an IMAP message, use it
        if (imapMessage != null) {
            if (imapMessage instanceof MimeMessage) {
                ((MimeMessage)imapMessage).writeTo(os);
                return;
            }
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
        File extrasDir = new File(messageDir, DIR_EXTRAS);
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

        File extrasDir = new File(messageDir, DIR_EXTRAS);
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

        File extrasDir = new File(messageDir, DIR_EXTRAS);
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

        File attachmentsDir = new File(messageDir, DIR_ATTACHMENTS);
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

        File attachmentsDir = new File(messageDir, DIR_ATTACHMENTS);
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

    /**
     * Get the text content of an attachment, extracting text from PDFs if needed
     *
     * @param attachmentName The name of the attachment
     * @return The text content of the attachment, or null if not available or not convertible
     * @throws IOException If there is an error reading or processing the attachment
     * @throws MessagingException If there is a messaging error
     */
    public String getTextAttachment(String attachmentName) throws IOException, MessagingException {
        if (messageDir == null || !messageDir.exists()) {
            return null;
        }

        // Sanitize filename to prevent directory traversal
        String sanitizedFilename = attachmentName.replaceAll("[\\\\/:*?\"<>|]", "_");

        File attachmentsDir = new File(messageDir, DIR_ATTACHMENTS);
        File attachmentFile = new File(attachmentsDir, sanitizedFilename);

        // Check if the attachment exists
        if (!attachmentFile.exists() || !attachmentFile.isFile()) {
            return null;
        }

        // Check if there's already a cached text version
        File textVersionFile = new File(attachmentsDir, sanitizedFilename + ".txt");
        if (textVersionFile.exists()) {
            // Return the cached text version
            return new String(java.nio.file.Files.readAllBytes(textVersionFile.toPath()), StandardCharsets.UTF_8);
        }

        // If the attachment is a PDF, try to extract text
        if (attachmentName.toLowerCase().endsWith(".pdf")) {
            try (PDDocument document = PDDocument.load(attachmentFile)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);

                // Cache the extracted text
                try (FileWriter writer = new FileWriter(textVersionFile)) {
                    writer.write(text);
                }

                return text;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error extracting text from PDF: " + attachmentName, e);
                return null;
            }
        }

        // For non-PDF attachments, return null or potentially add other converters here
        return null;
    }

    /**
     * Get recipients by type
     */
    @Override
    public Address[] getRecipients(Message.RecipientType type) throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.getRecipients(type);
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        // Parse from properties
        if (type == Message.RecipientType.TO) {
            String toStr = messageProperties.getProperty(PROP_TO);
            if (toStr != null && !toStr.isEmpty()) {
                try {
                    return InternetAddress.parse(toStr);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error parsing TO addresses", e);
                }
            }
        } else if (type == Message.RecipientType.CC) {
            String ccStr = messageProperties.getProperty(PROP_CC);
            if (ccStr != null && !ccStr.isEmpty()) {
                try {
                    return InternetAddress.parse(ccStr);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error parsing CC addresses", e);
                }
            }
        }

        // Handle cache miss
        if (handleCacheMiss("getRecipients")) {
            try {
                return imapMessage.getRecipients(type);
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting recipients from server after cache miss", e);
            }
        }

        return null;
    }

    /**
     * Get reply-to addresses
     */
    @Override
    public Address[] getReplyTo() throws MessagingException {
        // Check if we should use server data
        if (shouldUseServerForReading()) {
            return imapMessage.getReplyTo();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        String replyToStr = messageProperties.getProperty(PROP_REPLY_TO);
        if (replyToStr != null && !replyToStr.isEmpty()) {
            try {
                return InternetAddress.parse(replyToStr);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error parsing REPLY-TO addresses", e);
            }
        }

        // Handle cache miss
        if (handleCacheMiss("getReplyTo")) {
            try {
                return imapMessage.getReplyTo();
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error getting reply-to from server after cache miss", e);
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return "CachedMessage{" +
                "imapMessage=" + imapMessage +
                ", messageDir=" + messageDir +
                ", contentLoaded=" + contentLoaded +
                ", folder=" + folder +
                ", messageProperties=" + messageProperties +
                ", textContent='" + textContent + '\'' +
                ", htmlContent='" + htmlContent + '\'' +
                ", hasHtmlContent=" + hasHtmlContent +
                ", hasTextContent=" + hasTextContent +
                ", flags=" + flags +
                ", sentDate=" + sentDate +
                ", receivedDate=" + receivedDate +
                ", subject='" + subject + '\'' +
                ", from='" + from + '\'' +
                ", to=" + Arrays.toString(to) +
                ", cc=" + Arrays.toString(cc) +
                ", replyTo='" + replyTo + '\'' +
                ", listeners=" + listeners +
                '}';
    }
}