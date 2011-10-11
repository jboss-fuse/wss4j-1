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
package org.swssf.wss.impl.processor.output;

import org.apache.commons.codec.binary.Base64;
import org.swssf.wss.ext.*;
import org.swssf.wss.impl.derivedKey.AlgoFactory;
import org.swssf.wss.impl.derivedKey.ConversationException;
import org.swssf.wss.impl.derivedKey.DerivationAlgorithm;
import org.swssf.wss.impl.securityToken.ProcessorInfoSecurityToken;
import org.swssf.xmlsec.config.JCEAlgorithmMapper;
import org.swssf.xmlsec.crypto.Crypto;
import org.swssf.xmlsec.crypto.Merlin;
import org.swssf.xmlsec.ext.*;

import javax.crypto.spec.SecretKeySpec;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.UnsupportedEncodingException;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * @author $Author$
 * @version $Revision$ $Date$
 */
public class DerivedKeyTokenOutputProcessor extends AbstractOutputProcessor {

    public DerivedKeyTokenOutputProcessor(WSSSecurityProperties securityProperties, XMLSecurityConstants.Action action) throws XMLSecurityException {
        super(securityProperties, action);
    }

    @Override
    public void processEvent(XMLEvent xmlEvent, OutputProcessorChain outputProcessorChain) throws XMLStreamException, XMLSecurityException {
        try {

            String tokenId = outputProcessorChain.getSecurityContext().get(WSSConstants.PROP_USE_THIS_TOKEN_ID_FOR_DERIVED_KEY);
            if (tokenId == null) {
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILED_ENCRYPTION);
            }
            SecurityTokenProvider wrappingSecurityTokenProvider = outputProcessorChain.getSecurityContext().getSecurityTokenProvider(tokenId);
            if (wrappingSecurityTokenProvider == null) {
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILED_ENCRYPTION);
            }
            final SecurityToken wrappingSecurityToken = wrappingSecurityTokenProvider.getSecurityToken(null);
            if (wrappingSecurityToken == null) {
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILED_ENCRYPTION);
            }

            final String wsuIdDKT = "DK-" + UUID.randomUUID().toString();

            int offset = 0;
            int length = 0;

            XMLSecurityConstants.Action action = getAction();
            if (action.equals(WSSConstants.SIGNATURE_WITH_DERIVED_KEY)) {
                length = JCEAlgorithmMapper.getAlgorithmMapping(getSecurityProperties().getSignatureAlgorithm()).getKeyLength() / 8;
            } else if (action.equals(WSSConstants.ENCRYPT_WITH_DERIVED_KEY)) {
                length = JCEAlgorithmMapper.getAlgorithmMapping(getSecurityProperties().getEncryptionSymAlgorithm()).getKeyLength() / 8;
            }

            byte[] label;
            try {
                label = (WSSConstants.WS_SecureConversation_DEFAULT_LABEL + WSSConstants.WS_SecureConversation_DEFAULT_LABEL).getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new WSSecurityException("UTF-8 encoding is not supported", e);
            }

            byte[] nonce = new byte[16];
            WSSConstants.secureRandom.nextBytes(nonce);

            byte[] seed = new byte[label.length + nonce.length];
            System.arraycopy(label, 0, seed, 0, label.length);
            System.arraycopy(nonce, 0, seed, label.length, nonce.length);

            DerivationAlgorithm derivationAlgorithm;
            try {
                derivationAlgorithm = AlgoFactory.getInstance(WSSConstants.P_SHA_1);
            } catch (ConversationException e) {
                throw new WSSecurityException(e.getMessage(), e);
            }

            final byte[] derivedKeyBytes;
            try {
                byte[] secret;
                if (wrappingSecurityToken.getTokenType() == WSSConstants.SecurityContextToken) {
                    WSPasswordCallback passwordCallback = new WSPasswordCallback(wsuIdDKT, WSPasswordCallback.Usage.SECRET_KEY);
                    WSSUtils.doSecretKeyCallback(securityProperties.getCallbackHandler(), passwordCallback, wsuIdDKT);
                    if (passwordCallback.getKey() == null) {
                        throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "noKey", wsuIdDKT);
                    }
                    secret = passwordCallback.getKey();
                } else {
                    secret = wrappingSecurityToken.getSecretKey(null, null).getEncoded();
                }

                derivedKeyBytes = derivationAlgorithm.createKey(secret, seed, offset, length);
            } catch (ConversationException e) {
                throw new WSSecurityException(e.getMessage(), e);
            }

            final ProcessorInfoSecurityToken derivedKeySecurityToken = new ProcessorInfoSecurityToken() {

                private Map<String, Key> keyTable = new Hashtable<String, Key>();
                private OutputProcessor outputProcessor;

                public String getId() {
                    return wsuIdDKT;
                }

                public void setProcessor(OutputProcessor outputProcessor) {
                    this.outputProcessor = outputProcessor;
                }

                public Object getProcessor() {
                    return outputProcessor;
                }

                public boolean isAsymmetric() {
                    return false;
                }

                public Key getSecretKey(String algorithmURI, XMLSecurityConstants.KeyUsage keyUsage) throws WSSecurityException {
                    if (keyTable.containsKey(algorithmURI)) {
                        return keyTable.get(algorithmURI);
                    } else {
                        String algoFamily = JCEAlgorithmMapper.getJCERequiredKeyFromURI(algorithmURI);
                        Key key = new SecretKeySpec(derivedKeyBytes, algoFamily);
                        keyTable.put(algorithmURI, key);
                        return key;
                    }
                }

                public PublicKey getPublicKey(XMLSecurityConstants.KeyUsage keyUsage) throws WSSecurityException {
                    return null;
                }

                public X509Certificate[] getX509Certificates() throws WSSecurityException {
                    return null;
                }

                public void verify() throws WSSecurityException {
                }

                public SecurityToken getKeyWrappingToken() {
                    return wrappingSecurityToken;
                }

                public String getKeyWrappingTokenAlgorithm() {
                    return null;
                }

                public WSSConstants.TokenType getTokenType() {
                    return null;
                }
            };

            SecurityTokenProvider derivedKeysecurityTokenProvider = new SecurityTokenProvider() {
                public SecurityToken getSecurityToken(Crypto crypto) throws WSSecurityException {
                    return derivedKeySecurityToken;
                }

                public String getId() {
                    return wsuIdDKT;
                }
            };

            if (action.equals(WSSConstants.SIGNATURE_WITH_DERIVED_KEY)) {
                outputProcessorChain.getSecurityContext().put(WSSConstants.PROP_USE_THIS_TOKEN_ID_FOR_SIGNATURE, wsuIdDKT);
                outputProcessorChain.getSecurityContext().put(WSSConstants.PROP_APPEND_SIGNATURE_ON_THIS_ID, wsuIdDKT);
            } else if (action.equals(WSSConstants.ENCRYPT_WITH_DERIVED_KEY)) {
                outputProcessorChain.getSecurityContext().put(WSSConstants.PROP_USE_THIS_TOKEN_ID_FOR_ENCRYPTION, wsuIdDKT);
            }
            outputProcessorChain.getSecurityContext().registerSecurityTokenProvider(wsuIdDKT, derivedKeysecurityTokenProvider);
            FinalDerivedKeyTokenOutputProcessor finalDerivedKeyTokenOutputProcessor =
                    new FinalDerivedKeyTokenOutputProcessor(
                            getSecurityProperties(), getAction(), derivedKeySecurityToken,
                            offset, length, new String(Base64.encodeBase64(nonce)));
            finalDerivedKeyTokenOutputProcessor.getBeforeProcessors().add(wrappingSecurityToken.getProcessor());
            derivedKeySecurityToken.setProcessor(finalDerivedKeyTokenOutputProcessor);
            outputProcessorChain.addProcessor(finalDerivedKeyTokenOutputProcessor);
        } finally {
            outputProcessorChain.removeProcessor(this);
        }
        outputProcessorChain.processEvent(xmlEvent);
    }

    class FinalDerivedKeyTokenOutputProcessor extends AbstractOutputProcessor {

        private SecurityToken securityToken;
        private int offset;
        private int length;
        private String nonce;

        FinalDerivedKeyTokenOutputProcessor(XMLSecurityProperties securityProperties, XMLSecurityConstants.Action action,
                                            SecurityToken securityToken, int offset, int length, String nonce)
                throws XMLSecurityException {

            super(securityProperties, action);
            this.securityToken = securityToken;
            this.offset = offset;
            this.length = length;
            this.nonce = nonce;
        }

        @Override
        public void processEvent(XMLEvent xmlEvent, OutputProcessorChain outputProcessorChain) throws XMLStreamException, XMLSecurityException {
            outputProcessorChain.processEvent(xmlEvent);
            if (xmlEvent.isStartElement()) {
                StartElement startElement = xmlEvent.asStartElement();
                if (((WSSDocumentContext) outputProcessorChain.getDocumentContext()).isInSecurityHeader() && startElement.getName().equals(WSSConstants.TAG_wsse_Security)) {
                    OutputProcessorChain subOutputProcessorChain = outputProcessorChain.createSubChain(this);

                    Map<QName, String> attributes = new HashMap<QName, String>();
                    attributes.put(WSSConstants.ATT_wsu_Id, securityToken.getId());
                    createStartElementAndOutputAsEvent(subOutputProcessorChain, WSSConstants.TAG_wsc0502_DerivedKeyToken, attributes);

                    createSecurityTokenReferenceStructureForDerivedKey(subOutputProcessorChain, securityToken,
                            ((WSSSecurityProperties) getSecurityProperties()).getDerivedKeyKeyIdentifierType(),
                            ((WSSSecurityProperties) getSecurityProperties()).getDerivedKeyTokenReference(), getSecurityProperties().isUseSingleCert());
                    createStartElementAndOutputAsEvent(subOutputProcessorChain, WSSConstants.TAG_wsc0502_Offset, null);
                    createCharactersAndOutputAsEvent(subOutputProcessorChain, "" + offset);
                    createEndElementAndOutputAsEvent(subOutputProcessorChain, WSSConstants.TAG_wsc0502_Offset);
                    createStartElementAndOutputAsEvent(subOutputProcessorChain, WSSConstants.TAG_wsc0502_Length, null);
                    createCharactersAndOutputAsEvent(subOutputProcessorChain, "" + length);
                    createEndElementAndOutputAsEvent(subOutputProcessorChain, WSSConstants.TAG_wsc0502_Length);
                    createStartElementAndOutputAsEvent(subOutputProcessorChain, WSSConstants.TAG_wsc0502_Nonce, null);
                    createCharactersAndOutputAsEvent(subOutputProcessorChain, nonce);
                    createEndElementAndOutputAsEvent(subOutputProcessorChain, WSSConstants.TAG_wsc0502_Nonce);
                    createEndElementAndOutputAsEvent(subOutputProcessorChain, WSSConstants.TAG_wsc0502_DerivedKeyToken);

                    outputProcessorChain.removeProcessor(this);
                }
            }
        }

        protected void createSecurityTokenReferenceStructureForDerivedKey(
                OutputProcessorChain outputProcessorChain,
                SecurityToken securityToken,
                WSSConstants.KeyIdentifierType keyIdentifierType,
                WSSConstants.DerivedKeyTokenReference derivedKeyTokenReference,
                boolean useSingleCertificate)
                throws XMLStreamException, XMLSecurityException {

            Map<QName, String> attributes = new HashMap<QName, String>();
            attributes.put(WSSConstants.ATT_wsu_Id, "STRId-" + UUID.randomUUID().toString());
            if ((keyIdentifierType == WSSConstants.KeyIdentifierType.BST_DIRECT_REFERENCE
                    || keyIdentifierType == WSSConstants.KeyIdentifierType.BST_EMBEDDED)
                    && !useSingleCertificate) {
                attributes.put(WSSConstants.ATT_wsse11_TokenType, WSSConstants.NS_X509PKIPathv1);
            } else if (derivedKeyTokenReference == WSSConstants.DerivedKeyTokenReference.EncryptedKey) {
                attributes.put(WSSConstants.ATT_wsse11_TokenType, WSSConstants.NS_WSS_ENC_KEY_VALUE_TYPE);
            }
            createStartElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_SecurityTokenReference, attributes);

            X509Certificate[] x509Certificates = securityToken.getKeyWrappingToken().getX509Certificates();
            String tokenId = securityToken.getKeyWrappingToken().getId();

            if (keyIdentifierType == WSSConstants.KeyIdentifierType.ISSUER_SERIAL) {
                createX509IssuerSerialStructure(outputProcessorChain, x509Certificates);
            } else if (keyIdentifierType == WSSConstants.KeyIdentifierType.SKI_KEY_IDENTIFIER) {
                createX509SubjectKeyIdentifierStructure(outputProcessorChain, x509Certificates);
            } else if (keyIdentifierType == WSSConstants.KeyIdentifierType.X509_KEY_IDENTIFIER) {
                createX509KeyIdentifierStructure(outputProcessorChain, x509Certificates);
            } else if (keyIdentifierType == WSSConstants.KeyIdentifierType.THUMBPRINT_IDENTIFIER) {
                createThumbprintKeyIdentifierStructure(outputProcessorChain, x509Certificates);
            } else if (keyIdentifierType == WSSConstants.KeyIdentifierType.BST_EMBEDDED) {
                createBSTReferenceStructure(outputProcessorChain, tokenId, x509Certificates, useSingleCertificate, true);
            } else if (keyIdentifierType == WSSConstants.KeyIdentifierType.BST_DIRECT_REFERENCE) {
                createBSTReferenceStructure(outputProcessorChain, tokenId, x509Certificates, useSingleCertificate, false);
            } else if (keyIdentifierType == WSSConstants.KeyIdentifierType.EMBEDDED_SECURITY_TOKEN_REF) {
                createEmbeddedSecurityTokenReferenceStructure(outputProcessorChain, tokenId);
            } else {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_ENCRYPTION, "unsupportedSecurityToken", keyIdentifierType.name());
            }
            createEndElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_SecurityTokenReference);
        }

        //todo common method
        protected void createX509SubjectKeyIdentifierStructure(OutputProcessorChain outputProcessorChain, X509Certificate[] x509Certificates) throws XMLSecurityException, XMLStreamException {
            // As per the 1.1 specification, SKI can only be used for a V3 certificate
            if (x509Certificates[0].getVersion() != 3) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, "invalidCertForSKI");
            }

            Map<QName, String> attributes = new HashMap<QName, String>();
            attributes.put(WSSConstants.ATT_NULL_EncodingType, WSSConstants.SOAPMESSAGE_NS10_BASE64_ENCODING);
            attributes.put(WSSConstants.ATT_NULL_ValueType, WSSConstants.NS_X509SubjectKeyIdentifier);
            createStartElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_KeyIdentifier, attributes);
            byte data[] = new Merlin().getSKIBytesFromCert(x509Certificates[0]);
            createCharactersAndOutputAsEvent(outputProcessorChain, new Base64(76, new byte[]{'\n'}).encodeToString(data));
            createEndElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_KeyIdentifier);
        }

        //todo common method
        protected void createX509KeyIdentifierStructure(OutputProcessorChain outputProcessorChain, X509Certificate[] x509Certificates) throws XMLStreamException, XMLSecurityException {
            Map<QName, String> attributes = new HashMap<QName, String>();
            attributes.put(WSSConstants.ATT_NULL_EncodingType, WSSConstants.SOAPMESSAGE_NS10_BASE64_ENCODING);
            attributes.put(WSSConstants.ATT_NULL_ValueType, WSSConstants.NS_X509_V3_TYPE);
            createStartElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_KeyIdentifier, attributes);
            try {
                createCharactersAndOutputAsEvent(outputProcessorChain, new Base64(76, new byte[]{'\n'}).encodeToString(x509Certificates[0].getEncoded()));
            } catch (CertificateEncodingException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            }
            createEndElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_KeyIdentifier);
        }

        //todo common methdod
        protected void createThumbprintKeyIdentifierStructure(OutputProcessorChain outputProcessorChain, X509Certificate[] x509Certificates) throws XMLStreamException, XMLSecurityException {
            Map<QName, String> attributes = new HashMap<QName, String>();
            attributes.put(WSSConstants.ATT_NULL_EncodingType, WSSConstants.SOAPMESSAGE_NS10_BASE64_ENCODING);
            attributes.put(WSSConstants.ATT_NULL_ValueType, WSSConstants.NS_THUMBPRINT);
            createStartElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_KeyIdentifier, attributes);
            try {
                MessageDigest sha;
                sha = MessageDigest.getInstance("SHA-1");
                sha.reset();
                sha.update(x509Certificates[0].getEncoded());
                byte[] data = sha.digest();

                createCharactersAndOutputAsEvent(outputProcessorChain, new Base64(76, new byte[]{'\n'}).encodeToString(data));
            } catch (CertificateEncodingException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            } catch (NoSuchAlgorithmException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            }
            createEndElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_KeyIdentifier);
        }

        //todo common method
        protected void createBSTReferenceStructure(OutputProcessorChain outputProcessorChain, String referenceId, X509Certificate[] x509Certificates, boolean useSingleCertificate, boolean embed) throws XMLStreamException, XMLSecurityException {
            Map<QName, String> attributes = new HashMap<QName, String>();
            String valueType;
            if (useSingleCertificate) {
                valueType = WSSConstants.NS_X509_V3_TYPE;
            } else {
                valueType = WSSConstants.NS_X509PKIPathv1;
            }
            attributes.put(WSSConstants.ATT_NULL_URI, "#" + referenceId);
            attributes.put(WSSConstants.ATT_NULL_ValueType, valueType);
            createStartElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_Reference, attributes);
            if (embed) {
                createBinarySecurityTokenStructure(outputProcessorChain, referenceId, x509Certificates, useSingleCertificate);
            }
            createEndElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_Reference);
        }

        //todo common method
        protected void createEmbeddedSecurityTokenReferenceStructure(OutputProcessorChain outputProcessorChain, String referenceId) throws XMLStreamException, XMLSecurityException {
            Map<QName, String> attributes = new HashMap<QName, String>();
            attributes.put(WSSConstants.ATT_NULL_URI, "#" + referenceId);
            createStartElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_Reference, attributes);
            createEndElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_Reference);
        }

        //todo common method
        protected void createBinarySecurityTokenStructure(OutputProcessorChain outputProcessorChain, String referenceId, X509Certificate[] x509Certificates, boolean useSingleCertificate) throws XMLStreamException, XMLSecurityException {
            Map<QName, String> attributes = new HashMap<QName, String>();
            String valueType;
            if (useSingleCertificate) {
                valueType = WSSConstants.NS_X509_V3_TYPE;
            } else {
                valueType = WSSConstants.NS_X509PKIPathv1;
            }
            attributes.put(WSSConstants.ATT_NULL_EncodingType, WSSConstants.SOAPMESSAGE_NS10_BASE64_ENCODING);
            attributes.put(WSSConstants.ATT_NULL_ValueType, valueType);
            attributes.put(WSSConstants.ATT_wsu_Id, referenceId);
            createStartElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_BinarySecurityToken, attributes);
            try {
                if (useSingleCertificate) {
                    createCharactersAndOutputAsEvent(outputProcessorChain, new Base64(76, new byte[]{'\n'}).encodeToString(x509Certificates[0].getEncoded()));
                } else {
                    try {
                        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509", "BC");
                        List<X509Certificate> certificates = Arrays.asList(x509Certificates);
                        createCharactersAndOutputAsEvent(outputProcessorChain, new Base64(76, new byte[]{'\n'}).encodeToString(certificateFactory.generateCertPath(certificates).getEncoded()));
                    } catch (CertificateException e) {
                        throw new XMLSecurityException(XMLSecurityException.ErrorCode.INVALID_SECURITY_TOKEN, e);
                    } catch (NoSuchProviderException e) {
                        throw new XMLSecurityException(XMLSecurityException.ErrorCode.INVALID_SECURITY_TOKEN, e);
                    }
                }
            } catch (CertificateEncodingException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            }
            createEndElementAndOutputAsEvent(outputProcessorChain, WSSConstants.TAG_wsse_BinarySecurityToken);
        }
    }
}