/*
 * Copyright (c) 2016 Intel Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.oisp.apiclients.rules;

import org.oisp.apiclients.ApiClientHelper;
import org.oisp.apiclients.CustomRestTemplate;
import org.oisp.apiclients.DashboardConfig;
import org.oisp.apiclients.InvalidDashboardResponseException;
import org.oisp.apiclients.rules.model.ComponentRulesResponse;
import org.oisp.rules.RuleStatus;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Set;
import java.io.Serializable;
import java.io.ObjectInputStream;
import java.io.IOException;



public class DashboardRulesApi implements RulesApi, Serializable {

    private final String url;
    private final String token;
    private final DashboardConfig config;
    private static final String PATH = "/v1/api/";
    private static final String GET_COMPONENTS_RULES_PATH = "components/rules";
    private static final String UPDATE_RULES_PATH = "rules/synchronization_status/Sync";

    private static final String RULE_STATUS_NOT_SYNCHRONIZED = "NotSync";
    private transient RestTemplate template;

    public DashboardRulesApi(DashboardConfig dashboardConfig) {
        this(dashboardConfig, CustomRestTemplate.build(dashboardConfig).getRestTemplate());
    }

    public DashboardRulesApi(DashboardConfig dashboardConfig, RestTemplate restTemplate) {
        token = dashboardConfig.getToken();
        url = dashboardConfig.getUrl() + PATH;
        template = restTemplate;
        config = dashboardConfig;
    }

    @Override
    public List<ComponentRulesResponse> getActiveComponentsRules() throws InvalidDashboardResponseException {
        HttpHeaders headers = ApiClientHelper.getHttpHeaders(getToken());
        HttpEntity req = new HttpEntity<>(createRuleRequest(), headers);

        try {
            ParameterizedTypeReference<List<ComponentRulesResponse>> responseType = new ParameterizedTypeReference<List<ComponentRulesResponse>>() {
            };
            ResponseEntity<List<ComponentRulesResponse>> resp = template.exchange(getEndpoint(GET_COMPONENTS_RULES_PATH), HttpMethod.POST, req, responseType);

            if (resp.getStatusCode() != HttpStatus.OK) {
                throw new InvalidDashboardResponseException("Invalid response on - " + resp.getStatusCode());
            }
            return resp.getBody();
        } catch (RestClientException e) {
            throw new InvalidDashboardResponseException("Unknown dashboard response error.", e);
        }
    }

    @Override
    public void markRulesSynchronized(Set<String> rulesIds) throws InvalidDashboardResponseException {
        HttpHeaders headers = ApiClientHelper.getHttpHeaders(getToken());
        HttpEntity req = new HttpEntity<>(rulesIds, headers);

        try {
            ResponseEntity<Void> resp = template.exchange(getEndpoint(UPDATE_RULES_PATH), HttpMethod.PUT, req, Void.class);

            if (resp.getStatusCode() != HttpStatus.OK) {
                throw new InvalidDashboardResponseException("Invalid response when updating synchronization status - " + resp.getStatusCode());
            }
        } catch (RestClientException e) {
            throw new InvalidDashboardResponseException("Unknown dashboard response error when updating synchronization status.", e);
        }
    }

    private RuleRequest createRuleRequest() {
        return new RuleRequest(RuleStatus.asList(), RULE_STATUS_NOT_SYNCHRONIZED);
    }

    private String getEndpoint(String restMethod) {
        return url + restMethod;
    }

    private String getToken() {
        return token;
    }


    private void readObject(ObjectInputStream o)
            throws IOException, ClassNotFoundException
    {
        o.defaultReadObject();
        CustomRestTemplate.build(this.config).getRestTemplate();
    }


}
