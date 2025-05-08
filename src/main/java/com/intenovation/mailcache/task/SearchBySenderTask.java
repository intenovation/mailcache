package com.intenovation.mailcache.task;

import javax.mail.search.FromStringTerm;
import javax.mail.search.SearchTerm;

/**
 * Task that searches for messages by sender and applies a message task to the results.
 */
public class SearchBySenderTask extends ApplyMessageTaskToSearch {
    
    public SearchBySenderTask(AbstractMessageTask messageTask) {
        super("search-sender-" + messageTask.getName(), 
              "Search for messages from a specific sender and apply " + messageTask.getName(), 
              messageTask);
    }
    
    @Override
    public String getParameterName() {
        return "Sender Email";
    }
    
    @Override
    public String getParameterDescription() {
        return "Full or partial email address to search for in the From field";
    }
    
    @Override
    protected SearchTerm createSearchTerm(String searchString) {
        return new FromStringTerm(searchString);
    }
}