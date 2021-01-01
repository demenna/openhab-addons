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

import static org.openhab.binding.verisure.internal.VerisureBindingConstants.THING_TYPE_ALARM_TYPE1;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AlarmHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Riccardo De Menna - Initial contribution
 */
@NonNullByDefault
public class AlarmHandler extends BaseThingHandler implements Connection.Requester {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_ALARM_TYPE1);

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public AlarmHandler(Thing thing) {
        super(thing);
    }

    private @Nullable Connection connection;

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(() -> {
            try {
                @Nullable
                Bridge bridge = getBridge();
                if (bridge == null)
                    throw new IllegalStateException("Missing bridge");
                @Nullable
                BridgeHandler handler = (BridgeHandler) bridge.getHandler();
                if (handler == null)
                    throw new IllegalStateException("Missing handler");
                Connection c = handler.connection;
                for (Command command : Command.values()) {
                    c.registerAsArgumentProviderForRequestTypes(this, command);
                    c.registerAsResponseReceiverForRequestTypes(this, command);
                }
                connection = c;
                BigDecimal refresh = (BigDecimal) getConfig().get("refresh");
                if (refresh != null)
                    scheduleRefresh(refresh.intValue());
                updateStatus(ThingStatus.ONLINE);
            } catch (IllegalStateException e) {
                logger.warn("{}", e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            }
        });
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> s = scheduledRefresh;
        if (s != null)
            s.cancel(true);
        scheduledRefresh = null;
        Connection c = connection;
        if (c != null) {
            c.unregisterAsArgumentProvider(this);
            c.unregisterAsResponseReceiver(this);
        }
        updateStatus(ThingStatus.OFFLINE);
    }

    public void updateState(AlarmChannelUID channel, String state) {
        updateState(channel.name(), new StringType(state));
    }

    public enum Parameter implements Request.ParameterInterface {
        INSTALLATION("numinst"),
        PANEL("panel"),
        SERVICE("idservice"),
        IBS("instibs"),
        DEVICE("device"),
        SIGNAL("idsignal"),
        SIGNALTYPE("signaltype");

        private final String code;

        Parameter(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }
    }

    public enum AlarmChannelUID {
        status,
        perimeter,
        installationName,
        codewordVerisure,
        codewordCustomer,
        codewordCoercion,
        emails,
        phones,
        devices,
        svr;

        public static AlarmChannelUID from(ChannelUID uid) {
            return valueOf(uid.getId());
        }
    }

    public enum Command implements Request.CommandInterface {
        // @formatter:off
        INSTALLATION(false,"MYINSTALLATION"),
        SVR(false,"SVR"),
        STATUS(true,"EST"),
        DISARMED(true,"DARM"),
        ARMED_ALL(true,"ARM"),
        ARMED_NIGHT(true,"ARMNIGHT"),
        ARMED_DAY(true,"ARMDAY"),
        ARMED_PERIMETER(true,"PERI"),
        IMG(true,"IMG"),
        INF(false,"INF");
        // @formatter:on

        public final boolean async;
        private final String code;

        Command(boolean async, String code) {
            this.async = async;
            this.code = code;
        }

        @Override
        public String code() {
            return async ? code + "1" : code;
        }

        @Override
        public String followUpCode() {
            return code + "2";
        }

        @Override
        public boolean async() {
            return async;
        }
    }

    @SuppressWarnings("unused")
    public enum AlarmState {
        // @formatter:off
        DISARMED("0","0","0"),
        ARMED_ALL("A","0","A","1"),
        ARMED_NIGHT("Q","0","Q"),
        ARMED_DAY("P","0","P"),
        ARMED_PERIMETER("0","3","3"),
        ARMED_ALL_PERIMETER("A","3","4"),
        ARMED_DAY_PERIMETER("P","3","B"),
        ARMED_NIGHT_PERIMETER("Q","3","C"),
        TRIGGERED("?","?","???"), // TODO find out the status code
        SOS("?","?","???"), // TODO find out the status code
        BUSY("BUSY","BUSY","BUSY"),
        UNKNOWN("UNKNOWN","UNKNOWN","UNKNOWN");
        // @formatter:on

        private final String indoorCode;
        private final String outdoorCode;
        private final String[] parserCodes;

        AlarmState(String indoorCode, String outdoorCode, String... parserCodes) {
            this.indoorCode = indoorCode;
            this.outdoorCode = outdoorCode;
            this.parserCodes = parserCodes;
        }

        public AlarmState indoorState() {
            return AlarmState.forCode(indoorCode);
        }

        public AlarmState outdoorState() {
            return AlarmState.forCode(outdoorCode);
        }

        private final static HashMap<String, AlarmState> codeMap = new HashMap<>();

        static {
            for (AlarmState c : AlarmState.values())
                for (String s : c.parserCodes)
                    codeMap.put(s, c);
        }

        public static AlarmState forCode(String code) {
            @Nullable
            AlarmState r = codeMap.get(code);
            if (r == null)
                throw new IllegalArgumentException("No valid alarm State for " + code);
            return r;
        }

        public void updateHandlerState(AlarmHandler handler) {
            handler.updateState(AlarmChannelUID.status, indoorState().name());
            handler.updateState(AlarmChannelUID.perimeter, outdoorState().name());
        }
    }

    @SuppressWarnings("unused")
    public enum InstallationElement implements Response.Evaluator {
        NAME("/PET/INSTALLATION/@alias", AlarmChannelUID.installationName),
        CODEWORD_VERISURE("/PET/INSTALLATION/CODEWORDS/@securitas", AlarmChannelUID.codewordVerisure),
        CODEWORD_CUSTOMER("/PET/INSTALLATION/CODEWORDS/@customer", AlarmChannelUID.codewordCustomer),
        CODEWORD_COERCION("/PET/INSTALLATION/CODEWORDS/@coercion", AlarmChannelUID.codewordCoercion),
        EMAILS("/PET/INSTALLATION/EMAILS/EMAIL/@address", AlarmChannelUID.emails),
        PHONES("/PET/INSTALLATION/PHONES/PHONE/@number", AlarmChannelUID.phones),
        DEVICES("/PET/INSTALLATION/DEVICES/text()", AlarmChannelUID.devices);

        InstallationElement(String path, AlarmChannelUID channelUID) {
            this.channelUID = channelUID;
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

        private final AlarmChannelUID channelUID;
    }

    public enum InstallationState {
        IDLE,
        BUSY;

        private final Logger logger = LoggerFactory.getLogger(getClass());

        public void updateHandlerState(AlarmHandler handler) {
            updateHandlerState(handler, null);
        }

        public void updateHandlerState(AlarmHandler handler, @Nullable Response response) {
            for (InstallationElement element : InstallationElement.values())
                try {
                    handler.updateState(element.channelUID, response != null ? response.get(element) : name());
                } catch (Response.MissingElementException e) {
                    logger.warn("{}", e.getMessage());
                }
        }
    }

    private @Nullable ScheduledFuture<?> scheduledRefresh;

    private void scheduleRefresh(int refresh) {
        ScheduledFuture<?> s = scheduledRefresh;
        if (s != null)
            s.cancel(true);
        if (refresh > 0)
            scheduledRefresh = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    Connection c = connection;
                    if (c != null)
                        c.schedule(Command.STATUS);
                } catch (Exception e) {
                    logger.warn("{}", e.getMessage());
                }
            }, refresh, refresh, TimeUnit.MINUTES);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, org.openhab.core.types.Command command) {
        Connection c = connection;
        if (c == null) {
            logger.warn("Missing connection");
            return;
        }
        if ("CANCEL".equals(command.toString())) {
            c.cancel();
            c.schedule(Command.STATUS);
            return;
        }
        if (command instanceof RefreshType)
            switch (AlarmChannelUID.from(channelUID)) {
                case status:
                case perimeter:
                    c.schedule(Command.STATUS);
                    break;
                case installationName:
                case codewordVerisure:
                case codewordCustomer:
                case codewordCoercion:
                case emails:
                case phones:
                case devices:
                    c.schedule(Command.INSTALLATION);
                    break;
                case svr:
                    c.schedule(Command.SVR);
                    break;
            }
        else
            switch (AlarmChannelUID.from(channelUID)) {
                case status:
                case perimeter:
                    c.schedule(Command.valueOf(command.toString()));
                    break;
                default:
                    c.schedule(Command.valueOf(channelUID.getId()));
                    break;
            }
    }

    @Override
    public Map<Request.ParameterInterface, @Nullable String> argumentsForRequest(
            Enum<? extends Request.CommandInterface> command) {
        HashMap<Request.ParameterInterface, @Nullable String> map = new HashMap<>();
        Configuration config = getConfig();
        map.put(Parameter.INSTALLATION, (String) config.get("installation"));
        map.put(Parameter.PANEL, (String) config.get("panel"));
        return map;
    }

    @Override
    public void willPostRequest(Enum<? extends Request.CommandInterface> command,
            Map<Request.ParameterInterface, @Nullable String> arguments) throws Request.MissingParameterException {
        if (arguments.get(Parameter.INSTALLATION) == null)
            throw new Request.MissingParameterException(Parameter.INSTALLATION);
        if (arguments.get(Parameter.PANEL) == null)
            throw new Request.MissingParameterException(Parameter.PANEL);
        Command c = (Command) command;
        switch (c) {
            case STATUS:
            case DISARMED:
            case ARMED_ALL:
            case ARMED_NIGHT:
            case ARMED_DAY:
            case ARMED_PERIMETER:
                AlarmState.BUSY.updateHandlerState(this);
                break;
            case INSTALLATION:
                InstallationState.BUSY.updateHandlerState(this);
                break;
            case SVR:
                // TODO add missing implementation
                break;
            case IMG:
            case INF:
                // TODO add missing implementation
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + command);
        }
    }

    @Override
    public void requestComplete(Response response) {
        Command c = (Command) response.command;
        try {
            switch (c) {
                case STATUS:
                case DISARMED:
                case ARMED_ALL:
                case ARMED_NIGHT:
                case ARMED_DAY:
                case ARMED_PERIMETER:
                    if (response.success())
                        AlarmState.forCode(response.status()).updateHandlerState(this);
                    else
                        AlarmState.UNKNOWN.updateHandlerState(this);
                    break;
                case INSTALLATION:
                    InstallationState.IDLE.updateHandlerState(this, response.success() ? response : null);
                    break;
                case SVR:
                    if (response.success())
                        updateState(AlarmChannelUID.svr, response.sim());
                    break;
                case IMG:
                case INF:
                    // TODO add missing part
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + response.command);
            }
        } catch (Response.MissingElementException e) {
            logger.warn("{}", e.getMessage());
        }
    }
}
