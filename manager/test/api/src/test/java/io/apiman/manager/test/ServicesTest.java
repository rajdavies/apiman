/*
 * Copyright 2014 JBoss Inc
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
package io.apiman.manager.test;

import io.apiman.manager.test.junit.RestTestGatewayLog;
import io.apiman.manager.test.junit.RestTestPlan;
import io.apiman.manager.test.junit.RestTestPublishPayload;
import io.apiman.manager.test.junit.RestTester;

import org.junit.runner.RunWith;

/**
 * Runs the "services" test plan.
 *
 * @author eric.wittmann@redhat.com
 */
@RunWith(RestTester.class)
@RestTestPlan("test-plans/services-testPlan.xml")
@RestTestGatewayLog(
        "GET:/mock-gateway/system/status\n" +
        "PUT:/mock-gateway/services\n"
)
@RestTestPublishPayload({
    "",
    "{\"publicService\":false,\"organizationId\":\"Organization1\",\"serviceId\":\"Service1\",\"version\":\"1.0\",\"endpointType\":\"rest\",\"endpoint\":\"http://localhost:8080/ping\",\"endpointProperties\":{\"foo\":\"foo-value\",\"bar\":\"bar-value\"},\"servicePolicies\":[]}"
})
public class ServicesTest {

}
