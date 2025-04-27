# MailCache System Architecture

## Overview

The MailCache system provides a robust caching layer for JavaMail that enables efficient email access across various connectivity scenarios while maintaining data integrity and supporting record-keeping requirements. This document outlines the architectural principles, goals, and design guidelines that should govern the development and maintenance of the system.

## Core Goals

1. **Reliable Record Keeping**: Support businesses in maintaining complete records of all communications, particularly invoices and important documents.
2. **Efficient Email Access**: Provide high-performance email access. If there is an issue with network connectivity user has to switch to offline mode.
3. **Data Integrity**: Ensure data consistency between server and cache, with safeguards against data loss.
4. **Flexible Operation Modes**: Support different operational scenarios through distinct operational modes.
5. **Multi-User Environment**: Enable management of multiple email accounts within a single application.

## Fundamental Principles

### Data Management Principles

1. **Data Preservation**
    - Messages are never truly deleted from the local cache
    - All "deleted" items are moved to archive folders
    - This ensures compliance with record-keeping requirements for businesses
    - Enables recovery from accidental deletions

2. **Transactional Operations**
    - Every write or delete operation follows a strict transaction pattern:
        1. First execute the operation on the server
        2. Then update the local cache accordingly
        3. Finally, commit the changes on the server (through connection management if needed)
    - If a step fails, the operation should be rolled back or the error clearly reported
    - This approach ensures data consistency between server and cache

3. **Server-Cache Synchronization**
    - The cache is designed to always be an accurate representation of the server
    - After write operations, we download the updated server version to refresh the cache
    - For operations like message appending, we retrieve the new message to get the correct message ID
    - This ensures the cache accurately represents what exists on the server

4. **Offline Preparation**
    - The entire message and all attachments are downloaded when a message is accessed
    - Optimization: Attachments with the same name aren't re-downloaded (except in REFRESH mode)
    - This approach enables seamless transitions between online and offline modes
    - Users can work efficiently even without network connectivity

5. **Human-Readable Storage**
    - Message folders use year-month-day and subject format for easy browsing
    - Cache structure is designed to be human-navigable through regular file browsers
    - Helps with manual recovery and auditing if needed

### Operational Principles

6. **Server-First Operations**
    - All changes are attempted on the server before updating the local cache
    - This prevents data loss if server operations fail
    - Follows the principle: "Truth is on the server, cache is a copy"

7. **Mode-Appropriate Behavior**
    - Each operation respects the current cache mode's constraints
    - OFFLINE mode: All operations are read-only
    - ACCELERATED mode: Server operations with local fallback
    - ONLINE mode: Server-focused operations with caching for performance
    - REFRESH mode: Always fetches latest from server
    - DESTRUCTIVE mode: Enables delete operations with local archiving

8. **Safe Defaults**
    - Delete operations are restricted to DESTRUCTIVE mode only
    - Write operations throw exceptions in OFFLINE mode
    - Default mode (ACCELERATED) balances performance and data integrity

9. **Comprehensive Error Handling**
    - All server operations include appropriate error handling
    - Graceful fallback to cache when appropriate
    - Clear error reporting when operations cannot proceed

### Design Principles

10. **Cache Structure Integrity**
    - Consistent directory and file naming conventions
    - Proper separation of message content, metadata, and attachments
    - Atomic file operations where possible to prevent corruption

11. **Multi-User Support**
    - Cache structure organized by username for multiple accounts
    - Isolation between user caches for security and performance
    - Central management through the MailCache class

12. **Change Notification**
    - Comprehensive event system for UI updates
    - Events propagate from deepest components (messages) to top-level components (store)
    - UI can register listeners at any level of the hierarchy

## Extended Principles (Additional Recommendations)

### Enhanced Data Management

13. **Cache Consistency**
    - All operations should maintain the internal consistency of the cache
    - File system operations should use atomic patterns where possible
    - Use file locks or temporary files during updates to prevent corruption

14. **Fault Tolerance**
    - Gracefully handle connection failures during write operations
    - Create recovery points during complex operations
    - Support automatic retry for failed server operations

15. **Atomic Operations**
    - Complex operations (like moving multiple messages) should be atomic
    - Either all parts succeed or the operation is rolled back
    - Use transaction logs for recovery from interruptions

16. **Idempotent Operations**
    - Design operations to be safely repeatable if interrupted
    - Handle duplicate detection for message operations
    - Support recovery from interrupted operations

17. **Cache Versioning**
    - Support for different cache data formats as the application evolves
    - Version metadata stored with cache structures
    - Upgrade paths for migrating between cache versions

### Performance Considerations

18. **Minimal Download**
    - Only download new or changed content to minimize bandwidth usage
    - Support differential updates for folders
    - Use message headers to detect changes before downloading full content

19. **Memory Efficiency**
    - Use streaming operations for large messages and attachments
    - Implement lazy loading for message content
    - Dispose of resources promptly after use

20. **Thread Safety**
    - Support concurrent access from multiple threads
    - Use thread-safe collections for store maps and listener lists
    - Properly synchronize cache access to prevent corruption

21. **Search Optimization**
    - Index cached messages for faster offline search
    - Support for complex search queries without server connectivity
    - Incremental index updates as messages are added or modified

22. **Smart Synchronization**
    - Prioritize synchronization of recent or important folders
    - Support background synchronization for less critical folders
    - Allow user control over synchronization priorities

### Architectural Patterns

23. **Clean Separation of Concerns**
    - IMAP protocol details separated from caching logic
    - Cache storage implementation separated from cache logic
    - UI notification separated from core operations

24. **Composition over Inheritance**
    - Build complex behavior from simpler components
    - Prefer delegation to deep inheritance hierarchies
    - Use wrapper patterns for adding functionality to existing components

25. **Observer Pattern**
    - Use for change notification (implemented via listeners)
    - Support multiple observers at different levels of the hierarchy
    - Ensure consistent event propagation

26. **Strategy Pattern**
    - Different cache modes implement different strategies
    - Allow runtime switching between strategies
    - Common interface for all strategies

27. **Factory Pattern**
    - For creating appropriate cache components
    - Central factory methods in MailCache class
    - Support for different store implementations

28. **Builder Pattern**
    - For constructing complex cache configurations
    - Fluent interfaces for specifying cache parameters
    - Default configurations for common scenarios

## Implementation Guidelines

### Code Organization

- Package structure should reflect separation of concerns
- Clear naming conventions for all components
- Comprehensive JavaDoc documentation

### Error Handling

- Use specific exception types for different error conditions
- Log all errors with appropriate detail
- Provide recovery mechanisms where possible

### Testing

- Comprehensive unit tests for all components
- Integration tests for server interactions
- Mock server for testing without real IMAP server

### Configuration

- Support for different configuration sources (properties, XML, etc.)
- Runtime reconfiguration where appropriate
- Sensible defaults for all settings

## Deployment Considerations

### Disk Space Management

- Monitoring of cache size
- Options for cache pruning when space is limited
- Support for moving cache to different volumes

### Security

- Secure storage of credentials
- Encryption options for sensitive cached data
- Proper handling of temporary files

### Cross-Platform Support

- Path handling compatible with different operating systems
- Character encoding considerations
- Resource limit considerations

## Conclusion

The MailCache architecture is designed to provide a robust, reliable, and efficient caching layer for JavaMail. By adhering to these principles and guidelines, the system can meet the needs of businesses requiring complete record-keeping while providing excellent performance and flexibility for email operations.

The architecture balances the competing concerns of performance, reliability, and flexibility, with a strong emphasis on data integrity and preservation. This focus on never losing data, particularly important business communications like invoices, distinguishes MailCache from simpler caching solutions.