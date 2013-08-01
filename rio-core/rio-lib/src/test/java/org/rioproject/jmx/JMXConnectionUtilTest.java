/*
 * Copyright to the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rioproject.jmx;

import junit.framework.Assert;
import org.junit.Test;
import org.rioproject.config.Constants;

/**
 * Tests for th {@code JMXConnectionUtil}
 *
 * @author Dennis Reedy
 */
public class JMXConnectionUtilTest {
    @Test
    public void testCreateJMXConnection() throws Exception {
        JMXConnectionUtil.createJMXConnection();
        Assert.assertNotNull(System.getProperty(Constants.JMX_SERVICE_URL));
    }

    @Test
    public void testGetPlatformMBeanAgentID() throws Exception {
        String agentID = JMXConnectionUtil.getPlatformMBeanServerAgentId();
        Assert.assertNotNull(agentID);
    }
}
