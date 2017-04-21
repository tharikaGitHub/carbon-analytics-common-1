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

package org.wso2.carbon.event.simulator.core.internal.generator.database.core;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.event.simulator.core.exception.EventGenerationException;
import org.wso2.carbon.event.simulator.core.exception.InsufficientAttributesException;
import org.wso2.carbon.event.simulator.core.exception.InvalidConfigException;
import org.wso2.carbon.event.simulator.core.exception.SimulatorInitializationException;
import org.wso2.carbon.event.simulator.core.internal.bean.DBSimulationDTO;
import org.wso2.carbon.event.simulator.core.internal.generator.EventGenerator;
import org.wso2.carbon.event.simulator.core.internal.generator.database.util.DatabaseConnector;
import org.wso2.carbon.event.simulator.core.internal.util.EventConverter;
import org.wso2.carbon.event.simulator.core.internal.util.EventSimulatorConstants;
import org.wso2.carbon.event.simulator.core.service.EventSimulatorDataHolder;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.query.api.definition.Attribute;

import static org.wso2.carbon.event.simulator.core.internal.util.CommonOperations.checkAvailability;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DatabaseEventGenerator class is used to generate events from a database
 */
public class DatabaseEventGenerator implements EventGenerator {
    private static final Logger log = LoggerFactory.getLogger(DatabaseEventGenerator.class);
    private long timestampStartTime;
    private long timestampEndTime;
    private long currentTimestamp;
    private DBSimulationDTO dbSimulationConfig;
    private Event nextEvent = null;
    private ResultSet resultSet;
    private DatabaseConnector databaseConnection;
    private List<Attribute> streamAttributes;
    private List<String> columnNames;

    /**
     * DatabaseEventGenerator() constructor
     * It performs the following tasks
     * 1.initializes database event generator
     * 2.set the timestamp start and end time.
     *
     * @param sourceConfiguration JSON object containing configuration for database event generation
     * @param timestampStartTime  least possible value for timestamp
     * @param timestampEndTime    maximum possible value for timestamp
     * @throws InsufficientAttributesException if the number of columns specified is not equal to the number of
     *                                         stream attributes
     */
    public DatabaseEventGenerator(JSONObject sourceConfiguration, long timestampStartTime, long timestampEndTime)
            throws InvalidConfigException, InsufficientAttributesException {
//        create a dbSimulatioDTO object containing database simulation configuration
        dbSimulationConfig = validateDBConfiguration(sourceConfiguration);
//        set timestamp boundary
        this.timestampStartTime = timestampStartTime;
        this.timestampEndTime = timestampEndTime;
        if (log.isDebugEnabled()) {
            log.debug("Timestamp range initiated for database event generator for stream '" +
                    dbSimulationConfig.getStreamName() + "'. Timestamp start time : " + timestampStartTime +
                    " and timestamp end time : " + timestampEndTime);
        }
        if (dbSimulationConfig.getTimestampAttribute() == null) {
            currentTimestamp = timestampStartTime;
        }
        columnNames = dbSimulationConfig.getColumnNames();
        initializeResources();
    }

    /**
     * start() method is used to retrieve the resultSet from the data source and to obtain the first event
     */
    @Override
    public void start() {
        try {
            resultSet = databaseConnection.getDatabaseEventItems(dbSimulationConfig.getTableName(),
                    dbSimulationConfig.getColumnNames(), dbSimulationConfig.getTimestampAttribute(),
                    timestampStartTime, timestampEndTime);
            if (resultSet != null && !resultSet.isBeforeFirst()) {
                throw new EventGenerationException("Table '" + dbSimulationConfig.getTableName() + "' contains " +
                        " no entries for the columns specified in source configuration " +
                        dbSimulationConfig.toString());
            }
            getNextEvent();
            if (log.isDebugEnabled() && resultSet != null) {
                log.debug("Retrieved resultset to simulate stream '" + dbSimulationConfig.getStreamName() +
                        "' and initialized variable nextEvent.");
            }
        } catch (SQLException e) {
            log.error("Error occurred when retrieving resultset from database ' " +
                            dbSimulationConfig.getDataSourceLocation() + "' to simulate to simulate stream '" +
                            dbSimulationConfig.getStreamName() + "' using source configuration " +
                            dbSimulationConfig.toString(), e);
            throw new EventGenerationException("Error occurred when retrieving resultset from database ' " +
                    dbSimulationConfig.getDataSourceLocation() + "' to simulate to simulate stream '" +
                    dbSimulationConfig.getStreamName() + "' using source configuration " + dbSimulationConfig.toString()
                    , e);
        }
        if (log.isDebugEnabled()) {
            log.debug("Start database generator for stream '" + dbSimulationConfig.getStreamName() + "'");
        }
    }

    /**
     * stop() method is used to close database resources held by the database event generator
     */
    @Override
    public void stop() {
        if (databaseConnection != null) {
            databaseConnection.closeConnection();
        }
        if (log.isDebugEnabled()) {
            log.debug("Stop database generator for stream '" + dbSimulationConfig.getStreamName() + "'");
        }
    }

    /**
     * poll() method is used to retrieve the nextEvent of generator and assign the next event of with least timestamp
     * as nextEvent
     *
     * @return nextEvent
     */
    @Override
    public Event poll() {
        /**
         * if nextEvent is not null, it implies that more events may be generated by the generator. Hence call
         * getNExtEvent(0 method to assign the next event with least timestamp as nextEvent.
         * else if nextEvent == null, it implies that generator will not generate any more events. Hence return null.
         * */
        Event tempEvent = nextEvent;
        if (tempEvent != null) {
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
             * if the resultset has a next entry, create an event using that entry and assign it to nextEvent
             * else, assign null to nextEvent
             * */
            if (resultSet != null) {
                if (resultSet.next()) {
                    Object[] attributeValues = new Object[streamAttributes.size()];
                    long timestamp = -1;
                    /**
                     * if timestamp attribute is specified use the value of the respective column as timestamp
                     * else, calculate the timestamp.
                     * timestamp of first event will be currentTimestamp and timestamp of successive event
                     * will be (last event timestamp + interval)
                     * */
                    if (dbSimulationConfig.getTimestampAttribute() != null) {
                        timestamp = resultSet.getLong(dbSimulationConfig.getTimestampAttribute());
                    } else if (timestampEndTime == -1 || currentTimestamp <= timestampEndTime) {
                        timestamp = currentTimestamp;
                        currentTimestamp += dbSimulationConfig.getTimestampInterval();
                    }
                    if (timestamp != -1) {
                        int i = 0;
                        /**
                         * For each attribute in streamAttributes, use attribute type to determine the getter method
                         * to be used to access the resultset and use the attribute name to access a particular field
                         * in resultset
                         * */
                        for (Attribute attribute : streamAttributes) {
                            switch (attribute.getType()) {
                                case STRING:
                                    attributeValues[i] = resultSet.getString(columnNames.get(i++));
                                    break;
                                case INT:
                                    attributeValues[i] = resultSet.getInt(columnNames.get(i++));
                                    break;
                                case DOUBLE:
                                    attributeValues[i] = resultSet.getDouble(columnNames.get(i++));
                                    break;
                                case FLOAT:
                                    attributeValues[i] = resultSet.getFloat(columnNames.get(i++));
                                    break;
                                case BOOL:
                                    attributeValues[i] = resultSet.getBoolean(columnNames.get(i++));
                                    break;
                                case LONG:
                                    attributeValues[i] = resultSet.getLong(columnNames.get(i++));
                                    break;
                                default:
//                                this statement is never reaches since attribute type is an enum
                            }
                        }
                    }
                    nextEvent = EventConverter.eventConverter(streamAttributes, attributeValues, timestamp);
                } else {
                    nextEvent = null;
                }
            }
        } catch (SQLException e) {
            throw new EventGenerationException("Error occurred when accessing result set to simulate to simulate " +
                    "stream '" + dbSimulationConfig.getStreamName() + "' using source configuration " +
                    dbSimulationConfig.toString(), e);
        } catch (EventGenerationException e) {
            log.error("Error occurred when generating event using database event " +
                    "generator to simulate stream '" + dbSimulationConfig.getStreamName() + "' using source " +
                    "configuration " + dbSimulationConfig.toString() + ". Drop event and create next event. ", e);
            getNextEvent();
        }
    }

    /**
     * getStreamName() method returns the name of the stream to which events are generated
     *
     * @return stream name
     */
    @Override
    public String getStreamName() {
        return dbSimulationConfig.getStreamName();
    }

    /**
     * getExecutionPlanName() method returns the name of the execution plan to which events are generated
     *
     * @return execution plan name
     */
    @Override
    public String getExecutionPlanName() {
        return dbSimulationConfig.getExecutionPlanName();
    }

    /**
     * initializeResources() is used to establish the database connection
     * */
    @Override
    public void initializeResources() {
        databaseConnection = new DatabaseConnector();
        databaseConnection.connectToDatabase(dbSimulationConfig.getDriver(),
                dbSimulationConfig.getDataSourceLocation(), dbSimulationConfig.getUsername(),
                dbSimulationConfig.getPassword());
    }

    /**
     * validateDBConfiguration() method parses the database simulation configuration into a DBSimulationDTO object
     *
     * @param sourceConfig JSON object containing configuration required to simulate stream
     * @return DBSimulationDTO containing database simulation configuration
     * @throws InvalidConfigException if the stream configuration is invalid
     */
    private DBSimulationDTO validateDBConfiguration(JSONObject sourceConfig) throws InvalidConfigException,
            InsufficientAttributesException {
        /**
         * set properties to DBSimulationDTO.
         *
         * Perform the following checks prior to setting the properties.
         * 1. has
         * 2. isNull
         * 3. isEmpty
         *
         * if any of the above checks fail, throw an exception indicating which property is missing.
         * */
        if (!checkAvailability(sourceConfig, EventSimulatorConstants.STREAM_NAME)) {
            throw new InvalidConfigException("Stream name is required for database simulation. Invalid " +
                    "source configuration : " + sourceConfig.toString());
        }
        if (!checkAvailability(sourceConfig, EventSimulatorConstants.EXECUTION_PLAN_NAME)) {
            throw new InvalidConfigException("Execution plan name is required for database simulation of stream '" +
                    sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Invalid source configuration : " +
                    sourceConfig.toString());
        }
//        retrieve the stream definition
        streamAttributes = EventSimulatorDataHolder.getInstance().getEventStreamService()
                .getStreamAttributes(sourceConfig.getString(EventSimulatorConstants.EXECUTION_PLAN_NAME),
                        sourceConfig.getString(EventSimulatorConstants.STREAM_NAME));
        /**
         * check whether the execution plan has been deployed.
         * if streamAttributes == null, it implies that execution plan has not been deployed yet
         * */
        if (streamAttributes != null) {
            if (!checkAvailability(sourceConfig, EventSimulatorConstants.DRIVER)) {
                throw new InvalidConfigException("A driver name is required for database simulation of stream '" +
                        sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Invalid source" +
                        " configuration : " + sourceConfig.toString());
            }
            if (!checkAvailability(sourceConfig, EventSimulatorConstants.DATA_SOURCE_LOCATION)) {
                throw new InvalidConfigException("Data source location is required for database simulation of" +
                        " stream '" + sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Invalid " +
                        "source configuration : " + sourceConfig.toString());
            }
            if (!checkAvailability(sourceConfig, EventSimulatorConstants.USER_NAME)) {
                throw new InvalidConfigException("Username is required for database simulation of stream '" +
                        sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Invalid source" +
                        " configuration : " + sourceConfig.toString());
            }
            if (!checkAvailability(sourceConfig, EventSimulatorConstants.PASSWORD)) {
                throw new InvalidConfigException("Password is required for database simulation of stream '" +
                        sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Invalid source" +
                        " configuration : " + sourceConfig.toString());
            }
            if (!checkAvailability(sourceConfig, EventSimulatorConstants.TABLE_NAME)) {
                throw new InvalidConfigException("Table name is required for database simulation of stream '" +
                        sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Invalid source " +
                        "configuration : " + sourceConfig.toString());
            }
            /**
             * either a timestamp attribute must be specified or the timestampInterval between timestamps of 2
             * consecutive events must be specified.
             * if time interval is specified the timestamp of the first event will be the timestampStartTime and
             * consecutive event will have timestamp = last timestamp + time interval
             * if both timestamp attribute and time interval are not specified set timestamp interval to 1 second
             * */
            String timestampAttribute = null;
            long timestampInterval = -1;
            if (checkAvailability(sourceConfig, EventSimulatorConstants.TIMESTAMP_ATTRIBUTE)) {
                timestampAttribute = sourceConfig.getString(EventSimulatorConstants.TIMESTAMP_ATTRIBUTE);
            } else if (checkAvailability(sourceConfig, EventSimulatorConstants.TIMESTAMP_INTERVAL)) {
                timestampInterval = sourceConfig.getLong(EventSimulatorConstants.TIMESTAMP_INTERVAL);
                if (timestampInterval < 0) {
                    throw new InvalidConfigException("Time interval must be a positive value for database " +
                            "simulation of stream '" + sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) +
                            "'. Invalid source configuration : " + sourceConfig.toString());
                }
            } else {
                log.warn("Either timestamp end time or time interval is required for database simulation of stream '" +
                        sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Time interval will " +
                        "be set to 1 second for source configuration : " + sourceConfig.toString());
                timestampInterval = 1000;
            }
            /**
             * insert the specified column names into a list and set it to database configuration
             * check whether the source configuration has columnNames element
             * if not, throw an exception
             * else, check whether the column names are null. this is inferred as user implying that the column names
             * are identical to the stream attribute names
             * if the column names are not null but is empty throw an exception
             * else convert the string of comma separated column names into an arraylist
             * if the arraylist does not contain any empty strings, set property columnNames
             * */
            List<String> columns;
            if (sourceConfig.has(EventSimulatorConstants.COLUMN_NAMES_LIST)) {
                if (sourceConfig.isNull(EventSimulatorConstants.COLUMN_NAMES_LIST)) {
                    columns = new ArrayList<>();
                    streamAttributes.forEach(attribute -> columns.add(attribute.getName()));
                } else {
                    if (!sourceConfig.getString(EventSimulatorConstants.COLUMN_NAMES_LIST).isEmpty()) {
                        columns = Arrays.asList(sourceConfig.getString(EventSimulatorConstants.COLUMN_NAMES_LIST)
                                .split("\\s*,\\s*"));
                        if (columns.contains("")) {
                            throw new InvalidConfigException("Column name cannot contain empty values. Invalid" +
                                    " source configuration : " + sourceConfig.toString());
                        } else if (columns.size() != streamAttributes.size()) {
                            log.error("Stream '" + sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) +
                                    "' has " + streamAttributes.size() + " attribute(s) but random source" +
                                    " configuration " + sourceConfig.toString() + "contains values for only " +
                                    columns.size() + " attribute(s).");
                            throw new InsufficientAttributesException("Stream '" +
                                    sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "' has "
                                    + streamAttributes.size() + " attribute(s) but random source configuration " +
                                    sourceConfig.toString() + "contains values for only " + columns.size() +
                                    " attribute(s).");
                        }
                    } else {
                        throw new InvalidConfigException("Column names list is required for database simulation." +
                                " Invalid source configuration : " + sourceConfig.toString());
                    }
                }
            } else {
                throw new InvalidConfigException("Column names list is required for database simulation. Invalid " +
                        "source configuration : " + sourceConfig.toString());
            }
//        create DBSimulationDTO object containing db simulation configuration
            DBSimulationDTO dbSimulationDTO = new DBSimulationDTO();
            dbSimulationDTO.setStreamName(sourceConfig.getString(EventSimulatorConstants.STREAM_NAME));
            dbSimulationDTO.setExecutionPlanName(sourceConfig.getString(EventSimulatorConstants.EXECUTION_PLAN_NAME));
            dbSimulationDTO.setDriver(sourceConfig.getString(EventSimulatorConstants.DRIVER));
            dbSimulationDTO.setDataSourceLocation(sourceConfig.getString(EventSimulatorConstants.DATA_SOURCE_LOCATION));
            dbSimulationDTO.setUsername(sourceConfig.getString(EventSimulatorConstants.USER_NAME));
            dbSimulationDTO.setPassword(sourceConfig.getString(EventSimulatorConstants.PASSWORD));
            dbSimulationDTO.setTableName(sourceConfig.getString(EventSimulatorConstants.TABLE_NAME));
            dbSimulationDTO.setTimestampAttribute(timestampAttribute);
            dbSimulationDTO.setTimestampInterval(timestampInterval);
            dbSimulationDTO.setColumnNames(columns);
            return dbSimulationDTO;
        } else {
            log.error("Error occurred when initializing database event generator to simulate stream '" +
                    sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. Stream '" +
                    sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "' does not exist. Invalid source " +
                    "configuration : " + sourceConfig.toString());
            throw new SimulatorInitializationException("Error occurred when initializing database event generator " +
                    "to simulate stream '" + sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "'. " +
                    "Stream '" + sourceConfig.getString(EventSimulatorConstants.STREAM_NAME) + "' does not exist. " +
                    "Invalid source configuration : " + sourceConfig.toString());
        }

    }

    @Override
    public String toString() {
        return dbSimulationConfig.toString();
    }
}
