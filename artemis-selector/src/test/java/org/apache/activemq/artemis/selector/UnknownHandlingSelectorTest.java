/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.selector;

import org.apache.activemq.artemis.selector.filter.BooleanExpression;
import org.apache.activemq.artemis.selector.filter.FilterException;
import org.apache.activemq.artemis.selector.impl.SelectorParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class UnknownHandlingSelectorTest {

   private MockMessage message;

   @BeforeEach
   public void setUp() throws Exception {
      message = new MockMessage();
      message.setDestination("FOO.BAR");
      message.setJMSType("selector-test");
      message.setJMSMessageID("connection:1:1:1:1");
      message.setBooleanProperty("trueProp", true);
      message.setBooleanProperty("falseProp", false);
      message.setObjectProperty("nullProp", null);
   }

   /*
    * | NOT
    * +------+------
    * |  T   |   F
    * |  F   |   T
    * |  U   |   U
    * +------+-------
    */
   @Test
   public void notEvaluation() throws Exception {
      assertSelector("not(trueProp)", false);
      assertSelector("not(falseProp)", true);
      assertSelector("not(unknownProp)", false);
   }

   /*
    * | AND  |   T   |   F   |   U
    * +------+-------+-------+-------
    * |  T   |   T   |   F   |   U
    * |  F   |   F   |   F   |   F
    * |  U   |   U   |   F   |   U
    * +------+-------+-------+-------
    */
   @Test
   public void andEvaluation() throws Exception {
      assertSelectorEvaluatesToTrue("trueProp AND trueProp");
      assertSelectorEvaluatesToFalse("trueProp AND falseProp");
      assertSelectorEvaluatesToFalse("falseProp AND trueProp");
      assertSelectorEvaluatesToFalse("falseProp AND falseProp");
      assertSelectorEvaluatesToFalse("falseProp AND unknownProp");
      assertSelectorEvaluatesToFalse("unknownProp AND falseProp");
      assertSelectorEvaluatesToUnknown("trueProp AND unknownProp");
      assertSelectorEvaluatesToUnknown("unknownProp AND trueProp");
      assertSelectorEvaluatesToUnknown("unknownProp AND unknownProp");
   }

   /*
    * | OR   |   T   |   F   |   U
    * +------+-------+-------+--------
    * |  T   |   T   |   T   |   T
    * |  F   |   T   |   F   |   U
    * |  U   |   T   |   U   |   U
    * +------+-------+-------+-------
    */
   @Test
   public void orEvaluation() throws Exception {
      assertSelectorEvaluatesToTrue("trueProp OR trueProp");
      assertSelectorEvaluatesToTrue("trueProp OR falseProp");
      assertSelectorEvaluatesToTrue("falseProp OR trueProp");
      assertSelectorEvaluatesToTrue("trueProp OR unknownProp");
      assertSelectorEvaluatesToTrue("unknownProp OR trueProp");
      assertSelectorEvaluatesToFalse("falseProp OR falseProp");
      assertSelectorEvaluatesToUnknown("falseProp OR unknownProp");
      assertSelectorEvaluatesToUnknown("unknownProp OR falseProp");
      assertSelectorEvaluatesToUnknown("unknownProp OR unknownProp");
   }

   @Test
   public void comparisonWithUnknownShouldEvaluateToUnknown() throws Exception {
      assertSelectorEvaluatesToUnknown("unknownProp = 0");
      assertSelectorEvaluatesToUnknown("unknownProp > 0");
      assertSelectorEvaluatesToUnknown("unknownProp >= 0");
      assertSelectorEvaluatesToUnknown("unknownProp < 0");
      assertSelectorEvaluatesToUnknown("unknownProp <= 0");
      assertSelectorEvaluatesToUnknown("unknownProp <> 0");
      assertSelectorEvaluatesToUnknown("unknownProp LIKE 'zero'");
      assertSelectorEvaluatesToUnknown("unknownProp NOT LIKE 'zero'");
      assertSelectorEvaluatesToUnknown("unknownProp IN ('zero')");
      assertSelectorEvaluatesToUnknown("unknownProp NOT IN ('zero')");
      assertSelectorEvaluatesToUnknown("unknownProp BETWEEN 1 AND 2");
      assertSelectorEvaluatesToUnknown("unknownProp NOT BETWEEN 1 AND 2");
   }

   @Test
   public void comparisonWithNullPropShouldEvaluateToUnknown() throws Exception {
      assertSelectorEvaluatesToUnknown("nullProp = 0");
      assertSelectorEvaluatesToUnknown("nullProp > 0");
      assertSelectorEvaluatesToUnknown("nullProp >= 0");
      assertSelectorEvaluatesToUnknown("nullProp < 0");
      assertSelectorEvaluatesToUnknown("nullProp <= 0");
      assertSelectorEvaluatesToUnknown("nullProp <> 0");
      assertSelectorEvaluatesToUnknown("nullProp LIKE 'zero'");
      assertSelectorEvaluatesToUnknown("nullProp NOT LIKE 'zero'");
      assertSelectorEvaluatesToUnknown("nullProp IN ('zero')");
      assertSelectorEvaluatesToUnknown("nullProp NOT IN ('zero')");
      assertSelectorEvaluatesToUnknown("nullProp BETWEEN 1 AND 2");
      assertSelectorEvaluatesToUnknown("nullProp NOT BETWEEN 1 AND 2");
   }

   @Test
   public void isNullIsNotNull() throws Exception {
      assertSelectorEvaluatesToTrue("unknownProp IS NULL");
      assertSelectorEvaluatesToTrue("nullProp IS NULL");
      assertSelectorEvaluatesToFalse("trueProp IS NULL");
      assertSelectorEvaluatesToFalse("unknownProp IS NOT NULL");
      assertSelectorEvaluatesToFalse("nullProp IS NOT NULL");
      assertSelectorEvaluatesToTrue("trueProp IS NOT NULL");
   }

   @Test
   public void arithmeticWithNull() throws Exception {
      assertSelectorEvaluatesToUnknown("-unknownProp = 0");
      assertSelectorEvaluatesToUnknown("+unknownProp = 0");
      assertSelectorEvaluatesToUnknown("unknownProp * 2 = 0");
      assertSelectorEvaluatesToUnknown("unknownProp / 2 = 0");
      assertSelectorEvaluatesToUnknown("unknownProp + 2 = 0");
      assertSelectorEvaluatesToUnknown("unknownProp - 2 = 0");
   }

   protected void assertSelectorEvaluatesToUnknown(String selector) throws FilterException {
      assertSelector(selector, false);
      assertSelector(not(selector), false);
   }

   protected void assertSelectorEvaluatesToTrue(String selector) throws FilterException {
      assertSelector(selector, true);
      assertSelector(not(selector), false);
   }

   protected void assertSelectorEvaluatesToFalse(String selector) throws FilterException {
      assertSelector(selector, false);
      assertSelector(not(selector), true);
   }

   protected void assertSelector(String text, boolean expected) throws FilterException {
      BooleanExpression selector = SelectorParser.parse(text);
      assertNotNull(selector, "Created a valid selector");
      boolean value = selector.matches(message);
      assertEquals(expected, value, "Selector for: " + text);
   }

   private static String not(String selector) {
      return "not(" + selector + ")";
   }
}
