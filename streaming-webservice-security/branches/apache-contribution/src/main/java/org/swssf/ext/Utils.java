/**
 * Copyright 2010, 2011 Marc Giger
 *
 * This file is part of the streaming-webservice-security-framework (swssf).
 *
 * The streaming-webservice-security-framework is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The streaming-webservice-security-framework is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the streaming-webservice-security-framework.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.swssf.ext;

import org.apache.commons.codec.binary.Base64;
import org.swssf.config.TransformerAlgorithmMapper;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.xml.namespace.QName;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Namespace;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * @author $Author$
 * @version $Revision$ $Date$
 */
public class Utils {

    private Utils() {
    }

    /**
     * Returns the Id reference without the leading #
     *
     * @param reference The reference on which to drop the #
     * @return The reference without a leading #
     */
    public static String dropReferenceMarker(String reference) {
        if (reference.startsWith("#")) {
            return reference.substring(1);
        }
        return reference;
    }

    /**
     * Returns the XMLEvent type in String form
     *
     * @param xmlEvent
     * @return The XMLEvent type as string representation
     */
    public static String getXMLEventAsString(XMLEvent xmlEvent) {
        int eventType = xmlEvent.getEventType();

        switch (eventType) {
            case XMLEvent.START_ELEMENT:
                return "START_ELEMENT";
            case XMLEvent.END_ELEMENT:
                return "END_ELEMENT";
            case XMLEvent.PROCESSING_INSTRUCTION:
                return "PROCESSING_INSTRUCTION";
            case XMLEvent.CHARACTERS:
                return "CHARACTERS";
            case XMLEvent.COMMENT:
                return "COMMENT";
            case XMLEvent.START_DOCUMENT:
                return "START_DOCUMENT";
            case XMLEvent.END_DOCUMENT:
                return "END_DOCUMENT";
            case XMLEvent.ATTRIBUTE:
                return "ATTRIBUTE";
            case XMLEvent.DTD:
                return "DTD";
            case XMLEvent.NAMESPACE:
                return "NAMESPACE";
            default:
                throw new IllegalArgumentException("Illegal XMLEvent received: " + eventType);
        }
    }

    /**
     * Executes the Callback handling. Typically used to fetch passwords
     *
     * @param callbackHandler
     * @param callback
     * @throws WSSecurityException if the callback couldn't be executed
     */
    public static void doPasswordCallback(CallbackHandler callbackHandler, Callback callback) throws WSSecurityException {
        if (callbackHandler == null) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "noCallback");
        }
        try {
            callbackHandler.handle(new Callback[]{callback});
        } catch (IOException e) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "noPassword", e);
        } catch (UnsupportedCallbackException e) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "noPassword", e);
        }
    }

    /**
     * Try to get the secret key from a CallbackHandler implementation
     *
     * @param cb a CallbackHandler implementation
     * @return An array of bytes corresponding to the secret key (can be null)
     * @throws WSSecurityException
     */
    public static void doSecretKeyCallback(CallbackHandler callbackHandler, Callback callback, String id) throws WSSecurityException {
        if (callbackHandler != null) {
            try {
                callbackHandler.handle(new Callback[]{callback});
            } catch (IOException e) {
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "noPassword", e);
            } catch (UnsupportedCallbackException e) {
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "noPassword", e);
            }
        }
    }

    public static Class loadClass(String className) throws ClassNotFoundException {
        return Thread.currentThread().getContextClassLoader().loadClass(className);
    }

    public static String doPasswordDigest(byte[] nonce, String created, String password) throws WSSecurityException {
        try {
            byte[] b1 = nonce != null ? nonce : new byte[0];
            byte[] b2 = created != null ? created.getBytes("UTF-8") : new byte[0];
            byte[] b3 = password.getBytes("UTF-8");
            byte[] b4 = new byte[b1.length + b2.length + b3.length];
            int offset = 0;
            System.arraycopy(b1, 0, b4, offset, b1.length);
            offset += b1.length;

            System.arraycopy(b2, 0, b4, offset, b2.length);
            offset += b2.length;

            System.arraycopy(b3, 0, b4, offset, b3.length);

            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.reset();
            sha.update(b4);
            return new String(Base64.encodeBase64(sha.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "noSHA1availabe", e);
        } catch (UnsupportedEncodingException e) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static XMLEvent createXMLEventNS(XMLEvent xmlEvent, Deque<List<ComparableNamespace>> nsStack, Deque<List<ComparableAttribute>> attrStack) {
        if (xmlEvent.isStartElement()) {
            StartElement startElement = xmlEvent.asStartElement();
            QName startElementName = startElement.getName();

            List<String> prefixList = new LinkedList<String>();
            prefixList.add(startElementName.getPrefix());

            List<ComparableNamespace> comparableNamespaceList = new LinkedList<ComparableNamespace>();

            ComparableNamespace curElementNamespace = new ComparableNamespace(startElementName.getPrefix(), startElementName.getNamespaceURI());
            comparableNamespaceList.add(curElementNamespace);

            @SuppressWarnings("unchecked")
            Iterator<Namespace> namespaceIterator = startElement.getNamespaces();
            while (namespaceIterator.hasNext()) {
                Namespace namespace = namespaceIterator.next();
                String prefix = namespace.getPrefix();

                if ((prefix == null || prefix.length() == 0) && (namespace.getNamespaceURI() == null || namespace.getNamespaceURI().length() == 0)) {
                    continue;
                }

                if (!prefixList.contains(prefix)) {
                    prefixList.add(prefix);
                    ComparableNamespace tmpNameSpace = new ComparableNamespace(prefix, namespace.getNamespaceURI());
                    comparableNamespaceList.add(tmpNameSpace);
                }
            }

            List<ComparableAttribute> comparableAttributeList = new LinkedList<ComparableAttribute>();

            @SuppressWarnings("unchecked")
            Iterator<Attribute> attributeIterator = startElement.getAttributes();
            while (attributeIterator.hasNext()) {
                Attribute attribute = attributeIterator.next();
                String prefix = attribute.getName().getPrefix();

                if (prefix != null && prefix.length() == 0 && attribute.getName().getNamespaceURI().length() == 0) {
                    continue;
                }
                if (!"xml".equals(prefix)) {
                    if (!"".equals(prefix)) {
                        //does an attribute have an namespace?
                        if (!prefixList.contains(prefix)) {
                            prefixList.add(prefix);
                            ComparableNamespace tmpNameSpace = new ComparableNamespace(prefix, attribute.getName().getNamespaceURI());
                            comparableNamespaceList.add(tmpNameSpace);
                        }
                        continue;
                    }
                }
                //add all attrs with xml - prefix (eg. xml:lang) to attr list:
                comparableAttributeList.add(new ComparableAttribute(attribute.getName(), attribute.getValue()));
            }

            nsStack.push(comparableNamespaceList);
            attrStack.push(comparableAttributeList);

            return new XMLEventNS(xmlEvent, nsStack.toArray(new List[nsStack.size()]), attrStack.toArray(new List[attrStack.size()]));
        } else if (xmlEvent.isEndElement()) {
            XMLEventNS xmlEventNS = new XMLEventNS(xmlEvent, nsStack.toArray(new List[nsStack.size()]), attrStack.toArray(new List[attrStack.size()]));
            nsStack.pop();
            attrStack.pop();
            return xmlEventNS;
        }
        return xmlEvent;
    }

    public static boolean isResponsibleActorOrRole(StartElement startElement, String soapVersionNamespace, String responsibleActor) {
        QName actorRole;
        if (Constants.NS_SOAP11.equals(soapVersionNamespace)) {
            actorRole = Constants.ATT_soap11_Actor;
        } else {
            actorRole = Constants.ATT_soap12_Role;
        }

        String actor = null;
        @SuppressWarnings("unchecked")
        Iterator<Attribute> attributeIterator = startElement.getAttributes();
        while (attributeIterator.hasNext()) {
            Attribute next = attributeIterator.next();
            if (actorRole.equals(next.getName())) {
                actor = next.getValue();
            }
        }

        if (responsibleActor == null) {
            return actor == null;
        } else {
            return responsibleActor.equals(actor);
        }
    }

    public static Transformer getTransformer(Object methodParameter1, Object methodParameter2, String algorithm) throws WSSecurityException, InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        Class<Transformer> transformerClass = TransformerAlgorithmMapper.getTransformerClass(algorithm);
        Transformer childTransformer;
        try {
            Constructor<Transformer> constructor = transformerClass.getConstructor(Transformer.class);
            childTransformer = constructor.newInstance(methodParameter1);
        } catch (NoSuchMethodException e) {
            Constructor<Transformer> constructor = transformerClass.getConstructor(String.class, OutputStream.class);
            childTransformer = constructor.newInstance(methodParameter1, methodParameter2);
        }
        return childTransformer;
    }
}
