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

package org.apache.wss4j.dom.action;

import java.util.List;

import javax.security.auth.callback.CallbackHandler;

import org.apache.wss4j.common.SecurityActionToken;
import org.apache.wss4j.common.SignatureActionToken;
import org.apache.wss4j.common.WSEncryptionPart;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.derivedKey.ConversationConstants;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.handler.WSHandler;
import org.apache.wss4j.dom.message.WSSecDKSign;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class SignatureDerivedAction extends AbstractDerivedAction implements Action {

    public void execute(WSHandler handler, SecurityActionToken actionToken, RequestData reqData)
            throws WSSecurityException {
        CallbackHandler callbackHandler = reqData.getCallbackHandler();
        if (callbackHandler == null) {
            callbackHandler = handler.getPasswordCallbackHandler(reqData);
        }

        SignatureActionToken signatureToken = null;
        if (actionToken instanceof SignatureActionToken) {
            signatureToken = (SignatureActionToken)actionToken;
        }
        if (signatureToken == null) {
            signatureToken = reqData.getSignatureToken();
        }

        WSPasswordCallback passwordCallback =
            handler.getPasswordCB(signatureToken.getUser(), WSConstants.DKT_SIGN, callbackHandler, reqData);
        WSSecDKSign wsSign = new WSSecDKSign(reqData.getSecHeader());
        wsSign.setIdAllocator(reqData.getWssConfig().getIdAllocator());
        wsSign.setAddInclusivePrefixes(reqData.isAddInclusivePrefixes());

        if (signatureToken.getSignatureAlgorithm() != null) {
            wsSign.setSignatureAlgorithm(signatureToken.getSignatureAlgorithm());
        }
        if (signatureToken.getDigestAlgorithm() != null) {
            wsSign.setDigestAlgorithm(signatureToken.getDigestAlgorithm());
        }
        if (signatureToken.getC14nAlgorithm() != null) {
            wsSign.setSigCanonicalization(signatureToken.getC14nAlgorithm());
        }
        wsSign.setUserInfo(signatureToken.getUser(), passwordCallback.getPassword());

        if (reqData.isUse200512Namespace()) {
            wsSign.setWscVersion(ConversationConstants.VERSION_05_12);
        } else {
            wsSign.setWscVersion(ConversationConstants.VERSION_05_02);
        }

        if (signatureToken.getDerivedKeyLength() > 0) {
            wsSign.setDerivedKeyLength(signatureToken.getDerivedKeyLength());
        }

        Document doc = reqData.getSecHeader().getSecurityHeaderElement().getOwnerDocument();
        Element tokenElement =
            setupTokenReference(reqData, signatureToken, wsSign, passwordCallback, doc);
        wsSign.setAttachmentCallbackHandler(reqData.getAttachmentCallbackHandler());
        wsSign.setStoreBytesInAttachment(reqData.isStoreBytesInAttachment());

        try {
            List<WSEncryptionPart> parts = signatureToken.getParts();
            if (parts != null && !parts.isEmpty()) {
                wsSign.getParts().addAll(parts);
            } else {
                wsSign.getParts().add(WSSecurityUtil.getDefaultEncryptionPart(doc));
            }

            wsSign.prepare();

            List<javax.xml.crypto.dsig.Reference> referenceList = wsSign.addReferencesToSign(wsSign.getParts());

            // Put the DerivedKeyToken Element in the right place in the security header
            Node nextSibling = null;
            if (tokenElement == null
                && "EncryptedKey".equals(signatureToken.getDerivedKeyTokenReference())) {
                nextSibling = findEncryptedKeySibling(reqData);
            } else if (tokenElement == null
                && "SecurityContextToken".equals(signatureToken.getDerivedKeyTokenReference())) {
                nextSibling = findSCTSibling(reqData);
            }

            if (nextSibling == null) {
                wsSign.computeSignature(referenceList);
            } else {
                wsSign.computeSignature(referenceList, true, (Element)nextSibling);
            }

            if (nextSibling == null) {
                wsSign.prependDKElementToHeader();
            } else {
                reqData.getSecHeader().getSecurityHeaderElement().insertBefore(
                    wsSign.getdktElement(), wsSign.getSignatureElement());
            }

            if (tokenElement != null) {
                WSSecurityUtil.prependChildElement(reqData.getSecHeader().getSecurityHeaderElement(), tokenElement);
            }

            reqData.getSignatureValues().add(wsSign.getSignatureValue());
        } catch (WSSecurityException e) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, e, "empty",
                                          new Object[] {"Error during Signature: "});
        }
    }

    private Element setupTokenReference(
        RequestData reqData, SignatureActionToken signatureToken,
        WSSecDKSign wsSign, WSPasswordCallback passwordCallback,
        Document doc
    ) throws WSSecurityException {
        String derivedKeyTokenReference = signatureToken.getDerivedKeyTokenReference();

        if ("EncryptedKey".equals(derivedKeyTokenReference)) {
            return setupEKReference(wsSign, reqData.getSecHeader(), passwordCallback, signatureToken, reqData.getEncryptionToken(),
                                     reqData.isUse200512Namespace(), doc, null, null);
        } else if ("SecurityContextToken".equals(derivedKeyTokenReference)) {
            return setupSCTReference(wsSign, passwordCallback, signatureToken, reqData.getEncryptionToken(),
                                     reqData.isUse200512Namespace(), doc);
        } else {
            // DirectReference

            if (signatureToken.getDerivedKeyIdentifier() != 0) {
                wsSign.setKeyIdentifierType(signatureToken.getDerivedKeyIdentifier());
            } else {
                wsSign.setKeyIdentifierType(WSConstants.THUMBPRINT_IDENTIFIER);
            }

            byte[] key = null;
            if (passwordCallback.getKey() != null) {
                key = passwordCallback.getKey();
            } else if (signatureToken.getKey() != null) {
                key = signatureToken.getKey();
            } else if (signatureToken.getCrypto() != null) {
                Crypto crypto = signatureToken.getCrypto();
                key = crypto.getPrivateKey(signatureToken.getUser(), passwordCallback.getPassword()).getEncoded();
            }
            wsSign.setCrypto(signatureToken.getCrypto());
            wsSign.setExternalKey(key, (String)null);

            return null;
        }
    }
}
