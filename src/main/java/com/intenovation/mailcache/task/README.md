# MailCache Task Package

This package contains a collection of background tasks for the MailCache system that provide operations for mail folder and message management, search functionality, and system maintenance.

## Overview

The task package provides a hierarchy of classes for operating on mail folders, messages, and performing mail-related operations. These tasks can be executed from the MailCache CLI application or integrated into other applications using the MailCache API.

## Task Hierarchy

The task package follows a consistent inheritance hierarchy:

### Base Task Classes

- **BackgroundTask** - The common ancestor from the appfw framework
    - **AbstractFolderTask** - Base class for tasks that operate on mail folders
    - **AbstractMessageTask** - Base class for tasks that operate on individual messages
    - **AbstractSearchTask** - Base class for tasks that perform mail searches

### Specialized Task Categories

#### Folder Tasks
Tasks that operate on mail folders:
- **ListFoldersTask** - Lists all folders and subfolders in a mail store
- **SynchronizeFolderTask** - Synchronizes a folder with the server

#### Message Tasks
Tasks that operate on individual messages:
- **ReadMessageTask** - Reads basic message information (subject, sender, date)

#### Search Tasks
Tasks that search for messages matching specific criteria:
- **SearchBySenderTask** - Searches for messages from a specific sender
- **SearchBySubjectTask** - Searches for messages with a specific subject
- **SearchByYearTask** - Searches for messages from a specific year

#### Composite Tasks
Tasks that combine multiple operations:
- **ApplyMessageTaskToFolder** - Applies a message task to all messages in a folder
- **ApplyMessageTaskToSearch** - Base class for applying a message task to search results
- **ApplyToYearDomainTask** - Applies a message task to messages filtered by year/domain

#### System Tasks
Tasks that operate on the mail system:
- **ListStoresTask** - Lists all available mail stores

## Usage

Tasks can be executed from the MailCache CLI using the appropriate command:

```bash
mailcache-cli list-folders username:INBOX
mailcache-cli read username:INBOX:123456
mailcache-cli search-sender-read user@example.com
mailcache-cli list-stores
```

Or programmatically:

```java
// Get a folder
CachedFolder folder = store.getFolder("INBOX");

// Create and execute a task
ListFoldersTask task = new ListFoldersTask();
String result = task.executeOnFolder(progressCallback, folder);
```

## Task Parameters

Tasks accept different parameter types depending on their function:

- **Folder tasks** - Require a CachedFolder instance (format: `username:folderPath`)
- **Message tasks** - Require a CachedMessage instance (format: `username:folderPath:messageId`)
- **Search tasks** - Require a search string (e.g., email address, subject text, or year)
- **System tasks** - May not require any parameters

## Progress Reporting

All tasks support progress reporting through the ProgressStatusCallback interface, which provides:

- Progress percentage (0-100)
- Status message updates

## Extending the Task Framework

To create a new task:

1. Identify the appropriate base class (AbstractFolderTask, AbstractMessageTask, AbstractSearchTask, or BackgroundTask)
2. Extend the base class and implement the required methods
3. Register the task in MailCacheApp.getTasks() to make it available in the CLI

Example:

```java
public class MyCustomTask extends AbstractFolderTask {
    
    public MyCustomTask() {
        super("my-custom", "My custom folder task");
    }
    
    @Override
    protected String executeOnFolder(ProgressStatusCallback callback, CachedFolder folder)
            throws InterruptedException {
        // Task implementation
        return "Task completed successfully";
    }
}
```

## Best Practices

1. Follow consistent naming (all task classes should end with "Task")
2. Provide meaningful progress updates via the callback
3. Implement proper error handling and cleanup
4. Support task cancellation by checking Thread.currentThread().isInterrupted()
5. Return human-readable result messages