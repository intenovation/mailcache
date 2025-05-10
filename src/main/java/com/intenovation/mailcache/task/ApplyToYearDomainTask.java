package com.intenovation.mailcache.task;

import com.intenovation.appfw.config.ConfigItemType;
import com.intenovation.appfw.config.StandardConfigItemTypes;
import com.intenovation.appfw.task.BackgroundTask;
import com.intenovation.appfw.task.ProgressStatusCallback;


import java.util.logging.Logger;

/**
 * Task that takes a year/domain filter parameter and delegates to the ProcessAllEmailsTask.
 * This provides more user-friendly parameter handling for the command line.
 */
public class ApplyToYearDomainTask extends BackgroundTask {
    private static final Logger LOGGER = Logger.getLogger(ApplyToYearDomainTask.class.getName());
    
    private final boolean newestFirst;

    private final AbstractMessageTask messageTask;
    
    /**
     * Create a new instance of the task.
     * 
     * @param newestFirst Whether to process newest emails first
     */
    public ApplyToYearDomainTask(boolean newestFirst, AbstractMessageTask messageTask) {
        super(
            newestFirst ? "apply-newest-"+messageTask.getName() : "apply-oldest-"+messageTask.getName(),
            newestFirst ? "Process emails matching year/domain filter (newest first)" : 
                        "Process emails matching year/domain filter"
        );
        this.newestFirst = newestFirst;

        this.messageTask = messageTask;
    }
    
    @Override
    public ConfigItemType getParameterType() {
        return StandardConfigItemTypes.TEXT;
    }
    
    @Override
    public String getParameterName() {
        return "Filter";
    }
    
    @Override
    public String getParameterDescription() {
        return "Filter in format: YEAR/DOMAIN (e.g., 2024/example.com) - either part can be omitted";
    }
    
    @Override
    public String execute(ProgressStatusCallback callback, Object parameter) 
            throws InterruptedException {
        
        String filterParam = "";
        if (parameter instanceof String) {
            filterParam = (String) parameter;
        }
        
        LOGGER.info("Executing filter task with parameter: " + filterParam);
        
        // Validate parameter format
        if (!filterParam.isEmpty() && !filterParam.contains("/") && !isNumeric(filterParam)) {
            // If it's just a domain without a year, prefix with "/" to match expected format
            filterParam = "/" + filterParam;
            LOGGER.info("Adjusted parameter to: " + filterParam);
        }
        
        // Delegate to the processAllEmailsTask
        return messageTask.execute(callback, filterParam);
    }
    
    /**
     * Check if a string is numeric (to determine if it's a year)
     */
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}