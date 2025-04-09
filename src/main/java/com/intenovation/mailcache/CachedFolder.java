package com.intenovation.mailcache;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A JavaMail Folder implementation that supports the three cache modes
 */
public class CachedFolder extends Folder {
    private static final Logger LOGGER = Logger.getLogger(CachedFolder.class.getName());

    private CachedStore cachedStore;
    private Folder imapFolder;
    private File cacheDir;
    private String folderName;
    private boolean isOpen = false;
    private int mode = -1;

    /**
     * Create a new CachedFolder
     */
    public CachedFolder(CachedStore store, String name, boolean createDirectory) {
        super(store);
        this.cachedStore = store;
        this.folderName = name;

        // Create cache directory for this folder
        if (createDirectory && store.getCacheDirectory() != null) {
            this.cacheDir = new File(store.getCacheDirectory(),
                    name.replace('/', File.separatorChar));
            if (!this.cacheDir.exists()) {
                this.cacheDir.mkdirs();
            }

            // Create messages directory
            File messagesDir = new File(cacheDir, "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }
        }

        // For ONLINE and ACCELERATED modes, get the corresponding IMAP folder
        if (store.getMode() != CacheMode.OFFLINE && store.getImapStore() != null) {
            try {
                this.imapFolder = store.getImapStore().getFolder(name);
            } catch (MessagingException e) {
                // Log exception but continue
                LOGGER.log(Level.WARNING, "Could not get IMAP folder: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Get the cache directory for this folder
     */
    public File getCacheDir() {
        return cacheDir;
    }

    @Override
    public String getName() {
        return folderName;
    }

    @Override
    public String getFullName() {
        return folderName;
    }

    @Override
    public Folder getParent() throws MessagingException {
        int lastSlash = folderName.lastIndexOf('/');
        if (lastSlash == -1) {
            return cachedStore.getDefaultFolder();
        } else {
            String parentName = folderName.substring(0, lastSlash);
            return new CachedFolder(cachedStore, parentName, true);
        }
    }

    @Override
    public boolean exists() throws MessagingException {
        // Check cache directory first
        if (cacheDir != null && cacheDir.exists()) {
            return true;
        }

        // For ONLINE mode, also check IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null) {
            return imapFolder.exists();
        }

        return false;
    }

    @Override
    public Folder[] list(String pattern) throws MessagingException {
        List<Folder> folders = new ArrayList<>();

        // For OFFLINE mode or when caching, list from cache
        if (cachedStore.getMode() == CacheMode.OFFLINE || cacheDir != null) {
            File[] subdirs = cacheDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    // Skip special directories like "messages"
                    if (!subdir.getName().equals("messages")) {
                        String childName = folderName.isEmpty() ?
                                subdir.getName() :
                                folderName + "/" + subdir.getName();
                        folders.add(new CachedFolder(cachedStore, childName, false));
                    }
                }
            }
        }

        // For ONLINE mode, also list from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null) {
            Folder[] imapFolders = imapFolder.list(pattern);
            for (Folder folder : imapFolders) {
                // Check if already added from cache
                boolean found = false;
                for (Folder cacheFolder : folders) {
                    if (cacheFolder.getFullName().equals(folder.getFullName())) {
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    folders.add(new CachedFolder(cachedStore, folder.getFullName(), true));
                }
            }
        }

        return folders.toArray(new Folder[0]);
    }

    @Override
    public Folder getFolder(String name) throws MessagingException {
        // Build the full name
        String fullName = folderName.isEmpty() ? name : folderName + "/" + name;
        return new CachedFolder(cachedStore, fullName, true);
    }

    @Override
    public char getSeparator() throws MessagingException {
        return '/';
    }

    @Override
    public int getType() throws MessagingException {
        return HOLDS_MESSAGES | HOLDS_FOLDERS;
    }

    @Override
    public boolean create(int type) throws MessagingException {
        // In OFFLINE mode, only create locally
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            if (cacheDir != null && !cacheDir.exists()) {
                boolean result = cacheDir.mkdirs();

                // Create messages directory
                File messagesDir = new File(cacheDir, "messages");
                if (!messagesDir.exists()) {
                    messagesDir.mkdirs();
                }

                return result;
            }
            return false;
        }

        // In other modes, try to create on server and locally
        boolean success = true;

        // Create on server if possible
        if (imapFolder != null) {
            try {
                success = imapFolder.create(type);
            } catch (MessagingException e) {
                success = false;
            }
        }

        // Always create locally
        if (cacheDir != null && !cacheDir.exists()) {
            boolean dirCreated = cacheDir.mkdirs();

            // Create messages directory
            File messagesDir = new File(cacheDir, "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }

            return dirCreated && success;
        }

        return success;
    }

    @Override
    public boolean delete(boolean recurse) throws MessagingException {
        // In OFFLINE mode, cannot delete
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot delete folders in OFFLINE mode");
        }

        boolean success = true;

        // Delete on server if possible
        if (imapFolder != null) {
            try {
                success = imapFolder.delete(recurse);
            } catch (MessagingException e) {
                success = false;
            }
        }

        // In ACCELERATED mode, also delete locally
        if (cachedStore.getMode() == CacheMode.ACCELERATED && cacheDir != null) {
            if (recurse) {
                return deleteRecursive(cacheDir) && success;
            } else {
                return cacheDir.delete() && success;
            }
        }

        return success;
    }

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

    @Override
    public boolean renameTo(Folder folder) throws MessagingException {
        // In OFFLINE mode, cannot rename
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot rename folders in OFFLINE mode");
        }

        boolean success = true;

        // Rename on server if possible
        if (imapFolder != null && folder instanceof CachedFolder) {
            try {
                CachedFolder cachedFolder = (CachedFolder) folder;
                if (cachedFolder.imapFolder != null) {
                    success = imapFolder.renameTo(cachedFolder.imapFolder);
                }
            } catch (MessagingException e) {
                success = false;
            }
        }

        // In ACCELERATED mode, also rename locally
        if (cachedStore.getMode() == CacheMode.ACCELERATED &&
                cacheDir != null && folder instanceof CachedFolder) {
            CachedFolder cachedFolder = (CachedFolder) folder;
            if (cachedFolder.cacheDir != null) {
                return cacheDir.renameTo(cachedFolder.cacheDir) && success;
            }
        }

        return success;
    }

    @Override
    public void open(int mode) throws MessagingException {
        if (isOpen) {
            throw new IllegalStateException("Folder is already open");
        }

        this.mode = mode;

        // For ONLINE mode, open IMAP folder
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null) {
            imapFolder.open(mode);
        }

        isOpen = true;
    }

    @Override
    public void close(boolean expunge) throws MessagingException {
        if (!isOpen) {
            throw new IllegalStateException("Folder is not open");
        }

        // For ONLINE mode, close IMAP folder
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null) {
            imapFolder.close(expunge);
        }

        isOpen = false;
        this.mode = -1;
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public Flags getPermanentFlags() {
        try {
            // For ONLINE mode, get from IMAP
            if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
                return imapFolder.getPermanentFlags();
            }
        } catch (Exception e) {
            // Fallback to empty flags
        }

        return new Flags();
    }

    @Override
    public Message[] getMessages(int start, int end) throws MessagingException {
        checkOpen();

        // For ONLINE mode with search, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            // Get from IMAP
            Message[] imapMessages = imapFolder.getMessages(start, end);

            // Create cached versions
            CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
            for (int i = 0; i < imapMessages.length; i++) {
                cachedMessages[i] = new CachedMessage(this, imapMessages[i]);
            }

            return cachedMessages;
        }

        // For other modes, get from local cache
        List<Message> messages = new ArrayList<>();

        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);

                if (messageDirs != null) {
                    // Sort directories to ensure consistent order
                    // (implementation would sort by message number or date)

                    int count = 0;
                    for (File messageDir : messageDirs) {
                        count++;

                        if (count >= start && count <= end) {
                            try {
                                messages.add(new CachedMessage(this, messageDir));
                            } catch (MessagingException e) {
                                LOGGER.log(Level.WARNING, "Error loading message: " + e.getMessage(), e);
                            }
                        }

                        if (count > end) {
                            break;
                        }
                    }
                }
            }
        }

        return messages.toArray(new Message[0]);
    }

    @Override
    public Message[] getMessages() throws MessagingException {
        checkOpen();

        // For ONLINE mode, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            // Get from IMAP
            Message[] imapMessages = imapFolder.getMessages();

            // Create cached versions
            CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
            for (int i = 0; i < imapMessages.length; i++) {
                cachedMessages[i] = new CachedMessage(this, imapMessages[i]);
            }

            return cachedMessages;
        }

        // For other modes, get from local cache
        List<Message> messages = new ArrayList<>();

        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);

                if (messageDirs != null) {
                    for (File messageDir : messageDirs) {
                        try {
                            messages.add(new CachedMessage(this, messageDir));
                        } catch (MessagingException e) {
                            LOGGER.log(Level.WARNING, "Error loading message: " + e.getMessage(), e);
                        }
                    }
                }
            }
        }

        return messages.toArray(new Message[0]);
    }

    @Override
    public Message getMessage(int msgnum) throws MessagingException {
        checkOpen();

        // For ONLINE mode, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            // Get from IMAP
            Message imapMessage = imapFolder.getMessage(msgnum);

            // Create cached version
            return new CachedMessage(this, imapMessage);
        }

        // For other modes, get from local cache
        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);

                if (messageDirs != null) {
                    // Sort directories to ensure consistent order
                    // (implementation would sort by message number or date)

                    if (msgnum > 0 && msgnum <= messageDirs.length) {
                        // Note: This is a simplified implementation that assumes
                        // message numbers align with directory order, which isn't always true
                        // A real implementation would maintain a mapping of message numbers
                        return new CachedMessage(this, messageDirs[msgnum - 1]);
                    }
                }
            }
        }

        throw new MessagingException("Message number " + msgnum + " not found");
    }

    @Override
    public int getMessageCount() throws MessagingException {
        checkOpen();

        // For ONLINE mode, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            return imapFolder.getMessageCount();
        }

        // For other modes, count from cache
        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);
                if (messageDirs != null) {
                    return messageDirs.length;
                }
            }
        }

        return 0;
    }

    @Override
    public void appendMessages(Message[] msgs) throws MessagingException {
        // In OFFLINE mode, cannot append
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot append messages in OFFLINE mode");
        }

        checkOpen();

        // For other modes, append to both IMAP and cache
        if (imapFolder != null && imapFolder.isOpen()) {
            imapFolder.appendMessages(msgs);
        }

        // Save to local cache
        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }

            for (Message msg : msgs) {
                // Generate message ID if needed
                String messageId;
                try {
                    String[] headers = msg.getHeader("Message-ID");
                    messageId = (headers != null && headers.length > 0) ?
                            headers[0] : "msg_" + System.currentTimeMillis() + "_" +
                            Math.abs(msg.getSubject().hashCode());
                } catch (MessagingException e) {
                    messageId = "msg_" + System.currentTimeMillis();
                }

                // Create message directory
                File messageDir = new File(messagesDir, sanitizeFileName(messageId));
                if (!messageDir.exists()) {
                    messageDir.mkdirs();

                    // Save message to cache
                    if (msg instanceof MimeMessage) {
                        try (FileOutputStream fos = new FileOutputStream(
                                new File(messageDir, "message.mbox"))) {
                            ((MimeMessage) msg).writeTo(fos);
                        } catch (Exception e) {
                            throw new MessagingException("Error saving message", e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public Message[] expunge() throws MessagingException {
        // In OFFLINE mode, cannot expunge
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot expunge messages in OFFLINE mode");
        }

        checkOpen();

        // For other modes, expunge from both IMAP and cache
        Message[] expunged = null;
        if (imapFolder != null && imapFolder.isOpen()) {
            expunged = imapFolder.expunge();
        }

        // Expunge from local cache
        List<Message> expungedFromCache = new ArrayList<>();
        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);
                if (messageDirs != null) {
                    for (File messageDir : messageDirs) {
                        // Check if this message is marked for deletion
                        // (implementation would read flags from cached message)
                        // If deleted, remove from cache

                        // This is a placeholder for the actual implementation
                    }
                }
            }
        }

        return expunged != null ? expunged : new Message[0];
    }

    @Override
    public boolean hasNewMessages() throws MessagingException {
        // For ONLINE mode, check the IMAP folder
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null) {
            try {
                return imapFolder.hasNewMessages();
            } catch (MessagingException e) {
                // Fall back to local check if server check fails
                LOGGER.log(Level.WARNING, "Error checking for new messages on server", e);
            }
        }

        // For other modes or as fallback, just return false
        // since we can't reliably determine new messages without server access
        return false;
    }

    /**
     * Check if the folder is open
     */
    private void checkOpen() throws MessagingException {
        if (!isOpen) {
            throw new IllegalStateException("Folder is not open");
        }
    }

    /**
     * Sanitize a filename for safe filesystem storage
     */
    private String sanitizeFileName(String input) {
        return input.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}