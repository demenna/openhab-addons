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

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Request} class represents synchronous or asynchronous requests to the
 * Verisure service.
 * <p>
 * There should be no need to directly instantiate {@link Request}s as the {@link Connection}
 * takes care of creating and submitting them via the two public methods
 * {@link Connection#schedule(Enum)} and {@link Connection#post(Enum)}.
 *
 * @author Riccardo De Menna - Initial contribution
 */
@NonNullByDefault
public class Request {

    static class MaxAttemptsException extends Exception {
        private static final long serialVersionUID = -8378649529658734090L;

        MaxAttemptsException() {
            super("Maximum number of attempts reached");
        }
    }

    static class ErrorResponseException extends Exception {
        private static final long serialVersionUID = 1346558043934570L;

        ErrorResponseException(String code, String message) {
            super(String.format("ERROR(%s) - %s", code, message));
        }
    }

    static class HTTPStatusCodeException extends Exception {
        private static final long serialVersionUID = -4152991537969525915L;

        HTTPStatusCodeException(int code) {
            super(String.format("Failed HTTP post (%d)", code));
        }
    }

    static class MissingParameterException extends Exception {
        private static final long serialVersionUID = 2311276194551722567L;

        MissingParameterException(ParameterInterface parameter) {
            super(String.format("Missing mandatory parameter %s in request", parameter.name()));
        }
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private static int indexCounter = 0;
    final Enum<? extends CommandInterface> command;
    private final HttpClient httpClient;
    private final int intervalBetweenAttempts;
    private final int maxAttempts;
    private int attempt;
    private int index;
    private @Nullable String identification;

    public Request(Enum<? extends CommandInterface> command, HttpClient httpClient, int intervalBetweenAttempts,
            int maxAttempts) {
        this.command = command;
        this.httpClient = httpClient;
        this.intervalBetweenAttempts = intervalBetweenAttempts;
        this.maxAttempts = maxAttempts;
        attempt = 0;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof Request && command.equals(((Request) obj).command);
    }

    @Override
    public int hashCode() {
        return command.hashCode();
    }

    public int index() {
        return index;
    }

    public interface CommandInterface {
        String name();

        String code();

        String followUpCode();

        boolean async();
    }

    public interface ParameterInterface {
        String name();

        String code();
    }

    @SuppressWarnings("unused")
    private enum Parameter implements ParameterInterface {
        ID("ID"),
        REQUEST("request"),
        CALLBY("callby"),
        COUNTER("counter");

        private final String code;

        Parameter(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }
    }

    private static final String BASEURL = "https://mob2217.securitasdirect.es:12010/WebService/ws.do";

    private String urlForRequest(Map<ParameterInterface, @Nullable String> arguments) {
        ArrayList<String> list = new ArrayList<>();
        for (ParameterInterface p : arguments.keySet())
            list.add(p.code() + "=" + arguments.get(p));
        return BASEURL + "?" + String.join("&", list);
    }

    private static final String CALL_BY = "IPH_61";

    private synchronized Response post(Map<ParameterInterface, @Nullable String> arguments, boolean followUp)
            throws InterruptedException, ExecutionException, TimeoutException, MaxAttemptsException,
            ErrorResponseException, HTTPStatusCodeException, MissingParameterException {
        CommandInterface c = (CommandInterface) command;
        String request = null;
        if (followUp) {
            request = c.followUpCode();
            arguments.put(Parameter.COUNTER, String.valueOf(attempt));
            arguments.remove(Parameter.CALLBY);
        } else {
            index = indexCounter++;
            // @formatter:off
            identification = String.format(
                    "%s%s%3$tY%3$tm%3$td%3$tH%3$tM%3$tS%3$tL", 
                    "IPH_______________",
                    arguments.get(BridgeHandler.Parameter.USER), 
                    new java.util.Date()
            );
            // @formatter:on
            request = c.code();
            arguments.put(Parameter.CALLBY, CALL_BY);
        }
        arguments.put(Parameter.REQUEST, request);
        assert identification != null;
        arguments.put(Parameter.ID, identification);
        logger.debug("{}({}): {}", request, index, arguments);
        String url = urlForRequest(arguments);
        ContentResponse contentResponse = httpClient.GET(url);
        if (HttpStatus.OK_200 != contentResponse.getStatus())
            throw new HTTPStatusCodeException(contentResponse.getStatus());
        Response response = new Response(command, contentResponse.getContentAsString());
        try {
            String logEntry = String.format("%s(%d): %s - %s", request, index, response.result(), response.message());
            switch (response.result()) {
                case "ERROR":
                    logger.warn(logEntry);
                    throw new ErrorResponseException(response.error(), response.message());
                case "OK":
                    logger.info(logEntry);
                    if (c.async() && !followUp)
                        response = followUp(arguments);
                    break;
                case "WAIT":
                    if (++attempt >= maxAttempts)
                        throw new MaxAttemptsException();
                    logger.debug(logEntry);
                    response = followUp(arguments);
                    break;
                default:
                    logger.warn(logEntry);
            }
        } catch (Response.MissingElementException e) {
            logger.warn("{}({}): {}", request, index, e.getMessage());
        }
        return response;
    }

    Response post(Map<ParameterInterface, @Nullable String> arguments)
            throws InterruptedException, ExecutionException, TimeoutException, MaxAttemptsException,
            ErrorResponseException, HTTPStatusCodeException, MissingParameterException {
        return post(arguments, false);
    }

    private Response followUp(Map<ParameterInterface, @Nullable String> arguments)
            throws InterruptedException, MaxAttemptsException, ExecutionException, TimeoutException,
            HTTPStatusCodeException, ErrorResponseException, MissingParameterException {
        Thread.sleep(intervalBetweenAttempts * 1000);
        return post(arguments, true);
    }

    void cancel() {
        Thread.currentThread().interrupt();
    }
}
