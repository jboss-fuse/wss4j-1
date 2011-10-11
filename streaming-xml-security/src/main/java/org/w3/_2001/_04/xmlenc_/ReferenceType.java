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
package org.w3._2001._04.xmlenc_;

import org.swssf.xmlsec.ext.ParseException;
import org.swssf.xmlsec.ext.Parseable;
import org.swssf.xmlsec.ext.XMLSecurityConstants;
import org.swssf.xmlsec.ext.XMLSecurityUtils;

import javax.xml.bind.annotation.*;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * <p>Java class for ReferenceType complex type.
 * <p/>
 * <p>The following schema fragment specifies the expected content contained within this class.
 * <p/>
 * <pre>
 * &lt;complexType name="ReferenceType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;any namespace='##other' maxOccurs="unbounded" minOccurs="0"/>
 *       &lt;/sequence>
 *       &lt;attribute name="URI" use="required" type="{http://www.w3.org/2001/XMLSchema}anyURI" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "ReferenceType", propOrder = {
        "any"
})
public class ReferenceType implements Parseable {

    @XmlAnyElement(lax = true)
    protected List<Object> any;
    @XmlAttribute(name = "URI", required = true)
    @XmlSchemaType(name = "anyURI")
    protected String uri;

    private boolean processed = false;

    private QName startElementName;

    public ReferenceType(StartElement startElement) {
        this.startElementName = startElement.getName();
        @SuppressWarnings("unchecked")
        Iterator<Attribute> attributeIterator = startElement.getAttributes();
        while (attributeIterator.hasNext()) {
            Attribute attribute = attributeIterator.next();
            if (attribute.getName().equals(XMLSecurityConstants.ATT_NULL_URI)) {
                this.uri = XMLSecurityUtils.dropReferenceMarker(attribute.getValue());
            }
        }
    }

    public boolean parseXMLEvent(XMLEvent xmlEvent) throws ParseException {

        switch (xmlEvent.getEventType()) {
            case XMLStreamConstants.END_ELEMENT:
                EndElement endElement = xmlEvent.asEndElement();
                if (endElement.getName().equals(startElementName)) {
                    return true;
                }
            case XMLStreamConstants.START_ELEMENT:
                throw new ParseException("Unsupported Element: " + xmlEvent.asStartElement().getName());
            default:
                throw new ParseException("Unexpected event received " + XMLSecurityUtils.getXMLEventAsString(xmlEvent));
        }
    }

    public void validate() throws ParseException {
        if (uri == null) {
            throw new ParseException("Attribute \"URI\" is missing");
        }
    }

    /**
     * Gets the value of the any property.
     * <p/>
     * <p/>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the any property.
     * <p/>
     * <p/>
     * For example, to add a new item, do as follows:
     * <pre>
     *    getAny().add(newItem);
     * </pre>
     * <p/>
     * <p/>
     * <p/>
     * Objects of the following type(s) are allowed in the list
     * {@link Object }
     */
    public List<Object> getAny() {
        if (any == null) {
            any = new ArrayList<Object>();
        }
        return this.any;
    }

    /**
     * Gets the value of the uri property.
     *
     * @return possible object is
     *         {@link String }
     */
    public String getURI() {
        return uri;
    }

    /**
     * Sets the value of the uri property.
     *
     * @param value allowed object is
     *              {@link String }
     */
    public void setURI(String value) {
        this.uri = value;
    }

    public boolean isProcessed() {
        return processed;
    }

    public void setProcessed(boolean processed) {
        this.processed = processed;
    }
}