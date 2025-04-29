package com.intenovation.mailcache;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import javax.mail.search.SearchTerm;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
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
    private int cachedMessageCount = -1;    // Cache the message count locally for performance
    private int cachedUnreadCount = -1;     // Cache the unread count locally for performance

    // Listener support
    private final List<MailCacheChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Get the CachedStore this folder belongs to
     *
     * @return The CachedStore
     */
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

        // imapFolder is now initialized lazily via getImapFolder()
    }

    /**
     * Gets the IMAP folder corresponding to this cached folder
     * Lazy initialization happens here
     *
     * @return The IMAP folder or null if not available
     */
    public Folder getImapFolder() {
        CacheMode cacheMode = cachedStore.getMode();

        // Don't initialize in OFFLINE mode
        if (cacheMode == CacheMode.OFFLINE) {
            return null;
        }

        // Return if already initialized
        if (imapFolder != null) {
            return imapFolder;
        }

        // Check if IMAP store is available
        Store imapStore = cachedStore.getImapStore();
        if (imapStore == null) {
            LOGGER.fine("Cannot initialize IMAP folder - IMAP store is null");
            return null;
        }

        // Try to connect the store if it's not connected
        if (!imapStore.isConnected()) {
            LOGGER.info("IMAP store is not connected - attempting to connect");
            try {
                // We don't have connection parameters here, so we'll try connecting without parameters
                // The store should use the parameters from its initialization
                imapStore.connect();
                if (!imapStore.isConnected()) {
                    LOGGER.warning("Failed to connect IMAP store");
                    return null;
                }
                LOGGER.info("Successfully connected IMAP store");
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error connecting IMAP store: " + e.getMessage(), e);
                return null;
            }
        }

        // Now initialize the IMAP folder
        try {
            this.imapFolder = imapStore.getFolder(folderName);
            LOGGER.fine("Initialized IMAP folder: " + folderName);

            // Check if folder exists on server
            if (!imapFolder.exists() && cacheDir != null && cacheDir.exists()) {
                // Folder exists locally but not remotely - warn but don't create on server
                LOGGER.warning("Folder '" + folderName + "' exists locally but not on the server. " +
                        "This may indicate a synchronization issue.");
            }

            return imapFolder;
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Could not get IMAP folder: " + e.getMessage(), e);
            return null;
        }
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
    private boolean prepareImapFolderForOperation(int folderMode) throws MessagingException {
        CacheMode cacheMode = cachedStore.getMode();

        // Only proceed if we can use the server based on cache mode
        if (!cacheMode.shouldReadFromServer() && !cacheMode.shouldReadFromServerAfterCacheMiss()) {
            LOGGER.fine("Not initializing IMAP folder in mode: " + cacheMode);
            return false;
        }

        // Get IMAP folder with lazy initialization
        Folder folder = getImapFolder();
        if (folder == null) {
            LOGGER.warning("IMAP folder is null - cannot perform server operation");
            return false;
        }

        if ((folder.getType() & HOLDS_MESSAGES) == 0)
            return true;

        // Open the folder if needed
        if (!folder.isOpen()) {
            LOGGER.info("Opening IMAP folder: " + getFullName() + " in mode: " +
                    (folderMode == Folder.READ_ONLY ? "READ_ONLY" : "READ_WRITE"));
            try {
                folder.open(folderMode);
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error opening IMAP folder: " + getFullName(), e);
                return false;
            }
        } else if (folder.getMode() != folderMode && folderMode == Folder.READ_WRITE) {
            // If we need READ_WRITE but folder is only open READ_ONLY, reopen it
            LOGGER.info("Reopening IMAP folder in READ_WRITE mode: " + getFullName());
            folder.close(false);
            folder.open(folderMode);
        }

        return folder.isOpen();
    }

    /**
     * Commit changes to the server by closing and reopening the connection
     * This ensures all changes are flushed to the server
     *
     * @param expunge Whether to expunge deleted messages
     * @return true if the commit was successful, false otherwise
     */
    private boolean commitServerChanges(boolean expunge) {
        CacheMode cacheMode = cachedStore.getMode();

        // Only commit in modes that use the server
        if (!cacheMode.shouldReadFromServer()) {
            return true;
        }

        if (imapFolder != null && imapFolder.isOpen()) {
            try {
                // Get current mode to reopen with same mode
                int currentMode = imapFolder.getMode();

                // Close folder with appropriate expunge flag
                LOGGER.fine("Closing IMAP folder for commit with expunge=" + expunge);
                imapFolder.close(expunge);

                // Reopen folder
                LOGGER.fine("Reopening IMAP folder for commit");
                imapFolder.open(currentMode);

                return true;
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error committing changes to server", e);
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean exists() throws MessagingException {
        // Check cache directory first - optimize by using local cache when possible
        if (cacheDir != null && cacheDir.exists()) {
            return true;
        }

        // For modes that allow server operations, check IMAP
        CacheMode cacheMode = cachedStore.getMode();
        if (cacheMode.shouldReadFromServerAfterCacheMiss()) {
            boolean imapFolderExists = false;

            try {
                // Get IMAP folder with lazy initialization
                Folder folder = getImapFolder();

                if (folder != null) {
                    imapFolderExists = folder.exists();

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

    /**
     * List folders matching the pattern
     *
     * @param pattern Pattern to match folder names
     * @return Array of matching folders
     */
    @Override
    public CachedFolder[] list(String pattern) throws MessagingException {
        List<Folder> folders = new ArrayList<>();
        CacheMode cacheMode = cachedStore.getMode();

        // List from cache always, unless in REFRESH mode which always goes to server
        if (!cacheMode.shouldReadFromServer() || cacheDir != null) {
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

        // For modes that allow server operations, also list from IMAP
        if (cacheMode.shouldReadFromServer() || cacheMode.shouldReadFromServerAfterCacheMiss()) {
            try {
                // Ensure IMAP folder is initialized
                Folder imapFolder = getImapFolder();

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
                if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                    throw e; // Rethrow if we can't fall back to cache
                }
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
        CacheMode cacheMode = cachedStore.getMode();

        // Check if writing is allowed in this mode
        if (!cacheMode.isWriteAllowed()) {
            throw new MessagingException("Cannot create folders in " + cacheMode + " mode");
        }

        // In other modes, try to create on server first
        boolean success = true;

        // Create on server if possible
        try {
            // Lazy initialize IMAP folder
            Folder imapFolder = getImapFolder();

            if (imapFolder != null) {
                success = imapFolder.create(type);
                if (!success) {
                    LOGGER.warning("Failed to create folder on server: " + folderName);
                    return false; // Don't continue if server operation failed
                }
                LOGGER.info("Folder created on server successfully: " + folderName);

                // Commit the creation
                commitServerChanges(false);
            } else {
                LOGGER.warning("Cannot create folder on server - IMAP folder is null");
                return false;
            }
        } catch (MessagingException e) {
            LOGGER.log(Level.SEVERE, "Error creating folder on server", e);
            // In ACCELERATED mode, we can continue with local operations despite server failure
            if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                throw e; // Don't continue if server operation failed and no fallback
            }
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

            return result;
        }
        return false;
    }

    @Override
    public boolean delete(boolean recurse) throws MessagingException {
        CacheMode cacheMode = cachedStore.getMode();

        // Check if deletion is allowed in this mode
        if (!cacheMode.isDeleteAllowed()) {
            throw new MessagingException("Cannot delete folders unless in DESTRUCTIVE mode");
        }

        boolean success = true;

        // Delete on server if possible
        try {
            // Ensure IMAP folder is initialized and opened in READ_WRITE mode
            boolean imapReady = prepareImapFolderForOperation(Folder.READ_WRITE);

            if (imapReady) {
                success = imapFolder.delete(recurse);
                if (!success) {
                    LOGGER.warning("Failed to delete folder on server: " + folderName);
                    return false; // Don't delete locally if server delete failed
                }
                LOGGER.info("Folder deleted on server successfully: " + folderName);

                // Commit the deletion
                commitServerChanges(true);
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

    /**
     * Recursively delete a directory
     *
     * @param dir The directory to delete
     * @return true if successful, false otherwise
     */
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
        CacheMode cacheMode = cachedStore.getMode();

        // Check if writing is allowed in this mode
        if (!cacheMode.isWriteAllowed()) {
            throw new MessagingException("Cannot rename folders in " + cacheMode + " mode");
        }

        boolean success = true;

        // Rename on server if possible
        try {
            // Ensure IMAP folder is initialized and opened in READ_WRITE mode
            boolean imapReady = prepareImapFolderForOperation(Folder.READ_WRITE);

            if (imapReady && folder instanceof CachedFolder) {
                CachedFolder cachedFolder = (CachedFolder) folder;

                // Ensure destination IMAP folder is initialized (through lazy initialization)
                Folder destImapFolder = cachedFolder.getImapFolder();

                if (destImapFolder != null) {
                    success = imapFolder.renameTo(destImapFolder);
                    if (!success) {
                        LOGGER.warning("Failed to rename folder on server");
                        return false; // Don't rename locally if server rename failed
                    }
                    LOGGER.info("Folder renamed on server successfully");

                    // Commit the rename
                    commitServerChanges(false);
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
            // In ACCELERATED mode, we can continue with local operations despite server failure
            if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                throw e; // Don't continue if server operation failed and no fallback
            }
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
        CacheMode cacheMode = cachedStore.getMode();

        // For modes that use server, try to open IMAP folder
        if (cacheMode.shouldReadFromServer()) {
            try {
                prepareImapFolderForOperation(mode);
            } catch (MessagingException e) {
                LOGGER.log(Level.WARNING, "Error opening IMAP folder: " + e.getMessage(), e);
                // Continue with local operations in modes that allow fallback
                if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                    throw e; // Don't continue if no fallback allowed
                }
            }
        }

        isOpen = true;

        // Reset cached counts when opening the folder
        cachedMessageCount = -1;
        cachedUnreadCount = -1;

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
            if (cachedStore.getMode().shouldReadFromServer() && imapFolder != null && imapFolder.isOpen()) {
                imapFolder.close(expunge);
                LOGGER.fine("Closed IMAP folder: " + folderName);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error closing IMAP folder: " + e.getMessage(), e);
        }

        isOpen = false;
        this.mode = -1;

        // Reset cached counts when closing the folder
        cachedMessageCount = -1;
        cachedUnreadCount = -1;

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
            CacheMode cacheMode = cachedStore.getMode();

            // For modes that use server, get from IMAP
            if (cacheMode.shouldReadFromServer() && imapFolder != null && imapFolder.isOpen()) {
                return imapFolder.getPermanentFlags();
            }

            // For modes that use cache, construct a set of flags from cached messages
            if (cacheDir != null) {
                // This is an optimization we could implement - maintaining a cached set of all flags
                // used in this folder's messages. For now, we'll return a default set.
                Flags flags = new Flags();
                flags.add(Flags.Flag.ANSWERED);
                flags.add(Flags.Flag.DELETED);
                flags.add(Flags.Flag.DRAFT);
                flags.add(Flags.Flag.FLAGGED);
                flags.add(Flags.Flag.RECENT);
                flags.add(Flags.Flag.SEEN);
                return flags;
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
        CacheMode cacheMode = cachedStore.getMode();

        // For REFRESH mode, always get from IMAP
        if (cacheMode.shouldReadFromServer()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                // Get from IMAP
                Message[] imapMessages = imapFolder.getMessages(start, end);

                // Create cached versions
                CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
                for (int i = 0; i < imapMessages.length; i++) {
                    // In REFRESH mode, overwrite existing cache
                    boolean overwrite = cacheMode == CacheMode.REFRESH;
                    cachedMessages[i] = new CachedMessage(this, imapMessages[i], overwrite);
                }

                return cachedMessages;
            }
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
                                CachedMessage message = new CachedMessage(this, messageDir);
                                // Add change listener to propagate events
                                message.addChangeListener(event -> fireChangeEvent(event));
                                messages.add(message);
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

        // If we should try server after cache miss and we found nothing in cache
        if (messages.isEmpty() && cacheMode.shouldReadFromServerAfterCacheMiss()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                try {
                    LOGGER.info("Cache miss in getMessages(" + start + ", " + end +
                            ") - fetching from server");

                    // Get from IMAP
                    Message[] imapMessages = imapFolder.getMessages(start, end);

                    // Create cached versions
                    CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
                    for (int i = 0; i < imapMessages.length; i++) {
                        cachedMessages[i] = new CachedMessage(this, imapMessages[i]);
                    }

                    return cachedMessages;
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error getting messages from server after cache miss", e);
                }
            }
        }

        return messages.toArray(new CachedMessage[0]);
    }

    @Override
    public CachedMessage[] getMessages() throws MessagingException {
        checkOpen();
        CacheMode cacheMode = cachedStore.getMode();

        // For REFRESH mode, always get from IMAP
        if (cacheMode.shouldReadFromServer()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                // Get from IMAP
                Message[] imapMessages = imapFolder.getMessages();

                // Create cached versions
                CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
                for (int i = 0; i < imapMessages.length; i++) {
                    // In REFRESH mode, overwrite existing cache
                    boolean overwrite = cacheMode == CacheMode.REFRESH;
                    cachedMessages[i] = new CachedMessage(this, imapMessages[i], overwrite);
                }

                return cachedMessages;
            }
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

        // If we should try server after cache miss and we found nothing in cache
        if (messages.isEmpty() && cacheMode.shouldReadFromServerAfterCacheMiss()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                try {
                    LOGGER.info("Cache miss in getMessages() - fetching from server");

                    // Get from IMAP
                    Message[] imapMessages = imapFolder.getMessages();

                    // Create cached versions
                    CachedMessage[] cachedMessages = new CachedMessage[imapMessages.length];
                    for (int i = 0; i < imapMessages.length; i++) {
                        cachedMessages[i] = new CachedMessage(this, imapMessages[i]);
                    }

                    return cachedMessages;
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error getting messages from server after cache miss", e);
                }
            }
        }

        return messages.toArray(new CachedMessage[0]);
    }

    /**
     * Count the messages in the cache directory
     * Used internally as an optimization
     *
     * @return The number of messages in the cache directory
     */
    private int countCacheMessages() {
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

    /**
     * Count unread messages in the cache directory
     * Used internally as an optimization
     *
     * @return The number of unread messages in the cache
     */
    private int countCacheUnreadMessages() {
        int unreadCount = 0;
        if (cacheDir != null) {
            File messagesDir = new File(cacheDir, "messages");
            if (messagesDir.exists()) {
                File[] messageDirs = messagesDir.listFiles(File::isDirectory);
                if (messageDirs != null) {
                    for (File messageDir : messageDirs) {
                        // Check if message is marked as seen in flags.txt
                        File flagsFile = new File(messageDir, "flags.txt");
                        if (flagsFile.exists()) {
                            try (BufferedReader reader = new BufferedReader(new java.io.FileReader(flagsFile))) {
                                String line;
                                boolean seen = false;
                                while ((line = reader.readLine()) != null) {
                                    if ("SEEN".equals(line)) {
                                        seen = true;
                                        break;
                                    }
                                }
                                if (!seen) {
                                    unreadCount++;
                                }
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, "Error reading flags file", e);
                            }
                        } else {
                            // No flags file means not seen
                            unreadCount++;
                        }
                    }
                }
            }
        }
        return unreadCount;
    }

    @Override
    public int getMessageCount() throws MessagingException {
        checkOpen();
        CacheMode cacheMode = cachedStore.getMode();

        // Use cached count if available
        if (cachedMessageCount >= 0) {
            return cachedMessageCount;
        }

        // Always check cache first for all modes except REFRESH
        if (cacheMode != CacheMode.REFRESH) {
            int cacheCount = countCacheMessages();

            // In OFFLINE mode or when server is unavailable, use cache count
            if (cacheMode == CacheMode.OFFLINE ||
                    (cacheMode == CacheMode.ACCELERATED && (imapFolder == null || !imapFolder.isOpen()))) {

                cachedMessageCount = cacheCount;
                return cacheCount;
            }

            // For ONLINE and ACCELERATED modes with server connection,
            // use cache count if it's non-zero
            if ((cacheMode == CacheMode.ONLINE || cacheMode == CacheMode.ACCELERATED) && cacheCount > 0) {
                cachedMessageCount = cacheCount;
                return cacheCount;
            }
        }

        // For modes that use server search, get from IMAP
        if (cacheMode.shouldSearchOnServer()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                try {
                    int count = imapFolder.getMessageCount();
                    cachedMessageCount = count;
                    return count;
                } catch (MessagingException e) {
                    // Fall back to local cache if server check fails and mode allows it
                    if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                        throw e;
                    }
                    LOGGER.log(Level.WARNING, "Error getting message count from server", e);
                }
            }
        }

        // Fall back to cache count
        int count = countCacheMessages();
        cachedMessageCount = count;
        return count;
    }

    @Override
    public CachedMessage getMessage(int msgnum) throws MessagingException {
        checkOpen();
        CacheMode cacheMode = cachedStore.getMode();

        // For REFRESH mode, always get from IMAP
        if (cacheMode.shouldReadFromServer()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                // Get from IMAP
                Message imapMessage = imapFolder.getMessage(msgnum);

                // Create cached version, overwriting existing one in REFRESH mode
                boolean overwrite = cacheMode == CacheMode.REFRESH;
                return new CachedMessage(this, imapMessage, overwrite);
            }
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

        // Cache miss - try server if mode allows
        if (cacheMode.shouldReadFromServerAfterCacheMiss()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                try {
                    LOGGER.info("Cache miss in getMessage(" + msgnum + ") - fetching from server");

                    // Get from IMAP
                    Message imapMessage = imapFolder.getMessage(msgnum);

                    // Create cached version
                    return new CachedMessage(this, imapMessage);
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error getting message from server after cache miss", e);
                }
            }
        }

        throw new MessagingException("Message number " + msgnum + " not found");
    }

    /**
     * Append messages to the folder with enhanced error handling,
     * Message-ID preservation, and server synchronization. After appending,
     * it retrieves the messages from the server to get the correct message IDs.
     *
     * @param msgs The messages to append
     * @throws MessagingException If there is an error during the append operation
     */
    @Override
    public void appendMessages(Message[] msgs) throws MessagingException {
        CacheMode cacheMode = cachedStore.getMode();

        // Check if writing is allowed in this mode
        if (!cacheMode.isWriteAllowed()) {
            throw new MessagingException("Cannot append messages in " + cacheMode + " mode");
        }

        checkOpen();

        // Log the beginning of the operation
        LOGGER.info("Starting append operation to folder: " + folderName +
                " with " + msgs.length + " messages");

        // Store original Message-IDs for verification and retrieval
        List<String> messageIds = new ArrayList<>();

        // Check and preserve Message-IDs for all messages
        for (int i = 0; i < msgs.length; i++) {
            // Make sure each message has a Message-ID (important for Gmail label approach)
            String[] headers = null;
            try {
                headers = msgs[i].getHeader("Message-ID");
            } catch (MessagingException e) {
                e.printStackTrace();
                LOGGER.log(Level.WARNING, "Error checking Message-ID", e);
            }

            if (headers == null || headers.length == 0) {
                // Generate a Message-ID if missing
                String generatedId = "<" + System.currentTimeMillis() + "." + i + "@mailcache.generated>";
                try {
                    msgs[i].setHeader("Message-ID", generatedId);
                    messageIds.add(generatedId);
                    LOGGER.info("Added generated Message-ID to message #" + (i+1) + ": " + generatedId);
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error setting Message-ID", e);
                }
            } else {
                messageIds.add(headers[0]);
                LOGGER.fine("Message #" + (i+1) + " already has Message-ID: " + headers[0]);
            }
            messageValidation(msgs[i]);
        }

        // For other modes, append to server first
        boolean serverOperationSuccessful = false;
        Date appendStartTime = new Date(); // Record the time before append operation

        // Ensure IMAP folder is ready - key fix for server connectivity
        boolean imapReady = false;
        try {
            imapReady = prepareImapFolderForOperation(Folder.READ_WRITE);
        } catch (MessagingException e) {
            LOGGER.log(Level.WARNING, "Error preparing IMAP folder", e);
            // Continue with local operations in modes that allow fallback
            if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
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
                            messageValidation(imapMsg);
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
                                messageValidation(imapMsg);
                                imapMessages[i] = newMsg;
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, "Error creating MimeMessage from cached content", e);
                                throw new MessagingException("Error creating MimeMessage", e);
                            }
                        }
                    } else {
                        imapMessages[i] = msgs[i];
                        messageValidation(msgs[i]);
                        LOGGER.fine("Using original message for server append");
                        throw new RuntimeException("should not happen");
                    }
                }

                // Now append the IMAP messages to the IMAP folder
                imapFolder.appendMessages(imapMessages);
                serverOperationSuccessful = true;
                LOGGER.info("Server append completed successfully");

                // Reset cached message count since we've added messages
                cachedMessageCount = -1;

                // Commit changes to ensure they're visible on the server
                commitServerChanges(false);

                // Wait a brief moment to ensure server has processed the append
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Retrieve newly appended messages from the server
                LOGGER.info("Retrieving newly appended messages from server to update cache");

                // We can try multiple approaches to find the newly appended messages

                // Approach 1: Search by Message-ID
                for (String messageId : messageIds) {
                    try {
                        SearchTerm searchTerm = new javax.mail.search.HeaderTerm("Message-ID", messageId);
                        Message[] foundMessages = imapFolder.search(searchTerm);

                        if (foundMessages != null && foundMessages.length > 0) {
                            LOGGER.fine("Found appended message with ID: " + messageId);
                            // Create a CachedMessage which will update the cache
                            new CachedMessage(this, foundMessages[0], true);
                        }
                    } catch (MessagingException e) {
                        LOGGER.log(Level.WARNING, "Error searching for appended message with ID: " + messageId, e);
                    }
                }

                // Approach 2: Get messages newer than our append start time
                try {
                    SearchTerm newerTerm = new javax.mail.search.ReceivedDateTerm(
                            javax.mail.search.ComparisonTerm.GE, appendStartTime);
                    Message[] newerMessages = imapFolder.search(newerTerm);

                    if (newerMessages != null && newerMessages.length > 0) {
                        LOGGER.fine("Found " + newerMessages.length + " messages newer than append start time");

                        // Create CachedMessage instances for these new messages
                        for (Message newMsg : newerMessages) {
                            // Check if we already cached this message by Message-ID
                            String[] msgIds = newMsg.getHeader("Message-ID");
                            boolean alreadyCached = false;

                            if (msgIds != null && msgIds.length > 0) {
                                for (String id : messageIds) {
                                    if (id.equals(msgIds[0])) {
                                        alreadyCached = true;
                                        break;
                                    }
                                }
                            }

                            if (!alreadyCached) {
                                // Create a new CachedMessage to update the cache
                                new CachedMessage(this, newMsg, true);
                            }
                        }
                    }
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error searching for new messages by date", e);
                }

            } catch (MessagingException e) {
                LOGGER.log(Level.SEVERE, "Error appending messages to server", e);

                // In modes that allow fallback, we can continue with local operations despite server failure
                if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                    throw e; // Don't continue if server operation failed
                }
            }
        } else {
            LOGGER.warning("Skipping server append - IMAP folder not ready");

            // In modes that allow fallback, we can continue with local operations
            if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                throw new MessagingException("Cannot append to server - IMAP folder not ready");
            }
        }

        // Then handle local cache operations - proceed even if server operation failed in appropriate modes
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

                // Only create a new CachedMessage if we didn't already fetch it from the server
                // We'll know this by checking the message ID
                boolean alreadyCached = false;
                try {
                    String[] headers = msg.getHeader("Message-ID");
                    if (headers != null && headers.length > 0) {
                        String msgId = headers[0];

                        // Look for existing cached message with this ID
                        File[] messageDirs = messagesDir.listFiles(File::isDirectory);
                        if (messageDirs != null) {
                            for (File dir : messageDirs) {
                                File propsFile = new File(dir, "message.properties");
                                if (propsFile.exists()) {
                                    Properties props = new Properties();
                                    try (FileInputStream fis = new FileInputStream(propsFile)) {
                                        props.load(fis);
                                        String storedId = props.getProperty("message.id");
                                        if (msgId.equals(storedId)) {
                                            alreadyCached = true;
                                            break;
                                        }
                                    } catch (IOException e) {
                                        // Ignore and continue checking
                                    }
                                }
                            }
                        }
                    }
                } catch (MessagingException e) {
                    // If we can't get the Message-ID, just assume it's not cached
                    LOGGER.fine("Could not check Message-ID for caching: " + e.getMessage());
                }

                if (!alreadyCached) {
                    // Create a new CachedMessage - this will handle proper caching
                    try {
                        LOGGER.fine("Creating new CachedMessage for message #" + (i+1));
                        new CachedMessage(this, msg);
                    } catch (MessagingException e) {
                        LOGGER.log(Level.WARNING, "Error caching message #" + (i+1) + ": " + e.getMessage(), e);
                        // Continue with other messages
                    }
                } else {
                    LOGGER.fine("Skipping message #" + (i+1) + " - already cached from server fetch");
                }
            }

            LOGGER.info("Local cache append completed");

            // Reset cached counts since we've added messages
            cachedMessageCount = -1;
            cachedUnreadCount = -1;
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

    private static void messageValidation(Message imapMsg) throws MessagingException {
        String subject= imapMsg.getSubject();
        if (subject==null || subject.length()==0){
            throw new RuntimeException("Message Validation failed: Subject");
        }
    }

    @Override
    public int getUnreadMessageCount() throws MessagingException {
        checkOpen();
        CacheMode cacheMode = cachedStore.getMode();

        // Use cached count if available
        if (cachedUnreadCount >= 0) {
            return cachedUnreadCount;
        }

        // Always check cache first for all modes except REFRESH
        if (cacheMode != CacheMode.REFRESH) {
            int cacheUnreadCount = countCacheUnreadMessages();

            // In OFFLINE mode or when server is unavailable, use cache count
            if (cacheMode == CacheMode.OFFLINE ||
                    (cacheMode == CacheMode.ACCELERATED && (imapFolder == null || !imapFolder.isOpen()))) {

                cachedUnreadCount = cacheUnreadCount;
                return cacheUnreadCount;
            }

            // For ONLINE and ACCELERATED modes with server connection,
            // use cache count if it's non-zero
            if ((cacheMode == CacheMode.ONLINE || cacheMode == CacheMode.ACCELERATED) && cacheUnreadCount > 0) {
                cachedUnreadCount = cacheUnreadCount;
                return cacheUnreadCount;
            }
        }

        // For modes that use server search, get from IMAP
        if (cacheMode.shouldSearchOnServer()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                try {
                    int count = imapFolder.getUnreadMessageCount();
                    cachedUnreadCount = count;
                    return count;
                } catch (MessagingException e) {
                    // Fall back to local cache if server check fails and mode allows it
                    if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                        throw e;
                    }
                    LOGGER.log(Level.WARNING, "Error getting unread message count from server", e);
                }
            }
        }

        // Fall back to counting unread messages from cache
        int unreadCount = countCacheUnreadMessages();
        cachedUnreadCount = unreadCount;
        return unreadCount;
    }

    @Override
    public int getNewMessageCount() throws MessagingException {
        CacheMode cacheMode = cachedStore.getMode();

        // Only modes that use server can determine new message count
        if (cacheMode.shouldReadFromServer()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                try {
                    return imapFolder.getNewMessageCount();
                } catch (MessagingException e) {
                    // Log error but return 0
                    LOGGER.log(Level.WARNING, "Error getting new message count from server", e);
                }
            }
        }

        // Since "new" status is a server-side concept and not persistently
        // stored in our cache, we can't determine new message count from cache.
        // We could potentially implement this by tracking message IDs we've seen.
        return 0;
    }

    @Override
    public boolean hasNewMessages() throws MessagingException {
        CacheMode cacheMode = cachedStore.getMode();

        // For cache-based modes, we can determine if there are new messages
        // by comparing the number of messages in the cache versus what we've seen before
        // However, this is a very basic implementation and doesn't truly detect "new" messages.
        if (cacheMode == CacheMode.OFFLINE || cacheMode == CacheMode.ACCELERATED) {
            // This would require tracking previous message counts
            // For now, we'll always return false for OFFLINE mode
            if (cacheMode == CacheMode.OFFLINE) {
                return false;
            }
        }

        // For modes that use server, check IMAP
        if (cacheMode.shouldReadFromServer()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null) {
                try {
                    return imapFolder.hasNewMessages();
                } catch (MessagingException e) {
                    // Fall back to local check if server check fails and mode allows it
                    if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                        throw e;
                    }
                    LOGGER.log(Level.WARNING, "Error checking for new messages on server", e);
                }
            }
        }

        return false;
    }

    @Override
    public Message[] expunge() throws MessagingException {
        CacheMode cacheMode = cachedStore.getMode();

        // Check if deletion is allowed in this mode
        if (!cacheMode.isDeleteAllowed()) {
            throw new MessagingException("Cannot expunge messages unless in DESTRUCTIVE mode");
        }

        checkOpen();

        // Expunge from server first
        Message[] expunged = null;

        // Ensure IMAP folder is ready
        boolean imapReady = prepareImapFolderForOperation(Folder.READ_WRITE);

        if (imapReady) {
            expunged = imapFolder.expunge();
            LOGGER.info("Expunged " + (expunged != null ? expunged.length : 0) + " messages on server");

            // Commit the expunge operation
            commitServerChanges(true);
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

            // Reset cached counts after expunging
            cachedMessageCount = -1;
            cachedUnreadCount = -1;
        }

        return expunged != null ? expunged : new CachedMessage[0];
    }

    /**
     * Search for messages matching the term
     *
     * @param term The search term
     * @return Array of messages matching the term
     * @throws MessagingException If there is an error during the search
     */
    public CachedMessage[] search(SearchTerm term) throws MessagingException {
        checkOpen();
        CacheMode cacheMode = cachedStore.getMode();

        // For modes that prioritize cache, always search locally first
        if (cacheMode == CacheMode.OFFLINE || cacheMode == CacheMode.ACCELERATED) {
            LOGGER.info("Performing local cache search in " + cacheMode + " mode");
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

        // For modes that use server search, search on server
        if (cacheMode.shouldSearchOnServer()) {
            // Get IMAP folder with lazy initialization
            Folder imapFolder = getImapFolder();

            if (imapFolder != null && imapFolder.isOpen()) {
                try {
                    Message[] serverResults = imapFolder.search(term);

                    // Create cached versions of the server results
                    List<CachedMessage> cachedResults = new ArrayList<>();
                    for (Message msg : serverResults) {
                        cachedResults.add(new CachedMessage(this, msg));
                    }

                    LOGGER.info("Server search found " + cachedResults.size() + " messages");
                    return cachedResults.toArray(new CachedMessage[0]);
                } catch (MessagingException e) {
                    LOGGER.log(Level.WARNING, "Error searching on server, falling back to local cache", e);
                    // Continue with local search if mode allows fallback
                    if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                        throw e;
                    }
                }
            }
        }

        // Fallback to local cache search
        LOGGER.info("Performing local cache search as fallback");
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
     * then update the local cache accordingly. After the move, it retrieves the
     * messages from the destination to ensure message IDs are correct.
     *
     * @param messages The messages to move
     * @param destination The destination folder
     * @throws MessagingException If the move operation fails
     */
    public void moveMessages(Message[] messages, Folder destination) throws MessagingException {
        CacheMode cacheMode = cachedStore.getMode();

        // Check if writing is allowed in this mode
        if (!cacheMode.isWriteAllowed()) {
            throw new MessagingException("Cannot move messages in " + cacheMode + " mode");
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
        List<String> messageIds = new ArrayList<>();

        // Ensure both source and destination IMAP folders are ready
        boolean sourceImapReady = prepareImapFolderForOperation(Folder.READ_WRITE);
        boolean destImapReady = destFolder.prepareImapFolderForOperation(Folder.READ_WRITE);

        if (sourceImapReady && destImapReady) {
            try {
                LOGGER.info("Moving messages on server...");

                // Extract IMAP messages if needed
                List<Message> imapMessages = new ArrayList<>();

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
                        if (cacheMode.isDeleteAllowed()) {
                            imapFolder.expunge();
                        }
                    } else {
                        // For standard IMAP, mark messages as deleted in source folder
                        for (Message msg : imapMessages) {
                            msg.setFlag(Flags.Flag.DELETED, true);
                        }

                        // Expunge source folder on server if in deletion-allowed mode
                        if (cacheMode.isDeleteAllowed()) {
                            imapFolder.expunge();
                        }
                    }

                    // Commit the changes to both folders
                    commitServerChanges(cacheMode.isDeleteAllowed());
                    destFolder.commitServerChanges(false);

                    serverOperationSuccessful = true;
                    LOGGER.info("Server move operation completed successfully");

                    // Reset cached counts for both folders after moving
                    cachedMessageCount = -1;
                    cachedUnreadCount = -1;
                    destFolder.cachedMessageCount = -1;
                    destFolder.cachedUnreadCount = -1;

                    // Now retrieve the moved messages from the destination folder
                    // to ensure we have the correct message IDs and metadata
                    if (!messageIds.isEmpty()) {
                        LOGGER.info("Retrieving moved messages from destination folder to update cache");

                        // Only search if we have message IDs to search for
                        for (String messageId : messageIds) {
                            try {
                                // Create a search term for the message ID
                                SearchTerm searchTerm = new javax.mail.search.HeaderTerm("Message-ID", messageId);

                                // Search for the message in the destination folder
                                Message[] foundMessages = destFolder.imapFolder.search(searchTerm);

                                if (foundMessages != null && foundMessages.length > 0) {
                                    LOGGER.fine("Found moved message with ID: " + messageId + " in destination folder");

                                    // Create a new CachedMessage to update the cache
                                    // This will overwrite any existing cached message with the same ID
                                    new CachedMessage(destFolder, foundMessages[0], true);
                                } else {
                                    LOGGER.warning("Could not find moved message with ID: " + messageId + " in destination folder");
                                }
                            } catch (MessagingException e) {
                                LOGGER.log(Level.WARNING, "Error retrieving moved message with ID: " + messageId, e);
                            }
                        }
                    }
                } else {
                    LOGGER.warning("No IMAP messages to move");
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error moving messages on server", e);

                // Only continue with local operations if mode allows fallback
                if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                    throw new MessagingException("Failed to move messages on server", e);
                }
            }
        } else {
            LOGGER.warning("Cannot move messages on server - IMAP folders not ready");

            // Only continue with local operations if mode allows fallback
            if (!cacheMode.shouldReadFromServerAfterCacheMiss()) {
                throw new MessagingException("Failed to prepare IMAP folders for move operation");
            }
        }

        // Then update local cache - proceed even if server operation failed if mode allows
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

            // Reset cached counts for both folders after moving
            cachedMessageCount = -1;
            cachedUnreadCount = -1;
            destFolder.cachedMessageCount = -1;
            destFolder.cachedUnreadCount = -1;
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
            // Get IMAP folder with lazy initialization
            Folder folder = getImapFolder();

            if (folder != null && folder.getStore() != null) {
                URLName url = folder.getStore().getURLName();
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