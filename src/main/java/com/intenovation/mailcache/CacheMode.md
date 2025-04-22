# Mail Cache Operation Modes

The `CacheMode` enum defines four distinct operation modes for the mail cache system, each with specific behaviors and restrictions.

## Operation Modes Overview

### OFFLINE
- **Description**: All operations use local cache with no server connectivity required.
- **Use Case**: When no network connectivity is available or to ensure deterministic performance.
- **Exceptions**:
    - Throws `MessagingException` when attempting to append messages
    - Throws `MessagingException` when attempting to modify message flags
    - Throws `MessagingException` when attempting to rename or Create folders
    - Does not throw exceptions for read operations

### ACCELERATED
- **Description**: Reading and searching use local cache, writing happens both locally and on server.
- **Use Case**: Default mode providing the best balance of performance and functionality.
- **Exceptions**:
    - Throws `MessagingException` when attempting to delete messages
    - Throws `MessagingException` when attempting to delete folders
    - Throws `MessagingException` when attempting to expunge messages
    - Logs warnings but continues if server operations fail (falls back to cache)

### ONLINE
- **Description**: Searching happens on server, reading uses cache for speed, writing happens both locally and on server.
- **Use Case**: When you need real-time search results but still want fast message access.
- **Exceptions**:
    - Throws `MessagingException` when attempting to delete messages
    - Throws `MessagingException` when attempting to delete folders
    - Throws `MessagingException` when attempting to expunge messages
    - Throws `MessagingException` when server operations fail (no fallback)

### REFRESH
- **Description**: Always gets latest from server and overwrites cache, writing happens both locally and on server.
- **Use Case**: When you need to ensure latest server data or when cache format has changed.
- **Exceptions**:
  - Throws `MessagingException` when attempting to delete messages
  - Throws `MessagingException` when attempting to delete folders
  - Throws `MessagingException` when attempting to expunge messages
  - Throws `MessagingException` when server operations fail (no fallback)

### DESTRUCTIVE
- **Description**: The only mode that allows deleting messages and folders (with local archiving).
- **Use Case**: Administrative operations requiring deletion capabilities.
- **Exceptions**:
    - Does not throw exceptions for delete operations
    - Behaves like ONLINE mode for all other operations

## Operation Matrix
| Operation              | OFFLINE | ACCELERATED                        | ONLINE                 | REFRESH                | DESTRUCTIVE |
|------------------------|---------|------------------------------------|-----------------------|-----------------------|-------------|
| **Reading messages**   | Local cache only | Local cache, then server   | Local cache, then server | Always server, update cache | Same as ONLINE |
| **Searching**          | Local cache only | Local cache only           | Server                | Server                | Server |
| **Flagging messages**  | Exception | Server then cache                | Server then cache     | Server then cache     | Server then cache |
| **Creating folders**   | Exception | Server then cache                | Server then cache     | Server then cache     | Server then cache |
| **Appending messages** | Exception | Server then cache                | Server then cache     | Server then cache     | Server then cache |
| **Moving messages**    | Exception | Server then cache                | Server then cache     | Server then cache     | Server then cache |
| **Renaming folders**   | Exception | Server then cache                | Server then cache     | Server then cache     | Server then cache |
| **Deleting messages**  | Exception | Exception                        | Exception             | Exception             | Allowed (with local archiving) |
| **Deleting folders**   | Exception | Exception                        | Exception             | Exception             | Allowed (with local archiving) |
| **Expunging messages** | Exception | Exception                        | Exception             | Exception             | Allowed (with local archiving) |
| **exists folder**      | Local cache only | creates folder if exists only remotely | creates folder if exists only remotely | creates folder if exists only remotely | Same as ONLINE |


## Key Implementation Details

1. **Message Archiving**: Even in DESTRUCTIVE mode, deleted messages are not truly deleted but archived locally
2. **Synchronization**: `CacheManager` handles synchronization between server and local cache
3. **Server-First Operations**: All write operations attempt server updates before local updates
4. **Fallback Behavior**: ACCELERATED mode can fall back to cache-only operations if server is unavailable
5. **Exception Handling**: Operations that would violate the mode's constraints throw `MessagingException`

## Code Example

```java
// Set the mode on a CachedStore instance
CachedStore store = (CachedStore) session.getStore();
store.connect();

// Change to OFFLINE mode when needed
store.setMode(CacheMode.OFFLINE);

// Change to DESTRUCTIVE mode for deletion operations
store.setMode(CacheMode.DESTRUCTIVE);
```

## Best Practices

1. Use ACCELERATED mode for general operations
2. Switch to OFFLINE mode when network connectivity is poor
3. Use ONLINE mode when real-time search results are critical
4. Limit use of DESTRUCTIVE mode to specific administrative tasks
5. Always handle MessagingException appropriately based on the current mode