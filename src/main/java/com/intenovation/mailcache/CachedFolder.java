package com.intenovation.mailcache;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import javax.mail.search.SearchTerm;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A JavaMail Folder implementation that supports the cache modes
 * and change notification pattern.
 */
public class CachedFolder extends Folder {
    private static final Logger LOGGER = Logger.getLogger(CachedFolder.class.getName());

    private CachedStore cachedStore;





    private Folder imapFolder;  // IMAP folder corresponding to this cached folder
    private File cacheDir;     // Local directory for caching
    private String folderName; // Name of this folder
    private boolean isOpen = false;
    private int mode = -1;

    // Listener support
    private final List<MailCacheChangeListener> listeners = new CopyOnWriteArrayList<>();

    public CachedStore getStore() {
        return cachedStore;
    }

    /**
     * Create a new CachedFolder
     *
     * @param store The CachedStore this folder belongs to
     * @param name The name of the folder
     * @param createDirectory Whether to create the cache directory
     */
    public CachedFolder(CachedStore store, String name, boolean createDirectory) {
        super(store);
        this.cachedStore = store;
        this.folderName = name;

        // Setup the cache directory
        if (store.getCacheDirectory() != null) {
            this.cacheDir = new File(store.getCacheDirectory(),
                    name.replace('/', File.separatorChar));
        }

        // Create cache directory for this folder if requested
        if (createDirectory && store.getCacheDirectory() != null) {
            if (!this.cacheDir.exists()) {
                this.cacheDir.mkdirs();
            }

            // Create messages directory
            File messagesDir = new File(cacheDir, "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }

            // Create archived messages directory
            File archivedDir = new File(cacheDir, "archived_messages");
            if (!archivedDir.exists()) {
                archivedDir.mkdirs();
            }
        }

        getImapFolder();
    }

    public Folder getImapFolder() {
        if (imapFolder==null && cachedStore.getMode() != CacheMode.OFFLINE && cachedStore.getImapStore() != null) {
                try {
                    this.imapFolder = cachedStore.getImapStore().getFolder(folderName);
                    LOGGER.fine("Initialized IMAP folder: " + folderName);
                } catch (MessagingException e) {
                    // Log exception but continue - we'll initialize lazily when needed
                    LOGGER.log(Level.WARNING, "Could not get IMAP folder: " + e.getMessage(), e);
                }

        }
        return imapFolder;

    }
    /**
     * Add a change listener to this folder
     *
     * @param listener The listener to add
     */
    public void addChangeListener(MailCacheChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a change listener from this folder
     *
     * @param listener The listener to remove
     */
    public void removeChangeListener(MailCacheChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Fire a change event to all listeners
     *
     * @param event The event to fire
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
     * Get the cache directory for this folder
     *
     * @return The cache directory
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
    public CachedFolder getParent() throws MessagingException {
        int lastSlash = folderName.lastIndexOf('/');
        if (lastSlash == -1) {
            return cachedStore.getDefaultFolder();
        } else {
            String parentName = folderName.substring(0, lastSlash);
            return new CachedFolder(cachedStore, parentName, true);
        }
    }

    /**
     * Ensure the IMAP folder is initialized and opened when needed.
     * This is a key method for fixing server connectivity issues.
     *
     * @param folderMode The mode to open the folder in (READ_ONLY or READ_WRITE)
     * @return true if the IMAP folder is ready, false otherwise
     * @throws MessagingException If there is an error initializing or opening the folder
     */
    private boolean ensureImapFolderReady(int folderMode) throws MessagingException {
        // Only proceed if we're not in OFFLINE mode
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            LOGGER.fine("Not initializing IMAP folder in OFFLINE mode");
            return false;
        }

        // Check if IMAP store is available
        Store imapStore = cachedStore.getImapStore();
        if (imapStore == null) {
            LOGGER.warning("IMAP store is null - cannot perform server operation");
            return false;
        }

        if (!imapStore.isConnected()) {
            LOGGER.warning("IMAP store is not connected - cannot perform server operation");
            return false;
        }

        // Initialize IMAP folder if null
        if (imapFolder == null) {
            LOGGER.info("Lazily initializing IMAP folder: " + getFullName());
            imapFolder = imapStore.getFolder(getFullName());

            // For Gmail-style implementations we might need to ensure the label exists
            if (!imapFolder.exists() && folderMode == Folder.READ_WRITE) {
                LOGGER.info("Creating folder/label on server: " + getFullName());
                boolean created = imapFolder.create(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS);
                if (!created) {
                    LOGGER.warning("Failed to create folder/label on server: " + getFullName());
                }
            }
        }
        if ((imapFolder.getType() & HOLDS_MESSAGES) == 0)
            return true;
        // Open the folder if needed
        if (!imapFolder.isOpen()) {
            LOGGER.info("Opening IMAP folder: " + getFullName() + " in mode: " +
                    (folderMode == Folder.READ_ONLY ? "READ_ONLY" : "READ_WRITE"));
            try {
                imapFolder.open(folderMode);
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error opening IMAP folder: " + getFullName(), e);
                return false;
            }
        } else if (imapFolder.getMode() != folderMode && folderMode == Folder.READ_WRITE) {
            // If we need READ_WRITE but folder is only open READ_ONLY, reopen it
            LOGGER.info("Reopening IMAP folder in READ_WRITE mode: " + getFullName());
            imapFolder.close(false);
            imapFolder.open(folderMode);
        }

        return imapFolder.isOpen();
    }

    @Override
    public boolean exists() throws MessagingException {
        // Check cache directory first
        if (cacheDir != null && cacheDir.exists()) {
            return true;
        }

        // For ONLINE and other non-OFFLINE modes, also check IMAP
        if (cachedStore.getMode() != CacheMode.OFFLINE) {
            boolean imapFolderExists = false;

            try {
                // Ensure IMAP folder is initialized (but don't open it)
                if (imapFolder == null && cachedStore.getImapStore() != null) {
                    imapFolder = cachedStore.getImapStore().getFolder(folderName);
                }

                if (imapFolder != null) {
                    imapFolderExists = imapFolder.exists();

                    if (imapFolderExists) {
                        // Exists remotely but not locally - create it locally to fix the synch problem
                        this.createLocally();
                    }
                }
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error checking if IMAP folder exists: " + e.getMessage(), e);
                // Continue with local check only
            }

            return imapFolderExists;
        }

        return false;
    }

    @Override
    public CachedFolder[] list(String pattern) throws MessagingException {
        List<Folder> folders = new ArrayList<>();

        // For OFFLINE mode or when caching, list from cache
        if (cachedStore.getMode() == CacheMode.OFFLINE || cacheDir != null) {
            File[] subdirs = cacheDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    // Skip special directories like "messages" and "archived_messages"
                    if (!subdir.getName().equals("messages") && !subdir.getName().equals("archived_messages") && !subdir.getName().equals("extras")) {
                        String childName = folderName.isEmpty() ?
                                subdir.getName() :
                                folderName + "/" + subdir.getName();
                        CachedFolder folder = new CachedFolder(cachedStore, childName, false);
                        // Add change listener to propagate events
                        folder.addChangeListener(event -> fireChangeEvent(event));
                        folders.add(folder);
                    }
                }
            }
        }

        // For non-OFFLINE modes, also list from IMAP
        if (cachedStore.getMode() != CacheMode.OFFLINE) {
            try {
                // Ensure IMAP folder is initialized
                ensureImapFolderReady(Folder.READ_ONLY);

                if (imapFolder != null) {
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
                            CachedFolder cachedFolder = new CachedFolder(cachedStore, folder.getFullName(), true);
                            // Add change listener to propagate events
                            cachedFolder.addChangeListener(event -> fireChangeEvent(event));
                            folders.add(cachedFolder);
                        }
                    }
                }
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error listing IMAP folders: " + e.getMessage(), e);
                // Continue with results from cache
                e.printStackTrace();
            }
        }

        return folders.toArray(new CachedFolder[0]);
    }

    @Override
    public CachedFolder[] list() throws MessagingException {
        return list("%");
    }

    @Override
    public CachedFolder getFolder(String name) throws MessagingException {
        // Build the full name
        String fullName = folderName.isEmpty() ? name : folderName + "/" + name;
        CachedFolder folder = new CachedFolder(cachedStore, fullName, true);
        // Add change listener to propagate events
        folder.addChangeListener(event -> fireChangeEvent(event));
        return folder;
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
            throw new MessagingException("Cannot create folders in offline mode");
        }

        // In other modes, try to create on server first
        boolean success = true;

        // Create on server if possible
        try {
            // Ensure IMAP folder is initialized
            if (imapFolder == null && cachedStore.getImapStore() != null) {
                imapFolder = cachedStore.getImapStore().getFolder(folderName);
            }

            if (imapFolder != null) {
                success = imapFolder.create(type);
                if (!success) {
                    LOGGER.warning("Failed to create folder on server: " + folderName);
                    return false; // Don't continue if server operation failed
                }
                LOGGER.info("Folder created on server successfully: " + folderName);
            } else {
                LOGGER.warning("Cannot create folder on server - IMAP folder is null");
                return false;
            }
        } catch (MessagingException e) {
            LOGGER.log(Level.SEVERE, "Error creating folder on server", e);
            throw e; // Don't continue if server operation failed
        }

        // Then create locally
        return createLocally();
    }

    /**
     * Create the folder locally in the cache
     *
     * @return true if the folder was created, false otherwise
     */
    private boolean createLocally() {
        if (cacheDir != null && !cacheDir.exists()) {
            boolean result = cacheDir.mkdirs();

            // Create messages directory
            File messagesDir = new File(cacheDir, "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }

            // Create archived messages directory
            File archivedDir = new File(cacheDir, "archived_messages");
            if (!archivedDir.exists()) {
                archivedDir.mkdirs();
            }

            // Notify listeners
            fireChangeEvent(new MailCacheChangeEvent(this,
                    MailCacheChangeEvent.ChangeType.FOLDER_ADDED, this));

            return true;
        }
        return false;
    }

    @Override
    public boolean delete(boolean recurse) throws MessagingException {
        // Only allow deletion in DESTRUCTIVE mode
        if (cachedStore.getMode() != CacheMode.DESTRUCTIVE) {
            throw new MessagingException("Cannot delete folders unless in DESTRUCTIVE mode");
        }

        boolean success = true;

        // Delete on server if possible
        try {
            // Ensure IMAP folder is initialized and opened in READ_WRITE mode
            boolean imapReady = ensureImapFolderReady(Folder.READ_WRITE);

            if (imapReady) {
                success = imapFolder.delete(recurse);
                if (!success) {
                    LOGGER.warning("Failed to delete folder on server: " + folderName);
                    return false; // Don't delete locally if server delete failed
                }
                LOGGER.info("Folder deleted on server successfully: " + folderName);
            } else {
                LOGGER.warning("Cannot delete folder on server - IMAP folder not ready");
                return false;
            }
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error deleting folder on server: " + e.getMessage(), e);
            return false;
        }

        // Only delete locally if server operation was successful
        if (cacheDir != null) {
            // Actually, instead of deleting, move to an archive directory
            File archiveDir = new File(cachedStore.getCacheDirectory(), "archived_folders");
            if (!archiveDir.exists()) {
                archiveDir.mkdirs();
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            File archiveFolder = new File(archiveDir, folderName.replace('/', '_') + "_" + timestamp);

            return cacheDir.renameTo(archiveFolder);
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
        try {
            // Ensure IMAP folder is initialized and opened in READ_WRITE mode
            boolean imapReady = ensureImapFolderReady(Folder.READ_WRITE);

            if (imapReady && folder instanceof CachedFolder) {
                CachedFolder cachedFolder = (CachedFolder) folder;

                // Ensure destination IMAP folder is initialized
                if (cachedFolder.imapFolder == null && cachedStore.getImapStore() != null) {
                    cachedFolder.imapFolder = cachedStore.getImapStore().getFolder(cachedFolder.folderName);
                }

                if (cachedFolder.imapFolder != null) {
                    success = imapFolder.renameTo(cachedFolder.imapFolder);
                    if (!success) {
                        LOGGER.warning("Failed to rename folder on server");
                        return false; // Don't rename locally if server rename failed
                    }
                    LOGGER.info("Folder renamed on server successfully");
                } else {
                    LOGGER.warning("Cannot rename folder on server - destination IMAP folder is null");
                    return false;
                }
            } else {
                LOGGER.warning("Cannot rename folder on server - IMAP folder not ready or destination not a CachedFolder");
                return false;
            }
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error renaming folder on server: " + e.getMessage(), e);
            return false;
        }

        // Only rename locally if server operation was successful
        if (cacheDir != null && folder instanceof CachedFolder) {
            CachedFolder cachedFolder = (CachedFolder) folder;
            if (cachedFolder.cacheDir != null) {
                return cacheDir.renameTo(cachedFolder.cacheDir);
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

        // For non-OFFLINE modes, open IMAP folder
        if (cachedStore.getMode() != CacheMode.OFFLINE) {
            try {
                ensureImapFolderReady(mode);
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error opening IMAP folder: " + e.getMessage(), e);
                // Continue with local operations in ACCELERATED mode
                if (cachedStore.getMode() != CacheMode.ACCELERATED) {
                    throw e;
                }
            }
        }

        isOpen = true;

        // Notify listeners
        fireChangeEvent(new MailCacheChangeEvent(this,
                MailCacheChangeEvent.ChangeType.FOLDER_UPDATED, this));
    }

    @Override
    public void close(boolean expunge) throws MessagingException {
        if (!isOpen) {
            throw new IllegalStateException("Folder is not open");
        }

        try {
            // For non-OFFLINE modes, close IMAP folder
            if (cachedStore.getMode() != CacheMode.OFFLINE && imapFolder != null && imapFolder.isOpen()) {
                imapFolder.close(expunge);
                LOGGER.fine("Closed IMAP folder: " + folderName);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error closing IMAP folder: " + e.getMessage(), e);
        }

        isOpen = false;
        this.mode = -1;

        // Notify listeners
        fireChangeEvent(new MailCacheChangeEvent(this,
                MailCacheChangeEvent.ChangeType.FOLDER_UPDATED, this));
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public Flags getPermanentFlags() {
        try {
            // For non-OFFLINE modes, get from IMAP
            if (cachedStore.getMode() != CacheMode.OFFLINE && imapFolder != null && imapFolder.isOpen()) {
                return imapFolder.getPermanentFlags();
            }
        } catch (Exception e) {
            // Fallback to empty flags
            LOGGER.log(Level.FINE, "Error getting permanent flags from IMAP", e);
        }

        return new Flags();
    }

    @Override
    public CachedMessage[] getMessages(int start, int end) throws MessagingException {
        checkOpen();

        // For REFRESH mode, always get from IMAP
        if (cachedStore.getMode() == CacheMode.REFRESH && imapFolder != null && imapFolder.isOpen()) {
            // Get from IMAP
            Message[] imapMessages = imapFolder.getMessages(start, end);

            // Create cached versions, overwriting existing ones
            CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
            for (int i = 0; i < imapMessages.length; i++) {
                cachedMessages[i] = new CachedMessage(this, imapMessages[i], true); // Add overwrite flag
            }

            return cachedMessages;
        }

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
        List<CachedMessage> messages = new ArrayList<>();

        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);

                if (messageDirs != null) {
                    // Sort directories to ensure consistent order
                    // (implementation would sort by message number or date)
                    java.util.Arrays.sort(messageDirs);

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

        return messages.toArray(new CachedMessage[0]);
    }

    @Override
    public CachedMessage[] getMessages() throws MessagingException {
        checkOpen();

        // For REFRESH mode, always get from IMAP
        if (cachedStore.getMode() == CacheMode.REFRESH && imapFolder != null && imapFolder.isOpen()) {
            // Get from IMAP
            Message[] imapMessages = imapFolder.getMessages();

            // Create cached versions, overwriting existing ones
            CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
            for (int i = 0; i < imapMessages.length; i++) {
                cachedMessages[i] = new CachedMessage(this, imapMessages[i], true); // Add overwrite flag
            }

            return cachedMessages;
        }

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
        List<CachedMessage> messages = new ArrayList<>();

        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);

                if (messageDirs != null) {
                    // Sort directories to ensure consistent order
                    java.util.Arrays.sort(messageDirs);

                    for (File messageDir : messageDirs) {
                        try {
                            CachedMessage message = new CachedMessage(this, messageDir);
                            // Add change listener to propagate events
                            message.addChangeListener(event -> fireChangeEvent(event));
                            messages.add(message);
                        } catch (MessagingException e) {
                            LOGGER.log(Level.WARNING, "Error loading message: " + e.getMessage(), e);
                        }
                    }
                }
            }
        }

        return messages.toArray(new CachedMessage[0]);
    }

    @Override
    public int getMessageCount() throws MessagingException {
        checkOpen();

        // For ONLINE mode, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            try {
                return imapFolder.getMessageCount();
            } catch (MessagingException e) {
                // Fall back to local cache if server check fails
                LOGGER.log(Level.WARNING, "Error getting message count from server", e);
            }
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
    public CachedMessage getMessage(int msgnum) throws MessagingException {
        checkOpen();

        // For REFRESH mode, always get from IMAP
        if (cachedStore.getMode() == CacheMode.REFRESH && imapFolder != null && imapFolder.isOpen()) {
            // Get from IMAP
            Message imapMessage = imapFolder.getMessage(msgnum);

            // Create cached version, overwriting existing one
            return new CachedMessage(this, imapMessage, true); // Add overwrite flag
        }

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
                    java.util.Arrays.sort(messageDirs);

                    if (msgnum > 0 && msgnum <= messageDirs.length) {
                        return new CachedMessage(this, messageDirs[msgnum - 1]);
                    }
                }
            }
        }

        throw new MessagingException("Message number " + msgnum + " not found");
    }

    /**
     * Append messages to the folder with enhanced error handling
     * and Message-ID preservation. Fixes issues with Gmail labels
     * and server replication.
     *
     * @param msgs The messages to append
     * @throws MessagingException If there is an error during the append operation
     */
    @Override
    public void appendMessages(Message[] msgs) throws MessagingException {
        // In OFFLINE mode, cannot append
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot append messages in OFFLINE mode");
        }

        checkOpen();

        // Log the beginning of the operation
        LOGGER.info("Starting append operation to folder: " + folderName +
                " with " + msgs.length + " messages");

        // Check and preserve Message-IDs for all messages
        for (int i = 0; i < msgs.length; i++) {
            // Make sure each message has a Message-ID (important for Gmail label approach)
            String[] headers = null;
            try {
                headers = msgs[i].getHeader("Message-ID");
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error checking Message-ID", e);
            }

            if (headers == null || headers.length == 0) {
                // Generate a Message-ID if missing
                String generatedId = "<" + System.currentTimeMillis() + "." + i + "@mailcache.generated>";
                try {
                    msgs[i].setHeader("Message-ID", generatedId);
                    LOGGER.info("Added generated Message-ID to message #" + (i+1) + ": " + generatedId);
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error setting Message-ID", e);
                }
            } else {
                LOGGER.fine("Message #" + (i+1) + " already has Message-ID: " + headers[0]);
            }
        }

        // For other modes, append to server first
        boolean serverOperationSuccessful = false;

        // Ensure IMAP folder is ready - key fix for server connectivity
        boolean imapReady = false;
        try {
            imapReady = ensureImapFolderReady(Folder.READ_WRITE);
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error preparing IMAP folder", e);
            // Continue with local operations in ACCELERATED mode
            if (cachedStore.getMode() != CacheMode.ACCELERATED) {
                throw e;
            }
        }

        if (imapReady) {
            try {
                LOGGER.info("Appending messages to server...");

                // Extract or convert IMAP messages for server operation
                Message[] imapMessages = new Message[msgs.length];
                for (int i = 0; i < msgs.length; i++) {
                    if (msgs[i] instanceof CachedMessage) {
                        CachedMessage cachedMsg = (CachedMessage) msgs[i];
                        // Get the underlying IMAP message if available
                        Message imapMsg = cachedMsg.getImapMessage();
                        if (imapMsg != null) {
                            imapMessages[i] = imapMsg;
                            LOGGER.fine("Using existing IMAP message for message #" + (i+1));
                        } else {
                            // Create a new MimeMessage from the cached content
                            LOGGER.fine("Creating new MimeMessage from cached content for message #" + (i+1));

                            try {
                                MimeMessage newMsg = new MimeMessage(cachedStore.getSession(),
                                        cachedMsg.getInputStream());

                                // Ensure Message-ID is preserved from the cached message
                                String[] msgIds = cachedMsg.getHeader("Message-ID");
                                if (msgIds != null && msgIds.length > 0) {
                                    newMsg.setHeader("Message-ID", msgIds[0]);
                                    LOGGER.fine("Preserved Message-ID in new MimeMessage: " + msgIds[0]);
                                }

                                imapMessages[i] = newMsg;
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, "Error creating MimeMessage from cached content", e);
                                throw new MessagingException("Error creating MimeMessage", e);
                            }
                        }
                    } else {
                        imapMessages[i] = msgs[i];
                        LOGGER.fine("Using original message for server append");
                    }
                }

                // Now append the IMAP messages to the IMAP folder
                imapFolder.appendMessages(imapMessages);
                serverOperationSuccessful = true;
                LOGGER.info("Server append completed successfully");

            } catch (MessagingException e) {
                LOGGER.log(Level.SEVERE, "Error appending messages to server", e);

                // In ACCELERATED mode, we can continue with local operations despite server failure
                if (cachedStore.getMode() != CacheMode.ACCELERATED) {
                    throw e; // Don't continue if server operation failed
                }
            }
        } else {
            LOGGER.warning("Skipping server append - IMAP folder not ready");

            // In ACCELERATED mode, we can continue with local operations
            if (cachedStore.getMode() != CacheMode.ACCELERATED) {
                throw new MessagingException("Cannot append to server - IMAP folder not ready");
            }
        }

        // Then handle local cache operations - proceed even if server operation failed in ACCELERATED mode
        LOGGER.info("Appending messages to local cache...");

        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (!messagesDir.exists()) {
                messagesDir.mkdirs();
            }

            for (int i = 0; i < msgs.length; i++) {
                Message msg = msgs[i];

                // If it's already a CachedMessage for this folder, skip
                if (msg instanceof CachedMessage) {
                    CachedMessage cachedMsg = (CachedMessage) msg;
                    if (cachedMsg.getFolder() == this) {
                        LOGGER.fine("Skipping message #" + (i+1) + " - already cached in this folder");
                        continue; // Already cached in this folder
                    }
                }

                // Create a new CachedMessage - this will handle proper caching
                try {
                    LOGGER.fine("Creating new CachedMessage for message #" + (i+1));
                    new CachedMessage(this, msg);
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error caching message #" + (i+1) + ": " + e.getMessage(), e);
                    // Continue with other messages
                }
            }

            LOGGER.info("Local cache append completed");
        } else {
            LOGGER.warning("Cannot append to local cache - cache directory is null");
        }

        // Notify listeners of the change
        fireChangeEvent(new MailCacheChangeEvent(this,
                MailCacheChangeEvent.ChangeType.FOLDER_UPDATED, this));

        LOGGER.info("Append operation completed - Server: " +
                (serverOperationSuccessful ? "Success" : "Skipped/Failed") +
                ", Local: Success");
    }

    @Override
    public int getUnreadMessageCount() throws MessagingException {
        checkOpen();

        // For ONLINE mode, get from IMAP
        if (cachedStore.getMode() == CacheMode.ONLINE && imapFolder != null && imapFolder.isOpen()) {
            try {
                return imapFolder.getUnreadMessageCount();
            } catch (MessagingException e) {
                // Fall back to local cache if server check fails
                LOGGER.log(Level.WARNING, "Error getting unread message count from server", e);
            }
        }

        // For other modes, count unread messages from cache
        int unreadCount = 0;
        if (cacheDir != null) {
            try {
                Message[] messages = getMessages();
                for (Message message : messages) {
                    if (!message.isSet(Flags.Flag.SEEN)) {
                        unreadCount++;
                    }
                }
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error counting unread messages in cache", e);
            }
        }

        return unreadCount;
    }

    @Override
    public int getNewMessageCount() throws MessagingException {
        // For OFFLINE mode, we cannot determine new message count
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            return 0;
        }

        // For non-OFFLINE modes, check the IMAP folder
        if (imapFolder != null && imapFolder.isOpen()) {
            try {
                return imapFolder.getNewMessageCount();
            } catch (MessagingException e) {
                // Log error but return 0
                LOGGER.log(Level.WARNING, "Error getting new message count from server", e);
            }
        }

        return 0;
    }

    @Override
    public boolean hasNewMessages() throws MessagingException {
        // For OFFLINE mode, we cannot determine if there are new messages
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            return false;
        }

        // For non-OFFLINE modes, check the IMAP folder
        if (imapFolder != null) {
            try {
                return imapFolder.hasNewMessages();
            } catch (MessagingException e) {
                // Fall back to local check if server check fails
                LOGGER.log(Level.WARNING, "Error checking for new messages on server", e);
            }
        }

        return false;
    }

    @Override
    public Message[] expunge() throws MessagingException {
        // Only allow expunge in DESTRUCTIVE mode
        // we can not return CachedMessage because this came from imap
        if (cachedStore.getMode() != CacheMode.DESTRUCTIVE) {
            throw new MessagingException("Cannot expunge messages unless in DESTRUCTIVE mode");
        }

        checkOpen();

        // Expunge from server first
        Message[] expunged = null;

        // Ensure IMAP folder is ready
        boolean imapReady = ensureImapFolderReady(Folder.READ_WRITE);

        if (imapReady) {
            expunged = imapFolder.expunge();
            LOGGER.info("Expunged " + (expunged != null ? expunged.length : 0) + " messages on server");
        } else {
            LOGGER.warning("Cannot expunge on server - IMAP folder not ready");
        }

        // For DESTRUCTIVE mode, we can remove messages from the local cache
        // but we'll keep a backup in an "archive" directory
        if (expunged != null && expunged.length > 0 && cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            File archiveDir = new File(cacheDir, "archived_messages");
            if (!archiveDir.exists()) {
                archiveDir.mkdirs();
            }

            if (messagesDir.exists()) {
                for (Message msg : expunged) {
                    if (msg instanceof CachedMessage) {
                        CachedMessage cachedMsg = (CachedMessage) msg;
                        File messageDir = cachedMsg.getMessageDirectory();

                        // Move to archive instead of deleting
                        if (messageDir != null && messageDir.exists()) {
                            File archiveDest = new File(archiveDir, messageDir.getName());
                            boolean moved = messageDir.renameTo(archiveDest);
                            LOGGER.fine("Archived message: " + messageDir.getName() + ", success: " + moved);
                        }
                    }
                }
            }
        }

        return expunged != null ? expunged : new CachedMessage[0];
    }


    public CachedMessage[] search(SearchTerm term) throws MessagingException {
        checkOpen();

        // For ONLINE mode, search on server
        if (cachedStore.getMode() == CacheMode.ONLINE || cachedStore.getMode() == CacheMode.REFRESH) {
            try {
                // Ensure IMAP folder is ready
                boolean imapReady = ensureImapFolderReady(Folder.READ_ONLY);

                if (imapReady) {
                    Message[] serverResults = imapFolder.search(term);

                    // Create cached versions of the server results
                    List<CachedMessage> cachedResults = new ArrayList<>();
                    for (Message msg : serverResults) {
                        cachedResults.add(new CachedMessage(this, msg));
                    }

                    LOGGER.info("Server search found " + cachedResults.size() + " messages");
                    return cachedResults.toArray(new CachedMessage[0]);
                } else {
                    LOGGER.warning("Cannot search on server - IMAP folder not ready");
                }
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error searching on server, falling back to local cache", e);
            }
        }

        // For other modes or if server search fails, search in local cache
        LOGGER.info("Performing local cache search");
        CachedMessage[] messages = getMessages();
        List<CachedMessage> results = new ArrayList<>();

        for (CachedMessage msg : messages) {
            if (term.match(msg)) {
                results.add(msg);
            }
        }

        LOGGER.info("Local search found " + results.size() + " messages");
        return results.toArray(new CachedMessage[0]);
    }

    /**
     * Get the file system directory for this folder
     *
     * @return The directory for this folder
     */
    public File getFolderDirectory() {
        return cacheDir;
    }

    /**
     * Add an additional file to the folder
     *
     * @param filename The name of the file to create/update
     * @param content The content to write to the file
     * @throws java.io.IOException If there is an error writing the file
     */
    public void addAdditionalFile(String filename, String content) throws java.io.IOException {
        if (cacheDir == null || !cacheDir.exists()) {
            throw new java.io.IOException("Folder directory does not exist");
        }

        // Sanitize filename to prevent directory traversal
        String sanitizedFilename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        // Create extras directory if it doesn't exist
        File extrasDir = new File(cacheDir, "extras");
        if (!extrasDir.exists()) {
            extrasDir.mkdirs();
        }

        // Write the file
        File file = new File(extrasDir, sanitizedFilename);
        try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
            writer.write(content);
        }
    }

    /**
     * Get the content of an additional file in this folder
     *
     * @param filename The name of the file to read
     * @return The content of the file, or null if the file doesn't exist
     * @throws java.io.IOException If there is an error reading the file
     */
    public String getAdditionalFileContent(String filename) throws java.io.IOException {
        if (cacheDir == null || !cacheDir.exists()) {
            throw new java.io.IOException("Folder directory does not exist");
        }

        // Sanitize filename to prevent directory traversal
        String sanitizedFilename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        File extrasDir = new File(cacheDir, "extras");
        File file = new File(extrasDir, sanitizedFilename);

        if (!file.exists() || !file.isFile()) {
            return null;
        }

        return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Get a list of all additional files in this folder
     *
     * @return An array of file names
     */
    public String[] listAdditionalFiles() {
        if (cacheDir == null || !cacheDir.exists()) {
            return new String[0];
        }

        File extrasDir = new File(cacheDir, "extras");
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
     * Check if the folder is open
     */
    private void checkOpen() throws MessagingException {
        if (!isOpen) {
            throw new IllegalStateException("Folder is not open");
        }
    }



    /**
     * Move messages between folders. Will perform the move on the server first,
     * then update the local cache accordingly.
     *
     * @param messages The messages to move
     * @param destination The destination folder
     * @throws MessagingException If the move operation fails
     */
    public void moveMessages(Message[] messages, Folder destination) throws MessagingException {
        // In OFFLINE mode, cannot move
        if (cachedStore.getMode() == CacheMode.OFFLINE) {
            throw new MessagingException("Cannot move messages in OFFLINE mode");
        }

        checkOpen();

        if (!(destination instanceof CachedFolder)) {
            throw new MessagingException("Destination folder must be a CachedFolder");
        }

        CachedFolder destFolder = (CachedFolder) destination;
        LOGGER.info("Moving " + messages.length + " messages from " +
                getFullName() + " to " + destFolder.getFullName());

        // Move on server first if possible
        boolean serverOperationSuccessful = false;

        // Ensure both source and destination IMAP folders are ready
        boolean sourceImapReady = ensureImapFolderReady(Folder.READ_WRITE);
        boolean destImapReady = destFolder.ensureImapFolderReady(Folder.READ_WRITE);

        if (sourceImapReady && destImapReady) {
            try {
                LOGGER.info("Moving messages on server...");

                // Extract IMAP messages if needed
                List<Message> imapMessages = new ArrayList<>();
                List<String> messageIds = new ArrayList<>();

                for (Message msg : messages) {
                    if (msg instanceof CachedMessage) {
                        CachedMessage cachedMsg = (CachedMessage) msg;

                        // Save Message-ID for verification
                        String[] ids = cachedMsg.getHeader("Message-ID");
                        if (ids != null && ids.length > 0) {
                            messageIds.add(ids[0]);
                        }

                        Message imapMsg = cachedMsg.getImapMessage();
                        if (imapMsg != null) {
                            imapMessages.add(imapMsg);
                        } else {
                            LOGGER.warning("Could not get IMAP message for cached message");
                        }
                    }
                }

                if (!imapMessages.isEmpty()) {
                    // There's no standard moveMessages in JavaMail, so we implement
                    // using copy + delete

                    // First, copy messages to destination on server
                    LOGGER.fine("Copying messages to destination on server");
                    destFolder.imapFolder.appendMessages(imapMessages.toArray(new Message[0]));

                    // Handle Gmail's label approach differently
                    boolean isGmail = isGmailServer();

                    if (isGmail) {
                        LOGGER.info("Detected Gmail server - using label approach");

                        // For Gmail, we're essentially removing one label and adding another
                        // So we mark messages as deleted in the source folder
                        for (Message msg : imapMessages) {
                            msg.setFlag(Flags.Flag.DELETED, true);
                        }

                        // Gmail requires expunge to remove the label
                        if (cachedStore.getMode() == CacheMode.DESTRUCTIVE) {
                            imapFolder.expunge();
                        }
                    } else {
                        // For standard IMAP, mark messages as deleted in source folder
                        for (Message msg : imapMessages) {
                            msg.setFlag(Flags.Flag.DELETED, true);
                        }

                        // Expunge source folder on server if in DESTRUCTIVE mode
                        if (cachedStore.getMode() == CacheMode.DESTRUCTIVE) {
                            imapFolder.expunge();
                        }
                    }

                    serverOperationSuccessful = true;
                    LOGGER.info("Server move operation completed successfully");
                } else {
                    LOGGER.warning("No IMAP messages to move");
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error moving messages on server", e);

                // In ACCELERATED mode, continue with local operations
                if (cachedStore.getMode() != CacheMode.ACCELERATED) {
                    throw new MessagingException("Failed to move messages on server", e);
                }
            }
        } else {
            LOGGER.warning("Cannot move messages on server - IMAP folders not ready");

            // In ACCELERATED mode, continue with local operations
            if (cachedStore.getMode() != CacheMode.ACCELERATED) {
                throw new MessagingException("Failed to prepare IMAP folders for move operation");
            }
        }

        // Then update local cache - proceed even if server operation failed in ACCELERATED mode
        LOGGER.info("Updating local cache...");

        if (cacheDir != null && destFolder.cacheDir != null) {
            File srcMessagesDir = new File(cacheDir, "messages");
            File destMessagesDir = new File(destFolder.cacheDir, "messages");

            if (!destMessagesDir.exists()) {
                destMessagesDir.mkdirs();
            }

            for (Message msg : messages) {
                if (msg instanceof CachedMessage) {
                    CachedMessage cachedMsg = (CachedMessage) msg;
                    File messageDir = cachedMsg.getMessageDirectory();

                    if (messageDir != null && messageDir.exists()) {
                        File destMessageDir = new File(destMessagesDir, messageDir.getName());
                        if (destMessageDir.exists()) {
                            LOGGER.warning("Message directory collision during move: " + destMessageDir.getName());
                        } else {
                            // Move the directory
                            boolean moved = messageDir.renameTo(destMessageDir);
                            LOGGER.fine("Moved message directory: " + messageDir.getName() + ", success: " + moved);
                        }
                    }
                }
            }

            LOGGER.info("Local cache update completed");
        } else {
            LOGGER.warning("Cannot update local cache - cache directory is null");
        }

        // Notify listeners of the change for both folders
        fireChangeEvent(new MailCacheChangeEvent(this,
                MailCacheChangeEvent.ChangeType.FOLDER_UPDATED, this));

        destFolder.fireChangeEvent(new MailCacheChangeEvent(destFolder,
                MailCacheChangeEvent.ChangeType.FOLDER_UPDATED, destFolder));

        LOGGER.info("Move operation completed - Server: " +
                (serverOperationSuccessful ? "Success" : "Skipped/Failed") +
                ", Local: Success");
    }

    /**
     * Attempt to detect if we're connected to Gmail
     *
     * @return true if this appears to be a Gmail server
     */
    private boolean isGmailServer() {
        try {
            if (imapFolder != null && imapFolder.getStore() != null) {
                URLName url = imapFolder.getStore().getURLName();
                if (url != null && url.getHost() != null) {
                    String host = url.getHost().toLowerCase();
                    return host.contains("gmail") || host.contains("googlemail") ||
                            host.endsWith("google.com");
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error checking for Gmail server", e);
        }
        return false;
    }
}