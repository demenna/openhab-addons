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

import static org.openhab.binding.verisure.internal.VerisureBindingConstants.THING_TYPE_BRIDGE_TYPE1;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BridgeHandler} acts as wrapper around a Verisure Type1 subscription account.
 * Configuration is done by passing parameters through a .things configuration file, with user credentials
 * and optional connection details.
 * <p>
 * The constructor will take care of instantiating a {@link Connection}
 * (available as a public field for others) with which the bridge will register as both argument provider
 * and response receiver (See {@link Connection.Requester} interface).
 * <p>
 * It will mostly intervene in the Requester cycle by:
 * <ul>
 * <li>Ensuring mandatory credentials are present among the arguments</li>
 * <li>Keeping track of the HASH value passed by Verisure</li>
 * <li>Ensuring Login is executed if HASH is missing</li>
 * <li>Scheduling and rescheduling a Logout after a configurable idle time</li>
 * </ul>
 * <p>
 * Allowed configuration parameters are:
 * <ul>
 * <li>user -
 * Mandatory user name used to login with Verisure
 * (usually matches a phone number)</li>
 * <li>password -
 * Mandatory password used to login with Verisure.</li>
 * <li>country -
 * Mandatory country for your subscription.
 * Value must match one of {@link Country} ((i.e. ITALY, SPAIN etc.)</li>
 * <li>timeout -
 * Optional maximum number of idle seconds before connection logout.
 * Defaults to 300 (5 minutes)</li>
 * <li>interval -
 * Optional number of seconds to wait between attempts to read the result
 * of asynchronous requests.
 * Defaults to 3 seconds.</li>
 * <li>attempts -
 * Optional maximum number of attempts to read the result of asynchronous requests
 * before giving up. Defaults to 10</li>
 * </ul>
 * 
 * @author Riccardo De Menna - Initial contribution
 */
@NonNullByDefault
public class BridgeHandler extends BaseBridgeHandler implements Connection.Requester {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_BRIDGE_TYPE1);

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private int timeout = 60;
    private @Nullable String hash;
    Connection connection;

    public BridgeHandler(Bridge bridge, HttpClient httpClient) {
        super(bridge);
        connection = new Connection(httpClient, scheduler);
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(() -> {
            Configuration config = getConfig();
            BigDecimal timeout = (BigDecimal) config.get("timeout");
            if (timeout != null && timeout.intValue() > 0)
                this.timeout = timeout.intValue();
            connection.registerAsArgumentProviderForRequestTypes(this, Connection.Command.ALL);
            connection.registerAsResponseReceiverForRequestTypes(this, Connection.Command.ALL);
            connection.open(config);
            updateStatus(ThingStatus.ONLINE);
        });
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> s = scheduledLogout;
        if (s != null)
            s.cancel(true);
        scheduledLogout = null;
        connection.close();
        connection.unregisterAsArgumentProvider(this);
        connection.unregisterAsResponseReceiver(this);
        updateStatus(ThingStatus.OFFLINE);
    }

    /**
     * Convenience enum that simplifies configuration by providing country and language codes
     * with a single parameter.
     */
    @SuppressWarnings("unused")
    public enum Country {
        ITALY("IT", "it"),
        FRANCE("FR", "fr"),
        SPAIN("ES", "es"),
        PORTUGAL("PT", "pt"),
        ENGLAND("EN", "en");

        public final String countryCode;
        public final String languageCode;

        Country(String countryCode, String languageCode) {
            this.countryCode = countryCode;
            this.languageCode = languageCode;
        }
    }

    public enum Parameter implements Request.ParameterInterface {
        COUNTRY("Country"),
        LANGUAGE("lang"),
        USER("user"),
        PASSWORD("pwd"),
        HASH("hash");

        private final String code;

        Parameter(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }
    }

    public enum Command implements Request.CommandInterface {
        LOGIN("LOGIN"),
        LOGOUT("CLS");

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

    @Override
    public void handleCommand(ChannelUID channelUID, org.openhab.core.types.Command command) {
        // do something
    }

    @Override
    public Map<Request.ParameterInterface, @Nullable String> argumentsForRequest(
            Enum<? extends Request.CommandInterface> command) {
        HashMap<Request.ParameterInterface, @Nullable String> map = new HashMap<>();
        Configuration config = getConfig();
        Country c = Country.valueOf(((String) config.get("country")).toUpperCase());
        map.put(Parameter.COUNTRY, c.countryCode);
        map.put(Parameter.LANGUAGE, c.languageCode);
        map.put(Parameter.USER, (String) config.get("user"));
        if (Command.LOGIN.equals(command))
            map.put(Parameter.PASSWORD, (String) config.get("password"));
        else {
            if (hash == null)
                connection.post(Command.LOGIN);
            map.put(Parameter.HASH, hash);
        }
        return map;
    }

    @Override
    public void willPostRequest(Enum<? extends Request.CommandInterface> command,
            Map<Request.ParameterInterface, @Nullable String> arguments) throws Request.MissingParameterException {
        if (arguments.get(Parameter.COUNTRY) == null)
            throw new Request.MissingParameterException(Parameter.COUNTRY);
        if (arguments.get(Parameter.LANGUAGE) == null)
            throw new Request.MissingParameterException(Parameter.LANGUAGE);
        if (arguments.get(Parameter.USER) == null)
            throw new Request.MissingParameterException(Parameter.USER);
        if (command instanceof Command) {
            Command t = (Command) command;
            switch (t) {
                case LOGIN:
                    if (arguments.get(Parameter.PASSWORD) == null)
                        throw new Request.MissingParameterException(Parameter.PASSWORD);
                    break;
                case LOGOUT:
                    if (arguments.get(Parameter.HASH) == null)
                        throw new Request.MissingParameterException(Parameter.HASH);
                    break;
            }
        } else if (arguments.get(Parameter.HASH) == null)
            throw new Request.MissingParameterException(Parameter.HASH);
    }

    @Override
    public void requestComplete(Response response) {
        try {
            if (response.command instanceof Command) {
                Command c = (Command) response.command;
                switch (c) {
                    case LOGIN:
                        if (response.success()) {
                            hash = null;
                            String hash = response.hash();
                            assert hash != null;
                            this.hash = hash;
                            scheduleLogout();
                        } else
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                    response.message());
                        break;
                    case LOGOUT:
                        hash = null;
                        ScheduledFuture<?> sl = scheduledLogout;
                        if (sl != null)
                            sl.cancel(true);
                        if (!response.success())
                            logger.warn("Logout failed.");
                        break;
                }
            } else
                scheduleLogout();
        } catch (Response.MissingElementException e) {
            logger.warn("{}", e.getMessage());
        }
    }

    private @Nullable ScheduledFuture<?> scheduledLogout;

    private void scheduleLogout() {
        ScheduledFuture<?> s = scheduledLogout;
        if (s != null)
            s.cancel(true);
        scheduledLogout = scheduler.schedule(() -> {
            if (hash != null)
                connection.post(Command.LOGOUT);
        }, timeout, TimeUnit.SECONDS);
    }
}
