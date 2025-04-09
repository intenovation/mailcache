# JavaMail Cache Implementation

A robust solution for efficient email access with flexible online/offline capabilities.

## Overview

`com.intenovation.mailcache` provides a high-performance cache layer for JavaMail, allowing applications to work with emails efficiently in various connectivity scenarios. It implements the standard JavaMail API so existing applications can easily adopt it for improved performance and offline capabilities.

## Key Features

- **Three Operation Modes**:
    - **Online**: Searches happen on the server while reading uses the local cache for speed
    - **Offline**: All operations use the local cache with no server connectivity required
    - **Accelerated**: Reading and searching use local cache, writing happens both locally and on the server
- **Transparent Caching**: Automatically caches emails as they are accessed
- **Standard API**: Fully compatible with the JavaMail API
- **Seamless Transitions**: Switch between modes without restarting your application
- **Efficient Storage**: Optimized on-disk storage format for emails and attachments
- **Conflict Resolution**: Smart handling of conflicts between local and server versions
- **Bandwidth Optimization**: Only downloads required data, with support for partial message fetching

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
// Create a Session with cache configuration
Session session = MailCache.createSession(
    cacheDir,
    CacheMode.ACCELERATED,
    "imap.example.com",
    993,
    "username",
    "password",
    true
);

// Get the store
CachedStore store = (CachedStore) session.getStore();
store.connect();

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

## Operation Modes Explained

### Online Mode

In **Online** mode:
- **Reading**: Messages are read from the local cache if available, otherwise fetched from server and cached
- **Searching**: Search operations are performed on the server
- **Writing**: Changes are immediately sent to the server and updated in the cache

Use this mode when you need real-time search results but still want fast message access.

### Offline Mode

In **Offline** mode:
- **Reading**: Only locally cached messages are available
- **Searching**: Search operations only work on cached messages
- **Writing**: Not allowed - all operations are read-only

Use this mode when no network connectivity is available or to ensure deterministic performance.

### Accelerated Mode

In **Accelerated** mode:
- **Reading**: Messages are read from the local cache if available, otherwise fetched and cached
- **Searching**: Search operations are performed on the local cache only
- **Writing**: Changes are sent to the server and updated in the cache

This is the default mode, providing the best balance of performance and functionality.

## Advanced Features

### Selective Caching

```java
// Create a configuration with selective caching
CacheConfiguration config = new CacheConfiguration()
    .setCacheAttachments(true)
    .setMaxMessageSize(5 * 1024 * 1024) // 5MB max
    .setRetentionDays(30); // Keep cache for 30 days

// Create a session with this configuration
Session session = MailCache.createSessionWithConfig(
    cacheDir,
    CacheMode.ACCELERATED,
    config,
    "imap.example.com",
    993,
    "username",
    "password",
    true
);
```

### Synchronization Control

```java
// Get the cache manager
CacheManager manager = CacheManager.getInstance(store);

// Force synchronization of specific folders
manager.synchronize("INBOX");
manager.synchronize("Sent Items");

// Get sync status
SyncStatus status = manager.getSyncStatus("INBOX");
System.out.println("Last sync: " + status.getLastSyncTime());
System.out.println("Messages: " + status.getSyncedMessageCount());
```

### Cache Management

```java
// Clear specific folders from cache
CacheManager.getInstance(store).clearCache("Trash");

// Purge old messages
CacheManager.getInstance(store).purgeOlderThan(
    "INBOX", 
    30, // days
    false // don't delete flagged messages
);

// Get cache statistics
CacheStats stats = CacheManager.getInstance(store).getStatistics();
System.out.println("Cache size: " + stats.getTotalSize() + " bytes");
System.out.println("Message count: " + stats.getMessageCount());
```

## Directory Structure

The cache is organized on disk as follows:

```
/CacheDirectory/
├── .metadata/          # Cache metadata and indexes
├── INBOX/
│   ├── messages/       # Cached messages
│   │   ├── msg1/
│   │   │   ├── headers.properties
│   │   │   ├── content.txt
│   │   │   ├── content.html
│   │   │   └── attachments/
│   │   └── msg2/
│   │       └── ...
├── Sent/
│   └── ...
└── .sync/              # Synchronization status information
```

## Performance Considerations

- **Initial Synchronization**: The first time you access a folder, synchronization may take time depending on the number of messages
- **Memory Usage**: By default, message bodies are loaded on demand to minimize memory usage
- **Disk Space**: Message content and attachments are stored on disk, so ensure adequate space
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