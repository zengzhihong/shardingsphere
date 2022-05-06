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

package org.apache.shardingsphere.singletable.rule;

import lombok.Getter;
import org.apache.shardingsphere.infra.config.props.ConfigurationProperties;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.datanode.DataNode;
import org.apache.shardingsphere.infra.distsql.constant.ExportableConstants;
import org.apache.shardingsphere.infra.metadata.schema.QualifiedTable;
import org.apache.shardingsphere.infra.route.context.RouteContext;
import org.apache.shardingsphere.infra.route.context.RouteUnit;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.rule.identifier.scope.SchemaRule;
import org.apache.shardingsphere.infra.rule.identifier.type.DataNodeContainedRule;
import org.apache.shardingsphere.infra.rule.identifier.type.DataSourceContainedRule;
import org.apache.shardingsphere.infra.rule.identifier.type.ExportableRule;
import org.apache.shardingsphere.infra.rule.identifier.type.MutableDataNodeRule;
import org.apache.shardingsphere.infra.rule.identifier.type.TableContainedRule;
import org.apache.shardingsphere.singletable.config.SingleTableRuleConfiguration;
import org.apache.shardingsphere.singletable.datanode.SingleTableDataNodeLoader;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Single table rule.
 */
@Getter
public final class SingleTableRule implements SchemaRule, DataNodeContainedRule, TableContainedRule, MutableDataNodeRule, ExportableRule {
    
    private String defaultDataSource;
    
    private final Collection<String> dataSourceNames;
    
    private final Map<String, Collection<DataNode>> singleTableDataNodes;
    
    private final Map<String, String> tableNames;
    
    public SingleTableRule(final SingleTableRuleConfiguration config, final String databaseName, final DatabaseType databaseType,
                           final Map<String, DataSource> dataSourceMap, final Collection<ShardingSphereRule> builtRules, final ConfigurationProperties props) {
        Map<String, DataSource> aggregateDataSourceMap = getAggregateDataSourceMap(dataSourceMap, builtRules);
        dataSourceNames = aggregateDataSourceMap.keySet();
        singleTableDataNodes = SingleTableDataNodeLoader.load(databaseName, databaseType, aggregateDataSourceMap, getExcludedTables(builtRules), props);
        tableNames = singleTableDataNodes.entrySet().stream().collect(Collectors.toConcurrentMap(Entry::getKey, entry -> entry.getValue().iterator().next().getTableName()));
        config.getDefaultDataSource().ifPresent(optional -> defaultDataSource = optional);
    }
    
    private Map<String, DataSource> getAggregateDataSourceMap(final Map<String, DataSource> dataSourceMap, final Collection<ShardingSphereRule> builtRules) {
        Map<String, DataSource> result = new LinkedHashMap<>(dataSourceMap);
        for (ShardingSphereRule each : builtRules) {
            if (each instanceof DataSourceContainedRule) {
                result = getAggregateDataSourceMap(result, (DataSourceContainedRule) each);
            }
        }
        return result;
    }
    
    private Map<String, DataSource> getAggregateDataSourceMap(final Map<String, DataSource> dataSourceMap, final DataSourceContainedRule builtRule) {
        Map<String, DataSource> result = new LinkedHashMap<>();
        for (Entry<String, Collection<String>> entry : builtRule.getDataSourceMapper().entrySet()) {
            for (String each : entry.getValue()) {
                if (dataSourceMap.containsKey(each)) {
                    result.putIfAbsent(entry.getKey(), dataSourceMap.remove(each));
                }
            }
        }
        result.putAll(dataSourceMap);
        return result;
    }
    
    /**
     * Judge whether single tables are in same data source or not.
     *
     * @param singleTableNames single table names
     * @return whether single tables are in same data source or not
     */
    public boolean isSingleTablesInSameDataSource(final Collection<QualifiedTable> singleTableNames) {
        String firstFoundDataSourceName = null;
        for (QualifiedTable each : singleTableNames) {
            Optional<DataNode> dataNode = findSingleTableDataNode(each.getSchemaName(), each.getTableName());
            if (!dataNode.isPresent()) {
                continue;
            }
            if (null == firstFoundDataSourceName) {
                firstFoundDataSourceName = dataNode.get().getDataSourceName();
                continue;
            }
            if (!firstFoundDataSourceName.equals(dataNode.get().getDataSourceName())) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Judge whether all tables are in same data source or not.
     * 
     * @param routeContext route context
     * @param singleTableNames single table names
     * @return whether all tables are in same data source or not
     */
    public boolean isAllTablesInSameDataSource(final RouteContext routeContext, final Collection<QualifiedTable> singleTableNames) {
        if (!isSingleTablesInSameDataSource(singleTableNames)) {
            return false;
        }
        QualifiedTable sampleTable = singleTableNames.iterator().next();
        Optional<DataNode> dataNode = findSingleTableDataNode(sampleTable.getSchemaName(), sampleTable.getTableName());
        if (dataNode.isPresent()) {
            for (RouteUnit each : routeContext.getRouteUnits()) {
                if (!each.getDataSourceMapper().getLogicName().equals(dataNode.get().getDataSourceName())) {
                    return false;
                }
            }
        }
        return true;
    }
    
    /**
     * Get default data source.
     *
     * @return default data source
     */
    public Optional<String> getDefaultDataSource() {
        return Optional.ofNullable(defaultDataSource);
    }
    
    /**
     * Get single table names.
     *
     * @param qualifiedTables qualified tables
     * @return single table names
     */
    public Collection<QualifiedTable> getSingleTableNames(final Collection<QualifiedTable> qualifiedTables) {
        Collection<QualifiedTable> result = new LinkedList<>();
        for (QualifiedTable each : qualifiedTables) {
            Collection<DataNode> dataNodes = singleTableDataNodes.getOrDefault(each.getTableName(), new LinkedList<>());
            if (!dataNodes.isEmpty() && containsDataNode(each, dataNodes)) {
                result.add(each);
            }
        }
        return result;
    }
    
    private boolean containsDataNode(final QualifiedTable qualifiedTable, final Collection<DataNode> dataNodes) {
        for (DataNode each : dataNodes) {
            if (qualifiedTable.getSchemaName().equalsIgnoreCase(each.getSchemaName())) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public void put(final String dataSourceName, final String schemaName, final String tableName) {
        if (dataSourceNames.contains(dataSourceName)) {
            Collection<DataNode> dataNodes = singleTableDataNodes.computeIfAbsent(tableName, key -> new LinkedHashSet<>());
            DataNode dataNode = new DataNode(dataSourceName, tableName);
            dataNode.setSchemaName(schemaName);
            dataNodes.add(dataNode);
            tableNames.put(tableName.toLowerCase(), tableName);
        }
    }
    
    @Override
    public void remove(final String schemaName, final String tableName) {
        Collection<DataNode> dataNodes = singleTableDataNodes.getOrDefault(tableName, new LinkedHashSet<>());
        dataNodes.removeIf(each -> schemaName.equalsIgnoreCase(each.getSchemaName()));
        if (dataNodes.isEmpty()) {
            tableNames.remove(tableName.toLowerCase());
        }
    }
    
    @Override
    public Optional<DataNode> findSingleTableDataNode(final String schemaName, final String tableName) {
        Collection<DataNode> dataNodes = singleTableDataNodes.getOrDefault(tableName.toLowerCase(), new LinkedHashSet<>());
        for (DataNode each : dataNodes) {
            if (schemaName.equalsIgnoreCase(each.getSchemaName())) {
                return Optional.of(each);
            }
        }
        return Optional.empty();
    }
    
    private Collection<String> getExcludedTables(final Collection<ShardingSphereRule> rules) {
        return rules.stream().filter(each -> each instanceof DataNodeContainedRule)
                .flatMap(each -> ((DataNodeContainedRule) each).getAllTables().stream()).collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
    }
    
    @Override
    public Map<String, Collection<DataNode>> getAllDataNodes() {
        return singleTableDataNodes;
    }
    
    @Override
    public Collection<DataNode> getDataNodesByTableName(final String tableName) {
        return singleTableDataNodes.getOrDefault(tableName.toLowerCase(), Collections.emptyList());
    }
    
    @Override
    public Optional<String> findFirstActualTable(final String logicTable) {
        return Optional.empty();
    }
    
    @Override
    public boolean isNeedAccumulate(final Collection<String> tables) {
        return false;
    }
    
    @Override
    public Optional<String> findLogicTableByActualTable(final String actualTable) {
        return Optional.empty();
    }
    
    @Override
    public Optional<String> findActualTableByCatalog(final String catalog, final String logicTable) {
        return Optional.empty();
    }
    
    @Override
    public Collection<String> getAllTables() {
        return new HashSet<>(tableNames.values());
    }
    
    @Override
    public Collection<String> getTables() {
        return new HashSet<>(tableNames.values());
    }
    
    @Override
    public String getType() {
        return SingleTableRule.class.getSimpleName();
    }
    
    @Override
    public Map<String, Supplier<Object>> getExportedMethods() {
        Map<String, Supplier<Object>> result = new HashMap<>(1, 1);
        result.put(ExportableConstants.EXPORTABLE_KEY_SINGLE_TABLES, tableNames::keySet);
        return result;
    }
}
