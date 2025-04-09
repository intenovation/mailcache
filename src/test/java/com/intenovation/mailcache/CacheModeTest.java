package com.intenovation.mailcache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for CacheMode enum
 */
public class CacheModeTest {
    
    @Test
    public void testEnumValues() {
        // Test that the enum has the expected values
        assertEquals(3, CacheMode.values().length);
        assertNotNull(CacheMode.OFFLINE);
        assertNotNull(CacheMode.ACCELERATED);
        assertNotNull(CacheMode.ONLINE);
    }
    
    @Test
    public void testEnumValuesName() {
        // Test that the enum values have the expected names
        assertEquals("OFFLINE", CacheMode.OFFLINE.name());
        assertEquals("ACCELERATED", CacheMode.ACCELERATED.name());
        assertEquals("ONLINE", CacheMode.ONLINE.name());
    }
    
    @Test
    public void testValueOf() {
        // Test that the valueOf method works correctly
        assertEquals(CacheMode.OFFLINE, CacheMode.valueOf("OFFLINE"));
        assertEquals(CacheMode.ACCELERATED, CacheMode.valueOf("ACCELERATED"));
        assertEquals(CacheMode.ONLINE, CacheMode.valueOf("ONLINE"));
    }
    
    @Test
    public void testIllegalValueOf() {
        // Test that valueOf throws an IllegalArgumentException for an invalid value
        assertThrows(IllegalArgumentException.class, () -> CacheMode.valueOf("INVALID"));
    }
}