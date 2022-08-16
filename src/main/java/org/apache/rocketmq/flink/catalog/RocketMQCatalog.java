/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.flink.catalog;

import com.google.common.collect.Maps;
import java.util.Collections;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.types.DataType;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.flink.common.AvroSchemaConverter;
import org.apache.rocketmq.flink.common.constant.RocketMqCatalogConstant;
import org.apache.rocketmq.flink.common.constant.SchemaRegistryConstant;
import org.apache.rocketmq.remoting.protocol.LanguageCode;
import org.apache.rocketmq.schema.registry.client.SchemaRegistryClient;
import org.apache.rocketmq.schema.registry.client.SchemaRegistryClientFactory;
import org.apache.rocketmq.schema.registry.common.dto.GetSchemaResponse;
import org.apache.rocketmq.schema.registry.common.model.SchemaType;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;

import org.apache.flink.table.catalog.AbstractCatalog;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.CatalogDatabase;
import org.apache.flink.table.catalog.CatalogDatabaseImpl;
import org.apache.flink.table.catalog.CatalogFunction;
import org.apache.flink.table.catalog.CatalogPartition;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.catalog.exceptions.DatabaseAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotEmptyException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotExistException;
import org.apache.flink.table.catalog.exceptions.FunctionAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.FunctionNotExistException;
import org.apache.flink.table.catalog.exceptions.PartitionAlreadyExistsException;
import org.apache.flink.table.catalog.exceptions.PartitionNotExistException;
import org.apache.flink.table.catalog.exceptions.PartitionSpecInvalidException;
import org.apache.flink.table.catalog.exceptions.TableAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.table.catalog.exceptions.TableNotPartitionedException;
import org.apache.flink.table.catalog.stats.CatalogColumnStatistics;
import org.apache.flink.table.catalog.stats.CatalogTableStatistics;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.factories.Factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Expose a RocketMQ instance as a database catalog. */
public class RocketMQCatalog extends AbstractCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(RocketMQCatalog.class);
    public static final String DEFAULT_DB = "default";
    public final String namesrvAddr;
    private final String schemaRegistryUrl;
    private DefaultMQAdminExt mqAdminExt;
    private SchemaRegistryClient schemaRegistryClient;

    public RocketMQCatalog(String catalogName, String database, String namesrvAddr, String schemaRegistryUrl) {
        super(catalogName, database);
        this.namesrvAddr = namesrvAddr;
        this.schemaRegistryUrl = schemaRegistryUrl;
        LOG.info("Created RocketMQ Catalog {}", catalogName);
    }

    @Override
    public Optional<Factory> getFactory() {
        return Optional.of(new RocketMQCatalogFactory());
    }

    @Override
    public void open() throws CatalogException {
        if (mqAdminExt == null) {
            try {
                mqAdminExt = new DefaultMQAdminExt();
                mqAdminExt.setNamesrvAddr(namesrvAddr);
                mqAdminExt.setLanguage(LanguageCode.JAVA);
                mqAdminExt.start();
            } catch (MQClientException e) {
                throw new CatalogException(
                        "Failed to create RocketMQ admin using :" + namesrvAddr, e);
            }
        }
        if(schemaRegistryClient == null){
            schemaRegistryClient = SchemaRegistryClientFactory.newClient(schemaRegistryUrl, null);
        }
    }

    @Override
    public void close() throws CatalogException {
        if (Objects.nonNull(mqAdminExt)){
            mqAdminExt.shutdown();
        }
        if (Objects.nonNull(schemaRegistryClient)){
            schemaRegistryClient = null;
        }
    }

    @Override
    public List<String> listDatabases() throws CatalogException {
        return null;
    }

    @Override
    public CatalogDatabase getDatabase(String databaseName)
            throws DatabaseNotExistException, CatalogException {
        Map<String, String> properties = new HashMap<>();
        return new CatalogDatabaseImpl(properties, databaseName);
    }

    @Override
    public boolean databaseExists(String databaseName) throws CatalogException {
        return false;
    }

    @Override
    public void createDatabase(String name, CatalogDatabase database, boolean ignoreIfExists)
            throws DatabaseAlreadyExistException, CatalogException {}

    @Override
    public void dropDatabase(String name, boolean ignoreIfNotExists, boolean cascade)
            throws DatabaseNotExistException, DatabaseNotEmptyException, CatalogException {}

    @Override
    public List<String> listTables(String databaseName)
            throws DatabaseNotExistException, CatalogException {
        if (!getDefaultDatabase().equals(databaseName)) {
            throw new DatabaseNotExistException(getName(), databaseName);
        }
        try {
            List<String> tenant = schemaRegistryClient.getSubjectsByTenant(null, databaseName);
            return tenant;
        } catch (Exception e) {
            throw new CatalogException("Fail to get topics from schema registry client.", e);
        }
    }

    @Override
    public CatalogBaseTable getTable(ObjectPath tablePath)
        throws TableNotExistException, CatalogException {
        if (!tableExists(tablePath)) {
            throw new TableNotExistException(getName(), tablePath);
        }
        String subject = tablePath.getObjectName();
        try {
            GetSchemaResponse getSchemaResponse = schemaRegistryClient.getSchemaBySubject(subject);
            if (getSchemaResponse.getType() != SchemaType.AVRO) {
                throw new CatalogException("Only support avro schema.");
            }
            return getCatalogTableForSchema(subject,getSchemaResponse);
        } catch (Exception e) {
            throw new CatalogException("Fail to get schema from schema registry client.", e);
        }
    }

    private CatalogTable getCatalogTableForSchema(String topic,GetSchemaResponse getSchemaResponse) {
        org.apache.avro.Schema avroSchema = new org.apache.avro.Schema.Parser().parse(getSchemaResponse.getIdl());
        Schema.Builder builder = Schema.newBuilder();
        for (org.apache.avro.Schema.Field field : avroSchema.getFields()) {
            org.apache.avro.Schema.Type type = field.schema().getType();
            DataType dataType = AvroSchemaConverter.convertToDataType(type.getName());
            builder.column(field.name(), dataType);
        }
        Schema schema = builder.build();
        Map<String, String> options = Maps.newHashMap();
        options.put(RocketMqCatalogConstant.CONNECTOR, RocketMqCatalogConstant.ROCKETMQ_CONNECTOR);
        options.put(RocketMqCatalogConstant.TOPIC, topic);
        options.put(RocketMqCatalogConstant.NAME_SERVER_ADDRESS,mqAdminExt.getNamesrvAddr());
        return CatalogTable.of(schema, null, Collections.emptyList(), options);
    }

    @Override
    public boolean tableExists(ObjectPath tablePath) throws CatalogException {
        if (!getDefaultDatabase().equals(tablePath.getDatabaseName())) {
            return false;
        }
        if (StringUtils.isEmpty(tablePath.getObjectName())) {
            return false;
        }
        String subject = tablePath.getObjectName();
        try {
            GetSchemaResponse getSchemaResponse = schemaRegistryClient.getSchemaBySubject(subject);
            if (Objects.nonNull(getSchemaResponse)) {
                return true;
            }
        } catch (Exception e) {
            throw new CatalogException("Fail to get topics from schema registry client.", e);
        }
        return false;
    }

    @Override
    public void createTable(ObjectPath tablePath, CatalogBaseTable table, boolean ignoreIfExists)
            throws TableAlreadyExistException, DatabaseNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dropTable(ObjectPath tablePath, boolean ignoreIfNotExists)
            throws TableNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    // ------------------------------------------------------------------------
    // Unsupported catalog operations for RocketMQ
    // There should not be such permission in the connector, it is very dangerous
    // ------------------------------------------------------------------------

    @Override
    public List<String> listFunctions(String dbName)
            throws DatabaseNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public CatalogFunction getFunction(ObjectPath functionPath)
            throws FunctionNotExistException, CatalogException {
        throw new FunctionNotExistException("Not support to find functions.", functionPath);
    }

    @Override
    public boolean functionExists(ObjectPath functionPath) throws CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createFunction(
            ObjectPath functionPath, CatalogFunction function, boolean ignoreIfExists)
            throws FunctionAlreadyExistException, DatabaseNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void alterFunction(
            ObjectPath functionPath, CatalogFunction newFunction, boolean ignoreIfNotExists)
            throws FunctionNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dropFunction(ObjectPath functionPath, boolean ignoreIfNotExists)
            throws FunctionNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void alterDatabase(String name, CatalogDatabase newDatabase, boolean ignoreIfNotExists)
            throws DatabaseNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> listViews(String databaseName)
            throws DatabaseNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void alterTable(
            ObjectPath tablePath, CatalogBaseTable newTable, boolean ignoreIfNotExists)
            throws TableNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void renameTable(ObjectPath tablePath, String newTableName, boolean ignoreIfNotExists)
            throws TableNotExistException, TableAlreadyExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<CatalogPartitionSpec> listPartitions(ObjectPath tablePath)
            throws TableNotExistException, TableNotPartitionedException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<CatalogPartitionSpec> listPartitions(
            ObjectPath tablePath, CatalogPartitionSpec partitionSpec)
            throws TableNotExistException, TableNotPartitionedException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<CatalogPartitionSpec> listPartitionsByFilter(
            ObjectPath tablePath, List<Expression> expressions)
            throws TableNotExistException, TableNotPartitionedException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public CatalogPartition getPartition(ObjectPath tablePath, CatalogPartitionSpec partitionSpec)
            throws PartitionNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean partitionExists(ObjectPath tablePath, CatalogPartitionSpec partitionSpec)
            throws CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createPartition(
            ObjectPath tablePath,
            CatalogPartitionSpec partitionSpec,
            CatalogPartition partition,
            boolean ignoreIfExists)
            throws TableNotExistException, TableNotPartitionedException,
                    PartitionSpecInvalidException, PartitionAlreadyExistsException,
                    CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dropPartition(
            ObjectPath tablePath, CatalogPartitionSpec partitionSpec, boolean ignoreIfNotExists)
            throws PartitionNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void alterPartition(
            ObjectPath tablePath,
            CatalogPartitionSpec partitionSpec,
            CatalogPartition newPartition,
            boolean ignoreIfNotExists)
            throws PartitionNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public CatalogTableStatistics getTableStatistics(ObjectPath tablePath)
            throws TableNotExistException, CatalogException {
        return CatalogTableStatistics.UNKNOWN;
    }

    @Override
    public CatalogColumnStatistics getTableColumnStatistics(ObjectPath tablePath)
            throws TableNotExistException, CatalogException {
        return CatalogColumnStatistics.UNKNOWN;
    }

    @Override
    public CatalogTableStatistics getPartitionStatistics(
            ObjectPath tablePath, CatalogPartitionSpec partitionSpec)
            throws PartitionNotExistException, CatalogException {
        return CatalogTableStatistics.UNKNOWN;
    }

    @Override
    public CatalogColumnStatistics getPartitionColumnStatistics(
            ObjectPath tablePath, CatalogPartitionSpec partitionSpec)
            throws PartitionNotExistException, CatalogException {
        return CatalogColumnStatistics.UNKNOWN;
    }

    @Override
    public void alterTableStatistics(
            ObjectPath tablePath, CatalogTableStatistics tableStatistics, boolean ignoreIfNotExists)
            throws TableNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void alterTableColumnStatistics(
            ObjectPath tablePath,
            CatalogColumnStatistics columnStatistics,
            boolean ignoreIfNotExists)
            throws TableNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void alterPartitionStatistics(
            ObjectPath tablePath,
            CatalogPartitionSpec partitionSpec,
            CatalogTableStatistics partitionStatistics,
            boolean ignoreIfNotExists)
            throws PartitionNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void alterPartitionColumnStatistics(
            ObjectPath tablePath,
            CatalogPartitionSpec partitionSpec,
            CatalogColumnStatistics columnStatistics,
            boolean ignoreIfNotExists)
            throws PartitionNotExistException, CatalogException {
        throw new UnsupportedOperationException();
    }
}
