/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.verisure.internal.type1;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The {@link Response} class encapsulates XML-like responses received from the Verisure service.
 * Convenience methods use a Xpath parser to retrieve expected elements and values.
 *
 * @author Riccardo De Menna - Initial contribution
 */
@NonNullByDefault
public class Response {

    public static class MissingElementException extends Exception {
        private static final long serialVersionUID = 8187078915298337745L;

        MissingElementException(Evaluator element) {
            super(String.format("Missing expected element %s in response", element.name()));
        }
    }

    public final Enum<? extends Request.CommandInterface> command;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final String content;

    Response(Enum<? extends Request.CommandInterface> command, String content) {
        this.command = command;
        this.content = content;
    }

    private static final DocumentBuilderFactory DOM_FACTORY = DocumentBuilderFactory.newInstance();

    static {
        DOM_FACTORY.setNamespaceAware(true);
        DOM_FACTORY.setValidating(false);
    }

    public interface Evaluator {
        XPathFactory XPATH = XPathFactory.newInstance();

        String name();

        XPathExpression xpath();
    }

    public enum CommonElement implements Evaluator {
        HASH("/PET/HASH/text()"),
        INSTALLATION("/PET/NUMINST/text()"),
        LANGUAGE("/PET/LANG/text()"),
        IBS("/PET/INSTIBUS/text()"),
        STATUS("/PET/STATUS/text()"),
        SIM("/PET/SIM/text()"),
        MESSAGE("/PET/MSG/text()"),
        ERROR("/PET/ERR/text()"),
        RESULT("/PET/RES/text()");

        CommonElement(String path) {
            try {
                xpath = XPATH.newXPath().compile(path);
            } catch (XPathExpressionException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }

        private final XPathExpression xpath;

        @Override
        public XPathExpression xpath() {
            return xpath;
        }
    }

    private @Nullable Document parsableDocument;

    private @Nullable Document parsableDocument() throws IOException, SAXException, ParserConfigurationException {
        if (parsableDocument == null) {
            DocumentBuilder builder = DOM_FACTORY.newDocumentBuilder();
            InputSource inputSource = new InputSource(new StringReader(content));
            inputSource.setEncoding("UTF-8");
            parsableDocument = builder.parse(inputSource);
        }
        return parsableDocument;
    }

    public String get(Evaluator element) throws MissingElementException {
        try {
            Document doc = parsableDocument();
            if (doc != null)
                return (String) element.xpath().evaluate(doc, XPathConstants.STRING);
        } catch (IOException | SAXException | XPathExpressionException | ParserConfigurationException e) {
            logger.warn("{}", e.getMessage());
        }
        throw new MissingElementException(element);
    }

    public boolean success() throws MissingElementException {
        return "OK".equals(get(CommonElement.RESULT));
    }

    public String result() throws MissingElementException {
        return get(CommonElement.RESULT);
    }

    public String message() throws MissingElementException {
        return get(CommonElement.MESSAGE);
    }

    public String error() throws MissingElementException {
        return get(CommonElement.ERROR);
    }

    public String status() throws MissingElementException {
        return get(CommonElement.STATUS);
    }

    public String language() throws MissingElementException {
        return get(CommonElement.LANGUAGE);
    }

    public String installation() throws MissingElementException {
        return get(CommonElement.INSTALLATION);
    }

    public String hash() throws MissingElementException {
        return get(CommonElement.HASH);
    }

    public String sim() throws MissingElementException {
        return get(CommonElement.SIM);
    }

    public String ibs() throws MissingElementException {
        return get(CommonElement.IBS);
    }
}
