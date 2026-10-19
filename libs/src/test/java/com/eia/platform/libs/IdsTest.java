package com.eia.platform.libs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdsTest {
    @Test
    void newIdIsUuid() {
        assertNotNull(Ids.newId());
        assertEquals(36, Ids.newId().toString().length());
    }
}
