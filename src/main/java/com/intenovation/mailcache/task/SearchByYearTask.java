package com.intenovation.mailcache.task;

import com.intenovation.appfw.task.ProgressStatusCallback;
import com.intenovation.mailcache.CachedMessage;
import com.intenovation.mailcache.task.AbstractSearchTask;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.search.AndTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SentDateTerm;
import java.util.Calendar;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Task that searches for emails from a specific year and organizes them.
 */
public class SearchByYearTask extends AbstractSearchTask {
    private static final Logger LOGGER = Logger.getLogger(SearchByYearTask.class.getName());

    private final AbstractMessageTask messageTask;
    
    public SearchByYearTask(AbstractMessageTask messageTask) {
        super("search-year-"+messageTask.getName(), "Search for emails from a specific year and organize them");
        this.messageTask = messageTask;

    }
    
    @Override
    public String getParameterName() {
        return "Year";
    }
    
    @Override
    public String getParameterDescription() {
        return "Year to search for (e.g., 2024)";
    }
    
    @Override
    protected SearchTerm createSearchTerm(String yearString) {
        try {
            int year = Integer.parseInt(yearString);
            
            // Create a date search term for the specified year
            Calendar startCal = Calendar.getInstance();
            startCal.set(year, Calendar.JANUARY, 1, 0, 0, 0);
            startCal.set(Calendar.MILLISECOND, 0);

            Calendar endCal = Calendar.getInstance();
            endCal.set(year, Calendar.DECEMBER, 31, 23, 59, 59);
            endCal.set(Calendar.MILLISECOND, 999);

            return new AndTerm(
                new SentDateTerm(ComparisonTerm.GE, startCal.getTime()),
                new SentDateTerm(ComparisonTerm.LE, endCal.getTime())
            );
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid year format. Please enter a valid year (e.g., 2024)");
        }
    }
    
    @Override
    protected String processSearchResults(ProgressStatusCallback callback, Message[] messages, String searchTerm)
            throws InterruptedException, MessagingException {
        // Apply organize task to each message found
        int totalMessages = messages.length;
        if (totalMessages == 0) {
            return "No messages found from year: " + searchTerm;
        }
        
        LOGGER.info("Found " + totalMessages + " messages from year " + searchTerm);
        callback.update(20, "Found " + totalMessages + " messages from year " + searchTerm);
        
        int successCount = 0;
        int errorCount = 0;
        
        for (int i = 0; i < totalMessages; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Task cancelled");
            }
            
            Message message = messages[i];
            int progress = 20 + ((i * 80) / totalMessages);
            
            try {
                callback.update(progress, "Organizing message " + (i + 1) + "/" + totalMessages);
                
                if (message instanceof CachedMessage) {
                    CachedMessage cachedMessage = (CachedMessage) message;
                    String result = messageTask.executeOnMessage(callback, cachedMessage);
                    if (result.startsWith("Successfully")) {
                        successCount++;
                    } else {
                        errorCount++;
                    }
                } else {
                    errorCount++;
                    LOGGER.warning("Message is not a CachedMessage: " + message);
                }
            } catch (Exception e) {
                errorCount++;
                LOGGER.log(Level.WARNING, "Error organizing message: " + e.getMessage(), e);
            }
        }
        
        callback.update(100, "Completed organizing " + successCount + " messages");
        
        return "Processed " + totalMessages + " messages from year " + searchTerm + 
               ". Organized: " + successCount + ", Errors: " + errorCount;
    }
}