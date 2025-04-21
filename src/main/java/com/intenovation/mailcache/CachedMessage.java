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
import org.jsoup.Jsoup;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * A JavaMail Message implementation that supports caching
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
        String dirName = MassageDirName.formatMessageDirName(imapMessage);

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
     * Get the underlying IMAP message if available, or attempt to find it on the server
     *
     * @return The IMAP message or null if not available or not found
     */
    public Message getImapMessage() {
        // If we already have the IMAP message, return it
        if (imapMessage != null) {
            return imapMessage;
        }

        // If we're in OFFLINE mode, don't try to find the message on the server
        CachedStore store = (CachedStore)folder.getStore();
        if (store.getMode() == CacheMode.OFFLINE) {
            return null;
        }

        // Try to find the message on the server
        try {
            // Get the folder from the server
            Folder imapFolder = ((CachedFolder)folder).imapFolder;
            if (imapFolder == null || !imapFolder.isOpen()) {
                return null;
            }

            // Get the Message-ID from properties
            String messageId = messageProperties.getProperty(PROP_MESSAGE_ID);
            if (messageId == null || messageId.isEmpty()) {
                return null;
            }

            // Search for the message on the server using Message-ID
            Message[] messages = imapFolder.search(new javax.mail.search.HeaderTerm("Message-ID", messageId));
            if (messages != null && messages.length > 0) {
                // Found the message, store the reference for future use
                imapMessage = messages[0];
                return imapMessage;
            }

            // If we couldn't find it by Message-ID, try other criteria
            // This is a fallback approach using sent date and subject
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
                            return imapMessage;
                        }
                    }
                } catch (java.text.ParseException e) {
                    LOGGER.log(Level.WARNING, "Error parsing sent date: " + sentDateStr, e);
                }
            }
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
                }

                // Save received date
                Date msgReceivedDate = imapMessage.getReceivedDate();
                if (msgReceivedDate != null) {
                    messageProperties.setProperty(PROP_RECEIVED_DATE, sdf.format(msgReceivedDate));
                }

                messageProperties.setProperty(PROP_SUBJECT,
                        imapMessage.getSubject() != null ?
                                imapMessage.getSubject() : "");

                Address[] from = imapMessage.getFrom();
                if (from != null && from.length > 0) {
                    messageProperties.setProperty(PROP_FROM, from[0].toString());
                }

                // Save reply-to addresses
                Address[] replyTo = imapMessage.getReplyTo();
                if (replyTo != null && replyTo.length > 0) {
                    StringBuilder replyToStr = new StringBuilder();
                    for (int i = 0; i < replyTo.length; i++) {
                        if (i > 0) replyToStr.append(", ");
                        replyToStr.append(replyTo[i].toString());
                    }
                    messageProperties.setProperty(PROP_REPLY_TO, replyToStr.toString());
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

                            // Only save attachments if configured to do so
                            CachedStore store = (CachedStore) folder.getStore();
                            if (store.getConfig().isCacheAttachments()) {
                                saveAttachment(part);
                            }
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
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error saving message flags", e);
            }

            contentLoaded = true;
        } catch (Exception e) {
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
        if (isOnline(store)) {
            return imapMessage.getSubject();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (messageProperties != null) {
            return messageProperties.getProperty(PROP_SUBJECT);
        }

        return null;
    }

    @Override
    public Address[] getFrom() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
            return imapMessage.getFrom();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (messageProperties != null) {
            String from = messageProperties.getProperty(PROP_FROM);
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

    private boolean isOnline(CachedStore store) {
        return imapMessage != null && (store.getMode() == CacheMode.ONLINE || store.getMode() == CacheMode.DESTRUCTIVE);
    }

    @Override
    public Date getSentDate() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
            return imapMessage.getSentDate();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        if (messageProperties != null) {
            String dateStr = messageProperties.getProperty(PROP_SENT_DATE);
            if (dateStr != null && !dateStr.isEmpty()) {
                try {
                    // Try to parse using standard format first
                    try {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        return sdf.parse(dateStr);
                    } catch (java.text.ParseException pe) {
                        // Fall back to Date constructor if specific format fails
                        return new Date(dateStr);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error parsing sent date: " + dateStr, e);
                }
            }
        }

        return null;
    }

    @Override
    public Object getContent() throws MessagingException, IOException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
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

        return "";
    }

    /**
     * Get the HTML content if available
     *
     * @return HTML content or null if not available
     * @throws MessagingException If there is an error accessing the content
     */
    public String getHtmlContent() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, try to get HTML content
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
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

        return hasHtmlContent ? htmlContent : null;
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
        // If we have an IMAP message and in ONLINE mode, try to get text content
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
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
        // If we have an IMAP message and in ONLINE mode, check it
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
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

        return hasHtmlContent;
    }

    /**
     * Check if the message has text content
     *
     * @return true if text content is available
     * @throws MessagingException If there is an error checking content
     */
    public boolean hasTextContent() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, check it
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
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

        return hasTextContent;
    }

    /**
     * Check if the content is HTML based on the preferred content type
     *
     * @return true if the preferred content is HTML, false otherwise
     * @throws MessagingException If there is an error checking content type
     */
    public boolean isHtmlContent() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, check it
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
            try {
                return hasHtmlContent();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error checking HTML content from IMAP", e);
            }
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        // HTML is preferred if both are available
        return hasHtmlContent;
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
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error updating flags in cache", e);
            }
        }
    }

    @Override
    public Flags getFlags() throws MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
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
        if (isOnline(store)) {
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
        if (isOnline(store)) {
            return imapMessage.getSize();
        }

        // Otherwise use cached value
        if (!contentLoaded) {
            loadFromCache();
        }

        // Return size of preferred content
        if (hasHtmlContent) {
            return htmlContent != null ? htmlContent.length() : 0;
        } else if (hasTextContent) {
            return textContent != null ? textContent.length() : 0;
        }

        return 0;
    }

    @Override
    public InputStream getInputStream() throws IOException, MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
        if (isOnline(store)) {
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

        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void writeTo(OutputStream os) throws IOException, MessagingException {
        // If we have an IMAP message and in ONLINE mode, use it
        CachedStore store = (CachedStore)folder.getStore();
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

}