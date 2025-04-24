package com.intenovation.mailcache;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Debug utility class for troubleshooting message append issues in the MailCache API.
 * This class provides methods to diagnose message ID problems and server replication issues
 * when appending messages to folders.
 */
public class CachedFolderAppendDebug {
    private static final Logger LOGGER = Logger.getLogger(CachedFolderAppendDebug.class.getName());
    
    /**
     * Debug an append operation with enhanced logging and verification
     * 
     * @param folder The folder to append to
     * @param messages The messages to append
     * @throws MessagingException If there is an error during the append operation
     */
    public static void debugAppendMessages(CachedFolder folder, Message[] messages) throws MessagingException {
        if (folder == null) {
            LOGGER.severe("Cannot append: folder is null");
            throw new MessagingException("Cannot append: folder is null");
        }
        
        if (messages == null || messages.length == 0) {
            LOGGER.warning("No messages to append");
            return;
        }
        
        LOGGER.info("====== BEGIN APPEND DEBUG ======");
        LOGGER.info("Folder: " + folder.getFullName());
        LOGGER.info("Number of messages to append: " + messages.length);
        
        // Get the store and check mode
        CachedStore store = (CachedStore) folder.getStore();
        LOGGER.info("Current cache mode: " + store.getMode());
        
        // In OFFLINE mode, appends will fail
        if (store.getMode() == CacheMode.OFFLINE) {
            LOGGER.severe("Cannot append messages in OFFLINE mode (will throw exception)");
        }
        
        // Check IMAP store availability
        Folder imapFolder = folder.imapFolder;
        if (imapFolder == null) {
            LOGGER.warning("IMAP folder is null - server operations will fail");
        } else {
            LOGGER.info("IMAP folder is available: " + imapFolder.getFullName());
            LOGGER.info("IMAP folder is open: " + imapFolder.isOpen());
        }
        
        // Verify message headers
        for (int i = 0; i < messages.length; i++) {
            Message msg = messages[i];
            LOGGER.info("Message #" + (i+1) + ":");
            
            // Check for Message-ID
            String[] headers = msg.getHeader("Message-ID");
            if (headers == null || headers.length == 0) {
                LOGGER.warning("Message #" + (i+1) + " has NO Message-ID - this may cause issues");
                
                // Add a Message-ID if missing
                LOGGER.info("Adding a generated Message-ID to message #" + (i+1));
                String generatedId = "<" + System.currentTimeMillis() + "." + i + "@mailcache.generated>";
                msg.setHeader("Message-ID", generatedId);
                LOGGER.info("Added Message-ID: " + generatedId);
            } else {
                LOGGER.info("Message-ID: " + headers[0]);
            }
            
            // Log key headers for debugging
            logHeaders(msg, "Subject", "From", "To", "Date", "Content-Type");
        }
        
        // Now perform the actual append operation
        try {
            // Track time for performance analysis
            long startTime = System.currentTimeMillis();
            
            // Call the original append method - actual implementation would replace this
            // with the real append operation rather than calling itself recursively
            LOGGER.info("Executing appendMessages operation...");
            //folder.appendMessages(messages);
            performDebugAppend(folder, messages);
            
            long endTime = System.currentTimeMillis();
            LOGGER.info("Append operation completed in " + (endTime - startTime) + "ms");
            
            // Verify append worked
            LOGGER.info("Verifying messages were appended...");
            verifyAppendedMessages(folder, messages);
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during append operation", e);
            throw new MessagingException("Append operation failed", e);
        } finally {
            LOGGER.info("====== END APPEND DEBUG ======");
        }
    }
    
    /**
     * Log specified headers from a message for debugging
     * 
     * @param msg The message to extract headers from
     * @param headerNames The names of headers to log
     */
    private static void logHeaders(Message msg, String... headerNames) {
        try {
            for (String headerName : headerNames) {
                String[] values = msg.getHeader(headerName);
                if (values != null && values.length > 0) {
                    LOGGER.info("  " + headerName + ": " + values[0]);
                } else {
                    LOGGER.info("  " + headerName + ": [not present]");
                }
            }
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error accessing message headers", e);
        }
    }
    
    /**
     * Perform the debug-enhanced append operation
     * This demonstrates the proper append sequence: 
     * 1. First append to server
     * 2. Then update local cache
     * 
     * @param folder The folder to append to
     * @param messages The messages to append
     * @throws MessagingException If there's an error during append
     */
    private static void performDebugAppend(CachedFolder folder, Message[] messages) throws MessagingException {
        try {
            CachedStore store = (CachedStore) folder.getStore();
            
            // Only attempt server operations in non-OFFLINE modes
            if (store.getMode() != CacheMode.OFFLINE) {
                Folder imapFolder = folder.imapFolder;
                
                if (imapFolder != null && imapFolder.isOpen()) {
                    LOGGER.info("Appending to server...");
                    
                    // Extract or convert IMAP messages for server operation
                    Message[] imapMessages = new Message[messages.length];
                    for (int i = 0; i < messages.length; i++) {
                        if (messages[i] instanceof CachedMessage) {
                            CachedMessage cachedMsg = (CachedMessage) messages[i];
                            // Get the underlying IMAP message if available
                            Message imapMsg = cachedMsg.getImapMessage();
                            if (imapMsg != null) {
                                imapMessages[i] = imapMsg;
                                LOGGER.info("Using existing IMAP message for message #" + (i+1));
                            } else {
                                // Create a new MimeMessage from the cached content
                                LOGGER.info("Creating new MimeMessage from cached content for message #" + (i+1));
                                imapMessages[i] = new MimeMessage(store.getSession(),
                                        cachedMsg.getInputStream());
                                
                                // Ensure Message-ID is preserved
                                String[] msgIds = cachedMsg.getHeader("Message-ID");
                                if (msgIds != null && msgIds.length > 0) {
                                    imapMessages[i].setHeader("Message-ID", msgIds[0]);
                                    LOGGER.info("Preserved Message-ID: " + msgIds[0]);
                                }
                            }
                        } else {
                            imapMessages[i] = messages[i];
                            LOGGER.info("Using original message for message #" + (i+1));
                        }
                    }

                    // Now append the IMAP messages to the IMAP folder
                    LOGGER.info("Calling IMAP appendMessages...");
                    imapFolder.appendMessages(imapMessages);
                    LOGGER.info("Server append completed successfully");
                } else {
                    LOGGER.warning("Skipping server append: IMAP folder is null or not open");
                }
            } else {
                LOGGER.info("Skipping server append in OFFLINE mode");
            }

            // Now handle local cache operations
            File cacheDir = folder.getCacheDir();
            if (cacheDir != null) {
                LOGGER.info("Appending to local cache...");
                File messagesDir = new File(cacheDir, "messages");
                if (!messagesDir.exists()) {
                    LOGGER.info("Creating messages directory: " + messagesDir.getPath());
                    messagesDir.mkdirs();
                }

                for (int i = 0; i < messages.length; i++) {
                    Message msg = messages[i];
                    // If it's already a CachedMessage for this folder, skip
                    if (msg instanceof CachedMessage) {
                        CachedMessage cachedMsg = (CachedMessage) msg;
                        if (cachedMsg.getFolder() == folder) {
                            LOGGER.info("Skipping message #" + (i+1) + " - already cached in this folder");
                            continue; // Already cached in this folder
                        }
                    }

                    // Create a new CachedMessage - this will handle proper caching
                    LOGGER.info("Creating new CachedMessage for message #" + (i+1));
                    try {
                        new CachedMessage(folder, msg);
                        LOGGER.info("Local caching completed for message #" + (i+1));
                    } catch (MessagingException e) {
                        LOGGER.log(Level.WARNING, "Error caching message #" + (i+1) + ": " + e.getMessage(), e);
                        // Continue with other messages
                    }
                }
                LOGGER.info("Local cache append completed");
            } else {
                LOGGER.warning("Cannot append to local cache: cache directory is null");
            }
            
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "I/O error during append operation", e);
            throw new MessagingException("I/O error during append", e);
        }
    }
    
    /**
     * Verify that the messages were properly appended
     * 
     * @param folder The folder to check
     * @param messages The messages that should have been appended
     */
    private static void verifyAppendedMessages(CachedFolder folder, Message[] messages) {
        try {
            // Get store and mode
            CachedStore store = (CachedStore) folder.getStore();
            
            // Verify in local cache
            LOGGER.info("Verifying messages in local cache...");
            File cacheDir = folder.getCacheDir();
            if (cacheDir != null) {
                File messagesDir = new File(cacheDir, "messages");
                if (messagesDir.exists()) {
                    File[] messageDirs = messagesDir.listFiles(File::isDirectory);
                    if (messageDirs != null) {
                        LOGGER.info("Found " + messageDirs.length + " message directories in cache");
                    } else {
                        LOGGER.warning("Could not list message directories in cache");
                    }
                } else {
                    LOGGER.warning("Messages directory does not exist: " + messagesDir.getPath());
                }
            } else {
                LOGGER.warning("Cache directory is null");
            }
            
            // Verify on server for non-OFFLINE modes
            if (store.getMode() != CacheMode.OFFLINE) {
                LOGGER.info("Verifying messages on server...");
                Folder imapFolder = folder.imapFolder;
                if (imapFolder != null && imapFolder.isOpen()) {
                    Message[] imapMessages = imapFolder.getMessages();
                    LOGGER.info("Found " + imapMessages.length + " messages on server");
                    
                    // Try to match against the appended messages
                    for (Message msg : messages) {
                        boolean found = false;
                        String[] msgIds = msg.getHeader("Message-ID");
                        if (msgIds != null && msgIds.length > 0) {
                            String msgId = msgIds[0];
                            
                            for (Message imapMsg : imapMessages) {
                                String[] imapMsgIds = imapMsg.getHeader("Message-ID");
                                if (imapMsgIds != null && imapMsgIds.length > 0 && 
                                    imapMsgIds[0].equals(msgId)) {
                                    found = true;
                                    LOGGER.info("Found message with ID " + msgId + " on server");
                                    break;
                                }
                            }
                            
                            if (!found) {
                                LOGGER.warning("Message with ID " + msgId + " not found on server!");
                            }
                        } else {
                            LOGGER.warning("Cannot verify a message without Message-ID on server");
                        }
                    }
                } else {
                    LOGGER.warning("Cannot verify on server: IMAP folder is null or not open");
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during verification", e);
        }
    }
    
    /**
     * A specialized version of appendMessages that fixes Message-ID issues.
     * This method can be used as a replacement for CachedFolder.appendMessages
     * 
     * @param folder The folder to append to
     * @param messages The messages to append
     * @throws MessagingException If there is an error during the append operation
     */
    public static void appendMessagesWithIdFix(CachedFolder folder, Message[] messages) throws MessagingException {
        // In OFFLINE mode, cannot append
        CachedStore store = (CachedStore) folder.getStore();
        if (store.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot append messages in OFFLINE mode");
        }

        if (!folder.isOpen()) {
            throw new IllegalStateException("Folder is not open");
        }

        // Process each message to ensure it has a Message-ID
        for (int i = 0; i < messages.length; i++) {
            Message msg = messages[i];
            String[] headers = msg.getHeader("Message-ID");
            if (headers == null || headers.length == 0) {
                // Generate a Message-ID if missing
                String generatedId = "<" + System.currentTimeMillis() + "." + i + "@mailcache.generated>";
                msg.setHeader("Message-ID", generatedId);
                LOGGER.info("Added Message-ID: " + generatedId + " to message #" + (i+1));
            }
        }

        // For other modes, append to server first
        if (folder.imapFolder != null && folder.imapFolder.isOpen()) {
            try {
                // Extract or convert IMAP messages for server operation
                Message[] imapMessages = new Message[messages.length];
                for (int i = 0; i < messages.length; i++) {
                    if (messages[i] instanceof CachedMessage) {
                        CachedMessage cachedMsg = (CachedMessage) messages[i];
                        // Get the underlying IMAP message if available
                        Message imapMsg = cachedMsg.getImapMessage();
                        if (imapMsg != null) {
                            imapMessages[i] = imapMsg;
                        } else {
                            // Create a new MimeMessage from the cached content
                            MimeMessage newMsg = new MimeMessage(store.getSession(),
                                    cachedMsg.getInputStream());
                            
                            // Ensure Message-ID is preserved
                            String[] msgIds = cachedMsg.getHeader("Message-ID");
                            if (msgIds != null && msgIds.length > 0) {
                                newMsg.setHeader("Message-ID", msgIds[0]);
                            }
                            
                            imapMessages[i] = newMsg;
                        }
                    } else {
                        imapMessages[i] = messages[i];
                    }
                }

                // Now append the IMAP messages to the IMAP folder
                try {
                    folder.imapFolder.appendMessages(imapMessages);
                } catch (MessagingException e) {
                    LOGGER.log(Level.SEVERE, "Error appending messages to server", e);
                    throw e; // Don't continue if server operation failed
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error preparing messages for server append", e);
                throw new MessagingException("Error preparing messages for server append", e);
            }
        }

        // Then handle local cache operations
        if (folder.getCacheDir() != null) {
            File messagesDir = new File(folder.getCacheDir(), "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }

            for (Message msg : messages) {
                // If it's already a CachedMessage for this folder, skip
                if (msg instanceof CachedMessage) {
                    CachedMessage cachedMsg = (CachedMessage) msg;
                    if (cachedMsg.getFolder() == folder) {
                        continue; // Already cached in this folder
                    }
                }

                // Create a new CachedMessage - this will handle proper caching
                try {
                    new CachedMessage(folder, msg);
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error caching message: " + e.getMessage(), e);
                    // Continue with other messages
                }
            }
        }
        
        // Notify listeners of the change
        folder.fireChangeEvent(new MailCacheChangeEvent(folder, 
                MailCacheChangeEvent.ChangeType.FOLDER_UPDATED, folder));
    }
}