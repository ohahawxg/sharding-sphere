/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.dbtest.asserts;

import com.google.common.base.Strings;
import io.shardingsphere.core.api.yaml.YamlMasterSlaveDataSourceFactory;
import io.shardingsphere.core.api.yaml.YamlShardingDataSourceFactory;
import io.shardingsphere.core.constant.DatabaseType;
import io.shardingsphere.dbtest.common.DatabaseUtil;
import io.shardingsphere.dbtest.config.DataSetsParser;
import io.shardingsphere.dbtest.config.bean.AssertSubDefinition;
import io.shardingsphere.dbtest.config.bean.DDLDataSetAssert;
import io.shardingsphere.dbtest.config.bean.DMLDataSetAssert;
import io.shardingsphere.dbtest.config.bean.DQLDataSetAssert;
import io.shardingsphere.dbtest.config.bean.DataSetAssert;
import io.shardingsphere.dbtest.config.bean.DatasetDatabase;
import io.shardingsphere.dbtest.config.bean.DatasetDefinition;
import io.shardingsphere.dbtest.config.bean.ParameterDefinition;
import io.shardingsphere.dbtest.config.dataset.DataSetColumnMetadata;
import io.shardingsphere.dbtest.env.DatabaseTypeEnvironment;
import io.shardingsphere.dbtest.env.EnvironmentPath;
import io.shardingsphere.dbtest.env.datasource.DataSourceUtil;
import io.shardingsphere.dbtest.env.schema.SchemaEnvironmentManager;
import io.shardingsphere.test.sql.SQLCaseType;
import io.shardingsphere.test.sql.SQLCasesLoader;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.xml.sax.SAXException;

import javax.sql.DataSource;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AssertEngine {
    
    private final DataSetAssert dataSetAssert;
    
    private final String shardingRuleType;
    
    private final DatabaseTypeEnvironment databaseTypeEnvironment;
    
    private final SQLCaseType caseType;
    
    private final Map<String, DataSource> dataSourceMap;
    
    private final String rootPath;
    
    private final DataInitializer dataInitializer;
    
    public AssertEngine(
            final DataSetAssert dataSetAssert, final String shardingRuleType, final DatabaseTypeEnvironment databaseTypeEnvironment, final SQLCaseType caseType) throws IOException, JAXBException {
        this.dataSetAssert = dataSetAssert;
        this.shardingRuleType = shardingRuleType;
        this.databaseTypeEnvironment = databaseTypeEnvironment;
        this.caseType = caseType;
        if (databaseTypeEnvironment.isEnabled()) {
            dataSourceMap = createDataSourceMap(SchemaEnvironmentManager.getDataSourceNames(shardingRuleType), databaseTypeEnvironment.getDatabaseType());
            rootPath = dataSetAssert.getPath().substring(0, dataSetAssert.getPath().lastIndexOf(File.separator) + 1);
            dataInitializer = new DataInitializer(EnvironmentPath.getDataInitializeResourceFile(shardingRuleType), dataSourceMap);
        } else {
            dataSourceMap = null;
            rootPath = null;
            dataInitializer = null;
        }
    }
    
    /**
     * Run assert.
     */
    public void run() throws IOException, SQLException, SAXException, ParserConfigurationException, XPathExpressionException, ParseException, JAXBException {
        if (!databaseTypeEnvironment.isEnabled()) {
            return;
        }
        DataSource dataSource = createDataSource(dataSourceMap);
        if (dataSetAssert instanceof DQLDataSetAssert) {
            dqlRun((DQLDataSetAssert) dataSetAssert, dataSource);
        } else if (dataSetAssert instanceof DMLDataSetAssert) {
            dmlRun((DMLDataSetAssert) dataSetAssert, dataSource);
        } else if (dataSetAssert instanceof DDLDataSetAssert) {
            ddlRun((DDLDataSetAssert) dataSetAssert, dataSource);
        }
    }
    
    private Map<String, DataSource> createDataSourceMap(final Collection<String> dataSourceNames, final DatabaseType databaseType) {
        Map<String, DataSource> result = new HashMap<>(dataSourceNames.size(), 1);
        for (String each : dataSourceNames) {
            result.put(each, DataSourceUtil.createDataSource(databaseType, each));
        }
        return result;
    }
    
    private DataSource createDataSource(final Map<String, DataSource> dataSourceMap) throws SQLException, IOException {
        return "masterslaveonly".equals(shardingRuleType)
                        ? YamlMasterSlaveDataSourceFactory.createDataSource(dataSourceMap, new File(EnvironmentPath.getShardingRuleResourceFile(shardingRuleType)))
                        : YamlShardingDataSourceFactory.createDataSource(dataSourceMap, new File(EnvironmentPath.getShardingRuleResourceFile(shardingRuleType)));
    }
    
    private void ddlRun(final DDLDataSetAssert ddlDefinition, final DataSource dataSource) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        String rootSQL = ddlDefinition.getSql();
        rootSQL = SQLCasesLoader.getInstance().getSupportedSQL(rootSQL);
        String expectedDataFile = rootPath + "asserts/ddl/" + shardingRuleType + "/" + ddlDefinition.getExpectedDataFile();
        if (!new File(expectedDataFile).exists()) {
            expectedDataFile = rootPath + "asserts/ddl/" + ddlDefinition.getExpectedDataFile();
        }
        if (ddlDefinition.getParameter().getValues().isEmpty() && ddlDefinition.getParameter().getValueReplaces().isEmpty()) {
            List<AssertSubDefinition> subAsserts = ddlDefinition.getSubAsserts();
            if (subAsserts.isEmpty()) {
                doUpdateUseStatementToExecuteUpdateDDL(expectedDataFile, dataSource, ddlDefinition, rootSQL);
                doUpdateUseStatementToExecuteDDL(expectedDataFile, dataSource, ddlDefinition, rootSQL);
                doUpdateUsePreparedStatementToExecuteUpdateDDL(expectedDataFile, dataSource, ddlDefinition, rootSQL);
                doUpdateUsePreparedStatementToExecuteDDL(expectedDataFile, dataSource, ddlDefinition, rootSQL);
            } else {
                ddlSubRun(dataSource, ddlDefinition, rootSQL, expectedDataFile, subAsserts);
            }
        } else {
            doUpdateUseStatementToExecuteUpdateDDL(expectedDataFile, dataSource, ddlDefinition, rootSQL);
            doUpdateUseStatementToExecuteDDL(expectedDataFile, dataSource, ddlDefinition, rootSQL);
            doUpdateUsePreparedStatementToExecuteUpdateDDL(expectedDataFile, dataSource, ddlDefinition, rootSQL);
            doUpdateUsePreparedStatementToExecuteDDL(expectedDataFile, dataSource, ddlDefinition, rootSQL);
            List<AssertSubDefinition> subAsserts = ddlDefinition.getSubAsserts();
            if (!subAsserts.isEmpty()) {
                ddlSubRun(dataSource, ddlDefinition, rootSQL, expectedDataFile, subAsserts);
            }
        }
    }
    
    private void ddlSubRun(final DataSource dataSource, final DDLDataSetAssert anAssert, final String rootSQL, final String expectedDataFile, final List<AssertSubDefinition> subAsserts) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        for (AssertSubDefinition each : subAsserts) {
            if (!each.getDatabaseTypes().contains(databaseTypeEnvironment.getDatabaseType())) {
                break;
            }
            String baseConfig = each.getShardingRuleTypes();
            if (StringUtils.isNotBlank(baseConfig)) {
                String[] baseConfigs = StringUtils.split(baseConfig, ",");
                boolean flag = true;
                for (String config : baseConfigs) {
                    if (shardingRuleType.equals(config)) {
                        flag = false;
                    }
                }
                //Skip use cases that do not need to run
                if (flag) {
                    continue;
                }
            }
            String expectedDataFileSub = each.getExpectedDataFile();
            ParameterDefinition parameter = each.getParameter();
            String expectedDataFileTmp = expectedDataFile;
            if (StringUtils.isBlank(expectedDataFileSub)) {
                expectedDataFileSub = anAssert.getExpectedDataFile();
            } else {
                expectedDataFileTmp = rootPath + "asserts/ddl/" + shardingRuleType + "/" + expectedDataFileSub;
                if (!new File(expectedDataFileTmp).exists()) {
                    expectedDataFileTmp = rootPath + "asserts/ddl/" + expectedDataFileSub;
                }
            }
            if (parameter == null) {
                parameter = anAssert.getParameter();
            }
            DDLDataSetAssert anAssertSub = new DDLDataSetAssert(anAssert.getId(), anAssert.getInitSql(),
                    anAssert.getShardingRuleTypes(), anAssert.getDatabaseTypes(), anAssert.getCleanSql(), expectedDataFileSub,
                    anAssert.getSql(), anAssert.getTable(),
                    parameter, anAssert.getSubAsserts(), "");
            doUpdateUseStatementToExecuteUpdateDDL(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
            doUpdateUseStatementToExecuteDDL(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
            doUpdateUsePreparedStatementToExecuteUpdateDDL(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
            doUpdateUsePreparedStatementToExecuteDDL(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
        }
    }
    
    private void dmlRun(final DMLDataSetAssert dmlDefinition, final DataSource dataSource) throws IOException, SAXException, ParserConfigurationException, XPathExpressionException, SQLException, ParseException {
        String rootSQL = dmlDefinition.getSql();
        rootSQL = SQLCasesLoader.getInstance().getSupportedSQL(rootSQL);
        String expectedDataFile = rootPath + "asserts/dml/" + shardingRuleType + "/" + dmlDefinition.getExpectedDataFile();
        if (!new File(expectedDataFile).exists()) {
            expectedDataFile = rootPath + "asserts/dml/" + dmlDefinition.getExpectedDataFile();
        }
        int resultDoUpdateUseStatementToExecuteUpdate = 0;
        int resultDoUpdateUseStatementToExecute = 0;
        int resultDoUpdateUsePreparedStatementToExecuteUpdate = 0;
        int resultDoUpdateUsePreparedStatementToExecute = 0;
        if (dmlDefinition.getParameter().getValues().isEmpty() && dmlDefinition.getParameter().getValueReplaces().isEmpty()) {
            List<AssertSubDefinition> subAsserts = dmlDefinition.getSubAsserts();
            if (subAsserts.isEmpty()) {
                resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFile, dataSource, dmlDefinition, rootSQL);
                resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFile, dataSource, dmlDefinition, rootSQL);
                resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFile, dataSource, dmlDefinition, rootSQL);
                resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFile, dataSource, dmlDefinition, rootSQL);
            } else {
                for (AssertSubDefinition subAssert : subAsserts) {
                    if (!subAssert.getDatabaseTypes().contains(databaseTypeEnvironment.getDatabaseType())) {
                        break;
                    }
                    String baseConfigSub = subAssert.getShardingRuleTypes();
                    if (StringUtils.isNotBlank(baseConfigSub)) {
                        String[] baseConfigs = StringUtils.split(baseConfigSub, ",");
                        boolean flag = true;
                        for (String config : baseConfigs) {
                            if (shardingRuleType.equals(config)) {
                                flag = false;
                            }
                        }
                        //Skip use cases that do not need to run
                        if (flag) {
                            continue;
                        }
                    }
                    String expectedDataFileSub = subAssert.getExpectedDataFile();
                    ParameterDefinition parameter = subAssert.getParameter();
                    ParameterDefinition expectedParameter = subAssert.getExpectedParameter();
                    String expectedDataFileTmp = expectedDataFile;
                    if (StringUtils.isBlank(expectedDataFileSub)) {
                        expectedDataFileSub = dmlDefinition.getExpectedDataFile();
                    } else {
                        expectedDataFileTmp = rootPath + "asserts/dml/" + shardingRuleType + "/" + expectedDataFileSub;
                        if (!new File(expectedDataFileTmp).exists()) {
                            expectedDataFileTmp = rootPath + "asserts/dml/" + expectedDataFileSub;
                        }
                    }
                    if (parameter == null) {
                        parameter = dmlDefinition.getParameter();
                    }
                    if (expectedParameter == null) {
                        expectedParameter = dmlDefinition.getParameter();
                    }
                    DMLDataSetAssert anAssertSub = new DMLDataSetAssert(dmlDefinition.getId(),
                            expectedDataFileSub, dmlDefinition.getShardingRuleTypes(), dmlDefinition.getDatabaseTypes(), subAssert.getExpectedUpdate(), dmlDefinition.getSql(),
                            dmlDefinition.getExpectedSql(), parameter, expectedParameter, dmlDefinition.getSubAsserts(), "");
                    resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
                    resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
                    resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
                    resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
                }
            }
        } else {
            resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFile, dataSource, dmlDefinition, rootSQL);
            resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFile, dataSource, dmlDefinition, rootSQL);
            resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFile, dataSource, dmlDefinition, rootSQL);
            resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFile, dataSource, dmlDefinition, rootSQL);
            List<AssertSubDefinition> subAsserts = dmlDefinition.getSubAsserts();
            if (!subAsserts.isEmpty()) {
                for (AssertSubDefinition subAssert : subAsserts) {
                    if (!subAssert.getDatabaseTypes().contains(databaseTypeEnvironment.getDatabaseType())) {
                        break;
                    }
                    String baseConfigSub = subAssert.getShardingRuleTypes();
                    if (StringUtils.isNotBlank(baseConfigSub)) {
                        String[] baseConfigs = StringUtils.split(baseConfigSub, ",");
                        boolean flag = true;
                        for (String config : baseConfigs) {
                            if (shardingRuleType.equals(config)) {
                                flag = false;
                            }
                        }
                        //Skip use cases that do not need to run
                        if (flag) {
                            continue;
                        }
                    }
                    String expectedDataFileSub = subAssert.getExpectedDataFile();
                    ParameterDefinition parameter = subAssert.getParameter();
                    ParameterDefinition expectedParameter = subAssert.getExpectedParameter();
                    String expectedDataFileTmp = expectedDataFile;
                    if (StringUtils.isBlank(expectedDataFileSub)) {
                        expectedDataFileSub = dmlDefinition.getExpectedDataFile();
                    } else {
                        expectedDataFileTmp = rootPath + "asserts/dml/" + shardingRuleType + "/" + expectedDataFileSub;
                        if (!new File(expectedDataFileTmp).exists()) {
                            expectedDataFileTmp = rootPath + "asserts/dml/" + expectedDataFileSub;
                        }
                    }
                    if (parameter == null) {
                        parameter = dmlDefinition.getParameter();
                    }
                    if (expectedParameter == null) {
                        expectedParameter = dmlDefinition.getParameter();
                    }
                    DMLDataSetAssert anAssertSub = new DMLDataSetAssert(dmlDefinition.getId(),
                            expectedDataFileSub, dmlDefinition.getShardingRuleTypes(), dmlDefinition.getDatabaseTypes(), subAssert.getExpectedUpdate(), dmlDefinition.getSql(),
                            dmlDefinition.getExpectedSql(), parameter, expectedParameter, dmlDefinition.getSubAsserts(), "");
                    resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
                    resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
                    resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
                    resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
                }
            }
        }
        if (null != dmlDefinition.getExpectedUpdate()) {
            Assert.assertEquals("Update row number error UpdateUseStatementToExecuteUpdate", dmlDefinition.getExpectedUpdate().intValue(), resultDoUpdateUseStatementToExecuteUpdate);
            Assert.assertEquals("Update row number error UpdateUseStatementToExecute", dmlDefinition.getExpectedUpdate().intValue(), resultDoUpdateUseStatementToExecute);
            Assert.assertEquals("Update row number error UpdateUsePreparedStatementToExecuteUpdate", dmlDefinition.getExpectedUpdate().intValue(), resultDoUpdateUsePreparedStatementToExecuteUpdate);
            Assert.assertEquals("Update row number error UpdateUsePreparedStatementToExecute", dmlDefinition.getExpectedUpdate().intValue(), resultDoUpdateUsePreparedStatementToExecute);
        }
    }
    
    private void dqlRun(final DQLDataSetAssert dqlDefinition, final DataSource dataSource) throws IOException, SAXException, ParserConfigurationException, XPathExpressionException, SQLException, ParseException {
        String rootSQL = dqlDefinition.getSql();
        rootSQL = SQLCasesLoader.getInstance().getSupportedSQL(rootSQL);
        try {
            dataInitializer.initializeData();
            String expectedDataFile = rootPath + "asserts/dql/" + shardingRuleType + "/" + dqlDefinition.getExpectedDataFile();
            if (!new File(expectedDataFile).exists()) {
                expectedDataFile = rootPath + "asserts/dql/" + dqlDefinition.getExpectedDataFile();
            }
            if (dqlDefinition.getParameter().getValues().isEmpty() && dqlDefinition.getParameter().getValueReplaces().isEmpty()) {
                List<AssertSubDefinition> subAsserts = dqlDefinition.getSubAsserts();
                if (subAsserts.isEmpty()) {
                    doSelectUsePreparedStatement(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                    doSelectUsePreparedStatementToExecuteSelect(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                    doSelectUseStatement(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                    doSelectUseStatementToExecuteSelect(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                } else {
                    dqlSubRun(dataSource, dqlDefinition, rootSQL, expectedDataFile, subAsserts);
                }
            } else {
                doSelectUsePreparedStatement(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                doSelectUsePreparedStatementToExecuteSelect(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                doSelectUseStatement(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                doSelectUseStatementToExecuteSelect(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                List<AssertSubDefinition> subAsserts = dqlDefinition.getSubAsserts();
                if (!subAsserts.isEmpty()) {
                    dqlSubRun(dataSource, dqlDefinition, rootSQL, expectedDataFile, subAsserts);
                }
            }
        } finally {
            dataInitializer.clearData();
        }
    }
    
    private void dqlSubRun(final DataSource dataSource, final DQLDataSetAssert anAssert, final String rootSQL, final String expectedDataFile, final List<AssertSubDefinition> subAsserts) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        for (AssertSubDefinition subAssert : subAsserts) {
            if (!subAssert.getDatabaseTypes().contains(databaseTypeEnvironment.getDatabaseType())) {
                break;
            }
            String baseSubConfig = subAssert.getShardingRuleTypes();
            if (StringUtils.isNotBlank(baseSubConfig)) {
                String[] baseConfigs = StringUtils.split(baseSubConfig, ",");
                boolean flag = true;
                for (String config : baseConfigs) {
                    if (rootPath.equals(config)) {
                        flag = false;
                    }
                }
                //Skip use cases that do not need to run
                if (flag) {
                    continue;
                }
            }
            String expectedDataFileSub = subAssert.getExpectedDataFile();
            ParameterDefinition parameter = subAssert.getParameter();
            String expectedDataFileTmp = expectedDataFile;
            if (StringUtils.isBlank(expectedDataFileSub)) {
                expectedDataFileSub = anAssert.getExpectedDataFile();
            } else {
                expectedDataFileTmp = rootPath + "asserts/dql/" + rootPath + "/" + expectedDataFileSub;
                if (!new File(expectedDataFileTmp).exists()) {
                    expectedDataFileTmp = rootPath + "asserts/dql/" + expectedDataFileSub;
                }
            }
            if (null == parameter) {
                parameter = anAssert.getParameter();
            }
            DQLDataSetAssert anAssertSub = new DQLDataSetAssert(anAssert.getId(),
                    expectedDataFileSub, anAssert.getShardingRuleTypes(), anAssert.getDatabaseTypes(), anAssert.getSql(),
                    parameter, anAssert.getSubAsserts(), "");
            doSelectUsePreparedStatement(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
            doSelectUsePreparedStatementToExecuteSelect(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
            doSelectUseStatement(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
            doSelectUseStatementToExecuteSelect(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
        }
    }
    
    private int doUpdateUsePreparedStatementToExecute(final String expectedDataFile, final DataSource dataSource, final DMLDataSetAssert anAssert, final String rootSQL) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            dataInitializer.initializeData();
            try (Connection connection = dataSource.getConnection()) {
                int actual = DatabaseUtil.updateUsePreparedStatementToExecute(connection, rootSQL,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
                
                if (anAssert.getExpectedUpdate() != null) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getInstance().getSupportedSQL(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(connection, checksql,
                        anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
                return actual;
            }
        } finally {
            dataInitializer.clearData();
        }
    }
    
    private void doUpdateUsePreparedStatementToExecuteDDL(final String expectedDataFile, final DataSource dataSource, final DDLDataSetAssert anAssert, final String rootsql) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getCleanSql());
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getInitSql());
                }
                DatabaseUtil.updateUsePreparedStatementToExecute(con, rootsql,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
                
                String table = anAssert.getTable();
                List<DataSetColumnMetadata> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getCleanSql());
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getInitSql());
            }
        }
    }
    
    private int doUpdateUsePreparedStatementToExecuteUpdate(final String expectedDataFile, final DataSource dataSource, final DMLDataSetAssert anAssert, final String rootSQL) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            dataInitializer.initializeData();
            try (Connection connection = dataSource.getConnection()) {
                int actual = DatabaseUtil.updateUsePreparedStatementToExecuteUpdate(connection, rootSQL, anAssert.getParameter());
                DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
                if (null != anAssert.getExpectedUpdate()) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checkSQL = anAssert.getExpectedSql();
                checkSQL = SQLCasesLoader.getInstance().getSupportedSQL(checkSQL);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(connection, checkSQL, anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
                return actual;
            }
        } finally {
            dataInitializer.clearData();
        }
    }
    
    private void doUpdateUsePreparedStatementToExecuteUpdateDDL(final String expectedDataFile, final DataSource dataSource, final DDLDataSetAssert anAssert, final String rootSQL) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getCleanSql());
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getInitSql());
                }
                DatabaseUtil.updateUsePreparedStatementToExecuteUpdate(con, rootSQL,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
                String table = anAssert.getTable();
                List<DataSetColumnMetadata> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getCleanSql());
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getInitSql());
            }
        }
    }
    
    private int doUpdateUseStatementToExecute(final String expectedDataFile, final DataSource dataSource, final DMLDataSetAssert anAssert, final String rootSQL) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            dataInitializer.initializeData();
            try (Connection connection = dataSource.getConnection()) {
                int actual = DatabaseUtil.updateUseStatementToExecute(connection, rootSQL, anAssert.getParameter());
                DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
                if (anAssert.getExpectedUpdate() != null) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checkSQL = anAssert.getExpectedSql();
                checkSQL = SQLCasesLoader.getInstance().getSupportedSQL(checkSQL);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(connection, checkSQL, anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
                return actual;
            }
        } finally {
            dataInitializer.clearData();
        }
    }
    
    private void doUpdateUseStatementToExecuteDDL(final String expectedDataFile, final DataSource dataSource, final DDLDataSetAssert anAssert, final String rootSQL) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getCleanSql());
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getInitSql());
                }
                DatabaseUtil.updateUseStatementToExecute(con, rootSQL, anAssert.getParameter());
                DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
                String table = anAssert.getTable();
                List<DataSetColumnMetadata> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table);
            }
        } finally {
            if (!Strings.isNullOrEmpty(anAssert.getCleanSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getCleanSql());
            }
            if (!Strings.isNullOrEmpty(anAssert.getInitSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getInitSql());
            }
        }
    }
    
    private int doUpdateUseStatementToExecuteUpdate(final String expectedDataFile, final DataSource dataSource, final DMLDataSetAssert anAssert, final String rootSQL) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            dataInitializer.initializeData();
            try (Connection connection = dataSource.getConnection()) {
                int actual = DatabaseUtil.updateUseStatementToExecuteUpdate(connection, rootSQL, anAssert.getParameter());
                DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
                if (null != anAssert.getExpectedUpdate()) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checkSQL = anAssert.getExpectedSql();
                checkSQL = SQLCasesLoader.getInstance().getSupportedSQL(checkSQL);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(connection, checkSQL, anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
                return actual;
            }
        } finally {
            dataInitializer.clearData();
        }
    }
    
    private void doUpdateUseStatementToExecuteUpdateDDL(final String expectedDataFile, final DataSource dataSource, final DDLDataSetAssert anAssert, final String rootsql) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getCleanSql());
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getInitSql());
                }
                DatabaseUtil.updateUseStatementToExecuteUpdate(con, rootsql, anAssert.getParameter());
                DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
                String table = anAssert.getTable();
                List<DataSetColumnMetadata> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table);
            }
        } finally {
            if (!Strings.isNullOrEmpty(anAssert.getCleanSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getCleanSql());
            }
            if (!Strings.isNullOrEmpty(anAssert.getInitSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseTypeEnvironment.getDatabaseType(), anAssert.getInitSql());
            }
        }
    }
    
    private void doSelectUseStatement(final String expectedDataFile, final DataSource dataSource, final DQLDataSetAssert anAssert, final String rootSQL) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection connection = dataSource.getConnection()) {
            DatasetDatabase datasetDatabase = DatabaseUtil.selectUseStatement(connection, rootSQL, anAssert.getParameter());
            DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, datasetDatabase);
        }
    }
    
    private void doSelectUseStatementToExecuteSelect(final String expectedDataFile, final DataSource dataSource, final DQLDataSetAssert anAssert, final String rootSQL) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection connection = dataSource.getConnection()) {
            DatasetDatabase datasetDatabase = DatabaseUtil.selectUseStatementToExecuteSelect(connection, rootSQL, anAssert.getParameter());
            DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, datasetDatabase);
        }
    }
    
    private void doSelectUsePreparedStatement(final String expectedDataFile, final DataSource dataSource, final DQLDataSetAssert anAssert, final String rootSQL) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection connection = dataSource.getConnection()) {
            DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(connection, rootSQL, anAssert.getParameter());
            DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
        }
    }
    
    private void doSelectUsePreparedStatementToExecuteSelect(final String expectedDataFile, final DataSource dataSource, final DQLDataSetAssert anAssert, final String rootSQL) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection connection = dataSource.getConnection()) {
            DatasetDatabase datasetDatabase = DatabaseUtil.selectUsePreparedStatementToExecuteSelect(connection, rootSQL, anAssert.getParameter());
            DatasetDefinition checkDataset = DataSetsParser.parse(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, datasetDatabase);
        }
    }
}
