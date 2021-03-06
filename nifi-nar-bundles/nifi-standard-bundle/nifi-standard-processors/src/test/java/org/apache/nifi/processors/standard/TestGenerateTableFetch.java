/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;


import org.apache.nifi.components.state.Scope;
import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processors.standard.db.impl.DerbyDatabaseAdapter;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.apache.nifi.util.file.FileUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import static org.apache.nifi.processors.standard.AbstractDatabaseFetchProcessor.DB_TYPE;
import static org.apache.nifi.processors.standard.AbstractDatabaseFetchProcessor.REL_SUCCESS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;


/**
 * Unit tests for the GenerateTableFetch processor.
 */
public class TestGenerateTableFetch {

    TestRunner runner;
    GenerateTableFetch processor;
    DBCPServiceSimpleImpl dbcp;

    private final static String DB_LOCATION = "target/db_gtf";

    @BeforeClass
    public static void setupBeforeClass() throws IOException {
        System.setProperty("derby.stream.error.file", "target/derby.log");

        // remove previous test database, if any
        final File dbLocation = new File(DB_LOCATION);
        try {
            FileUtils.deleteFile(dbLocation, true);
        } catch (IOException ioe) {
            // Do nothing, may not have existed
        }
    }

    @AfterClass
    public static void cleanUpAfterClass() throws Exception {
        try {
            DriverManager.getConnection("jdbc:derby:" + DB_LOCATION + ";shutdown=true");
        } catch (SQLNonTransientConnectionException e) {
            // Do nothing, this is what happens at Derby shutdown
        }
        // remove previous test database, if any
        final File dbLocation = new File(DB_LOCATION);
        try {
            FileUtils.deleteFile(dbLocation, true);
        } catch (IOException ioe) {
            // Do nothing, may not have existed
        }
    }

    @Before
    public void setUp() throws Exception {
        processor = new GenerateTableFetch();
        //Mock the DBCP Controller Service so we can control the Results
        dbcp = spy(new DBCPServiceSimpleImpl());

        final Map<String, String> dbcpProperties = new HashMap<>();

        runner = TestRunners.newTestRunner(processor);
        runner.addControllerService("dbcp", dbcp, dbcpProperties);
        runner.enableControllerService(dbcp);
        runner.setProperty(GenerateTableFetch.DBCP_SERVICE, "dbcp");
        runner.setProperty(DB_TYPE, new DerbyDatabaseAdapter().getName());
    }

    @Test
    public void testAddedRows() throws ClassNotFoundException, SQLException, InitializationException, IOException {

        // load test data to database
        final Connection con = ((DBCPService) runner.getControllerService("dbcp")).getConnection();
        Statement stmt = con.createStatement();

        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }

        stmt.execute("create table TEST_QUERY_DB_TABLE (id integer not null, name varchar(100), scale float, created_on timestamp, bignum bigint default 0)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, name, scale, created_on) VALUES (0, 'Joe Smith', 1.0, '1962-09-23 03:23:34.234')");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, name, scale, created_on) VALUES (1, 'Carrie Jones', 5.0, '2000-01-01 03:23:34.234')");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, name, scale, created_on) VALUES (2, NULL, 2.0, '2010-01-01 00:00:00')");

        runner.setProperty(GenerateTableFetch.TABLE_NAME, "TEST_QUERY_DB_TABLE");
        runner.setIncomingConnection(false);
        runner.setProperty(GenerateTableFetch.MAX_VALUE_COLUMN_NAMES, "ID");

        runner.run();
        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 1);
        MockFlowFile flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        String query = new String(flowFile.toByteArray());
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE ORDER BY ID FETCH NEXT 10000 ROWS ONLY", query);
        ResultSet resultSet = stmt.executeQuery(query);
        // Should be three records
        assertTrue(resultSet.next());
        assertTrue(resultSet.next());
        assertTrue(resultSet.next());
        assertFalse(resultSet.next());
        runner.clearTransferState();

        // Run again, this time no flowfiles/rows should be transferred
        runner.run();
        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 0);
        runner.clearTransferState();

        // Add 3 new rows with a higher ID and run with a partition size of 2. Two flow files should be transferred
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, name, scale, created_on) VALUES (3, 'Mary West', 15.0, '2000-01-01 03:23:34.234')");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, name, scale, created_on) VALUES (4, 'Marty Johnson', 15.0, '2011-01-01 03:23:34.234')");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, name, scale, created_on) VALUES (5, 'Marty Johnson', 15.0, '2011-01-01 03:23:34.234')");
        runner.setProperty(GenerateTableFetch.PARTITION_SIZE, "2");
        runner.run();
        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 2);

        // Verify first flow file's contents
        flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        query = new String(flowFile.toByteArray());
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE WHERE ID > 2 ORDER BY ID FETCH NEXT 2 ROWS ONLY", query);
        resultSet = stmt.executeQuery(query);
        // Should be two records
        assertTrue(resultSet.next());
        assertTrue(resultSet.next());
        assertFalse(resultSet.next());

        // Verify second flow file's contents
        flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(1);
        query = new String(flowFile.toByteArray());
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE WHERE ID > 2 ORDER BY ID OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY", query);
        resultSet = stmt.executeQuery(query);
        // Should be one record
        assertTrue(resultSet.next());
        assertFalse(resultSet.next());
        runner.clearTransferState();

        // Add a new row with a higher ID and run, one flow file will be transferred
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, name, scale, created_on) VALUES (6, 'Mr. NiFi', 1.0, '2012-01-01 03:23:34.234')");
        runner.run();
        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 1);
        flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        query = new String(flowFile.toByteArray());
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE WHERE ID > 5 ORDER BY ID FETCH NEXT 2 ROWS ONLY", query);
        resultSet = stmt.executeQuery(query);
        // Should be one record
        assertTrue(resultSet.next());
        assertFalse(resultSet.next());
        runner.clearTransferState();

        // Set name as the max value column name (and clear the state), all rows should be returned since the max value for name has not been set
        runner.getStateManager().clear(Scope.CLUSTER);
        runner.setProperty(GenerateTableFetch.MAX_VALUE_COLUMN_NAMES, "name");
        runner.run();
        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 4); // 7 records with partition size 2 means 4 generated FlowFiles
        flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE ORDER BY name FETCH NEXT 2 ROWS ONLY", new String(flowFile.toByteArray()));
        flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(1);
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE ORDER BY name OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY", new String(flowFile.toByteArray()));
        flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(2);
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE ORDER BY name OFFSET 4 ROWS FETCH NEXT 2 ROWS ONLY", new String(flowFile.toByteArray()));
        flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(3);
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE ORDER BY name OFFSET 6 ROWS FETCH NEXT 2 ROWS ONLY", new String(flowFile.toByteArray()));

        runner.clearTransferState();
    }

    @Test
    public void testMultiplePartitions() throws ClassNotFoundException, SQLException, InitializationException, IOException {

        // load test data to database
        final Connection con = ((DBCPService) runner.getControllerService("dbcp")).getConnection();
        Statement stmt = con.createStatement();

        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }

        stmt.execute("create table TEST_QUERY_DB_TABLE (id integer not null, bucket integer not null)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (0, 0)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (1, 0)");

        runner.setProperty(GenerateTableFetch.TABLE_NAME, "TEST_QUERY_DB_TABLE");
        runner.setIncomingConnection(false);
        runner.setProperty(GenerateTableFetch.MAX_VALUE_COLUMN_NAMES, "ID, BUCKET");
        // Set partition size to 1 so we can compare flow files to records
        runner.setProperty(GenerateTableFetch.PARTITION_SIZE, "1");

        runner.run();
        runner.assertAllFlowFilesTransferred(GenerateTableFetch.REL_SUCCESS, 2);
        runner.clearTransferState();

        // Add a new row in the same bucket
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (2, 0)");
        runner.run();
        runner.assertAllFlowFilesTransferred(GenerateTableFetch.REL_SUCCESS, 1);
        runner.clearTransferState();

        // Add a new row in a new bucket
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (3, 1)");
        runner.run();
        runner.assertAllFlowFilesTransferred(GenerateTableFetch.REL_SUCCESS, 1);
        runner.clearTransferState();

        // Add a new row in an old bucket, it should not be transferred
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (4, 0)");
        runner.run();
        runner.assertTransferCount(GenerateTableFetch.REL_SUCCESS, 0);

        // Add a new row in the second bucket, only the new row should be transferred
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (5, 1)");
        runner.run();
        runner.assertAllFlowFilesTransferred(GenerateTableFetch.REL_SUCCESS, 1);
        runner.clearTransferState();
    }

    @Test
    public void testMultiplePartitionsIncomingFlowFiles() throws ClassNotFoundException, SQLException, InitializationException, IOException {

        // load test data to database
        final Connection con = ((DBCPService) runner.getControllerService("dbcp")).getConnection();
        Statement stmt = con.createStatement();

        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE1");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }

        stmt.execute("create table TEST_QUERY_DB_TABLE1 (id integer not null, bucket integer not null)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE1 (id, bucket) VALUES (0, 0)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE1 (id, bucket) VALUES (1, 0)");

        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE2");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }

        stmt.execute("create table TEST_QUERY_DB_TABLE2 (id integer not null, bucket integer not null)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE2 (id, bucket) VALUES (0, 0)");


        runner.setProperty(GenerateTableFetch.TABLE_NAME, "${tableName}");
        runner.setIncomingConnection(true);
        runner.setProperty(GenerateTableFetch.PARTITION_SIZE, "${partSize}");

        runner.enqueue("".getBytes(), new HashMap<String, String>() {{
            put("tableName", "TEST_QUERY_DB_TABLE1");
            put("partSize", "1");
        }});

        runner.enqueue("".getBytes(), new HashMap<String, String>() {{
            put("tableName", "TEST_QUERY_DB_TABLE2");
            put("partSize", "2");
        }});

        // The table does not exist, expect the original flow file to be routed to failure
        runner.enqueue("".getBytes(), new HashMap<String, String>() {{
            put("tableName", "TEST_QUERY_DB_TABLE3");
            put("partSize", "1");
        }});

        runner.run(3);
        runner.assertTransferCount(AbstractDatabaseFetchProcessor.REL_SUCCESS, 3);

        // Two records from table 1
        assertEquals(runner.getFlowFilesForRelationship(AbstractDatabaseFetchProcessor.REL_SUCCESS).stream().filter(
                (ff) -> "TEST_QUERY_DB_TABLE1".equals(ff.getAttribute("tableName"))).count(),
                2);

        // One record from table 2
        assertEquals(runner.getFlowFilesForRelationship(AbstractDatabaseFetchProcessor.REL_SUCCESS).stream().filter(
                (ff) -> "TEST_QUERY_DB_TABLE2".equals(ff.getAttribute("tableName"))).count(),
                1);

        // Table 3 doesn't exist, should be routed to failure
        runner.assertTransferCount(GenerateTableFetch.REL_FAILURE, 1);

        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE1");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }
        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE2");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }
    }

    @Test
    public void testBackwardsCompatibilityStateKeyStaticTableDynamicMaxValues() throws Exception {
        // load test data to database
        final Connection con = ((DBCPService) runner.getControllerService("dbcp")).getConnection();
        Statement stmt = con.createStatement();

        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }

        stmt.execute("create table TEST_QUERY_DB_TABLE (id integer not null, bucket integer not null)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (0, 0)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (1, 0)");

        runner.setProperty(GenerateTableFetch.TABLE_NAME, "TEST_QUERY_DB_TABLE");
        runner.setIncomingConnection(true);
        runner.setProperty(GenerateTableFetch.MAX_VALUE_COLUMN_NAMES, "${maxValueCol}");
        runner.enqueue("".getBytes(), new HashMap<String, String>() {{
            put("maxValueCol", "id");
        }});

        // Pre-populate the state with a key for column name (not fully-qualified)
        StateManager stateManager = runner.getStateManager();
        stateManager.setState(new HashMap<String, String>() {{
            put("id", "0");
        }}, Scope.CLUSTER);

        // Pre-populate the column type map with an entry for id (not fully-qualified)
        processor.columnTypeMap.put("id", 4);

        runner.run();

        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 1);
        MockFlowFile flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE WHERE id > 0 ORDER BY id FETCH NEXT 10000 ROWS ONLY", new String(flowFile.toByteArray()));
    }

    @Test
    public void testBackwardsCompatibilityStateKeyDynamicTableDynamicMaxValues() throws Exception {
        // load test data to database
        final Connection con = ((DBCPService) runner.getControllerService("dbcp")).getConnection();
        Statement stmt = con.createStatement();

        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }

        stmt.execute("create table TEST_QUERY_DB_TABLE (id integer not null, bucket integer not null)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (0, 0)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (1, 0)");

        runner.setProperty(GenerateTableFetch.TABLE_NAME, "${tableName}");
        runner.setIncomingConnection(true);
        runner.setProperty(GenerateTableFetch.MAX_VALUE_COLUMN_NAMES, "${maxValueCol}");
        runner.enqueue("".getBytes(), new HashMap<String, String>() {{
            put("tableName", "TEST_QUERY_DB_TABLE");
            put("maxValueCol", "id");
        }});

        // Pre-populate the state with a key for column name (not fully-qualified)
        StateManager stateManager = runner.getStateManager();
        stateManager.setState(new HashMap<String, String>() {{
            put("id", "0");
        }}, Scope.CLUSTER);

        // Pre-populate the column type map with an entry for id (not fully-qualified)
        processor.columnTypeMap.put("id", 4);

        runner.run();

        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 1);
        MockFlowFile flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        // Note there is no WHERE clause here. Because we are using dynamic tables, the old state key/value is not retrieved
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE ORDER BY id FETCH NEXT 10000 ROWS ONLY", new String(flowFile.toByteArray()));

        runner.clearTransferState();
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (2, 0)");

        runner.enqueue("".getBytes(), new HashMap<String, String>() {{
            put("tableName", "TEST_QUERY_DB_TABLE");
            put("maxValueCol", "id");
        }});
        runner.run();

        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 1);
        flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE WHERE id > 1 ORDER BY id FETCH NEXT 10000 ROWS ONLY", new String(flowFile.toByteArray()));
    }

    @Test
    public void testBackwardsCompatibilityStateKeyDynamicTableStaticMaxValues() throws Exception {
        // load test data to database
        final Connection con = ((DBCPService) runner.getControllerService("dbcp")).getConnection();
        Statement stmt = con.createStatement();

        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }

        stmt.execute("create table TEST_QUERY_DB_TABLE (id integer not null, bucket integer not null)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (0, 0)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (1, 0)");

        runner.setProperty(GenerateTableFetch.TABLE_NAME, "${tableName}");
        runner.setIncomingConnection(true);
        runner.setProperty(GenerateTableFetch.MAX_VALUE_COLUMN_NAMES, "id");
        runner.enqueue("".getBytes(), new HashMap<String, String>() {{
            put("tableName", "TEST_QUERY_DB_TABLE");
        }});

        // Pre-populate the state with a key for column name (not fully-qualified)
        StateManager stateManager = runner.getStateManager();
        stateManager.setState(new HashMap<String, String>() {{
            put("id", "0");
        }}, Scope.CLUSTER);

        // Pre-populate the column type map with an entry for id (not fully-qualified)
        processor.columnTypeMap.put("id", 4);

        runner.run();

        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 1);
        MockFlowFile flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        // Note there is no WHERE clause here. Because we are using dynamic tables, the old state key/value is not retrieved
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE ORDER BY id FETCH NEXT 10000 ROWS ONLY", new String(flowFile.toByteArray()));

        runner.clearTransferState();
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (2, 0)");

        runner.enqueue("".getBytes(), new HashMap<String, String>() {{
            put("tableName", "TEST_QUERY_DB_TABLE");
            put("maxValueCol", "id");
        }});
        runner.run();

        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 1);
        flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE WHERE id > 1 ORDER BY id FETCH NEXT 10000 ROWS ONLY", new String(flowFile.toByteArray()));
    }

    @Test
    public void testBackwardsCompatibilityStateKeyVariableRegistry() throws Exception {
        // load test data to database
        final Connection con = ((DBCPService) runner.getControllerService("dbcp")).getConnection();
        Statement stmt = con.createStatement();

        try {
            stmt.execute("drop table TEST_QUERY_DB_TABLE");
        } catch (final SQLException sqle) {
            // Ignore this error, probably a "table does not exist" since Derby doesn't yet support DROP IF EXISTS [DERBY-4842]
        }

        stmt.execute("create table TEST_QUERY_DB_TABLE (id integer not null, bucket integer not null)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (0, 0)");
        stmt.execute("insert into TEST_QUERY_DB_TABLE (id, bucket) VALUES (1, 0)");

        runner.setProperty(GenerateTableFetch.TABLE_NAME, "${tableName}");
        runner.setIncomingConnection(false);
        runner.setProperty(GenerateTableFetch.MAX_VALUE_COLUMN_NAMES, "${maxValueCol}");

        runner.setVariable("tableName", "TEST_QUERY_DB_TABLE");
        runner.setVariable("maxValueCol", "id");

        // Pre-populate the state with a key for column name (not fully-qualified)
        StateManager stateManager = runner.getStateManager();
        stateManager.setState(new HashMap<String, String>() {{
            put("id", "0");
        }}, Scope.CLUSTER);

        // Pre-populate the column type map with an entry for id (not fully-qualified)
        processor.columnTypeMap.put("id", 4);

        runner.run();

        runner.assertAllFlowFilesTransferred(REL_SUCCESS, 1);
        MockFlowFile flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        // Note there is no WHERE clause here. Because we are using dynamic tables (i.e. Expression Language,
        // even when not referring to flow file attributes), the old state key/value is not retrieved
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE ORDER BY id FETCH NEXT 10000 ROWS ONLY", new String(flowFile.toByteArray()));
    }

    @Test
    public void testRidiculousRowCount() throws ClassNotFoundException, SQLException, InitializationException, IOException {
        long rowCount= Long.parseLong(Integer.toString(Integer.MAX_VALUE)) + 100;
        int partitionSize = 1000000;
        int expectedFileCount = (int)(rowCount/partitionSize) + 1;

        Connection conn = mock(Connection.class);
        when(dbcp.getConnection()).thenReturn(conn);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        doNothing().when(st).close();
        ResultSet rs = mock(ResultSet.class);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn((int)rowCount);
        when(rs.getLong(1)).thenReturn(rowCount);

        final ResultSetMetaData resultSetMetaData = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(resultSetMetaData);
        when(resultSetMetaData.getColumnCount()).thenReturn(2);
        when(resultSetMetaData.getTableName(1)).thenReturn("");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.INTEGER);
        when(resultSetMetaData.getColumnName(1)).thenReturn("COUNT");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.INTEGER);
        when(resultSetMetaData.getColumnName(2)).thenReturn("ID");
        when(rs.getInt(2)).thenReturn(1000);


        runner.setProperty(GenerateTableFetch.TABLE_NAME, "TEST_QUERY_DB_TABLE");
        runner.setIncomingConnection(false);
        runner.setProperty(GenerateTableFetch.MAX_VALUE_COLUMN_NAMES, "ID");
        runner.setProperty(GenerateTableFetch.PARTITION_SIZE, Integer.toString(partitionSize));

        runner.run();
        runner.assertAllFlowFilesTransferred(REL_SUCCESS, expectedFileCount);
        MockFlowFile flowFile = runner.getFlowFilesForRelationship(REL_SUCCESS).get(0);
        String query = new String(flowFile.toByteArray());
        assertEquals("SELECT * FROM TEST_QUERY_DB_TABLE ORDER BY ID FETCH NEXT 1000000 ROWS ONLY", query);
        runner.clearTransferState();
    }


    /**
     * Simple implementation only for GenerateTableFetch processor testing.
     */
    private class DBCPServiceSimpleImpl extends AbstractControllerService implements DBCPService {

        @Override
        public String getIdentifier() {
            return "dbcp";
        }

        @Override
        public Connection getConnection() throws ProcessException {
            try {
                Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
                return DriverManager.getConnection("jdbc:derby:" + DB_LOCATION + ";create=true");
            } catch (final Exception e) {
                throw new ProcessException("getConnection failed: " + e);
            }
        }
    }
}