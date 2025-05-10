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
public class SearchByYearTask extends ApplyMessageTaskToSearch {
    private static final Logger LOGGER = Logger.getLogger(SearchByYearTask.class.getName());


    public SearchByYearTask(AbstractMessageTask messageTask) {
        super("search-year-"+messageTask.getName(), "Search for emails from a specific year and organize them",messageTask);


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
    

}