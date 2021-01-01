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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.openhab.core.config.core.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Connection} class represents the internet connection to Verisure.
 * It is responsible of submitting {@link Request} objects to the service either
 * immediately via the blocking {@link Connection#post(Enum)} or more gracefully
 * by queuing via {@link Connection#schedule(Enum)} and then processing them
 * in a scheduled thread.
 *
 * @author Riccardo De Menna - Initial contribution
 */
@NonNullByDefault
public class Connection {

    /**
     * The {@link Requester} interface defines three methods by which interested
     * parties can intervene in the process of submitting {@link Request} objects to Verisure.
     * <p>
     * The cycle is composed of three interface methods that the {@link Connection} will
     * call in succession:
     * <ul>
     * <li>{@link Requester#argumentsForRequest(Enum)} is the first method
     * that gets called so that interested parties can add their parameters to the request.
     * For example, the bridge object will use this method to add all the mandatory credentials.</li>
     * <li>{@link Requester#willPostRequest(Enum, Map)} is the second method
     * that gets called so that interested parties can check for mandatory parameters
     * and potentially oppose the submission by throwing {@link Request.MissingParameterException}.</li>
     * <li>{@link Requester#requestComplete(Response)} is the final method
     * that gets called after the submission is complete, passing around the response.
     * Note that, in the case of {@link Request} objects that require asynchronous processing,
     * this method will be invoked only for the final submission, insulating the implementors
     * from having to deal with waiting and follow up requests.</li>
     * </ul>
     */
    public interface Requester {
        Map<Request.ParameterInterface, @Nullable String> argumentsForRequest(
                Enum<? extends Request.CommandInterface> command);

        void willPostRequest(Enum<? extends Request.CommandInterface> command,
                Map<Request.ParameterInterface, @Nullable String> arguments) throws Request.MissingParameterException;

        void requestComplete(Response response);
    }

    private final static String USER_AGENT = "Verisure/5 CFNetwork/1206 Darwin/20.1.0";
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final HashMap<Request.CommandInterface, @Nullable Set<Requester>> argumentProviders;
    private final HashMap<Request.CommandInterface, @Nullable Set<Requester>> responseReceivers;
    private final ScheduledExecutorService scheduler;
    private final HttpClient httpClient;

    public Connection(HttpClient httpClient, ScheduledExecutorService scheduler) {
        argumentProviders = new HashMap<>();
        responseReceivers = new HashMap<>();
        this.scheduler = scheduler;
        this.httpClient = httpClient;
        this.httpClient.setUserAgentField(new HttpField(HttpHeader.USER_AGENT, USER_AGENT));
        // this.httpClient.getProxyConfiguration().getProxies().add(new HttpProxy("127.0.0.1", 8888));
    }

    /**
     * Method to invoke to register as argument providers for {@link Request.CommandInterface}s.
     * Registered parties will receive calls for {@link Requester#argumentsForRequest(Enum)}
     * 
     * @param provider The provider to register
     * @param commands The {@link Request.CommandInterface}s for which to register.
     */
    public void registerAsArgumentProviderForRequestTypes(Requester provider, Request.CommandInterface... commands) {
        for (Request.CommandInterface c : commands)
            argumentProviders.computeIfAbsent(c, k -> new HashSet<>()).add(provider);
    }

    /**
     * Method to invoke to unregister as argument provider for {@link Request.CommandInterface}s.
     * 
     * @param provider The provider to unregister.
     */
    public void unregisterAsArgumentProvider(Requester provider) {
        for (Request.CommandInterface c : argumentProviders.keySet())
            argumentProviders.get(c).remove(provider);
    }

    /**
     * Method to invoke to register as response receiver for {@link Request.CommandInterface}s.
     * Registered parties will receive calls for both {@link Requester#willPostRequest(Enum, Map)}
     * and {@link Requester#requestComplete(Response)}, before and after the submission.
     * 
     * @param receiver The receiver to register
     * @param commands The {@link Request.CommandInterface}s for which to register.
     */
    public void registerAsResponseReceiverForRequestTypes(Requester receiver, Request.CommandInterface... commands) {
        for (Request.CommandInterface c : commands)
            responseReceivers.computeIfAbsent(c, k -> new HashSet<>()).add(receiver);
    }

    /**
     * Method to invoke to unregister as response receiver for {@link Request.CommandInterface}s.
     * 
     * @param receiver The receiver to unregister.
     */
    public void unregisterAsResponseReceiver(Requester receiver) {
        for (Request.CommandInterface c : responseReceivers.keySet())
            responseReceivers.get(c).remove(receiver);
    }

    public enum Command implements Request.CommandInterface {
        ALL("ALL");

        private final String code;

        Command(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String followUpCode() {
            return code;
        }

        @Override
        public boolean async() {
            return false;
        }
    }

    private static final ConcurrentLinkedQueue<@Nullable Request> queue = new ConcurrentLinkedQueue<>();
    private static final int QUEUE_POLL_RATE = 2;
    private @Nullable ScheduledFuture<?> queueSchedule;
    private @Nullable Request runningRequest;
    private int interval = 3;
    private int attempts = 10;

    /**
     * Method invoked by {@link BridgeHandler}
     * during the initialization process. Should not be used by others.
     * 
     * @param config The {@link Configuration} object provided by the bridge.
     */
    public void open(Configuration config) {
        BigDecimal interval = (BigDecimal) config.get("interval");
        if (interval != null && interval.intValue() > 0)
            this.interval = interval.intValue();
        BigDecimal attempts = (BigDecimal) config.get("attempts");
        if (attempts != null && attempts.intValue() > 0)
            this.attempts = attempts.intValue();
        if (queueSchedule != null)
            return;
        queueSchedule = scheduler.scheduleWithFixedDelay(() -> {
            connectionLock.lock();
            try {
                Request r = queue.poll();
                if (r != null) {
                    runningRequest = r;
                    processRequest(r);
                }
            } catch (Exception e) {
                logger.warn("{}", e.getMessage());
            } finally {
                runningRequest = null;
                connectionLock.unlock();
            }
        }, QUEUE_POLL_RATE, QUEUE_POLL_RATE, TimeUnit.SECONDS);
    }

    /**
     * Method invoked by {@link BridgeHandler}
     * during the disposing process. Should not be used by others.
     */
    public void close() {
        ScheduledFuture<?> q = queueSchedule;
        if (q != null)
            q.cancel(true);
        queueSchedule = null;
        runningRequest = null;
    }

    private HashSet<Requester> requesters(Request.CommandInterface command,
            HashMap<Request.CommandInterface, @Nullable Set<Requester>> requesters) {
        HashSet<Requester> result = new HashSet<>();
        if (requesters != null) {
            Set<Requester> set;
            set = requesters.get(Command.ALL);
            if (set != null)
                result.addAll(set);
            set = requesters.get(command);
            if (set != null)
                result.addAll(set);
        }
        return result;
    }

    private void processRequest(Request request) {
        try {
            Request.CommandInterface command = (Request.CommandInterface) request.command;
            HashMap<Request.ParameterInterface, @Nullable String> arguments = new HashMap<>();
            for (Requester provider : requesters(command, argumentProviders))
                arguments.putAll(provider.argumentsForRequest(request.command));
            for (Requester receiver : requesters(command, responseReceivers))
                receiver.willPostRequest(request.command, arguments);
            Response response = request.post(arguments);
            for (Requester receiver : requesters(command, responseReceivers))
                receiver.requestComplete(response);
        } catch (Request.MissingParameterException | Request.HTTPStatusCodeException | Request.MaxAttemptsException
                | Request.ErrorResponseException | InterruptedException | ExecutionException | TimeoutException e) {
            logger.warn("{}", e.getMessage());
        }
    }

    private final ReentrantLock connectionLock = new ReentrantLock();

    /**
     * Schedules a {@link Request} object by placing it in a queue and then
     * processing it via a {@link Runnable} running periodically on a separate thread.
     * If the queue already contains a similar request, the method does nothing.
     * 
     * @param type The {@link Request.CommandInterface} object to schedule.
     */
    public void schedule(Enum<? extends Request.CommandInterface> type) {
        connectionLock.lock();
        try {
            Request.CommandInterface c = (Request.CommandInterface) type;
            Request request = new Request(type, httpClient, interval, attempts);
            if (request.equals(runningRequest) || queue.contains(request))
                logger.debug("{}: Coalescing request", c.code());
            else
                queue.add(request);
        } finally {
            connectionLock.unlock();
        }
    }

    /**
     * Submits a {@link Request} right away. Blocking call that should be avoided on
     * the main thread. Used by {@link BridgeHandler}
     * to "insert" login requests before a scheduled task if the hash is missing.
     * 
     * @param type The {@link Request.CommandInterface} object to submit.
     */
    public void post(Enum<? extends Request.CommandInterface> type) {
        connectionLock.lock();
        try {
            Request request = new Request(type, httpClient, interval, attempts);
            processRequest(request);
        } finally {
            connectionLock.unlock();
        }
    }

    /**
     * Attempts to cancel scheduled and running {@link Request} objects
     * by clearing the queue and invoking {@link Request#cancel()}.
     */
    public void cancel() {
        Request r = runningRequest;
        if (r != null)
            r.cancel();
        connectionLock.lock();
        try {
            queue.clear();
        } finally {
            connectionLock.unlock();
        }
    }
}
