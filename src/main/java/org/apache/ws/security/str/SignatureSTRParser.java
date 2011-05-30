/**
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

package org.apache.ws.security.str;

import org.apache.ws.security.CustomTokenPrincipal;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSDerivedKeyTokenPrincipal;
import org.apache.ws.security.WSDocInfo;
import org.apache.ws.security.WSPasswordCallback;
import org.apache.ws.security.WSSecurityEngine;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.handler.RequestData;
import org.apache.ws.security.message.token.BinarySecurity;
import org.apache.ws.security.message.token.DerivedKeyToken;
import org.apache.ws.security.message.token.PKIPathSecurity;
import org.apache.ws.security.message.token.SecurityContextToken;
import org.apache.ws.security.message.token.SecurityTokenReference;
import org.apache.ws.security.message.token.UsernameToken;
import org.apache.ws.security.message.token.X509Security;
import org.apache.ws.security.processor.Processor;
import org.apache.ws.security.saml.SAMLKeyInfo;
import org.apache.ws.security.saml.SAMLUtil;
import org.apache.ws.security.saml.ext.AssertionWrapper;
import org.apache.ws.security.saml.ext.OpenSAMLUtil;
import org.apache.ws.security.util.WSSecurityUtil;
import org.w3c.dom.Element;

import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import javax.security.auth.callback.Callback;
import javax.xml.namespace.QName;

/**
 * This implementation of STRParser is for parsing a SecurityTokenReference element, found in the
 * KeyInfo element associated with a Signature element.
 */
public class SignatureSTRParser implements STRParser {
    
    /**
     * The Signature method. This is used when deriving a key to use for verifying the signature.
     */
    public static final String SIGNATURE_METHOD = "signature_method";
    
    /**
     * The secret key length. This is used when deriving a key from a Username token for the
     * non-standard WSE implementation.
     */
    public static final String SECRET_KEY_LENGTH = "secret_key_length";
    
    private X509Certificate[] certs;
    
    private byte[] secretKey;
    
    private PublicKey publicKey;
    
    private Principal principal;
    
    private boolean trustedCredential;
    
    /**
     * Parse a SecurityTokenReference element and extract credentials.
     * 
     * @param strElement The SecurityTokenReference element
     * @param data the RequestData associated with the request
     * @param wsDocInfo The WSDocInfo object to access previous processing results
     * @param parameters A set of implementation-specific parameters
     * @throws WSSecurityException
     */
    public void parseSecurityTokenReference(
        Element strElement,
        RequestData data,
        WSDocInfo wsDocInfo,
        Map<String, Object> parameters
    ) throws WSSecurityException {
        boolean bspCompliant = true;
        Crypto crypto = data.getSigCrypto();
        if (data.getWssConfig() != null) {
            bspCompliant = data.getWssConfig().isWsiBSPCompliant();
        }
        SecurityTokenReference secRef = new SecurityTokenReference(strElement, bspCompliant);
        //
        // Here we get some information about the document that is being
        // processed, in particular the crypto implementation, and already
        // detected BST that may be used later during dereferencing.
        //
        String uri = null;
        if (secRef.containsReference()) {
            uri = secRef.getReference().getURI();
            if (uri.charAt(0) == '#') {
                uri = uri.substring(1);
            }
        } else if (secRef.containsKeyIdentifier()) {
            uri = secRef.getKeyIdentifierValue();
        }
        
        WSSecurityEngineResult result = wsDocInfo.getResult(uri);
        if (result != null) {
            processPreviousResult(result, secRef, data, parameters, bspCompliant);
        } else if (secRef.containsReference()) {
            Element token = 
                secRef.getTokenElement(strElement.getOwnerDocument(), wsDocInfo, data.getCallbackHandler());
            QName el = new QName(token.getNamespaceURI(), token.getLocalName());
            if (el.equals(WSSecurityEngine.BINARY_TOKEN)) {
                certs = getCertificatesTokenReference(secRef, token, crypto, bspCompliant);
            } else if (el.equals(WSSecurityEngine.SAML_TOKEN) 
                || el.equals(WSSecurityEngine.SAML2_TOKEN)) {
                Processor proc = data.getWssConfig().getProcessor(WSSecurityEngine.SAML_TOKEN);
                //
                // Just check to see whether the token was processed or not
                //
                Element processedToken = 
                    secRef.findProcessedTokenElement(
                        strElement.getOwnerDocument(), wsDocInfo, 
                        data.getCallbackHandler(), uri, secRef.getReference().getValueType()
                    );
                AssertionWrapper assertion = null;
                if (processedToken == null) {
                    List<WSSecurityEngineResult> samlResult =
                        proc.handleToken(token, data, wsDocInfo);
                    assertion = 
                        (AssertionWrapper)samlResult.get(0).get(
                            WSSecurityEngineResult.TAG_SAML_ASSERTION
                        );
                } else {
                    assertion = new AssertionWrapper(processedToken);
                    assertion.parseHOKSubject(data, wsDocInfo);
                }
                if (bspCompliant) {
                    BSPEnforcer.checkSamlTokenBSPCompliance(secRef, assertion);
                }
                SAMLKeyInfo keyInfo = assertion.getSubjectKeyInfo();
                X509Certificate[] foundCerts = keyInfo.getCerts();
                if (foundCerts != null) {
                    certs = new X509Certificate[]{foundCerts[0]};
                }
                secretKey = keyInfo.getSecret();
                principal = createPrincipalFromSAML(assertion);
            } else if (el.equals(WSSecurityEngine.ENCRYPTED_KEY)) {
                if (bspCompliant) {
                    BSPEnforcer.checkEncryptedKeyBSPCompliance(secRef);
                }
                Processor proc = data.getWssConfig().getProcessor(WSSecurityEngine.ENCRYPTED_KEY);
                List<WSSecurityEngineResult> encrResult =
                    proc.handleToken(token, data, wsDocInfo);
                secretKey = 
                    (byte[])encrResult.get(0).get(
                                                  WSSecurityEngineResult.TAG_SECRET
                    );
                principal = new CustomTokenPrincipal(token.getAttribute("Id"));
            } else {
                String id = secRef.getReference().getURI();
                secretKey = getSecretKeyFromToken(id, null, data);
                principal = new CustomTokenPrincipal(id);
            }
        } else if (secRef.containsX509Data() || secRef.containsX509IssuerSerial()) {
            X509Certificate[] foundCerts = secRef.getX509IssuerSerial(crypto);
            if (foundCerts != null) {
                certs = new X509Certificate[]{foundCerts[0]};
            }
        } else if (secRef.containsKeyIdentifier()) {
            if (secRef.getKeyIdentifierValueType().equals(SecurityTokenReference.ENC_KEY_SHA1_URI)) {
                if (bspCompliant) {
                    BSPEnforcer.checkEncryptedKeyBSPCompliance(secRef);
                }
                String id = secRef.getKeyIdentifierValue();
                secretKey = 
                    getSecretKeyFromToken(id, SecurityTokenReference.ENC_KEY_SHA1_URI, 
                                          data);
                principal = new CustomTokenPrincipal(id);
            } else if (WSConstants.WSS_SAML_KI_VALUE_TYPE.equals(secRef.getKeyIdentifierValueType())
                || WSConstants.WSS_SAML2_KI_VALUE_TYPE.equals(secRef.getKeyIdentifierValueType())) {
                AssertionWrapper assertion = 
                    SAMLUtil.getAssertionFromKeyIdentifier(
                        secRef, strElement, data, wsDocInfo
                    );
                if (bspCompliant) {
                    BSPEnforcer.checkSamlTokenBSPCompliance(secRef, assertion);
                }
                SAMLKeyInfo samlKi = 
                    SAMLUtil.getCredentialFromSubject(assertion, data,
                                                      wsDocInfo, bspCompliant);
                X509Certificate[] foundCerts = samlKi.getCerts();
                if (foundCerts != null) {
                    certs = new X509Certificate[]{foundCerts[0]};
                }
                secretKey = samlKi.getSecret();
                publicKey = samlKi.getPublicKey();
                principal = createPrincipalFromSAML(assertion);
            } else {
                if (bspCompliant) {
                    BSPEnforcer.checkBinarySecurityBSPCompliance(secRef, null);
                }
                X509Certificate[] foundCerts = secRef.getKeyIdentifier(crypto);
                if (foundCerts != null) {
                    certs = new X509Certificate[]{foundCerts[0]};
                }
            }
        } else {
            throw new WSSecurityException(
                    WSSecurityException.INVALID_SECURITY,
                    "unsupportedKeyInfo", 
                    new Object[]{strElement.toString()}
            );
        }
        
        if (certs != null && principal == null) {
            principal = certs[0].getSubjectX500Principal();
        }
    }
    
    /**
     * Get the X509Certificates associated with this SecurityTokenReference
     * @return the X509Certificates associated with this SecurityTokenReference
     */
    public X509Certificate[] getCertificates() {
        return certs;
    }
    
    /**
     * Get the Principal associated with this SecurityTokenReference
     * @return the Principal associated with this SecurityTokenReference
     */
    public Principal getPrincipal() {
        return principal;
    }
    
    /**
     * Get the PublicKey associated with this SecurityTokenReference
     * @return the PublicKey associated with this SecurityTokenReference
     */
    public PublicKey getPublicKey() {
        return publicKey;
    }
    
    /**
     * Get the Secret Key associated with this SecurityTokenReference
     * @return the Secret Key associated with this SecurityTokenReference
     */
    public byte[] getSecretKey() {
        return secretKey;
    }
    
    /**
     * Get whether the returned credential is already trusted or not. This is currently
     * applicable in the case of a credential extracted from a trusted HOK SAML Assertion,
     * and a BinarySecurityToken that has been processed by a Validator. In these cases,
     * the SignatureProcessor does not need to verify trust on the credential.
     * @return true if trust has already been verified on the returned Credential
     */
    public boolean isTrustedCredential() {
        return trustedCredential;
    }
    /**
     * Extracts the certificate(s) from the Binary Security token reference.
     *
     * @param elem The element containing the binary security token. This is
     *             either X509 certificate(s) or a PKIPath.
     * @return an array of X509 certificates
     * @throws WSSecurityException
     */
    private static X509Certificate[] getCertificatesTokenReference(
        SecurityTokenReference secRef,
        Element elem, 
        Crypto crypto,
        boolean bspCompliant)
        throws WSSecurityException {
        if (crypto == null) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "noSigCryptoFile");
        }
        BinarySecurity token = createSecurityToken(elem);
        if (bspCompliant) {
            BSPEnforcer.checkBinarySecurityBSPCompliance(secRef, token);
        }
        if (token instanceof PKIPathSecurity) {
            return ((PKIPathSecurity) token).getX509Certificates(crypto);
        } else {
            X509Certificate cert = ((X509Security) token).getX509Certificate(crypto);
            return new X509Certificate[]{cert};
        }
    }
    
    /**
     * Checks the <code>element</code> and creates appropriate binary security object.
     *
     * @param element The XML element that contains either a <code>BinarySecurityToken
     *                </code> or a <code>PKIPath</code> element. Other element types a not
     *                supported
     * @return the BinarySecurity object, either a <code>X509Security</code> or a
     *         <code>PKIPathSecurity</code> object.
     * @throws WSSecurityException
     */
    private static BinarySecurity createSecurityToken(Element element) throws WSSecurityException {

        String type = element.getAttribute("ValueType");
        if (X509Security.X509_V3_TYPE.equals(type)) {
            X509Security x509 = new X509Security(element);
            return (BinarySecurity) x509;
        } else if (PKIPathSecurity.getType().equals(type)) {
            PKIPathSecurity pkiPath = new PKIPathSecurity(element);
            return (BinarySecurity) pkiPath;
        }
        throw new WSSecurityException(
            WSSecurityException.UNSUPPORTED_SECURITY_TOKEN,
            "unsupportedBinaryTokenType", 
            new Object[]{type}
        );
    }
    
    /**
     * A method to create a Principal from a SAML Assertion
     * @param assertion An AssertionWrapper object
     * @return A principal
     */
    private Principal createPrincipalFromSAML(
        AssertionWrapper assertion
    ) {
        Principal principal = new CustomTokenPrincipal(assertion.getId());
        ((CustomTokenPrincipal)principal).setTokenObject(assertion);
        String confirmMethod = null;
        List<String> methods = assertion.getConfirmationMethods();
        if (methods != null && methods.size() > 0) {
            confirmMethod = methods.get(0);
        }
        if (OpenSAMLUtil.isMethodHolderOfKey(confirmMethod) && assertion.isSigned()) {
            trustedCredential = true;
        }
        return principal;
    }
    
    /**
     * Get the Secret Key from a CallbackHandler
     * @param id The id of the element
     * @param type The type of the element (can be null)
     * @param cb The CallbackHandler object
     * @return A Secret Key
     * @throws WSSecurityException
     */
    private byte[] getSecretKeyFromToken(
        String id,
        String type,
        RequestData data
    ) throws WSSecurityException {
        if (id.charAt(0) == '#') {
            id = id.substring(1);
        }
        WSPasswordCallback pwcb = 
            new WSPasswordCallback(id, null, type, WSPasswordCallback.SECRET_KEY, data);
        try {
            Callback[] callbacks = new Callback[]{pwcb};
            data.getCallbackHandler().handle(callbacks);
        } catch (Exception e) {
            throw new WSSecurityException(
                WSSecurityException.FAILURE,
                "noPassword", 
                new Object[] {id}, 
                e
            );
        }

        return pwcb.getKey();
    }
    
    /**
     * Process a previous security result
     */
    private void processPreviousResult(
        WSSecurityEngineResult result,
        SecurityTokenReference secRef,
        RequestData data,
        Map<String, Object> parameters,
        boolean bspCompliant
    ) throws WSSecurityException {
        int action = ((Integer)result.get(WSSecurityEngineResult.TAG_ACTION)).intValue();
        if (WSConstants.UT_NOPASSWORD == action || WSConstants.UT == action) {
            if (bspCompliant) {
                BSPEnforcer.checkUsernameTokenBSPCompliance(secRef);
            }
            UsernameToken usernameToken = 
                (UsernameToken)result.get(WSSecurityEngineResult.TAG_USERNAME_TOKEN);

            usernameToken.setRawPassword(data);
            if (usernameToken.isDerivedKey()) {
                secretKey = usernameToken.getDerivedKey();
            } else {
                int keyLength = ((Integer)parameters.get(SECRET_KEY_LENGTH)).intValue();
                secretKey = usernameToken.getSecretKey(keyLength);
            }
            principal = usernameToken.createPrincipal();
        } else if (WSConstants.BST == action) {
            if (bspCompliant) {
                BinarySecurity token = 
                    (BinarySecurity)result.get(
                        WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN
                    );
                BSPEnforcer.checkBinarySecurityBSPCompliance(secRef, token);
            }
            certs = 
                (X509Certificate[])result.get(WSSecurityEngineResult.TAG_X509_CERTIFICATES);
            Boolean validatedToken = 
                (Boolean)result.get(WSSecurityEngineResult.TAG_VALIDATED_TOKEN);
            if (validatedToken.booleanValue()) {
                trustedCredential = true;
            }
        } else if (WSConstants.ENCR == action) {
            if (bspCompliant) {
                BSPEnforcer.checkEncryptedKeyBSPCompliance(secRef);
            }
            secretKey = (byte[])result.get(WSSecurityEngineResult.TAG_SECRET);
            String id = (String)result.get(WSSecurityEngineResult.TAG_ID);
            principal = new CustomTokenPrincipal(id);
        } else if (WSConstants.SCT == action) {
            secretKey = (byte[])result.get(WSSecurityEngineResult.TAG_SECRET);
            SecurityContextToken sct = 
                (SecurityContextToken)result.get(
                        WSSecurityEngineResult.TAG_SECURITY_CONTEXT_TOKEN
                );
            principal = new CustomTokenPrincipal(sct.getIdentifier());
        } else if (WSConstants.DKT == action) {
            DerivedKeyToken dkt = 
                (DerivedKeyToken)result.get(WSSecurityEngineResult.TAG_DERIVED_KEY_TOKEN);
            int keyLength = dkt.getLength();
            if (keyLength <= 0) {
                String algorithm = (String)parameters.get(SIGNATURE_METHOD);
                keyLength = WSSecurityUtil.getKeyLength(algorithm);
            }
            byte[] secret = (byte[])result.get(WSSecurityEngineResult.TAG_SECRET);
            secretKey = dkt.deriveKey(keyLength, secret); 
            principal = dkt.createPrincipal();
            ((WSDerivedKeyTokenPrincipal)principal).setSecret(secret);
        } else if (WSConstants.ST_UNSIGNED == action || WSConstants.ST_SIGNED == action) {
            AssertionWrapper assertion = 
                (AssertionWrapper)result.get(WSSecurityEngineResult.TAG_SAML_ASSERTION);
            if (bspCompliant) {
                BSPEnforcer.checkSamlTokenBSPCompliance(secRef, assertion);
            }
            SAMLKeyInfo keyInfo = assertion.getSubjectKeyInfo();
            X509Certificate[] foundCerts = keyInfo.getCerts();
            if (foundCerts != null) {
                certs = new X509Certificate[]{foundCerts[0]};
            }
            secretKey = keyInfo.getSecret();
            publicKey = keyInfo.getPublicKey();
            principal = createPrincipalFromSAML(assertion);
        }
    }
    
    
}