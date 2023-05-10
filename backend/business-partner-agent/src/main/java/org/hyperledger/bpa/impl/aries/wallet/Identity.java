/*
 * Copyright (c) 2020-2023 - for information on the respective copyright owner
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
package org.hyperledger.bpa.impl.aries.wallet;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.util.CollectionUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hyperledger.acy_py.generated.model.DID;
import org.hyperledger.acy_py.generated.model.DIDCreate;
import org.hyperledger.acy_py.generated.model.DIDCreateOptions;
import org.hyperledger.aries.AriesClient;
import org.hyperledger.aries.api.resolver.DIDDocument;
import org.hyperledger.aries.api.wallet.DefaultDidMethod;
import org.hyperledger.aries.api.wallet.ListWalletDidFilter;
import org.hyperledger.bpa.api.ApiConstants;
import org.hyperledger.bpa.api.exception.NetworkException;
import org.hyperledger.bpa.client.CachingAriesClient;
import org.hyperledger.bpa.client.DidDocClient;
import org.hyperledger.bpa.impl.util.AriesStringUtil;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
@Singleton
public class Identity {

    @Value("${bpa.host}")
    String host;

    @Value("${bpa.web.only}")
    boolean webOnly;

    @Getter
    @Value("${bpa.did.prefix}")
    String didPrefix;

    @Inject
    @Setter
    AriesClient acaPy;

    @Inject
    @Setter
    CachingAriesClient acaCache;

    @Inject
    DidDocClient ur;

    public @io.micronaut.core.annotation.NonNull String getMyDid() {
        String myDid = null;
        if (webOnly) {
            myDid = ApiConstants.DID_METHOD_WEB + host;
        } else {
            try {
                Optional<DID> walletDid = acaCache.walletDidPublic();
                if (walletDid.isPresent()) {
                    myDid = "did:" + walletDid.get().getMethod() + ":" + walletDid.get().getDid();
                }
            } catch (IOException e) {
                log.error("aca-py not reachable", e);
            }
        }
        if (StringUtils.isEmpty(myDid)) {
            throw new IllegalStateException("aca-py has no public did configured");
        }
        return myDid;
    }

    public String getMyKeyId(String myDid) {
        String myKeyId = "no-public-did";
        if (myDid != null) {
            if (webOnly) {
                myKeyId = myDid + ApiConstants.DEFAULT_KEY_ID;
            } else {
                final Optional<DIDDocument> didDoc = ur.getDidDocument(myDid);
                if (didDoc.isPresent()) {
                    Optional<DIDDocument.VerificationMethod> vm = didDoc.get().getVerificationMethod().stream()
                            .filter(k -> ApiConstants.DEFAULT_VERIFICATION_KEY_TYPE.equals(k.getType())).findFirst();
                    if (vm.isPresent()) {
                        myKeyId = vm.get().getId();
                    }
                }
            }
        }
        return myKeyId;
    }

    public Optional<String> getVerkey() {
        Optional<String> verkey = Optional.empty();
        try {
            Optional<DID> walletDid = acaCache.walletDidPublic();
            if (walletDid.isEmpty()) {
                log.warn("No public did available, falling back to local did. VP can only be validated locally.");
                final Optional<List<DID>> walletDids = acaPy.walletDid(ListWalletDidFilter
                        .builder()
                        .keyType(DID.KeyTypeEnum.ED25519)
                        .method(DefaultDidMethod.SOV.getMethod())
                        .build());
                if (walletDids.isPresent() && !walletDids.get().isEmpty()) {
                    walletDid = Optional.of(walletDids.get().get(0));
                } else {
                    walletDid = acaPy
                            .walletDidCreate(DIDCreate.builder().method(DefaultDidMethod.SOV.getMethod()).build());
                }
            }
            if (walletDid.isPresent()) {
                verkey = Optional.of(walletDid.get().getVerkey());
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new NetworkException("aca-py not available");
        }
        return verkey;
    }

    public boolean isSchemaCreator(@NonNull String schemaId, @NonNull String did) {
        // we can place logic in here that will deal with different did formats later
        final String schemaCreator = AriesStringUtil.schemaGetCreator(schemaId);
        String creatorDid = did;
        if (StringUtils.startsWith(did, didPrefix)) {
            creatorDid = AriesStringUtil.getLastSegment(did);
        }
        return StringUtils.equals(schemaCreator, creatorDid);
    }

    public boolean isMySchema(@NonNull String schemaId) {
        String myDid = this.getMyDid();
        if (StringUtils.isNotEmpty(myDid)) {
            return this.isSchemaCreator(schemaId, myDid);
        }
        return false;
    }

    public String getDidKey() {
        try {
            Optional<List<DID>> didKey = acaPy.walletDid(ListWalletDidFilter
                    .builder()
                    .keyType(DID.KeyTypeEnum.BLS12381G2)
                    .method(DefaultDidMethod.KEY.getMethod())
                    .build());
            if (didKey.isEmpty() || CollectionUtils.isEmpty(didKey.get())) {
                DID did = acaPy.walletDidCreate(DIDCreate
                        .builder()
                        .method(DefaultDidMethod.KEY.getMethod())
                        .options(DIDCreateOptions.builder()
                                .keyType(DIDCreateOptions.KeyTypeEnum.BLS12381G2)
                                .build())
                        .build())
                        .orElseThrow();
                return did.getDid();
            } else {
                return didKey.get().get(0).getDid();
            }
        } catch (IOException e) {
            log.error("");
        }
        return null;
    }

}
