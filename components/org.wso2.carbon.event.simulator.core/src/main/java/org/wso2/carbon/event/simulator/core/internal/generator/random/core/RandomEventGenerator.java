/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.event.simulator.core.internal.generator.random.core;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.event.simulator.core.exception.EventGenerationException;
import org.wso2.carbon.event.simulator.core.exception.InsufficientAttributesException;
import org.wso2.carbon.event.simulator.core.exception.InvalidConfigException;
import org.wso2.carbon.event.simulator.core.exception.SimulatorInitializationException;
import org.wso2.carbon.event.simulator.core.internal.bean.RandomSimulationDTO;
import org.wso2.carbon.event.simulator.core.internal.generator.EventGenerator;
import org.wso2.carbon.event.simulator.core.internal.generator.random.RandomAttributeGenerator;
import org.wso2.carbon.event.simulator.core.internal.util.EventConverter;
import org.wso2.carbon.event.simulator.core.internal.util.EventSimulatorConstants;
import org.wso2.carbon.event.simulator.core.internal.util.RandomAttrGeneratorFactoryImpl;
import org.wso2.carbon.event.simulator.core.service.EventSimulatorDataHolder;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.query.api.definition.Attribute;

import static org.wso2.carbon.event.simulator.core.internal.util.CommonOperations.checkAvailability;
import static org.wso2.carbon.event.simulator.core.internal.util.CommonOperations.checkAvailabilityOfArray;

import java.util.ArrayList;
import java.util.List;

/**
 * RandomEventGenerator class implements interface EventGenerator.
 * This class is responsible for producing events using random data generation.
 */
public class RandomEventGenerator implements EventGenerator {
    private static final Logger log = LoggerFactory.getLogger(RandomEventGenerator.class);
    private RandomSimulationDTO randomSimulationConfig;
    private List<RandomAttributeGenerator> randomAttrGenerators;
    private List<Attribute> streamAttributes;
    private long currentTimestamp;
    private long timestampEndTime;
    private Event nextEvent = null;

    /**
     * constructor used to initialize random event generator and set the timestamp start and end time.
     *
     * @param sourceConfiguration JSON object containing configuration for random event generation
     * @param timestampStartTime  least possible value for timestamp
     * @param timestampEndTime    maximum possible value for timestamp
     * @throws InvalidConfigException          if random stream simulation configuration is invalid
     * @throws InsufficientAttributesException if the number of random attribute configurations provided is not equal
     *                                         to the number of stream attributes
     */
    public RandomEventGenerator(JSONObject sourceConfiguration, long timestampStartTime, long timestampEndTime)
            throws InvalidConfigException, InsufficientAttributesException {
//        create a RandomSimulationDTO object containing random simulation configuration
        randomSimulationConfig = validateRandomConfiguration(sourceConfiguration);
//        set timestamp boundary for event generation
        this.currentTimestamp = timestampStartTime;
        this.timestampEndTime = timestampEndTime;
    }


    /**
     * start() method is used to retrieve the first event
     */
    @Override
    public void start() {
        getNextEvent();

        if (log.isDebugEnabled()) {
            log.debug("Stop random generator for stream '" + randomSimulationConfig.getStreamName() + "'");
        }
    }


    /**
     * stop() method
     */
    @Override
    public void stop() {
        if (log.isDebugEnabled()) {
            log.debug("Stop random generator for stream '" + randomSimulationConfig.getStreamName() + "'");
        }
//        do nothing
    }


    /**
     * poll() method is used to retrieve the nextEvent of generator and assign the next event of with least timestamp
     * as nextEvent
     *
     * @return nextEvent
     */
    @Override
    public Event poll() {
        Event tempEvent = null;
        /**
         * if nextEvent is not null, it implies that more events may be generated by the generator. Hence call
         * getNExtEvent(0 method to assign the next event with least timestamp as nextEvent.
         * else if nextEvent == null, it implies that generator will not generate any more events. Hence return null.
         * */
        if (nextEvent != null) {
            tempEvent = nextEvent;
            getNextEvent();
        }
        return tempEvent;
    }


    /**
     * peek() method is used to access the nextEvent of generator
     *
     * @return nextEvent
     */
    @Override
    public Event peek() {
        return nextEvent;
    }


    /**
     * getNextEvent() method is used to get the next event with least timestamp
     */
    @Override
    public void getNextEvent() {
        try {
            /**
             * if timestampEndTime != null and is greater than the currentTimestamp, more events can be generated.
             * else, nextEvent is set to null to indicate that the generator will not produce any more events
             * */
            if (timestampEndTime == -1 || currentTimestamp <= timestampEndTime) {
                Object[] attributeValues = new Object[streamAttributes.size()];
                int i = 0;
                for (RandomAttributeGenerator randomAttributeGenerator : randomAttrGenerators) {
                    attributeValues[i++] = randomAttributeGenerator.generateAttribute();
                }
                nextEvent = EventConverter.eventConverter(streamAttributes, attributeValues, currentTimestamp);
                currentTimestamp += randomSimulationConfig.getTimestampInterval();
            } else {
                nextEvent = null;
            }
        } catch (EventGenerationException e) {
            log.error("Error occurred when generating an even using random event generator to simulate stream '" +
                    randomSimulationConfig.getStreamName() + "' using source configuration " + this.toString(), e);
            throw new EventGenerationException("Error occurred when generating an even using random event generator " +
                    "to simulate stream '" + randomSimulationConfig.getStreamName() + "' using source configuration " +
                    this.toString(), e);
        }
    }

    /**
     * getStreamName() method returns the name of the stream to which events are generated
     *
     * @return stream name
     */
    @Override
    public String getStreamName() {
        return randomSimulationConfig.getStreamName();
    }


    /**
     * getExecutionPlanName() method returns the name of the execution plan to which events are generated
     *
     * @return execution plan name
     */
    @Override
    public String getExecutionPlanName() {
        return randomSimulationConfig.getExecutionPlanName();
    }

    /**
     * reinitializeResources() is used to reinitialize resources used for simulation when restarting a stopped
     * simulation
     */
    @Override
    public void initializeResources() {
//        this method is not needed for random event generation
    }

    /**
     * validateRandomConfiguration() method parses the database simulation configuration into a DBSimulationDTO object
     *
     * @param sourceConfig JSON object containing configuration required to simulate stream
     * @throws InvalidConfigException if the stream configuration is invalid
     */
    private RandomSimulationDTO validateRandomConfiguration(JSONObject sourceConfig) throws InvalidConfigException,
            InsufficientAttributesException {
        /**
         * set properties to RandomSimulationDTO.
         *
         * Perform the following checks prior to setting the properties.
         * 1. has
         * 2. isNull
         * 3. isEmpty
         *
         * if any of the above checks fail, throw an exception indicating which property is missing.
         * */
        if (!checkAvailability(sourceConfig, EventSimulatorConstants.STREAM_NAME)) {
            throw new InvalidConfigException("Stream name is required for random data simulation. Invalid source" +
                    " configuration provided : " + sourceConfig.toString());
        }
        if (!checkAvailability(sourceConfig, EventSimulatorConstants.EXECUTION_PLAN_NAME)) {
            throw new InvalidConfigException("Execution plan name is required for random simulation of stream '" +
                    sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Invalid source configuration " +
                    "provided : " + sourceConfig.toString());
        }
//        retrieve stream attributes of stream being simulated
        streamAttributes = EventSimulatorDataHolder.getInstance().getEventStreamService()
                .getStreamAttributes(sourceConfig.getString(EventSimulatorConstants.EXECUTION_PLAN_NAME),
                        sourceConfig.getString(EventSimulatorConstants.STREAM_NAME));
        /**
         * check whether the execution plan has been deployed.
         * if streamAttributes == null, it implies that execution plan has not been deployed yet hence throw an
         * exception
         * */
        if (streamAttributes != null) {
            /**
             * validate attribute configuration of the random stream configuration
             * if all random attributes can be initialized without an error, create the random simulation configuration
             * object
             * */
            if (checkAvailabilityOfArray(sourceConfig, EventSimulatorConstants.ATTRIBUTE_CONFIGURATION)) {
                randomAttrGenerators = new ArrayList<>();
                if (streamAttributes.size() ==
                        sourceConfig.getJSONArray(EventSimulatorConstants.ATTRIBUTE_CONFIGURATION).length()) {
//        create attribute generators for each attribute configuration using random attribute generator factory class
                    RandomAttrGeneratorFactoryImpl attrGeneratorFactory = new RandomAttrGeneratorFactoryImpl();
                    for (int i = 0; i < sourceConfig.getJSONArray(EventSimulatorConstants.ATTRIBUTE_CONFIGURATION).
                            length(); i++) {
                        randomAttrGenerators.add(attrGeneratorFactory.getRandomAttrGenerator(
                                sourceConfig.getJSONArray(EventSimulatorConstants.ATTRIBUTE_CONFIGURATION).
                                        getJSONObject(i), streamAttributes.get(i).getType()));
                    }
                } else {
                    log.error("Stream '" + sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "' has "
                            + streamAttributes.size() + " attribute(s) but random source configuration " +
                            sourceConfig.toString() + " contains attribute configurations for only " +
                            sourceConfig.getJSONArray(EventSimulatorConstants.ATTRIBUTE_CONFIGURATION).length() +
                            "attribute(s).");
                    throw new InsufficientAttributesException("Stream '" +
                            sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "' has "
                            + streamAttributes.size() + " attribute(s) but random source configuration " +
                            sourceConfig.toString() + " contains attribute configurations for only " +
                            sourceConfig.getJSONArray(EventSimulatorConstants.ATTRIBUTE_CONFIGURATION).length() +
                            "attribute(s).");
                }
            } else {
                throw new InvalidConfigException("Attribute configuration is required for random simulation of" +
                        " stream '" + sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Invalid " +
                        "source configuration : " + sourceConfig.toString());
            }
            /**
             * if the user doesn't specify a timestamp interval for random event generation, take 1 second as the
             * default time interval
             * */
            long timestampInterval;
            if (checkAvailability(sourceConfig, EventSimulatorConstants.TIMESTAMP_INTERVAL)) {
                timestampInterval = sourceConfig.getLong(EventSimulatorConstants.TIMESTAMP_INTERVAL);
                if (timestampInterval <= 0) {
                    throw new InvalidConfigException("Time interval between timestamps of 2 consecutive events" +
                            " must be a positive value.");
                }
            } else {
                log.warn("Time interval is required for random data simulation of stream '" +
                        sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Time interval will " +
                        "be set to 1 second for source configuration : " + sourceConfig.toString());
                timestampInterval = 1000;
            }
//        create a RandomSimulationDTO object containing random simulation configuration
            RandomSimulationDTO randomSimulationDTO = new RandomSimulationDTO();
            randomSimulationDTO.setStreamName(sourceConfig.getString(EventSimulatorConstants.STREAM_NAME));
            randomSimulationDTO.setExecutionPlanName(sourceConfig
                    .getString(EventSimulatorConstants.EXECUTION_PLAN_NAME));
            randomSimulationDTO.setTimestampInterval(timestampInterval);
            return randomSimulationDTO;
        } else {
            log.error("Error occurred when initializing random event generator to simulate stream '" +
                    sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Stream '" +
                    sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "' does not exist. " +
                    "Invalid source configuration : " + sourceConfig.toString());
            throw new SimulatorInitializationException("Error occurred when initializing random event generator to " +
                    "simulate stream '" + sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Stream '" +
                    sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "' does not exist. " +
                    "Invalid source configuration : " + sourceConfig.toString());
        }
    }

    @Override
    public String toString() {
        StringBuilder config = new StringBuilder(randomSimulationConfig.toString());
        randomAttrGenerators.forEach(randomAttributeGenerator ->
                config.append(randomAttributeGenerator.getAttributeConfiguration()));
        return config.toString();
    }
}
