/*
 * Copyright (c) 2020-2022 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository at
 * https://github.com/hyperledger-labs/business-partner-agent
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
package org.hyperledger.bpa.impl.aries.credential;

import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hyperledger.acy_py.generated.model.V20CredIssueProblemReportRequest;
import org.hyperledger.acy_py.generated.model.V20CredIssueRequest;
import org.hyperledger.aries.api.ExchangeVersion;
import org.hyperledger.aries.api.credentials.Credential;
import org.hyperledger.aries.api.exception.AriesException;
import org.hyperledger.aries.api.issue_credential_v1.BaseCredExRecord;
import org.hyperledger.aries.api.issue_credential_v1.CredentialExchangeRole;
import org.hyperledger.aries.api.issue_credential_v1.CredentialExchangeState;
import org.hyperledger.aries.api.issue_credential_v1.V1CredentialExchange;
import org.hyperledger.aries.api.issue_credential_v2.V20CredExRecord;
import org.hyperledger.aries.api.issue_credential_v2.V2IssueIndyCredentialEvent;
import org.hyperledger.bpa.api.exception.EntityNotFoundException;
import org.hyperledger.bpa.api.exception.NetworkException;
import org.hyperledger.bpa.api.exception.WrongApiUsageException;
import org.hyperledger.bpa.api.notification.CredentialProposalEvent;
import org.hyperledger.bpa.config.AcaPyConfig;
import org.hyperledger.bpa.controller.api.issuer.CredEx;
import org.hyperledger.bpa.controller.api.issuer.CredentialOfferRequest;
import org.hyperledger.bpa.impl.aries.jsonld.LDContextHelper;
import org.hyperledger.bpa.persistence.model.BPACredentialExchange;
import org.hyperledger.bpa.persistence.repository.BPACredentialDefinitionRepository;
import org.hyperledger.bpa.persistence.repository.PartnerRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Wraps all credential issuer specific logic that is common for both indy and
 * json-ld credentials.
 */
@Slf4j
@Singleton
public abstract class BaseIssuerManager extends BaseCredentialManager {

    @Inject
    AcaPyConfig acaPyConfig;

    @Inject
    BPACredentialDefinitionRepository credDefRepo;

    @Inject
    PartnerRepository partnerRepo;

    @Inject
    ApplicationEventPublisher eventPublisher;

    // Credential Management - Called By User

    /**
     * Send partner a credential (counter) offer in reference to a proposal (Not to
     * be confused with the automated send-offer flow).
     *
     * @param id           credential exchange id
     * @param counterOffer {@link CredentialOfferRequest}
     * @return {@link CredEx} updated credential exchange, if found
     */
    public CredEx sendCredentialOffer(@NonNull UUID id, @NonNull CredentialOfferRequest counterOffer) {
        BPACredentialExchange credEx = issuerCredExRepo.findById(id).orElseThrow(EntityNotFoundException::new);
        if (!credEx.stateIsProposalReceived()) {
            throw new WrongApiUsageException(msg.getMessage("api.issuer.credential.send.offer.wrong.state",
                    Map.of("state", credEx.getState())));
        }
        Map<String, String> attributes;
        if (counterOffer.acceptAll()) {
            attributes = credEx.proposalAttributesToMap();
        } else {
            attributes = counterOffer.getAttributes();
        }
        try {
            return sendOffer(credEx, attributes,
                    new IdWrapper(counterOffer.getCredDefId(), counterOffer.getSchemaId()));
        } catch (IOException e) {
            throw new NetworkException(msg.getMessage("acapy.unavailable"), e);
        } catch (AriesException e) {
            if (e.getCode() == 400) {
                String message = msg.getMessage("api.issuer.credential.exchange.problem");
                credEx.pushStates(CredentialExchangeState.PROBLEM);
                issuerCredExRepo.updateAfterEventNoRevocationInfo(
                        credEx.getId(), credEx.getState(), credEx.getStateToTimestamp(), message);
                throw new WrongApiUsageException(message);
            }
            throw e;
        }
    }

    public record IdWrapper(String credDefId, String schemaId) {
    }

    /**
     * Indy or json-ld specific send counter offer implementation
     * 
     * @param credEx     {@link BPACredentialExchange}
     * @param attributes proposal or counter offer attributes
     * @param ids        {@link IdWrapper}
     * @return {@link CredEx}
     */
    protected abstract CredEx sendOffer(@NonNull BPACredentialExchange credEx, @NonNull Map<String, String> attributes,
            @NonNull IdWrapper ids) throws IOException;

    /**
     * Issuer declines credential proposal received from holder
     * 
     * @param id      {@link UUID} bpa credential exchange id
     * @param message optional reason
     */
    public void declineCredentialProposal(@NonNull UUID id, @Nullable String message) {
        if (StringUtils.isEmpty(message)) {
            message = msg.getMessage("api.issuer.credential.exchange.declined");
        }
        BPACredentialExchange credEx = getCredentialExchange(id);
        credEx.pushStates(CredentialExchangeState.DECLINED, Instant.now());
        issuerCredExRepo.updateAfterEventNoRevocationInfo(credEx.getId(), credEx.getState(),
                credEx.getStateToTimestamp(),
                message);
        declineCredentialExchange(credEx, message);
    }

    // Credential Management - Called By Event Handler

    public void handleCredentialProposal(@NonNull BaseCredExRecord ex, ExchangeVersion exchangeVersion) {
        partnerRepo.findByConnectionId(ex.getConnectionId()).ifPresent(partner -> {
            BPACredentialExchange.BPACredentialExchangeBuilder b = BPACredentialExchange
                    .builder()
                    .partner(partner)
                    .role(CredentialExchangeRole.ISSUER)
                    .state(ex.getState())
                    .pushStateChange(ex.getState(), Instant.now())
                    .exchangeVersion(exchangeVersion)
                    .credentialExchangeId(ex.getCredentialExchangeId())
                    .threadId(ex.getThreadId())
                    .credentialProposal(resolveProposal(ex));
            // preselecting first match
            credDefRepo.findBySchemaId(resolveSchemaIdFromProposal(ex)).stream().findFirst()
                    .ifPresentOrElse(dbCredDef -> {
                        b.schema(dbCredDef.getSchema()).credDef(dbCredDef);
                        issuerCredExRepo.save(b.build());
                    }, () -> {
                        b.errorMsg(msg.getMessage("api.holder.issuer.has.no.creddef",
                                Map.of("id", Objects.requireNonNullElse(resolveSchemaIdFromProposal(ex), ""))));
                        issuerCredExRepo.save(b.build());
                    });
            fireCredentialProposalEvent();
        });
    }

    /**
     * In v2 (indy and w3c) a holder can decide to skip negotiation and directly
     * start the whole flow with a request. So we check if there is a preceding
     * record if not decline with problem report TODO support v2 credential request
     * without prior negotiation
     *
     * @param ex {@link V20CredExRecord v2CredEx}
     */
    public void handleV2CredentialRequest(@NonNull V20CredExRecord ex) {
        issuerCredExRepo.findByCredentialExchangeId(ex.getCredentialExchangeId()).ifPresentOrElse(db -> {
            try {
                if (Boolean.FALSE.equals(acaPyConfig.getAutoRespondCredentialRequest())) {
                    ac.issueCredentialV2RecordsIssue(ex.getCredentialExchangeId(),
                            V20CredIssueRequest.builder().build());
                }
                db.pushStates(ex.getState(), ex.getUpdatedAt());
                issuerCredExRepo.updateAfterEventNoRevocationInfo(db.getId(),
                        db.getState(), db.getStateToTimestamp(), ex.getErrorMsg());
            } catch (IOException e) {
                log.error(msg.getMessage("acapy.unavailable"));
            }
        }, () -> {
            try {
                ac.issueCredentialV2RecordsProblemReport(ex.getCredentialExchangeId(), V20CredIssueProblemReportRequest
                        .builder()
                        .description(
                                "starting a credential exchange without prior negotiation is not supported by this agent")
                        .build());
                log.warn("Received credential request without existing offer, dropping request");
            } catch (IOException e) {
                log.error(msg.getMessage("acapy.unavailable"));
            }
        });
    }

    /**
     * Handle issue credential v2 state changes
     *
     * @param ex {@link V20CredExRecord}
     */
    public void handleV2CredentialExchange(@NonNull V20CredExRecord ex) {
        issuerCredExRepo.findByCredentialExchangeId(ex.getCredentialExchangeId())
                .ifPresent(bpaEx -> {
                    if (bpaEx.stateIsNotDeclined()) {
                        CredentialExchangeState state = ex.getState();
                        if (StringUtils.isNotEmpty(ex.getErrorMsg())) {
                            state = CredentialExchangeState.PROBLEM;
                        }
                        bpaEx.pushStates(state, ex.getUpdatedAt());
                        issuerCredExRepo.updateAfterEventNoRevocationInfo(bpaEx.getId(),
                                bpaEx.getState(), bpaEx.getStateToTimestamp(), ex.getErrorMsg());
                        if (ex.stateIsCredentialIssued() && ex.autoIssueEnabled()) {
                            if (ex.payloadIsIndy()) {
                                ex.getByFormat().findValuesInIndyCredIssue().ifPresent(
                                        attr -> issuerCredExRepo.updateCredential(bpaEx.getId(),
                                                Credential.builder().attrs(attr).build()));
                            } else {
                                issuerCredExRepo.updateCredential(bpaEx.getId(),
                                        BPACredentialExchange.ExchangePayload.jsonLD(ex.resolveLDCredential()));
                            }
                            // TODO events
                        }
                    }
                });
    }

    /**
     * Handle issue credential v2 revocation info
     *
     * @param revocationInfo {@link V2IssueIndyCredentialEvent}
     */
    public void handleIssueCredentialV2Indy(V2IssueIndyCredentialEvent revocationInfo) {
        // Note: This event contains no role info, so we have to check this here
        // explicitly
        issuerCredExRepo.findByCredentialExchangeId(revocationInfo.getCredExId()).ifPresent(bpaEx -> {
            if (bpaEx.roleIsIssuer() && StringUtils.isNotEmpty(revocationInfo.getRevRegId())) {
                issuerCredExRepo.updateRevocationInfo(bpaEx.getId(), revocationInfo.getRevRegId(),
                        revocationInfo.getCredRevId());
            } else if (bpaEx.roleIsHolder() && StringUtils.isNotEmpty(revocationInfo.getCredIdStored())) {
                issuerCredExRepo.updateReferent(bpaEx.getId(), revocationInfo.getCredIdStored());
                // holder event is missing the credRevId, so get it from aca-py in case the
                // credential is revocable
                if (StringUtils.isNotEmpty(revocationInfo.getRevRegId())) {
                    try {
                        ac.credential(revocationInfo.getCredIdStored()).ifPresent(
                                c -> issuerCredExRepo.updateRevocationInfo(bpaEx.getId(), c.getRevRegId(),
                                        c.getCredRevId()));
                    } catch (IOException e) {
                        log.error(msg.getMessage("acapy.unavailable"));
                    }
                }
            }
        });
    }

    // Helpers

    private BPACredentialExchange.ExchangePayload resolveProposal(@NonNull BaseCredExRecord ex) {
        if (ex instanceof V1CredentialExchange v1Indy) {
            return v1Indy.getCredentialProposalDict() != null
                    ? BPACredentialExchange.ExchangePayload
                            .indy(v1Indy.getCredentialProposalDict().getCredentialProposal())
                    : null;
        } else if (ex instanceof V20CredExRecord v2) {
            return BPACredentialExchange.ExchangePayload.jsonLD(v2.resolveLDCredProposal());
        }
        return null;
    }

    private String resolveSchemaIdFromProposal(@NonNull BaseCredExRecord ex) {
        if (ex instanceof V1CredentialExchange v1Indy) {
            return Objects.requireNonNull(v1Indy.getCredentialProposalDict()).getSchemaId();
        } else if (ex instanceof V20CredExRecord v2) {
            return LDContextHelper.findSchemaId(v2.resolveLDCredProposal());
        }
        return null;
    }

    // Events

    private void fireCredentialProposalEvent() {
        eventPublisher.publishEventAsync(new CredentialProposalEvent());
    }
}
