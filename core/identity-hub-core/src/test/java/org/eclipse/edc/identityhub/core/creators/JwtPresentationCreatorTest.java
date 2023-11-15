/*
 *  Copyright (c) 2023 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

package org.eclipse.edc.identityhub.core.creators;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.edc.iam.did.crypto.key.EcPrivateKeyWrapper;
import org.eclipse.edc.identitytrust.model.CredentialFormat;
import org.eclipse.edc.identitytrust.model.VerifiableCredentialContainer;
import org.eclipse.edc.spi.security.PrivateKeyResolver;
import org.eclipse.edc.verifiablecredentials.jwt.JwtCreationUtils;
import org.eclipse.edc.verifiablecredentials.jwt.TestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtPresentationCreatorTest extends PresentationCreatorTest {
    public static final List<String> REQUIRED_CLAIMS = asList("aud", "exp", "iat", "vp");
    private final Map<String, Object> audClaim = Map.of("aud", "did:web:test-audience");
    private final PrivateKeyResolver resolverMock = mock();
    private JwtPresentationCreator creator;

    @BeforeEach
    void setup() {
        var vpSigningKey = createKey(Curve.P_384, "vp-key");
        when(resolverMock.resolvePrivateKey(eq(KEY_ID), any())).thenReturn(new EcPrivateKeyWrapper(vpSigningKey));
        creator = new JwtPresentationCreator(resolverMock, Clock.systemUTC(), "did:web:test-issuer");
    }

    @Test
    @DisplayName("Verify succesful creation of a JWT_VP")
    void createPresentation_success() {
        var vcSigningKey = createKey(Curve.P_256, TestConstants.CENTRAL_ISSUER_KEY_ID);
        var jwtVc = JwtCreationUtils.createJwt(vcSigningKey, TestConstants.CENTRAL_ISSUER_DID, "degreeSub", TestConstants.VP_HOLDER_ID, Map.of("vc", TestConstants.VC_CONTENT_DEGREE_EXAMPLE));
        var vcc = new VerifiableCredentialContainer(jwtVc, CredentialFormat.JWT, createDummyCredential());

        var vpJwt = creator.createPresentation(List.of(vcc), KEY_ID, audClaim);
        assertThat(vpJwt).isNotNull();
        assertThatNoException().isThrownBy(() -> SignedJWT.parse(vpJwt));
        var claims = parseJwt(vpJwt);

        REQUIRED_CLAIMS.forEach(claim -> assertThat(claims.getClaim(claim)).describedAs("Claim '%s' cannot be null", claim)
                .isNotNull());

    }

    @Test
    @DisplayName("Should create a JWT_VP with VCs of different formats")
    void create_whenVcsNotSameFormat() {
        var vcSigningKey = createKey(Curve.P_256, TestConstants.CENTRAL_ISSUER_KEY_ID);
        var jwtVc = JwtCreationUtils.createJwt(vcSigningKey, TestConstants.CENTRAL_ISSUER_DID, "degreeSub", TestConstants.VP_HOLDER_ID, Map.of("vc", TestConstants.VC_CONTENT_DEGREE_EXAMPLE));
        var ldpVc = TestData.LDP_VC_WITH_PROOF;

        var vc1 = new VerifiableCredentialContainer(jwtVc, CredentialFormat.JWT, createDummyCredential());
        var vc2 = new VerifiableCredentialContainer(ldpVc, CredentialFormat.JSON_LD, createDummyCredential());

        var vpJwt = creator.createPresentation(List.of(vc1, vc2), KEY_ID, audClaim);
        assertThat(vpJwt).isNotNull();
        assertThatNoException().isThrownBy(() -> SignedJWT.parse(vpJwt));

        var claims = parseJwt(vpJwt);

        REQUIRED_CLAIMS.forEach(claim -> assertThat(claims.getClaim(claim)).describedAs("Claim '%s' cannot be null", claim)
                .isNotNull());
    }

    @Test
    @DisplayName("Should create a valid VP with no credential")
    void create_whenVcsEmpty_shouldReturnEmptyVp() {
        var vpJwt = creator.createPresentation(List.of(), KEY_ID, audClaim);
        assertThat(vpJwt).isNotNull();
        assertThatNoException().isThrownBy(() -> SignedJWT.parse(vpJwt));

        var claims = parseJwt(vpJwt);

        REQUIRED_CLAIMS
                .forEach(claim -> assertThat(claims.getClaim(claim)).describedAs("Claim '%s' cannot be null", claim)
                        .isNotNull());
    }

    @Test
    @DisplayName("Should throw an exception if no key is found for a key-id")
    void create_whenKeyNotFound() {
        var vcc = new VerifiableCredentialContainer("foobar", CredentialFormat.JWT, createDummyCredential());
        assertThatThrownBy(() -> creator.createPresentation(List.of(vcc), "not-exist", audClaim)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should throw an exception if the required additional data is missing")
    @Override
    void create_whenRequiredAdditionalDataMissing_throwsIllegalArgumentException() {
        var vcc = new VerifiableCredentialContainer("foobar", CredentialFormat.JWT, createDummyCredential());
        assertThatThrownBy(() -> creator.createPresentation(List.of(vcc), KEY_ID))
                .describedAs("Expected exception when no additional data provided")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> creator.createPresentation(List.of(vcc), KEY_ID, Map.of()))
                .describedAs("Expected exception when additional data does not contain expected value ('aud')")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should return an empty JWT when no credentials are passed")
    void create_whenEmptyList() {

        var vpJwt = creator.createPresentation(List.of(), KEY_ID, audClaim);
        assertThat(vpJwt).isNotNull();
        assertThatNoException().isThrownBy(() -> SignedJWT.parse(vpJwt));
        var claims = parseJwt(vpJwt);

        REQUIRED_CLAIMS.forEach(claim -> assertThat(claims.getClaim(claim)).describedAs("Claim '%s' cannot be null", claim)
                .isNotNull());
        assertThat(claims.getClaim("vp")).isNotNull();
    }

    private JWTClaimsSet parseJwt(String vpJwt) {
        try {
            return SignedJWT.parse(vpJwt).getJWTClaimsSet();
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }


}