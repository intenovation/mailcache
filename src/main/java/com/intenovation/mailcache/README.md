# JavaMail Cache Implementation

A robust solution for efficient email access with flexible online/offline capabilities and permanent message archiving.

## Overview

`com.intenovation.mailcache` provides a high-performance cache layer for JavaMail, allowing applications to work with emails efficiently in various connectivity scenarios. It implements the standard JavaMail API so existing applications can easily adopt it for improved performance and offline capabilities. The implementation is designed to preserve all messages permanently in the local cache for future reference, such as invoices and important communications.

## Key Philosophy

- **Data Preservation**: Messages are never truly deleted from the local cache, ensuring a complete historical record
- **Human-Readable Storage**: Message folders use year-month-day and subject format for easy browsing
- **Server-First Operations**: All changes happen on the server first, then locally, to prevent data loss
- **Safe Defaults**: Delete operations are restricted to DESTRUCTIVE mode only
- **Transparent Caching**: Access to local message storage for adding supplementary files

## Key Features

- **Four Operation Modes**:
  - **Online**: Searches happen on the server while reading uses the local cache for speed
  - **Offline**: All operations use the local cache with no server connectivity required
  - **Accelerated**: Reading and searching use local cache, writing happens both locally and on the server
  - **Destructive**: The only mode that allows deleting messages and folders (use with caution)
- **Permanent Archiving**: Messages deleted from the server are archived locally and never truly deleted
- **Standard API**: Fully compatible with the JavaMail API
- **Seamless Transitions**: Switch between modes without restarting your application
- **Efficient Storage**: Optimized on-disk storage format for emails and attachments
- **Conflict Resolution**: Smart handling of conflicts between local and server versions
- **Bandwidth Optimization**: Only downloads required data, with support for partial message fetching
- **Extended Functionality**: Access to add supplementary files alongside cached messages

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

// Create a cache directory
File cacheDir = new File("/path/to/cache");

// Open a store in ACCELERATED mode with IMAP connectivity
CachedStore store = MailCache.openStore(
    cacheDir,
    CacheMode.ACCELERATED,
    "imap.example.com",
    993,
    "username",
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

### Destructive Mode

In **Destructive** mode:
- All operations from Accelerated mode, plus:
- **Deletion**: Allows deleting messages and folders from the server
- **Archiving**: Messages deleted from server are archived locally, never truly deleted
- **Restoration**: Archived messages can be restored from the local archive

This mode should be used with caution, and typically only for administrative operations.

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


## Directory Structure

The cache is organized on disk as follows:

```
/CacheDirectory/
├── .metadata/                # Cache metadata and indexes
├── INBOX/
│   ├── messages/             # Cached messages
│   │   ├── 2024-04-09_Invoice_April/
│   │   │   ├── message.mbox
│   │   │   ├── headers.properties
│   │   │   ├── content.txt
│   │   │   ├── flags.txt
│   │   │   └── extras/       # Supplementary files
│   │   │       └── notes.txt
│   │   └── 2024-04-05_Meeting_Minutes/
│   │       └── ...
│   └── archived_messages/    # Messages archived from INBOX
│       └── 2024-01-10_Old_Report/
│           └── ...
├── Sent/
│   └── ...
└── .sync/                    # Synchronization status information
```

## Performance Considerations

- **Initial Synchronization**: The first time you access a folder, synchronization may take time depending on the number of messages
- **Memory Usage**: By default, message bodies are loaded on demand to minimize memory usage
- **Disk Space**: Since message content and attachments are stored permanently on disk, ensure adequate space
- **Search Performance**: In OFFLINE and ACCELERATED modes, search is limited by the local indexing performance

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