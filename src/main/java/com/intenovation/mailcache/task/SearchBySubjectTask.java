package com.intenovation.mailcache.task;

import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;

/**
 * Task that searches for messages by subject and applies a message task to the results.
 */
public class SearchBySubjectTask extends ApplyMessageTaskToSearch {
    
    public SearchBySubjectTask(AbstractMessageTask messageTask) {
        super("search-subject-" + messageTask.getName(), 
              "Search for messages with a specific subject and apply " + messageTask.getName(), 
              messageTask);
    }
    
    @Override
    public String getParameterName() {
        return "Subject Text";
    }
    
    @Override
    public String getParameterDescription() {
        return "Text to search for in message subjects";
    }
    
    @Override
    protected SearchTerm createSearchTerm(String searchString) {
        return new SubjectTerm(searchString);
    }
}