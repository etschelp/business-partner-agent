/**
 * Copyright (c) 2020 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository at
 * https://github.com/hyperledger-labs/organizational-agent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hyperledger.oa.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.inject.Inject;

import org.hyperledger.oa.core.RegisteredWebhook;
import org.hyperledger.oa.core.RegisteredWebhook.RegisteredWebhookMessage;
import org.hyperledger.oa.impl.WebhookService;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Manages webhooks
 *
 */
@Controller("/api/webhook")
@Tag(name = "Webhook")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.IO)
public class WebhookController {

    @Inject
    WebhookService ws;

    /**
     * List registered webhooks
     * 
     * @return list of {@link RegisteredWebhookMessage}
     */
    @Get
    public HttpResponse<List<RegisteredWebhookMessage>> listRegisteredWebhooks() {
        return HttpResponse.ok(ws.listRegisteredWebhooks());
    }

    /**
     * Register a new webhook
     * 
     * @param request {@link RegisteredWebhook}
     * @return {@link RegisteredWebhookMessage}
     */
    @Post
    public HttpResponse<RegisteredWebhookMessage> registerWebhook(@Body RegisteredWebhook request) {
        return HttpResponse.ok(ws.registerWebhook(request));
    }

    /**
     * Update a registered webhook
     * 
     * @param id      the webhook's id
     * @param request {@link RegisteredWebhook}
     * @return {@link RegisteredWebhookMessage}
     */
    @Put("/{id}")
    public HttpResponse<RegisteredWebhookMessage> updateWebhook(@PathVariable String id,
            @Body RegisteredWebhook request) {
        final Optional<RegisteredWebhookMessage> updated = ws.updateRegisteredWebhook(UUID.fromString(id), request);
        if (updated.isPresent()) {
            return HttpResponse.ok(updated.get());
        }
        return HttpResponse.notFound();
    }

    /**
     * Delete a registered webhook
     * 
     * @param id the webhook's id
     * @return always OK
     */
    @Delete("/{id}")
    public HttpResponse<Void> deleteWebhook(@PathVariable String id) {
        ws.deleteRegisteredWebhook(UUID.fromString(id));
        return HttpResponse.ok();
    }
}