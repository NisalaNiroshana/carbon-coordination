/*
 * Copyright (c) 2017, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.cluster.coordinator.rdbms;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.cluster.coordinator.commons.configs.CoordinationPropertyNames;
import org.wso2.carbon.cluster.coordinator.commons.exception.ClusterCoordinationException;
import org.wso2.carbon.cluster.coordinator.commons.node.NodeDetail;
import org.wso2.carbon.cluster.coordinator.commons.util.CommunicationBusContext;
import org.wso2.carbon.cluster.coordinator.commons.util.MemberEvent;
import org.wso2.carbon.cluster.coordinator.commons.util.MemberEventType;
import org.wso2.carbon.cluster.coordinator.rdbms.internal.RDBMSCoordinationServiceHolder;
import org.wso2.carbon.cluster.coordinator.rdbms.util.RDBMSConstants;
import org.wso2.carbon.cluster.coordinator.rdbms.util.StringUtil;
import org.wso2.carbon.datasource.core.api.DataSourceService;
import org.wso2.carbon.datasource.core.exception.DataSourceException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;


/**
 * The RDBMS based communication bus layer for the nodes. This layer handles the database level calls.
 */
public class RDBMSCommunicationBusContextImpl implements CommunicationBusContext {

    /**
     * The log class
     */
    private static final Log log = LogFactory.getLog(RDBMSCommunicationBusContextImpl.class);

    /**
     * The datasource which is used to be connected to the database.
     */
    private DataSource datasource;

    public RDBMSCommunicationBusContextImpl() {
        Map<String, Object> strategyConfiguration = (Map<String, Object>) RDBMSCoordinationServiceHolder.
                getClusterConfiguration().get(CoordinationPropertyNames.STRATEGY_CONFIG_NS);
        if (strategyConfiguration == null) {
            throw new ClusterCoordinationException("Configurations under name space "
                    + CoordinationPropertyNames.STRATEGY_CONFIG_NS + " under "
                    + CoordinationPropertyNames.CLUSTER_CONFIG_NS + " not specified in deployment.yaml");
        }
        String datasourceName = (String) strategyConfiguration.get(RDBMSConstants.RDBMS_COORDINATION_DS);
        if (datasourceName == null) {
            throw new ClusterCoordinationException("No datasource specified to be used with RDBMS Coordination " +
                    "Strategy. Please check configurations under " + CoordinationPropertyNames.CLUSTER_CONFIG_NS);
        }
        DataSourceService dataSourceService = RDBMSCoordinationServiceHolder.getDataSourceService();
        try {
            this.datasource = (HikariDataSource) dataSourceService.getDataSource(datasourceName);
            if (log.isDebugEnabled()) {
                log.debug("Datasource " + datasourceName + " configured correctly");
            }
        } catch (DataSourceException e) {
            throw new ClusterCoordinationException("Error in initializing the datasource " + datasourceName, e);
        }
        createTables();
    }

    public RDBMSCommunicationBusContextImpl(DataSource dataSource) {
        this.datasource = dataSource;
        createTables();
    }

    /**
     * Create the tables needed for RDBMS communication.
     */
    private void createTables() {
        createLeaderStatusTable();
        createClusterNodeStatusTable();
        createMembershipEventTable();
        createRemovedMembersTable();
    }

    /**
     * Create Leader Status Table.
     */
    private void createLeaderStatusTable() {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.CREATE_LEADER_STATUS_TABLE);
            preparedStatement.execute();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug("Leader Status Table Created Successfully");
            }
        } catch (SQLException e) {
            throw new ClusterCoordinationException("Error in executing create leader status table query.", e);
        } finally {
            close(preparedStatement, "Execute query");
            close(connection, "Execute query");
        }
    }

    /**
     * Create Cluster Node Status table.
     */
    private void createClusterNodeStatusTable() {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.CREATE_CLUSTER_NODE_STATUS_TABLE);
            preparedStatement.execute();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug("Cluster Node Status Table Created Successfully");
            }
        } catch (SQLException e) {
            throw new ClusterCoordinationException("Error in executing create cluster node status table query.", e);
        } finally {
            close(preparedStatement, "Execute query");
            close(connection, "Execute query");
        }
    }

    /**
     * Create Membership Event Table.
     */
    private void createMembershipEventTable() {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.CREATE_MEMBERSHIP_EVENT_TABLE);
            preparedStatement.execute();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug("Membership Event Table Created Successfully");
            }
        } catch (SQLException e) {
            throw new ClusterCoordinationException("Error in executing create membership event table query.", e);
        } finally {
            close(preparedStatement, "Execute query");
            close(connection, "Execute query");
        }
    }

    /**
     * Create Removed Members Table.
     */
    private void createRemovedMembersTable() {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.CREATE_REMOVED_MEMBERS_TABLE);
            preparedStatement.execute();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug("Removed Member Table Created Successfully");
            }
        } catch (SQLException e) {
            throw new ClusterCoordinationException("Error in executing create removed members table query.", e);
        } finally {
            close(preparedStatement, "Execute query");
            close(connection, "Execute query");
        }
    }

    @Override
    public void storeMembershipEvent(String changedMember, String groupId, List<String> clusterNodes,
                                     int membershipEventType) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement storeMembershipEventPreparedStatement = null;
        String task = "Storing membership event: " + membershipEventType + " for member: " + changedMember
                + " in group " + groupId;
        try {
            connection = getConnection();
            storeMembershipEventPreparedStatement = connection
                    .prepareStatement(RDBMSConstants.PS_INSERT_MEMBERSHIP_EVENT);
            for (String clusterNode : clusterNodes) {
                storeMembershipEventPreparedStatement.setString(1, clusterNode);
                storeMembershipEventPreparedStatement.setString(2, groupId);
                storeMembershipEventPreparedStatement.setInt(3, membershipEventType);
                storeMembershipEventPreparedStatement.setString(4, changedMember);
                storeMembershipEventPreparedStatement.addBatch();
            }
            storeMembershipEventPreparedStatement.executeBatch();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(StringUtil.removeCRLFCharacters(task) + " executed successfully");
            }
        } catch (SQLException e) {
            rollback(connection, task);
            throw new ClusterCoordinationException("Error storing membership change: " + membershipEventType +
                    " for member: " + changedMember + " in group " + groupId, e);
        } finally {
            close(storeMembershipEventPreparedStatement, task);
            close(connection, task);
        }
    }

    @Override
    public String getCoordinatorNodeId(String groupId) throws ClusterCoordinationException {
        {
            Connection connection = null;
            PreparedStatement preparedStatement = null;
            ResultSet resultSet = null;
            try {
                connection = getConnection();
                preparedStatement = connection.prepareStatement(RDBMSConstants.PS_GET_COORDINATOR_NODE_ID);
                preparedStatement.setString(1, groupId);
                resultSet = preparedStatement.executeQuery();
                String coordinatorNodeId;
                if (resultSet.next()) {
                    coordinatorNodeId = resultSet.getString(1);
                    if (log.isDebugEnabled()) {
                        log.debug("Coordinator node ID: " + StringUtil.removeCRLFCharacters(coordinatorNodeId) +
                                " for group : " + StringUtil.removeCRLFCharacters(groupId));
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("No coordinator present in database for group "
                                + StringUtil.removeCRLFCharacters(groupId));
                    }
                    coordinatorNodeId = null;
                }
                if (log.isDebugEnabled()) {
                    log.debug(RDBMSConstants.TASK_GET_COORDINATOR_INFORMATION + " executed successfully");
                }
                return coordinatorNodeId;
            } catch (SQLException e) {
                String errMsg = RDBMSConstants.TASK_GET_COORDINATOR_INFORMATION;
                throw new ClusterCoordinationException("Error occurred while " + errMsg, e);
            } finally {
                close(resultSet, RDBMSConstants.TASK_GET_COORDINATOR_INFORMATION);
                close(preparedStatement, RDBMSConstants.TASK_GET_COORDINATOR_INFORMATION);
                close(connection, RDBMSConstants.TASK_GET_COORDINATOR_INFORMATION);
            }
        }
    }

    /**
     * Get the connection to the database.
     */
    private Connection getConnection() throws SQLException {
        Connection connection = datasource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    /**
     * close the connection.
     *
     * @param connection The connection to be closed
     * @param task       The task which was running
     */
    private void close(Connection connection, String task) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.error("Failed to close connection after " + StringUtil.removeCRLFCharacters(task), e);
        }
    }

    /**
     * Close the prepared statement.
     *
     * @param preparedStatement The statement to be closed
     * @param task              The task which was running
     */
    private void close(PreparedStatement preparedStatement, String task) {
        if (preparedStatement != null) {
            try {
                preparedStatement.close();
            } catch (SQLException e) {
                log.error("Closing prepared statement failed after " + StringUtil.removeCRLFCharacters(task), e);
            }
        }
    }

    /**
     * The rollback method.
     *
     * @param connection The connection object which the rollback should be applied to
     * @param task       The task which was running
     */
    private void rollback(Connection connection, String task) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (SQLException e) {
                log.warn("Rollback failed on " + StringUtil.removeCRLFCharacters(task), e);
            }
        }
    }

    @Override
    public List<MemberEvent> readMemberShipEvents(String nodeID) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        PreparedStatement clearMembershipEvents = null;
        ResultSet resultSet = null;
        List<MemberEvent> membershipEvents = new ArrayList<MemberEvent>();
        String task = "retrieving membership events destined to: " + nodeID;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_SELECT_MEMBERSHIP_EVENT);
            preparedStatement.setString(1, nodeID);
            resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                MemberEvent membershipEvent = new MemberEvent(MemberEventType.getTypeFromInt(
                        resultSet.getInt(RDBMSConstants.MEMBERSHIP_CHANGE_TYPE)),
                        resultSet.getString(RDBMSConstants.MEMBERSHIP_CHANGED_MEMBER_ID),
                        resultSet.getString(RDBMSConstants.GROUP_ID));
                membershipEvents.add(membershipEvent);
            }
            clearMembershipEvents = connection.prepareStatement(RDBMSConstants.PS_CLEAN_MEMBERSHIP_EVENTS_FOR_NODE);
            clearMembershipEvents.setString(1, nodeID);
            clearMembershipEvents.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(task + " executed successfully");
            }
            return membershipEvents;
        } catch (SQLException e) {
            throw new ClusterCoordinationException("Error occurred while " + task, e);
        } finally {
            close(resultSet, task);
            close(preparedStatement, task);
            close(clearMembershipEvents, task);
            close(connection, task);
        }
    }

    /**
     * Close the resultset.
     *
     * @param resultSet The resultset which should be closed
     * @param task      The task which was running
     */
    private void close(ResultSet resultSet, String task) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (SQLException e) {
                log.error("Closing result set failed after " + task, e);
            }
        }
    }

    /**
     * Clear all the membership events.
     *
     * @throws ClusterCoordinationException
     */
    public void clearMembershipEvents() throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement clearMembershipEvents = null;
        String task = "Clearing all membership events";
        try {
            connection = getConnection();
            clearMembershipEvents = connection.prepareStatement(RDBMSConstants.PS_CLEAR_ALL_MEMBERSHIP_EVENTS);
            clearMembershipEvents.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(task + " executed successfully");
            }
        } catch (SQLException e) {
            rollback(connection, task);
            throw new ClusterCoordinationException("Error occurred while " + task, e);
        } finally {
            close(clearMembershipEvents, task);
            close(connection, task);
        }
    }

    @Override
    public boolean createCoordinatorEntry(String nodeId, String groupId) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;

        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_INSERT_COORDINATOR_ROW);
            preparedStatement.setString(1, groupId);
            preparedStatement.setString(2, nodeId);
            preparedStatement.setLong(3, System.currentTimeMillis());
            int updateCount = preparedStatement.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(RDBMSConstants.TASK_ADD_MESSAGE_ID + " " + nodeId + " executed successfully");
            }
            return updateCount != 0;
        } catch (SQLException e) {
            rollback(connection, RDBMSConstants.TASK_ADD_MESSAGE_ID);
            return false;
        } finally {
            close(preparedStatement, RDBMSConstants.TASK_ADD_MESSAGE_ID);
            close(connection, RDBMSConstants.TASK_ADD_MESSAGE_ID);
            //  return false;
        }
    }

    @Override
    public boolean checkIsCoordinator(String nodeId, String groupId) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_GET_COORDINATOR_ROW_FOR_NODE_ID);
            preparedStatement.setString(1, nodeId);
            preparedStatement.setString(2, groupId);
            resultSet = preparedStatement.executeQuery();
            boolean isCoordinator;
            isCoordinator = resultSet.next();
            if (log.isDebugEnabled()) {
                log.debug(RDBMSConstants.TASK_CHECK_IS_COORDINATOR + " instance id: " + nodeId
                        + " group ID: " + groupId + " executed successfully");
            }
            return isCoordinator;
        } catch (SQLException e) {
            String errMsg = RDBMSConstants.TASK_CHECK_IS_COORDINATOR + " instance id: " + nodeId
                    + " group ID: " + groupId;
            throw new ClusterCoordinationException("Error occurred while " + errMsg, e);
        } finally {
            close(resultSet, RDBMSConstants.TASK_CHECK_IS_COORDINATOR);
            close(preparedStatement, RDBMSConstants.TASK_CHECK_IS_COORDINATOR);
            close(connection, RDBMSConstants.TASK_CHECK_IS_COORDINATOR);
        }
    }

    @Override
    public boolean updateCoordinatorHeartbeat(String nodeId, String groupId) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatementForCoordinatorUpdate = null;
        try {
            connection = getConnection();
            preparedStatementForCoordinatorUpdate = connection
                    .prepareStatement(RDBMSConstants.PS_UPDATE_COORDINATOR_HEARTBEAT);
            preparedStatementForCoordinatorUpdate.setLong(1, System.currentTimeMillis());
            preparedStatementForCoordinatorUpdate.setString(2, nodeId);
            preparedStatementForCoordinatorUpdate.setString(3, groupId);
            int updateCount = preparedStatementForCoordinatorUpdate.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(RDBMSConstants.TASK_UPDATE_COORDINATOR_HEARTBEAT + "node id " + nodeId
                        + " executed successfully");
            }
            return updateCount != 0;
        } catch (SQLException e) {
            rollback(connection, RDBMSConstants.TASK_UPDATE_COORDINATOR_HEARTBEAT);
            throw new ClusterCoordinationException("Error occurred while "
                    + RDBMSConstants.TASK_UPDATE_COORDINATOR_HEARTBEAT
                    + ". instance ID: " + nodeId + " group ID: " + groupId, e);
        } finally {
            close(preparedStatementForCoordinatorUpdate, RDBMSConstants.TASK_UPDATE_COORDINATOR_HEARTBEAT);
            close(connection, RDBMSConstants.TASK_UPDATE_COORDINATOR_HEARTBEAT);
        }
    }

    @Override
    public boolean checkIfCoordinatorValid(String groupId, int heartbeatMaxAge) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_GET_COORDINATOR_HEARTBEAT);
            preparedStatement.setString(1, groupId);
            resultSet = preparedStatement.executeQuery();
            long currentTimeMillis = System.currentTimeMillis();
            boolean isCoordinator;
            if (resultSet.next()) {
                long coordinatorHeartbeat = resultSet.getLong(1);
                long heartbeatAge = currentTimeMillis - coordinatorHeartbeat;
                isCoordinator = heartbeatAge <= heartbeatMaxAge;
                if (log.isDebugEnabled()) {
                    log.debug("isCoordinator: " + isCoordinator + ", heartbeatAge: " + heartbeatMaxAge
                            + ", coordinatorHeartBeat: " + coordinatorHeartbeat + ", currentTime: "
                            + currentTimeMillis);
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("No coordinator present in database for group " + groupId);
                }
                isCoordinator = false;
            }
            return isCoordinator;
        } catch (SQLException e) {
            String errMsg = RDBMSConstants.TASK_CHECK_COORDINATOR_VALIDITY;
            throw new ClusterCoordinationException("Error occurred while " + errMsg, e);
        } finally {
            close(resultSet, RDBMSConstants.TASK_CHECK_COORDINATOR_VALIDITY);
            close(preparedStatement, RDBMSConstants.TASK_CHECK_COORDINATOR_VALIDITY);
            close(connection, RDBMSConstants.TASK_CHECK_COORDINATOR_VALIDITY);
        }
    }

    @Override
    public void removeCoordinator(String groupId, int heartbeatMaxAge) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = getConnection();
            long currentTimeMillis = System.currentTimeMillis();
            long thresholdTimeLimit = currentTimeMillis - heartbeatMaxAge;
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_DELETE_COORDINATOR);
            preparedStatement.setString(1, groupId);
            preparedStatement.setLong(2, thresholdTimeLimit);
            preparedStatement.executeUpdate();
            if (log.isDebugEnabled()) {
                log.debug(RDBMSConstants.TASK_REMOVE_COORDINATOR + " of group " + groupId + " executed successfully");
            }
            connection.commit();
        } catch (SQLException e) {
            rollback(connection, RDBMSConstants.TASK_REMOVE_COORDINATOR);
            throw new ClusterCoordinationException("Error occurred while " + RDBMSConstants.TASK_REMOVE_COORDINATOR, e);
        } finally {
            close(preparedStatement, RDBMSConstants.TASK_REMOVE_COORDINATOR);
            close(connection, RDBMSConstants.TASK_REMOVE_COORDINATOR);
        }
    }

    @Override
    public boolean updateNodeHeartbeat(String nodeId, String groupId) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatementForNodeUpdate = null;

        try {
            connection = getConnection();
            preparedStatementForNodeUpdate = connection.prepareStatement(RDBMSConstants.PS_UPDATE_NODE_HEARTBEAT);
            preparedStatementForNodeUpdate.setLong(1, System.currentTimeMillis());
            preparedStatementForNodeUpdate.setString(2, nodeId);
            preparedStatementForNodeUpdate.setString(3, groupId);
            int updateCount = preparedStatementForNodeUpdate.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(RDBMSConstants.TASK_UPDATE_NODE_HEARTBEAT + " of node " + nodeId + " executed successfully");
            }
            return updateCount != 0;
        } catch (SQLException e) {
            rollback(connection, RDBMSConstants.TASK_UPDATE_NODE_HEARTBEAT);
            throw new ClusterCoordinationException("Error occurred while " + RDBMSConstants.TASK_UPDATE_NODE_HEARTBEAT
                    + ". Node ID: " + nodeId + "and Group ID : " + groupId, e);
        } finally {
            close(preparedStatementForNodeUpdate, RDBMSConstants.TASK_UPDATE_NODE_HEARTBEAT);
            close(connection, RDBMSConstants.TASK_UPDATE_NODE_HEARTBEAT);
        }
    }

    @Override
    public void createNodeHeartbeatEntry(String nodeId, String groupId, Map propertiesMap)
            throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = getConnection();
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(propertiesMap);
            byte[] propertiesMapAsBytes = byteArrayOutputStream.toByteArray();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_INSERT_NODE_HEARTBEAT_ROW);
            preparedStatement.setString(1, nodeId);
            preparedStatement.setLong(2, System.currentTimeMillis());
            preparedStatement.setString(3, groupId);
            preparedStatement.setBinaryStream(4, new ByteArrayInputStream(propertiesMapAsBytes));
            preparedStatement.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(RDBMSConstants.TASK_CREATE_NODE_HEARTBEAT + " of node " + nodeId + " executed successfully");
            }
        } catch (SQLException e) {
            rollback(connection, RDBMSConstants.TASK_CREATE_NODE_HEARTBEAT);
            throw new ClusterCoordinationException("Error occurred while " + RDBMSConstants.TASK_CREATE_NODE_HEARTBEAT
                    + ". Node ID: " + nodeId + " group ID" + groupId, e);
        } catch (IOException e) {
            throw new ClusterCoordinationException(e);
        } finally {
            close(preparedStatement, RDBMSConstants.TASK_UPDATE_COORDINATOR_HEARTBEAT);
            close(connection, RDBMSConstants.TASK_CREATE_NODE_HEARTBEAT);
        }
    }

    @Override
    public List<NodeDetail> getAllNodeData(String groupId) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        String coordinatorNodeId = getCoordinatorNodeId(groupId);
        if (coordinatorNodeId == null) {
            coordinatorNodeId = getCoordinatorNodeId(groupId);
        }
        ArrayList<NodeDetail> nodeDataList = new ArrayList<NodeDetail>();

        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_GET_ALL_NODE_HEARTBEAT);
            preparedStatement.setString(1, groupId);
            resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                String nodeId = resultSet.getString(2);
                boolean isCoordinatorNode = false;
                if (coordinatorNodeId != null) {
                    isCoordinatorNode = coordinatorNodeId.equals(nodeId);
                }
                Map<String, Object> propertiesMap = null;
                if (resultSet.getBlob(3) != null) {
                    int blobLength = (int) resultSet.getBlob(3).length();
                    byte[] bytes = resultSet.getBlob(3).getBytes(1L, blobLength);
                    ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                    try {
                        ObjectInputStream ois = new RDBMSCommunicationBusContextImpl.LookAheadObjectInputStream(bis);
                        Object blobObject = ois.readObject();
                        if (blobObject instanceof Map) {
                            propertiesMap = (Map) blobObject;
                        }
                    } catch (IOException e) {
                        log.error("Error in retrieving properties map when getting details of cluster " + groupId, e);
                    }
                }
                long lastHeartbeat = resultSet.getLong(4);
                boolean isNewNode = convertIntToBoolean(resultSet.getInt(5));
                NodeDetail heartBeatData = new NodeDetail(nodeId, groupId, isCoordinatorNode,
                        lastHeartbeat, isNewNode, propertiesMap);
                nodeDataList.add(heartBeatData);
            }

        } catch (SQLException e) {
            String errMsg = RDBMSConstants.TASK_GET_ALL_QUEUES;
            throw new ClusterCoordinationException("Error occurred while " + errMsg, e);
        } catch (ClassNotFoundException e) {
            throw new ClusterCoordinationException("Error retrieving the property map. ", e);
        } finally {
            close(resultSet, RDBMSConstants.TASK_GET_ALL_QUEUES);
            close(preparedStatement, RDBMSConstants.TASK_GET_ALL_QUEUES);
            close(connection, RDBMSConstants.TASK_GET_ALL_QUEUES);
        }
        if (log.isDebugEnabled()) {
            log.debug(RDBMSConstants.TASK_GET_ALL_QUEUES + " of group " + groupId + " executed successfully");
        }
        return nodeDataList;
    }

    @Override
    public NodeDetail getRemovedNodeData(String nodeId, String groupId,
                                         String removedMemberId) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        PreparedStatement clearMembershipEvents = null;
        ResultSet resultSet = null;
        NodeDetail nodeDetail = null;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_SELECT_REMOVED_MEMBER_DETAILS);
            preparedStatement.setString(1, nodeId);
            preparedStatement.setString(2, removedMemberId);
            preparedStatement.setString(3, groupId);
            resultSet = preparedStatement.executeQuery();
            Map<String, Object> propertiesMap = null;

            if (resultSet.next()) {
                Blob blob = resultSet.getBlob(2);
                if (blob != null) {
                    int blobLength = (int) blob.length();
                    byte[] bytes = blob.getBytes(1, blobLength);
                    ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                    try {
                        ObjectInputStream ois = new RDBMSCommunicationBusContextImpl.LookAheadObjectInputStream(bis);
                        Object blobObject = ois.readObject();
                        if (blobObject instanceof Map) {
                            propertiesMap = (Map) blobObject;
                        }
                    } catch (IOException e) {
                        log.error("Error in retrieving properties map when getting details of cluster " + groupId, e);
                    }
                }
                nodeDetail = new NodeDetail(removedMemberId, groupId, false, 0, false,
                        propertiesMap);
            }
            clearMembershipEvents = connection
                    .prepareStatement(RDBMSConstants.PS_DELETE_REMOVED_MEMBER_DETAIL_FOR_NODE);
            clearMembershipEvents.setString(1, nodeId);
            clearMembershipEvents.setString(2, removedMemberId);
            clearMembershipEvents.setString(3, groupId);
            clearMembershipEvents.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            String errMsg = RDBMSConstants.TASK_GET_ALL_QUEUES;
            throw new ClusterCoordinationException("Error occurred while " + errMsg, e);
        } catch (ClassNotFoundException e) {
            throw new ClusterCoordinationException("Error retrieving the removed node data. ", e);
        } finally {
            close(resultSet, RDBMSConstants.TASK_GET_ALL_QUEUES);
            close(preparedStatement, RDBMSConstants.TASK_GET_ALL_QUEUES);
            close(clearMembershipEvents, RDBMSConstants.TASK_GET_ALL_QUEUES);
            close(connection, RDBMSConstants.TASK_GET_ALL_QUEUES);
        }
        if (log.isDebugEnabled()) {
            log.debug(RDBMSConstants.TASK_GET_ALL_QUEUES + " of removed nodes in group "
                    + StringUtil.removeCRLFCharacters(groupId) + " executed successfully");
        }
        return nodeDetail;
    }

    @Override
    public void updatePropertiesMap(String nodeId, String groupId, Map<String, Object> propertiesMap)
            throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ByteArrayOutputStream byteArrayOutputStream = null;
        try {
            connection = getConnection();
            byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(propertiesMap);
            byte[] propertiesMapAsBytes = byteArrayOutputStream.toByteArray();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_UPDATE_PROPERTIES_MAP);
            preparedStatement.setBinaryStream(1, new ByteArrayInputStream(propertiesMapAsBytes));
            preparedStatement.setString(2, nodeId);
            preparedStatement.setString(3, groupId);
            preparedStatement.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(RDBMSConstants.TASK_UPDATE_PROPERTIES_MAP + " of node " + nodeId + " executed successfully");
            }
        } catch (SQLException e) {
            rollback(connection, RDBMSConstants.TASK_UPDATE_PROPERTIES_MAP);
            throw new ClusterCoordinationException("Error occurred while " + RDBMSConstants.TASK_UPDATE_PROPERTIES_MAP
                    + ". Node ID: " + nodeId + " group ID " + groupId, e);
        } catch (IOException e) {
            throw new ClusterCoordinationException("Error in " + RDBMSConstants.PS_UPDATE_PROPERTIES_MAP + ". Node ID: "
                    + nodeId + " group ID " + groupId, e);
        } finally {
            if (byteArrayOutputStream != null) {
                try {
                    byteArrayOutputStream.close();
                } catch (IOException e) {
                    log.error("Closing Byte Array Output Stream after "
                            + RDBMSConstants.TASK_UPDATE_PROPERTIES_MAP, e);
                }
            }
            close(preparedStatement, RDBMSConstants.TASK_UPDATE_PROPERTIES_MAP);
            close(connection, RDBMSConstants.TASK_UPDATE_PROPERTIES_MAP);
        }

    }

    @Override
    public NodeDetail getNodeData(String nodeId, String groupId) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        String coordinatorNodeId = getCoordinatorNodeId(groupId);
        if (coordinatorNodeId == null) {
            coordinatorNodeId = getCoordinatorNodeId(groupId);
        }
        NodeDetail nodeDetail = null;

        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_GET_NODE_DATA);
            preparedStatement.setString(1, groupId);
            preparedStatement.setString(2, nodeId);
            resultSet = preparedStatement.executeQuery();
            Map<String, Object> propertiesMap = null;
            if (resultSet.next()) {
                boolean isCoordinatorNode = nodeId.equals(coordinatorNodeId);
                if (resultSet.getBlob(3) != null) {
                    int blobLength = (int) resultSet.getBlob(3).length();
                    byte[] bytes = resultSet.getBlob(3).getBytes(1L, blobLength);
                    ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                    try {
                        ObjectInputStream ois = new RDBMSCommunicationBusContextImpl.LookAheadObjectInputStream(bis);
                        Object blobObject = ois.readObject();
                        if (blobObject instanceof Map) {
                            propertiesMap = (Map) blobObject;
                        }
                    } catch (IOException e) {
                        log.error("Error in retrieving properties map when getting details of cluster " + groupId, e);
                    }
                }
                long lastHeartbeat = resultSet.getLong(4);
                boolean isNewNode = convertIntToBoolean(resultSet.getInt(5));
                nodeDetail = new NodeDetail(nodeId, groupId, isCoordinatorNode, lastHeartbeat,
                        isNewNode, propertiesMap);
            }

        } catch (SQLException e) {
            String errMsg = RDBMSConstants.TASK_GET_ALL_QUEUES;
            throw new ClusterCoordinationException("Error occurred while " + errMsg, e);
        } catch (ClassNotFoundException e) {
            throw new ClusterCoordinationException("Error retrieving the node data ", e);
        } finally {
            close(resultSet, RDBMSConstants.TASK_GET_ALL_QUEUES);
            close(preparedStatement, RDBMSConstants.TASK_GET_ALL_QUEUES);
            close(connection, RDBMSConstants.TASK_GET_ALL_QUEUES);
        }
        if (log.isDebugEnabled()) {
            log.debug("getting node data of node " + StringUtil.removeCRLFCharacters(nodeId) +
                    " executed successfully");
        }
        return nodeDetail;
    }

    /**
     * Convert a value to boolean.
     *
     * @param value the value to be converted to boolean
     * @return the converted boolean
     */
    private boolean convertIntToBoolean(int value) {
        return value != 0;
    }

    @Override
    public void removeNode(String nodeId, String groupId) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_DELETE_NODE_HEARTBEAT);
            preparedStatement.setString(1, nodeId);
            preparedStatement.setString(2, groupId);
            preparedStatement.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(RDBMSConstants.TASK_REMOVE_NODE_HEARTBEAT + " of node "
                        + StringUtil.removeCRLFCharacters(nodeId) + " executed successfully");
            }
        } catch (SQLException e) {
            rollback(connection, RDBMSConstants.TASK_REMOVE_NODE_HEARTBEAT);
            throw new ClusterCoordinationException("error occurred while "
                    + RDBMSConstants.TASK_REMOVE_NODE_HEARTBEAT, e);
        } finally {
            close(preparedStatement, RDBMSConstants.TASK_REMOVE_NODE_HEARTBEAT);
            close(connection, RDBMSConstants.TASK_REMOVE_NODE_HEARTBEAT);
        }
    }

    @Override
    public void insertRemovedNodeDetails(String removedMember, String groupId, List<String> clusterNodes,
                                         Map<String, Object> removedPropertiesMap) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement storeRemovedMembersPreparedStatement = null;
        String task = "Storing removed member: " + removedMember + " in group " + groupId;
        try {
            connection = getConnection();
            storeRemovedMembersPreparedStatement = connection
                    .prepareStatement(RDBMSConstants.PS_INSERT_REMOVED_MEMBER_DETAILS);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(removedPropertiesMap);
            objectOutputStream.flush();
            byteArrayOutputStream.flush();
            objectOutputStream.close();
            byteArrayOutputStream.close();
            byte[] propertiesMapAsBytes = byteArrayOutputStream.toByteArray();
            // ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(propertiesMapAsBytes);
            for (String clusterNode : clusterNodes) {
                storeRemovedMembersPreparedStatement.setString(1, clusterNode);
                storeRemovedMembersPreparedStatement.setString(2, groupId);
                storeRemovedMembersPreparedStatement.setString(3, removedMember);
                storeRemovedMembersPreparedStatement
                        .setBinaryStream(4, new ByteArrayInputStream(propertiesMapAsBytes));
                storeRemovedMembersPreparedStatement.addBatch();
            }
            storeRemovedMembersPreparedStatement.executeBatch();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(StringUtil.removeCRLFCharacters(task) + " executed successfully");
            }
        } catch (SQLException e) {
            rollback(connection, task);
            throw new ClusterCoordinationException(
                    "Error storing removed member: " + removedMember + " in group " + groupId, e);
        } catch (IOException e) {
            throw new ClusterCoordinationException("Error while inserting removed node data", e);
        } finally {
            close(storeRemovedMembersPreparedStatement, task);
            close(connection, task);
        }
    }

    @Override
    public void markNodeAsNotNew(String nodeId, String groupId) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = getConnection();
            preparedStatement = connection.prepareStatement(RDBMSConstants.PS_MARK_NODE_NOT_NEW);
            preparedStatement.setString(1, nodeId);
            preparedStatement.setString(2, groupId);
            int updateCount = preparedStatement.executeUpdate();
            if (updateCount == 0) {
                log.warn("No record was updated while marking node as not new");
            }
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(RDBMSConstants.TASK_MARK_NODE_NOT_NEW + " of node "
                        + StringUtil.removeCRLFCharacters(nodeId) + " executed successfully");
            }
        } catch (SQLException e) {
            rollback(connection, RDBMSConstants.TASK_MARK_NODE_NOT_NEW);
            throw new ClusterCoordinationException("Error occurred while " + RDBMSConstants.TASK_MARK_NODE_NOT_NEW, e);
        } finally {
            close(preparedStatement, RDBMSConstants.TASK_MARK_NODE_NOT_NEW);
            close(connection, RDBMSConstants.TASK_MARK_NODE_NOT_NEW);
        }
    }

    @Override
    public void clearHeartBeatData() throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement clearNodeHeartbeatData = null;
        PreparedStatement clearCoordinatorHeartbeatData = null;
        String task = "Clearing all heartbeat data";
        try {
            connection = getConnection();
            clearNodeHeartbeatData = connection.prepareStatement(RDBMSConstants.PS_CLEAR_NODE_HEARTBEATS);
            clearNodeHeartbeatData.executeUpdate();

            clearCoordinatorHeartbeatData = connection.prepareStatement(RDBMSConstants.PS_CLEAR_COORDINATOR_HEARTBEAT);
            clearCoordinatorHeartbeatData.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(task + " executed successfully");
            }
        } catch (SQLException e) {
            rollback(connection, task);
            throw new ClusterCoordinationException("Error occurred while " + task, e);
        } finally {
            close(clearNodeHeartbeatData, task);
            close(clearCoordinatorHeartbeatData, task);
            close(connection, task);
        }
    }

    @Override
    public void clearMembershipEvents(String nodeID, String groupID) throws ClusterCoordinationException {
        Connection connection = null;
        PreparedStatement clearMembershipEvents = null;
        String task = "Clearing all membership events for node: " + nodeID;
        try {
            connection = getConnection();
            clearMembershipEvents = connection.prepareStatement(RDBMSConstants.PS_CLEAN_MEMBERSHIP_EVENTS_FOR_NODE);
            clearMembershipEvents.setString(1, nodeID);
            clearMembershipEvents.executeUpdate();
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug(task + " executed successfully");
            }
        } catch (SQLException e) {
            rollback(connection, task);
            throw new ClusterCoordinationException("Error occurred while " + task, e);
        } finally {
            close(clearMembershipEvents, task);
            close(connection, task);
        }
    }

    private static class LookAheadObjectInputStream extends ObjectInputStream {

        LookAheadObjectInputStream(InputStream inputStream) throws IOException {
            super(inputStream);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException,
                ClassNotFoundException {
            try {
                if ((!(desc.getName().equals(String.class.getName()))) &&
                        (!(desc.getName().equals(Integer.class.getName()))) &&
                        (!(desc.getName().equals(Float.class.getName()))) &&
                        (!(desc.getName().equals(Long.class.getName()))) &&
                        (!(desc.getName().equals(Boolean.class.getName()))) &&
                        (!(desc.getName().equals(Number.class.getName()))) &&
                        (!(desc.getName().equals(CharSequence.class.getName()))) &&
                !(Class.forName(desc.getName()).newInstance() instanceof Map)) {
                    throw new InvalidClassException("Unauthorized deserialization attempt ", desc.getName());
                }
            } catch (InstantiationException | IllegalAccessException e) {
                throw new InvalidClassException("Invalid class of type " + desc.getName() +
                        " used for cluster properties.");
            }
            return super.resolveClass(desc);
        }
    }
}
