# JavaMail Cache Implementation

A robust solution for efficient email access with flexible online/offline capabilities and permanent message archiving - with comprehensive multi-user support.

## Overview

`com.intenovation.mailcache` provides a high-performance cache layer for JavaMail, allowing applications to work with emails efficiently in various connectivity scenarios. It implements the standard JavaMail API so existing applications can easily adopt it for improved performance and offline capabilities. The implementation is designed to preserve all messages permanently in the local cache for future reference, such as invoices and important communications.

## Key Philosophy

- **Data Preservation**: Messages are never truly deleted from the local cache, ensuring a complete historical record
- **Human-Readable Storage**: Message folders use year-month-day and subject format for easy browsing
- **Server-First Operations**: All changes happen on the server first, then locally, to prevent data loss
- **Safe Defaults**: Delete operations are restricted to DESTRUCTIVE mode only
- **Transparent Caching**: Access to local message storage for adding supplementary files
- **Multi-User Support**: Cache structure organizes data by username, allowing multiple accounts in one archive
- **Transactional Integrity**: Each write/delete operation follows a strict transaction flow to ensure data consistency
- **Server Synchronization**: After operations complete, the cache is updated from the server to ensure accuracy
- **Complete Message Caching**: All messages and attachments are fully downloaded for offline access
- **Information Preservation**: Instead of deleting data, it's moved to archive folders to fulfill record-keeping requirements

## Core Principles

### Transactional Operations
Every write or delete operation follows a strict transaction pattern:
- First execute the operation on the server
- Then update the local cache accordingly
- Finally, commit the changes on the server (by closing and reopening connections if necessary)

This approach ensures that server operations are always prioritized, preventing data inconsistencies if operations fail.

### Server-Cache Synchronization
The cache always aims to be an accurate representation of the server:
- After write operations complete, we look for the message or folder on the server
- We then download the server version to ensure the cache has the most accurate data
- For append operations, we handle new message IDs by downloading the newly created messages
- This ensures the cache always accurately represents what's on the server

### Offline Preparation
To ensure smooth offline operation:
- The entire message and all attachments are downloaded whenever a message is accessed
- If attachments with the same name are already downloaded, they aren't downloaded again (except in REFRESH mode)
- Message properties, flags, and content are all cached for complete offline access
- This approach enables seamless transitions between online and offline modes

### Data Loss Prevention
To meet record-keeping requirements and prevent accidental data loss:
- Information is never truly deleted from the system
- When deletion occurs, items are moved to archived folders (archived_messages or archived_folders)
- This architecture enables recovery from accidental deletions
- It also ensures that all received invoices and important communications are permanently preserved

## Key Features

- **Five Operation Modes**:
    - **Online**: Searches happen on the server while reading uses the local cache for speed
    - **Offline**: All operations use the local cache with no server connectivity required
    - **Accelerated**: Reading and searching use local cache, writing happens both locally and on the server
    - **Refresh**: Always gets latest from server, overwrites cache completely
    - **Destructive**: The only mode that allows deleting messages and folders (use with caution)
- **Permanent Archiving**: Messages deleted from the server are archived locally and never truly deleted
- **Standard API**: Fully compatible with the JavaMail API
- **Seamless Transitions**: Switch between modes without restarting your application
- **Efficient Storage**: Optimized on-disk storage format for emails and attachments
- **Conflict Resolution**: Smart handling of conflicts between local and server versions
- **Bandwidth Optimization**: Only downloads required data, with support for partial message fetching
- **Extended Functionality**: Access to add supplementary files alongside cached messages
- **Multi-User Caching**: Store multiple email accounts in the same base directory, organized by username

## Getting Started

### Installation

Add the dependency to your Maven project:

```xml
<dependency>
    <groupId>com.intenovation</groupId>
    <artifactId>mailcache</artifactId>
    <version>1.0.0</version>
</dependency>
```

Or with Gradle:

```gradle
implementation 'com.intenovation:mailcache:1.0.0'
```

### Basic Usage

```java
import com.intenovation.mailcache.*;
import javax.mail.*;

// Create a base cache directory
File baseCacheDir = new File("/path/to/cache");

// Open a store in ACCELERATED mode with IMAP connectivity
// The username-specific cache will be created at /path/to/cache/username/
CachedStore store = MailCache.openStore(
    baseCacheDir,
    CacheMode.ACCELERATED,
    "imap.example.com",
    993,
    "username@example.com",
    "password",
    true // Use SSL
);

// Work with folders and messages using standard JavaMail API
Folder inbox = store.getFolder("INBOX");
inbox.open(Folder.READ_ONLY);

Message[] messages = inbox.getMessages();
for (Message message : messages) {
    System.out.println("Subject: " + message.getSubject());
    // Messages are automatically cached as you access them
}

inbox.close(false);
store.close();
```

### Multi-User Support

The system automatically organizes cached data by username:

```java
import com.intenovation.mailcache.*;
import javax.mail.*;

// Create a base cache directory for all users
File baseCacheDir = new File("/path/to/cache");

// Open a store for a specific user
// This will create a cache at /path/to/cache/user1@example.com/
CachedStore userStore = MailCache.openStore(
    baseCacheDir,
    CacheMode.ACCELERATED,
    "imap.example.com",
    993,
    "user1@example.com",
    "password",
    true // Use SSL
);

// Open a store for another user 
// This will create a cache at /path/to/cache/user2@example.com/
CachedStore otherUserStore = MailCache.openStore(
    baseCacheDir,
    CacheMode.ACCELERATED,
    "imap.example.com",
    993,
    "user2@example.com",
    "password",
    true // Use SSL
);

// Get all active stores
Collection<CachedStore> allStores = MailCache.getAllStores();

// Get a specific store by username
CachedStore user1Store = MailCache.getStoreByUsername("user1@example.com");

// Close a specific store
MailCache.closeStore("user1@example.com");

// Close all stores
MailCache.closeAllStores();
```

### Working with Different Modes

```java
// Switch to OFFLINE mode when needed
store.setMode(CacheMode.OFFLINE);

// All operations now use only the local cache
Folder inbox = store.getFolder("INBOX");
inbox.open(Folder.READ_ONLY);
// ...work with cached messages...
inbox.close(false);

// Switch back to ONLINE mode
store.setMode(CacheMode.ONLINE);

// Now searches go to the server
inbox = store.getFolder("INBOX");
inbox.open(Folder.READ_WRITE);
// ...work with server messages...
inbox.close(true);

store.close();
```

### Adding Supplementary Files to Messages

```java
// Get a message
Folder inbox = store.getFolder("INBOX");
inbox.open(Folder.READ_WRITE);
Message[] messages = inbox.getMessages();

if (messages.length > 0 && messages[0] instanceof CachedMessage) {
    CachedMessage message = (CachedMessage) messages[0];
    
    // Add a supplementary file
    try {
        message.addAdditionalFile("notes.txt", "Important notes about this invoice");
        
        // List all supplementary files
        String[] files = message.listAdditionalFiles();
        for (String file : files) {
            System.out.println("Found file: " + file);
        }
        
        // Get content of a file
        String content = message.getAdditionalFileContent("notes.txt");
        System.out.println("Content: " + content);
    } catch (IOException e) {
        e.printStackTrace();
    }
}

inbox.close(false);
```

### Listener Support

Register for change notifications:

```java
// Add a listener to the MailCache system
MailCache.addChangeListener(new MailCacheChangeListener() {
    @Override
    public void mailCacheChanged(MailCacheChangeEvent event) {
        ChangeType type = event.getChangeType();
        
        switch (type) {
            case STORE_OPENED:
                System.out.println("Store opened: " + event.getChangedItem());
                break;
            case STORE_CLOSED:
                System.out.println("Store closed: " + event.getChangedItem());
                break;
            case FOLDER_UPDATED:
                System.out.println("Folder updated: " + event.getChangedItem());
                break;
            case MESSAGE_ADDED:
                System.out.println("Message added: " + event.getChangedItem());
                break;
            // Handle other event types...
        }
    }
});

// Add a listener directly to a folder
Folder inbox = store.getFolder("INBOX");
inbox.addChangeListener(event -> {
    System.out.println("Inbox change: " + event.getChangeType());
});
```

## Operation Modes Explained

### Online Mode

In **Online** mode:
- **Reading**: Messages are read from the local cache if available, otherwise fetched from server and cached
- **Searching**: Search operations are performed on the server
- **Writing**: Changes are immediately sent to the server and updated in the cache
- **Deletion**: Not allowed unless in DESTRUCTIVE mode

Use this mode when you need real-time search results but still want fast message access.

### Offline Mode

In **Offline** mode:
- **Reading**: Only locally cached messages are available
- **Searching**: Search operations only work on cached messages
- **Writing**: Not allowed - all operations are read-only
- **Deletion**: Not allowed

Use this mode when no network connectivity is available or to ensure deterministic performance.

### Accelerated Mode

In **Accelerated** mode:
- **Reading**: Messages are read from the local cache if available, otherwise fetched and cached
- **Searching**: Search operations are performed on the local cache only
- **Writing**: Changes are sent to the server first, then updated in the cache
- **Deletion**: Not allowed unless in DESTRUCTIVE mode

This is the default mode, providing the best balance of performance and functionality.

### Refresh Mode

In **Refresh** mode:
- **Reading**: Messages are always fetched from the server, and the cache is overwritten
- **Searching**: Search operations are performed on the server
- **Writing**: Changes are sent to the server first, then updated in the cache
- **Deletion**: Not allowed unless in DESTRUCTIVE mode

Use this mode when you need to ensure the cache contains the most up-to-date information.

### Destructive Mode

In **Destructive** mode:
- All operations from Online mode, plus:
- **Deletion**: Allows deleting messages and folders from the server
- **Archiving**: Messages deleted from server are archived locally, never truly deleted
- **Restoration**: Archived messages can be restored from the local archive

This mode should be used with caution, and typically only for administrative operations.

## Transaction Flow Example

For a typical write operation such as appending a message to a folder:

1. First, the operation is attempted on the server:
   ```java
   // First try to execute on the server
   imapFolder.appendMessages(imapMessages);
   ```

2. Then, if server operation succeeds, local cache is updated:
   ```java
   // Then update local cache
   new CachedMessage(this, msg);
   ```

3. Finally, changes are committed by closing and reopening the connection:
   ```java
   // Commit changes to the server
   commitServerChanges(false);
   ```

4. After the commit, the system retrieves the newly created messages from the server:
   ```java
   // Retrieve newly appended messages from the server
   SearchTerm searchTerm = new HeaderTerm("Message-ID", messageId);
   Message[] foundMessages = imapFolder.search(searchTerm);
   
   // Update the cache with the server version
   new CachedMessage(this, foundMessages[0], true);
   ```

This ensures that operations are executed in a consistent manner that prioritizes server state and ensures the cache accurately reflects it.

## Advanced Features

### Message Moving and Archiving

```java
// Get a cached folder
CachedFolder inbox = (CachedFolder) store.getFolder("INBOX");
CachedFolder archive = (CachedFolder) store.getFolder("Archive");

inbox.open(Folder.READ_WRITE);
archive.open(Folder.READ_WRITE);

// Get messages to move
Message[] messages = inbox.getMessages(1, 5);

// Move messages between folders
inbox.moveMessages(messages, archive);

inbox.close(false);
archive.close(false);
```

### Offline Access to Existing Cache

```java
// Create an offline store for viewing cached messages without server connectivity
File baseCacheDir = new File("/path/to/cache");
String username = "user@example.com";

CachedStore offlineStore = MailCache.openOfflineStore(baseCacheDir, username);

// Now work with the cached data
Folder inbox = offlineStore.getFolder("INBOX");
inbox.open(Folder.READ_ONLY);
        Message[] messages = inbox.getMessages();
// ...
        inbox.close(false);
        offlineStore.close();
```

## Directory Structure

The cache is organized on disk as follows:

```
/CacheDirectory/
├── user1@example.com/              # Username level - first level in hierarchy
│   ├── INBOX/
│   │   ├── messages/               # Cached messages
│   │   │   ├── 2024-04-09_Invoice_April/
│   │   │   │   ├── message.properties
│   │   │   │   ├── content.txt
│   │   │   │   ├── content.html    # Optional HTML version
│   │   │   │   ├── flags.txt
│   │   │   │   ├── attachments/    # Message attachments
│   │   │   │   │   └── invoice.pdf
│   │   │   │   └── extras/         # Supplementary files
│   │   │   │       └── notes.txt
│   │   │   └── 2024-04-05_Meeting_Minutes/
│   │   │       └── ...
│   │   └── archived_messages/      # Messages archived from INBOX
│   │       └── 2024-01-10_Old_Report/
│   │           └── ...
│   ├── archived_folders/           # Folders that have been "deleted"
│   │   └── OldProjects_timestamp/  # Renamed folders stored here rather than deleted
│   │       └── ...
│   └── Sent/
│       └── messages/
│           └── ...
│
├── user2@example.com/              # Another user's cache
│   ├── INBOX/
│   │   └── ...
│   └── ...
```

## Performance Considerations

- **Initial Synchronization**: The first time you access a folder, synchronization may take time depending on the number of messages
- **Memory Usage**: By default, message bodies are loaded on demand to minimize memory usage
- **Disk Space**: Since message content and attachments are stored permanently on disk, ensure adequate space
- **Search Performance**: In OFFLINE and ACCELERATED modes, search is limited by the local indexing performance
- **Multi-User Efficiency**: Each user has their own isolated cache, preventing cross-user performance impacts
- **Transaction Overhead**: The transactional approach adds some overhead but ensures data integrity

## Requirements

- Java 8 or higher
- JavaMail API 1.6 or higher
- Sufficient disk space for cached messages

## License

Apache License 2.0

## Acknowledgments

This project was inspired by:
- The JavaMail API design
- com.intenovation.eganizor.reader package
- com.intenovation.eganizor.downloader package