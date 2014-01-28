/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.webbeans.test.unittests.dependent;

import junit.framework.Assert;

import org.apache.webbeans.newtests.AbstractUnitTest;
import org.apache.webbeans.test.component.dependent.DependentComponent;
import org.apache.webbeans.test.component.dependent.DependentOwnerComponent;
import org.apache.webbeans.test.component.dependent.circular.DependentA;
import org.apache.webbeans.test.component.dependent.circular.DependentB;
import org.junit.Test;

public class DependentComponentTest extends AbstractUnitTest
{
    @Test
    public void testDependent()
    {
        startContainer(DependentComponent.class, DependentOwnerComponent.class);

        DependentOwnerComponent comp = getInstance(DependentOwnerComponent.class);

        DependentComponent dc = comp.getDependent();

        Assert.assertNotNull(dc);
    }

    @Test
    public void testDependentCircular()
    {
        startContainer(DependentA.class, DependentB.class);

        DependentA dependentA = getInstance(DependentA.class);
        Assert.assertNotNull(dependentA);
        Assert.assertNotNull(dependentA.getDependentB());
    }

}
