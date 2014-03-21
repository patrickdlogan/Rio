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
package org.rioproject.config;

import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * test loading properties from rio.env
 */
public class RioPropertiesTest {
    String oldRioHome;

    @Before
    public void setOldRioHome() {
        oldRioHome = System.getProperty("RIO_HOME");
        System.out.println("oldRioHome = ["+oldRioHome+"]");
    }

    @Test
    public void testLoad() throws Exception {
        System.setProperty("RIO_HOME", System.getProperty("user.dir")+"/src/test/resources");
        RioProperties.load();
        String locators = System.getProperty(Constants.LOCATOR_PROPERTY_NAME);
        Assert.assertNotNull(locators);
    }

    @After
    public void restore() {
        if(oldRioHome==null)
            System.clearProperty("RIO_HOME");
        else
            System.setProperty("RIO_HOME", oldRioHome);
    }
}
