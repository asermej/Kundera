/**
 * Copyright 2012 Impetus Infotech.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.impetus.client.cassandra;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.PersistenceException;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EmbeddableType;
import javax.persistence.metamodel.ManagedType;

import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.CfDef;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnDef;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.Compression;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.CounterColumn;
import org.apache.cassandra.thrift.CounterSuperColumn;
import org.apache.cassandra.thrift.CqlResult;
import org.apache.cassandra.thrift.CqlRow;
import org.apache.cassandra.thrift.IndexClause;
import org.apache.cassandra.thrift.IndexExpression;
import org.apache.cassandra.thrift.IndexType;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.KeySlice;
import org.apache.cassandra.thrift.KsDef;
import org.apache.cassandra.thrift.Mutation;
import org.apache.cassandra.thrift.NotFoundException;
import org.apache.cassandra.thrift.SchemaDisagreementException;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.cassandra.thrift.TimedOutException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.TException;
import org.scale7.cassandra.pelops.Bytes;
import org.scale7.cassandra.pelops.ColumnOrSuperColumnHelper;
import org.scale7.cassandra.pelops.Pelops;
import org.scale7.cassandra.pelops.pool.IThriftPool.IPooledConnection;

import com.impetus.client.cassandra.common.CassandraConstants;
import com.impetus.client.cassandra.common.CassandraUtilities;
import com.impetus.client.cassandra.config.CassandraPropertyReader;
import com.impetus.client.cassandra.datahandler.CassandraDataHandler;
import com.impetus.client.cassandra.pelops.PelopsUtils;
import com.impetus.client.cassandra.thrift.CQLTranslator;
import com.impetus.client.cassandra.thrift.CQLTranslator.TranslationType;
import com.impetus.client.cassandra.thrift.ThriftDataResultHelper;
import com.impetus.client.cassandra.thrift.ThriftRow;
import com.impetus.kundera.Constants;
import com.impetus.kundera.KunderaException;
import com.impetus.kundera.PersistenceProperties;
import com.impetus.kundera.client.Client;
import com.impetus.kundera.client.ClientBase;
import com.impetus.kundera.client.ClientPropertiesSetter;
import com.impetus.kundera.client.EnhanceEntity;
import com.impetus.kundera.db.DataRow;
import com.impetus.kundera.db.RelationHolder;
import com.impetus.kundera.db.SearchResult;
import com.impetus.kundera.graph.Node;
import com.impetus.kundera.lifecycle.states.RemovedState;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.KunderaMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.PersistenceUnitMetadata;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;
import com.impetus.kundera.property.PropertyAccessException;
import com.impetus.kundera.property.PropertyAccessorFactory;
import com.impetus.kundera.property.PropertyAccessorHelper;

/**
 * Base Class for all Cassandra Clients Contains methods that are applicable to
 * (bot not specific to) different Cassandra clients.
 * 
 * @author amresh.singh
 */
public abstract class CassandraClientBase extends ClientBase implements ClientPropertiesSetter
{

    /** log for this class. */
    private static Log log = LogFactory.getLog(CassandraClientBase.class);

    /** The cql version. */
    private String cqlVersion = CassandraConstants.CQL_VERSION_2_0;

    /** The consistency level. */
    private ConsistencyLevel consistencyLevel = ConsistencyLevel.ONE;

    /** The closed. */
    private boolean closed = false;

    /** list of nodes for batch processing. */
    private List<Node> nodes = new ArrayList<Node>();

    /** batch size. */
    private int batchSize;

    /**
     * constructor using fields.
     *
     * @param persistenceUnit the persistence unit
     */
    protected CassandraClientBase(String persistenceUnit)
    {
        PersistenceUnitMetadata puMetadata = KunderaMetadataManager.getPersistenceUnitMetadata(persistenceUnit);
        batchSize = puMetadata.getBatchSize();
        cqlVersion = CassandraPropertyReader.csmd != null ? CassandraPropertyReader.csmd.getCqlVersion()
                : CassandraConstants.CQL_VERSION_2_0;
    }

    /**
     * Populates foreign key as column.
     * 
     * @param rlName
     *            relation name
     * @param rlValue
     *            relation value
     * @param timestamp
     *            the timestamp
     * @return the column
     * @throws PropertyAccessException
     *             the property access exception
     */
    protected Column populateFkey(String rlName, Object rlValue, long timestamp) throws PropertyAccessException
    {
        Column col = new Column();
        col.setName(PropertyAccessorFactory.STRING.toBytes(rlName));
        col.setValue(PropertyAccessorHelper.getBytes(rlValue));
        col.setTimestamp(timestamp);
        return col;
    }

    /**
     * On counter column.
     *
     * @param m the m
     * @param isRelation the is relation
     * @param relations the relations
     * @param ks the ks
     * @return the list
     */
    protected List<Object> onCounterColumn(EntityMetadata m, boolean isRelation, List<String> relations,
            List<KeySlice> ks)
    {
        List<Object> entities;
        if (m.getType().isSuperColumnFamilyMetadata())
        {
            Map<Bytes, List<CounterSuperColumn>> qCounterSuperColumnResults = ColumnOrSuperColumnHelper
                    .transformKeySlices(ks, ColumnOrSuperColumnHelper.COUNTER_SUPER_COLUMN);
            entities = new ArrayList<Object>(qCounterSuperColumnResults.size());

            // populateDataForSuperCounter(m, qCounterSuperColumnResults,
            // entities, isRelation, relations);

            for (Bytes key : qCounterSuperColumnResults.keySet())
            {
                List<CounterSuperColumn> counterSuperColumns = qCounterSuperColumnResults.get(key);
                ThriftRow tr = new ThriftRow(PropertyAccessorHelper.getObject(m.getIdAttribute().getJavaType(),
                        key.toByteArray()), m.getTableName(), new ArrayList<Column>(0), new ArrayList<SuperColumn>(0),
                        new ArrayList<CounterColumn>(0), counterSuperColumns);
                entities.add(getDataHandler().populateEntity(tr, m, relations, isRelation));
            }

        }
        else
        {

            Map<Bytes, List<CounterColumn>> qCounterColumnResults = ColumnOrSuperColumnHelper.transformKeySlices(ks,
                    ColumnOrSuperColumnHelper.COUNTER_COLUMN);
            entities = new ArrayList<Object>(qCounterColumnResults.size());

            // populateDataForCounter(m, qCounterColumnResults, entities,
            // isRelation, relations, dataHandler);
            for (Bytes key : qCounterColumnResults.keySet())
            {
                List<CounterColumn> counterColumns = qCounterColumnResults.get(key);
                ThriftRow tr = new ThriftRow(PropertyAccessorHelper.getObject(m.getIdAttribute().getJavaType(),
                        key.toByteArray()), m.getTableName(), new ArrayList<Column>(0), new ArrayList<SuperColumn>(0),
                        counterColumns, new ArrayList<CounterSuperColumn>(0));
                entities.add(getDataHandler().populateEntity(tr, m, relations, isRelation));
            }
        }
        return entities;
    }

    /**
     * Compute entity via columns.
     *
     * @param m the m
     * @param isRelation the is relation
     * @param relations the relations
     * @param entities the entities
     * @param qResults the q results
     */
    protected void computeEntityViaColumns(EntityMetadata m, boolean isRelation, List<String> relations,
            List<Object> entities, Map<Bytes, List<Column>> qResults)
    {
        for (Bytes key : qResults.keySet())
        {
            List<Column> columns = qResults.get(key);
            ThriftRow tr = new ThriftRow(PropertyAccessorHelper.getObject(m.getIdAttribute().getJavaType(),
                    key.toByteArray()), m.getTableName(), columns, new ArrayList<SuperColumn>(0),
                    new ArrayList<CounterColumn>(0), new ArrayList<CounterSuperColumn>(0));
            Object o = getDataHandler().populateEntity(tr, m, relations, isRelation);
            if (o != null)
            {
                entities.add(o);
            }

        }
    }

    /**
     * Compute entity via super columns.
     *
     * @param m the m
     * @param isRelation the is relation
     * @param relations the relations
     * @param entities the entities
     * @param qResults the q results
     */
    protected void computeEntityViaSuperColumns(EntityMetadata m, boolean isRelation, List<String> relations,
            List<Object> entities, Map<Bytes, List<SuperColumn>> qResults)
    {
        for (Bytes key : qResults.keySet())
        {
            List<SuperColumn> superColumns = qResults.get(key);

            ThriftRow tr = new ThriftRow(PropertyAccessorHelper.getObject(m.getIdAttribute().getJavaType(),
                    key.toByteArray()), m.getTableName(), new ArrayList<Column>(0), superColumns,
                    new ArrayList<CounterColumn>(0), new ArrayList<CounterSuperColumn>(0));

            Object o = getDataHandler().populateEntity(tr, m, relations, isRelation);
            if (o != null)
            {
                entities.add(o);
            }

        }
    }

    /**
     * Adds relation foreign key values as thrift column/ value to thrift row.
     *
     * @param metadata the metadata
     * @param tf the tf
     * @param relations the relations
     */
    protected void addRelationsToThriftRow(EntityMetadata metadata, ThriftRow tf, List<RelationHolder> relations)
    {
        if (relations != null)
        {
            long timestamp = System.currentTimeMillis();
            MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                    metadata.getPersistenceUnit());
            for (RelationHolder rh : relations)
            {
                String linkName = rh.getRelationName();
                Object linkValue = rh.getRelationValue();

                if (linkName != null && linkValue != null)
                {
                    // if (metadata.getEmbeddedColumnsAsList().isEmpty())
                    if (metaModel.getEmbeddables(metadata.getEntityClazz()).isEmpty()/*
                                                                                      * metadata
                                                                                      * .
                                                                                      * getEmbeddedColumnsAsList
                                                                                      * (
                                                                                      * )
                                                                                      * .
                                                                                      * isEmpty
                                                                                      * (
                                                                                      * )
                                                                                      */)
                    {
                        if (metadata.isCounterColumnType())
                        {
                            CounterColumn col = populateCounterFkey(linkName, linkValue);
                            tf.addCounterColumn(col);
                        }
                        else
                        {
                            Column col = populateFkey(linkName, linkValue, timestamp);
                            tf.addColumn(col);
                        }

                    }
                    else
                    {
                        if (metadata.isCounterColumnType())
                        {
                            CounterSuperColumn counterSuperColumn = new CounterSuperColumn();
                            counterSuperColumn.setName(linkName.getBytes());
                            CounterColumn column = populateCounterFkey(linkName, linkValue);
                            counterSuperColumn.addToColumns(column);
                            tf.addCounterSuperColumn(counterSuperColumn);
                        }
                        else
                        {
                            SuperColumn superColumn = new SuperColumn();
                            superColumn.setName(linkName.getBytes());
                            Column column = populateFkey(linkName, linkValue, timestamp);
                            superColumn.addToColumns(column);
                            tf.addSuperColumn(superColumn);
                        }
                    }
                }
            }
        }
    }

    /**
     * Populate counter fkey.
     *
     * @param rlName the rl name
     * @param rlValue the rl value
     * @return the counter column
     */
    private CounterColumn populateCounterFkey(String rlName, Object rlValue)
    {
        CounterColumn counterCol = new CounterColumn();
        counterCol.setName(PropertyAccessorFactory.STRING.toBytes(rlName));
        counterCol.setValue((Long) rlValue);
        return counterCol;
    }

    /**
     * Deletes record for given primary key from counter column family.
     *
     * @param pKey the key
     * @param metadata the metadata
     * @param consistencyLevel the consistency level
     */
    protected void deleteRecordFromCounterColumnFamily(Object pKey, EntityMetadata metadata,
            ConsistencyLevel consistencyLevel)
    {
        ColumnPath path = new ColumnPath(metadata.getTableName());

        IPooledConnection conn = null;
        try
        {
            conn = PelopsUtils.getCassandraConnection(metadata.getPersistenceUnit());
            Cassandra.Client cassandra_client = conn.getAPI();
            cassandra_client.set_keyspace(metadata.getSchema());
            cassandra_client.remove_counter(
                    (CassandraUtilities.toBytes(pKey, metadata.getIdAttribute().getJavaType())).getBytes(), path,
                    consistencyLevel);

        }
        catch (InvalidRequestException ire)
        {
            log.error("Error during executing delete, Caused by :" + ire.getMessage());
            throw new PersistenceException(ire);
        }
        catch (UnavailableException ue)
        {
            log.error("Error during executing delete, Caused by :" + ue.getMessage());
            throw new PersistenceException(ue);
        }
        catch (TimedOutException toe)
        {
            log.error("Error during executing delete, Caused by :" + toe.getMessage());
            throw new PersistenceException(toe);
        }
        catch (TException te)
        {
            log.error("Error during executing delete, Caused by :" + te.getMessage());
            throw new PersistenceException(te);
        }
        finally
        {
            PelopsUtils.releaseConnection(conn);
        }
    }

    /**
     * Creates secondary indexes on columns if not already created.
     * 
     * @param tableName
     *            Column family name
     * @param poolName
     *            Pool Name
     * @param columns
     *            List of columns
     */
    protected void createIndexesOnColumns(String tableName, String poolName, List<Column> columns)
    {
        String keyspace = Pelops.getDbConnPool(poolName).getKeyspace();
        try
        {
            Cassandra.Client api = Pelops.getDbConnPool(poolName).getConnection().getAPI();
            KsDef ksDef = api.describe_keyspace(keyspace);
            List<CfDef> cfDefs = ksDef.getCf_defs();

            // Column family definition on which secondary index creation is
            // required
            CfDef columnFamilyDefToUpdate = null;
            boolean isUpdatable = false;
            // boolean isNew=false;

            for (CfDef cfDef : cfDefs)
            {
                if (cfDef.getName().equals(tableName))
                {
                    columnFamilyDefToUpdate = cfDef;
                    // isNew=false;
                    break;
                }
            }

            if (columnFamilyDefToUpdate == null)
            {
                throw new PersistenceException("Join table does not exist in database");
            }
            // //create a column family, in case it is not already available.
            // if(columnFamilyDefToUpdate == null)
            // {
            // isNew = true;
            // columnFamilyDefToUpdate = new CfDef(keyspace, tableName);
            // ksDef.addToCf_defs(columnFamilyDefToUpdate);
            // }

            // Get list of indexes already created
            List<ColumnDef> columnMetadataList = columnFamilyDefToUpdate.getColumn_metadata();
            List<String> indexList = new ArrayList<String>();

            if (columnMetadataList != null)
            {
                for (ColumnDef columnDef : columnMetadataList)
                {
                    indexList.add(Bytes.toUTF8(columnDef.getName()));
                }
                // need to set them to null else it is giving problem on update
                // column family and trying to add again existing indexes.
                // columnFamilyDefToUpdate.column_metadata = null;
            }

            // Iterate over all columns for creating secondary index on them
            for (Column column : columns)
            {

                ColumnDef columnDef = new ColumnDef();

                columnDef.setName(column.getName());
                columnDef.setValidation_class(BytesType.class.getSimpleName());
                columnDef.setIndex_type(IndexType.KEYS);

                // Add secondary index only if it's not already created
                // (if already created, it would be there in column family
                // definition)
                if (!indexList.contains(Bytes.toUTF8(column.getName())))
                {
                    isUpdatable = true;
                    columnFamilyDefToUpdate.addToColumn_metadata(columnDef);
                }
            }

            // Finally, update column family with modified column family
            // definition
            if (isUpdatable)
            {
                api.system_update_column_family(columnFamilyDefToUpdate);
            }// } else
             // {
             // api.system_add_column_family(columnFamilyDefToUpdate);
             // }

        }
        catch (InvalidRequestException e)
        {
            log.warn("Could not create secondary index on column family " + tableName + ".Details:" + e.getMessage());

        }
        catch (SchemaDisagreementException e)
        {
            log.warn("Could not create secondary index on column family " + tableName + ".Details:" + e.getMessage());

        }
        catch (TException e)
        {
            log.warn("Could not create secondary index on column family " + tableName + ".Details:" + e.getMessage());

        }
        catch (NotFoundException e)
        {
            log.warn("Could not create secondary index on column family " + tableName + ".Details:" + e.getMessage());

        }
        catch (PropertyAccessException e)
        {
            log.warn("Could not create secondary index on column family " + tableName + ".Details:" + e.getMessage());

        }
    }

    /**
     * Finds an entiry from database.
     *
     * @param entityClass the entity class
     * @param rowId the row id
     * @return the object
     */
    public Object find(Class entityClass, Object rowId)
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(entityClass);
        List<String> relationNames = entityMetadata.getRelationNames();
        return find(entityClass, entityMetadata, rowId, relationNames);
    }

    /**
     * Finds a {@link List} of entities from database.
     *
     * @param <E> the element type
     * @param entityClass the entity class
     * @param rowIds the row ids
     * @return the list
     */
    public <E> List<E> findAll(Class<E> entityClass, Object... rowIds)
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(entityClass);
        List<E> results = new ArrayList<E>();
        results = find(entityClass, entityMetadata.getRelationNames(), entityMetadata.getRelationNames() != null
                && !entityMetadata.getRelationNames().isEmpty(), entityMetadata, rowIds);
        return results.isEmpty() ? null : results;
    }

    /**
     * Find.
     * 
     * @param clazz
     *            the clazz
     * @param metadata
     *            the metadata
     * @param rowId
     *            the row id
     * @param relationNames
     *            the relation names
     * @return the object
     */
    private final Object find(Class<?> clazz, EntityMetadata metadata, Object rowId, List<String> relationNames)
    {

        List<Object> result = null;
        try
        {
            MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                    metadata.getPersistenceUnit());
            if (metaModel.isEmbeddable(metadata.getIdAttribute().getBindableJavaType()))
            {
                CQLTranslator translator = new CQLTranslator();
                String select_Query = translator.SELECTALL_QUERY;
                select_Query = StringUtils.replace(select_Query, CQLTranslator.COLUMN_FAMILY,
                        translator.ensureCase(new StringBuilder(), metadata.getTableName()).toString());
                StringBuilder builder = new StringBuilder(select_Query);
                EmbeddableType compoundKey = metaModel.embeddable(metadata.getIdAttribute().getBindableJavaType());
                onWhereClause(metadata, rowId, translator, builder, compoundKey);
                result = executeQuery(builder.toString(), metadata.getEntityClazz(), relationNames);
            }
            else
            {
                result = (List<Object>) find(clazz, relationNames, relationNames != null, metadata, rowId);
            }
        }
        catch (Exception e)
        {
            log.error("Error on retrieval" + e.getMessage());
            throw new PersistenceException(e);
        }

        return result != null & !result.isEmpty() ? result.get(0) : null;
    }

    /**
     * Find.
     *
     * @param <E> the element type
     * @param entityClass the entity class
     * @param superColumnMap the super column map
     * @param dataHandler the data handler
     * @return the list
     */
    public <E> List<E> find(Class<E> entityClass, Map<String, String> superColumnMap, CassandraDataHandler dataHandler)
    {
        List<E> entities = null;
        try
        {
            EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(getPersistenceUnit(), entityClass);
            entities = new ArrayList<E>();
            for (String superColumnName : superColumnMap.keySet())
            {
                String entityId = superColumnMap.get(superColumnName);
                List<SuperColumn> superColumnList = loadSuperColumns(entityMetadata.getSchema(),
                        entityMetadata.getTableName(), entityId,
                        new String[] { superColumnName.substring(0, superColumnName.indexOf("|")) });
                E e = (E) dataHandler.fromThriftRow(entityMetadata.getEntityClazz(), entityMetadata,
                        new DataRow<SuperColumn>(entityId, entityMetadata.getTableName(), superColumnList));
                if (e != null)
                {
                    entities.add(e);
                }
            }
        }
        catch (Exception e)
        {
            throw new KunderaException(e);
        }
        return entities;
    }

    /**
     * Executes Query.
     *
     * @param cqlQuery the cql query
     * @param clazz the clazz
     * @param relationalField the relational field
     * @param dataHandler the data handler
     * @return the list
     */
    public List executeQuery(String cqlQuery, Class clazz, List<String> relationalField,
            CassandraDataHandler dataHandler)
    {

        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(clazz);
        CqlResult result = null;
        List returnedEntities = null;
        IPooledConnection conn = null;
        String persistenceUnit = entityMetadata.getPersistenceUnit();
        String schema = entityMetadata.getSchema();
        try
        {
            conn = PelopsUtils.getCassandraConnection(persistenceUnit);
            Cassandra.Client cassandra_client = conn.getAPI();
            cassandra_client.set_keyspace(schema);
            cassandra_client.set_cql_version(getCqlVersion());

            MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                    entityMetadata.getPersistenceUnit());

            result = cassandra_client.execute_cql_query(ByteBufferUtil.bytes(cqlQuery),
                    org.apache.cassandra.thrift.Compression.NONE);
            if (result != null && (result.getRows() != null || result.getRowsSize() > 0))
            {
                returnedEntities = new ArrayList<Object>(result.getRowsSize());
                Iterator<CqlRow> iter = result.getRowsIterator();
                while (iter.hasNext())
                {
                    CqlRow row = iter.next();
                    Object rowKey = null;

                    if (!metaModel.isEmbeddable(entityMetadata.getIdAttribute().getBindableJavaType()))
                    {
                        rowKey = PropertyAccessorHelper.getObject(
                                entityMetadata.getIdAttribute().getBindableJavaType(), row.getKey());
                    }
                    // String rowKeys = Bytes.toUTF8(row.getKey());
                    ThriftRow thriftRow = null;
                    if (entityMetadata.isCounterColumnType())
                    {
                        log.info("Native query is not permitted on counter column returning null ");
                        return null;
                    }
                    else
                    {
                        thriftRow = new ThriftRow(rowKey, entityMetadata.getTableName(), row.getColumns(),
                                new ArrayList<SuperColumn>(0), new ArrayList<CounterColumn>(0),
                                new ArrayList<CounterSuperColumn>(0));
                    }

                    Object entity = dataHandler.populateEntity(thriftRow, entityMetadata, relationalField,
                            relationalField != null && !relationalField.isEmpty());

                    if (entity != null)
                    {
                        returnedEntities.add(entity);
                    }
                }
            }
        }
        catch (InvalidRequestException e)
        {
            log.error("Error while executing native CQL query Caused by:" + e.getLocalizedMessage());
            throw new PersistenceException(e);
        }
        catch (UnavailableException e)
        {
            log.error("Error while executing native CQL query Caused by:" + e.getLocalizedMessage());
            throw new PersistenceException(e);
        }
        catch (TimedOutException e)
        {
            log.error("Error while executing native CQL query Caused by:" + e.getLocalizedMessage());
            throw new PersistenceException(e);
        }
        catch (SchemaDisagreementException e)
        {
            log.error("Error while executing native CQL query Caused by:" + e.getLocalizedMessage());
            throw new PersistenceException(e);
        }
        catch (TException e)
        {
            log.error("Error while executing native CQL query Caused by:" + e.getLocalizedMessage());
            throw new PersistenceException(e);
        }
        catch (Exception e)
        {
            log.error("Error while executing native CQL query Caused by:" + e.getLocalizedMessage());
            e.printStackTrace();
            throw new PersistenceException(e);
        }
        finally
        {
            PelopsUtils.releaseConnection(conn);
        }
        return returnedEntities;

    }

    /**
     * Populate data.
     *
     * @param m the m
     * @param qResults the q results
     * @param entities the entities
     * @param isRelational the is relational
     * @param relationNames the relation names
     * @param dataHandler the data handler
     */
    protected void populateData(EntityMetadata m, Map<Bytes, List<Column>> qResults, List<Object> entities,
            boolean isRelational, List<String> relationNames, CassandraDataHandler dataHandler)
    {
        if (m.getType().isSuperColumnFamilyMetadata())
        {
            Set<Bytes> primaryKeys = qResults.keySet();

            if (primaryKeys != null && !primaryKeys.isEmpty())
            {
                Object[] rowIds = new Object[primaryKeys.size()];
                int i = 0;
                for (Bytes b : primaryKeys)
                {
                    rowIds[i] = PropertyAccessorHelper.getObject(b, (Field) m.getIdAttribute().getJavaMember());
                    i++;
                }
                entities.addAll(findAll(m.getEntityClazz(), rowIds));
            }

        }
        else
        {
            Iterator<Bytes> rowIter = qResults.keySet().iterator();
            while (rowIter.hasNext())
            {
                Bytes rowKey = rowIter.next();
                List<Column> columns = qResults.get(rowKey);
                try
                {
                    Object id = PropertyAccessorHelper
                            .getObject(m.getIdAttribute().getJavaType(), rowKey.toByteArray());

                    Object e = dataHandler.populateEntity(new ThriftRow(id, m.getTableName(), columns,
                            new ArrayList<SuperColumn>(0), new ArrayList<CounterColumn>(0),
                            new ArrayList<CounterSuperColumn>(0)), m, relationNames, isRelational);

                    // Object e = dataHandler
                    // .fromColumnThriftRow(m.getEntityClazz(), m, new
                    // ThriftRow(id, m.getTableName(), columns,
                    // null, null, null), relationNames, isRelational);
                    if (e != null)
                    {
                        entities.add(e);
                    }
                }
                catch (IllegalStateException e)
                {
                    throw new KunderaException(e);
                }
                catch (Exception e)
                {
                    throw new KunderaException(e);
                }
            }
        }

    }

    /**
     * Populate entities from key slices.
     *
     * @param m the m
     * @param isWrapReq the is wrap req
     * @param relations the relations
     * @param keys the keys
     * @param dataHandler the data handler
     * @return the list
     * @throws Exception the exception
     */
    protected List populateEntitiesFromKeySlices(EntityMetadata m, boolean isWrapReq, List<String> relations,
            List<KeySlice> keys, CassandraDataHandler dataHandler) throws Exception
    {
        List results;
        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                m.getPersistenceUnit());

        // List<String> superColumnNames = m.getEmbeddedColumnFieldNames();
        Set<String> superColumnAttribs = metaModel.getEmbeddables(m.getEntityClazz()).keySet();
        results = new ArrayList(keys.size());

        ThriftDataResultHelper dataGenerator = new ThriftDataResultHelper();
        for (KeySlice key : keys)
        {
            List<ColumnOrSuperColumn> columns = key.getColumns();

            byte[] rowKey = key.getKey();

            Object id = PropertyAccessorHelper.getObject(m.getIdAttribute().getJavaType(), rowKey);
            Map<ByteBuffer, List<ColumnOrSuperColumn>> data = new HashMap<ByteBuffer, List<ColumnOrSuperColumn>>(1);
            data.put(ByteBuffer.wrap(rowKey), columns);
            ThriftRow tr = new ThriftRow();
            tr.setId(id);
            tr.setColumnFamilyName(m.getTableName());
            tr = dataGenerator.translateToThriftRow(data, m.isCounterColumnType(), m.getType(), tr);
            results.add(dataHandler.populateEntity(tr, m, relations, isWrapReq));
        }
        return results;
    }

    protected void onpersistOverCompositeKey(EntityMetadata entityMetadata, Object entity,
            Cassandra.Client cassandra_client) throws InvalidRequestException, TException, UnavailableException,
            TimedOutException, SchemaDisagreementException, UnsupportedEncodingException
    {
        cassandra_client.set_cql_version(getCqlVersion());
        CQLTranslator translator = new CQLTranslator();
        String insert_Query = translator.INSERT_QUERY;
        insert_Query = StringUtils.replace(insert_Query, CQLTranslator.COLUMN_FAMILY,
                translator.ensureCase(new StringBuilder(), entityMetadata.getTableName()).toString());
        HashMap<TranslationType, String> translation = translator.prepareColumnOrColumnValues(entity,
                entityMetadata, TranslationType.ALL);
        insert_Query = StringUtils.replace(insert_Query, CQLTranslator.COLUMN_VALUES,
                translation.get(TranslationType.VALUE));
        insert_Query = StringUtils.replace(insert_Query, CQLTranslator.COLUMNS,
                translation.get(TranslationType.COLUMN));
        cassandra_client.execute_cql_query(ByteBuffer.wrap(insert_Query.getBytes(Constants.CHARSET_UTF8)), Compression.NONE);
    }

    /**
     * Gets the cql version.
     *
     * @return the cqlVersion
     */
    protected String getCqlVersion()
    {
        return cqlVersion;
    }

    /**
     * Sets the cql version.
     *
     * @param cqlVersion the cqlVersion to set
     */
    public void setCqlVersion(String cqlVersion)
    {
        this.cqlVersion = cqlVersion;
    }

    /**
     * Sets the consistency level.
     *
     * @param cLevel the new consistency level
     */
    public void setConsistencyLevel(ConsistencyLevel cLevel)
    {
        if (cLevel != null)
        {
            this.consistencyLevel = cLevel;
        }
        else
        {
            log.warn("Please provide resonable consistency Level");
        }
    }

    /**
     * Close.
     */
    public void close()
    {
        nodes.clear();
        nodes = null;
        closed = true;
    }

    /**
     * Checks if is open.
     * 
     * @return true, if is open
     */
    protected final boolean isOpen()
    {
        return !closed;
    }

    /**
     * Gets the consistency level.
     *
     * @return the consistency level
     */
    protected ConsistencyLevel getConsistencyLevel()
    {
        return consistencyLevel;
    }

    /**
     * On delete query.
     *
     * @param metadata the metadata
     * @param metaModel the meta model
     * @param compoundKeyObject the compound key object
     * @param compoundKey the compound key
     */
    protected void onDeleteQuery(EntityMetadata metadata, MetamodelImpl metaModel, Object compoundKeyObject,
            ManagedType compoundKey)
    {
        CQLTranslator translator = new CQLTranslator();
        String deleteQuery = CQLTranslator.DELETE_QUERY;

        deleteQuery = StringUtils.replace(deleteQuery, CQLTranslator.COLUMN_FAMILY,
                translator.ensureCase(new StringBuilder(), metadata.getTableName()).toString());

        StringBuilder deleteQueryBuilder = new StringBuilder(deleteQuery);

        onWhereClause(metadata, compoundKeyObject, translator, deleteQueryBuilder, compoundKey);

        executeQuery(deleteQueryBuilder.toString(), metadata.getEntityClazz(), null);
    }

    /**
     * On where clause.
     *
     * @param metadata the metadata
     * @param compoundKeyObject the compound key object
     * @param translator the translator
     * @param queryBuilder the query builder
     * @param compoundKey the compound key
     */
    private void onWhereClause(EntityMetadata metadata, Object compoundKeyObject, CQLTranslator translator,
            StringBuilder queryBuilder, ManagedType compoundKey)
    {
        Field[] fields = metadata.getIdAttribute().getBindableJavaType().getDeclaredFields();
        // EmbeddableType compoundKey =
        // metaModel.embeddable(metadata.getIdAttribute().getBindableJavaType());

        queryBuilder.append(CQLTranslator.ADD_WHERE_CLAUSE);

        for (Field field : fields)
        {
            Attribute attribute = compoundKey.getAttribute(field.getName());
            String columnName = ((AbstractAttribute) attribute).getJPAColumnName();
            // System.out.println("DD");
            translator.buildWhereClause(queryBuilder, columnName, field, compoundKeyObject);
        }

        // strip last "AND" clause.
        queryBuilder.delete(queryBuilder.lastIndexOf(CQLTranslator.AND_CLAUSE), queryBuilder.length());
    }

    /**
     * Find.
     *
     * @param entityClass the entity class
     * @param relationNames the relation names
     * @param isWrapReq the is wrap req
     * @param metadata the metadata
     * @param rowIds the row ids
     * @return the list
     */
    public abstract List find(Class entityClass, List<String> relationNames, boolean isWrapReq,
            EntityMetadata metadata, Object... rowIds);

    /**
     * Load super columns.
     *
     * @param keyspace the keyspace
     * @param columnFamily the column family
     * @param rowId the row id
     * @param superColumnNames the super column names
     * @return the list
     */
    protected abstract List<SuperColumn> loadSuperColumns(String keyspace, String columnFamily, String rowId,
            String... superColumnNames);

    /**
     * Query related methods.
     *
     * @param cqlQuery the cql query
     * @param clazz the clazz
     * @param relationalField the relational field
     * @return the list
     */
    public abstract List executeQuery(String cqlQuery, Class clazz, List<String> relationalField);

    /**
     * Find.
     *
     * @param ixClause the ix clause
     * @param m the m
     * @param isRelation the is relation
     * @param relations the relations
     * @param maxResult the max result
     * @param columns the columns
     * @return the list
     */
    public abstract List find(List<IndexClause> ixClause, EntityMetadata m, boolean isRelation, List<String> relations,
            int maxResult, List<String> columns);

    /**
     * Find by range.
     *
     * @param muinVal the muin val
     * @param maxVal the max val
     * @param m the m
     * @param isWrapReq the is wrap req
     * @param relations the relations
     * @param columns the columns
     * @param conditions the conditions
     * @return the list
     * @throws Exception the exception
     */
    public abstract List findByRange(byte[] muinVal, byte[] maxVal, EntityMetadata m, boolean isWrapReq,
            List<String> relations, List<String> columns, List<IndexExpression> conditions) throws Exception;

    /**
     * Search in inverted index.
     *
     * @param columnFamilyName the column family name
     * @param m the m
     * @param indexClauseMap the index clause map
     * @return the list
     */
    public abstract List<SearchResult> searchInInvertedIndex(String columnFamilyName, EntityMetadata m,
            Map<Boolean, List<IndexClause>> indexClauseMap);

    /**
     * Find.
     *
     * @param m the m
     * @param relationNames the relation names
     * @param conditions the conditions
     * @param maxResult the max result
     * @param columns the columns
     * @return the list
     */
    public abstract List<EnhanceEntity> find(EntityMetadata m, List<String> relationNames,
            List<IndexClause> conditions, int maxResult, List<String> columns);

    /**
     * Gets the data handler.
     *
     * @return the data handler
     */
    protected abstract CassandraDataHandler getDataHandler();

    /**
     * Delete.
     *
     * @param entity the entity
     * @param pKey the key
     */
    protected abstract void delete(Object entity, Object pKey);

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.persistence.api.Batcher#addBatch(com.impetus.kundera
     * .graph.Node)
     */
    /**
     * Adds the batch.
     *
     * @param node the node
     */
    public void addBatch(Node node)
    {

        if (node != null)
        {
            nodes.add(node);
        }

        onBatchLimit();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.persistence.api.Batcher#getBatchSize()
     */
    /**
     * Gets the batch size.
     *
     * @return the batch size
     */
    public int getBatchSize()
    {
        return batchSize;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.persistence.api.Batcher#executeBatch()
     */
    /**
     * Execute batch.
     *
     * @return the int
     */
    public int executeBatch()
    {
        String persistenceUnit = null;
        IPooledConnection conn = null;

        Map<ByteBuffer, Map<String, List<Mutation>>> mutationMap = new HashMap<ByteBuffer, Map<String, List<Mutation>>>();

        try
        {
            for (Node node : nodes)
            {
                if (node.isDirty())
                {
                    Object entity = node.getData();
                    Object id = node.getEntityId();
                    EntityMetadata metadata = KunderaMetadataManager.getEntityMetadata(node.getDataClass());
                    persistenceUnit = metadata.getPersistenceUnit();
                    isUpdate = node.isUpdate();

                    // delete can not be executed in batch
                    if (node.isInState(RemovedState.class))
                    {
                        delete(entity, id);
                    }
                    else
                    {
                        List<RelationHolder> relationHolders = getRelationHolders(node);
                        mutationMap = prepareMutation(metadata, entity, id, relationHolders, mutationMap);
                        indexNode(node, metadata);
                    }
                }
            }

            // Write Mutation map to database

            if (!mutationMap.isEmpty())
            {
                conn = PelopsUtils.getCassandraConnection(persistenceUnit);
                PersistenceUnitMetadata puMetadata = KunderaMetadata.INSTANCE.getApplicationMetadata()
                        .getPersistenceUnitMetadata(persistenceUnit);
                Cassandra.Client cassandra_client = conn.getAPI();
                cassandra_client.set_keyspace(puMetadata.getProperty(PersistenceProperties.KUNDERA_KEYSPACE));

                cassandra_client.batch_mutate(mutationMap, consistencyLevel);
            }
        }
        catch (InvalidRequestException e)
        {
            log.error("Error while persisting record. Details: " + e.getMessage());
            throw new KunderaException(e);
        }
        catch (TException e)
        {
            log.error("Error while persisting record. Details: " + e.getMessage());
            throw new KunderaException(e);
        }
        catch (UnavailableException e)
        {
            log.error("Error while persisting record. Details: " + e.getMessage());
            throw new KunderaException(e);
        }
        catch (TimedOutException e)
        {
            log.error("Error while persisting record. Details: " + e.getMessage());
            throw new KunderaException(e);
        }
        finally
        {
            PelopsUtils.releaseConnection(conn);
        }

        return mutationMap.size();
    }

    /**
     * Prepare mutation.
     *
     * @param entityMetadata the entity metadata
     * @param entity the entity
     * @param id the id
     * @param relationHolders the relation holders
     * @param mutationMap the mutation map
     * @return the map
     */
    protected Map<ByteBuffer, Map<String, List<Mutation>>> prepareMutation(EntityMetadata entityMetadata,
            Object entity, Object id, List<RelationHolder> relationHolders,
            Map<ByteBuffer, Map<String, List<Mutation>>> mutationMap)
    {

        if (!isOpen())
        {
            throw new PersistenceException("ThriftClient is closed.");
        }

        // check for counter column
        if (isUpdate && entityMetadata.isCounterColumnType())
        {
            throw new UnsupportedOperationException("Merge is not permitted on counter column! ");
        }

        ThriftRow tf = null;
        try
        {
            String columnFamily = entityMetadata.getTableName();
            tf = getDataHandler().toThriftRow(entity, id, entityMetadata, columnFamily);
        }
        catch (Exception e)
        {
            log.error("Error during persisting record, Details:" + e.getMessage());
            throw new KunderaException(e);
        }

        addRelationsToThriftRow(entityMetadata, tf, relationHolders);

        String columnFamily = entityMetadata.getTableName();
        // Create Insertion List
        List<Mutation> insertion_list = new ArrayList<Mutation>();

        /*********** Handling for counter column family ************/

        if (entityMetadata.isCounterColumnType())
        {
            List<CounterColumn> thriftCounterColumns = tf.getCounterColumns();
            List<CounterSuperColumn> thriftCounterSuperColumns = tf.getCounterSuperColumns();

            if (thriftCounterColumns != null && !thriftCounterColumns.isEmpty())
            {
                for (CounterColumn column : thriftCounterColumns)
                {
                    Mutation mut = new Mutation();
                    mut.setColumn_or_supercolumn(new ColumnOrSuperColumn().setCounter_column(column));
                    insertion_list.add(mut);
                }
            }

            if (thriftCounterSuperColumns != null && !thriftCounterSuperColumns.isEmpty())
            {
                for (CounterSuperColumn sc : thriftCounterSuperColumns)
                {
                    Mutation mut = new Mutation();
                    mut.setColumn_or_supercolumn(new ColumnOrSuperColumn().setCounter_super_column(sc));
                    insertion_list.add(mut);
                }
            }
        }

        else
        /********* Handling for column family and super column family *********/
        {
            List<Column> thriftColumns = tf.getColumns();
            List<SuperColumn> thriftSuperColumns = tf.getSuperColumns();

            // Populate Insertion list for columns
            if (thriftColumns != null && !thriftColumns.isEmpty())
            {
                for (Column column : thriftColumns)
                {
                    Mutation mut = new Mutation();
                    mut.setColumn_or_supercolumn(new ColumnOrSuperColumn().setColumn(column));
                    insertion_list.add(mut);
                }
            }

            // Populate Insertion list for super columns
            if (thriftSuperColumns != null && !thriftSuperColumns.isEmpty())
            {
                for (SuperColumn superColumn : thriftSuperColumns)
                {
                    Mutation mut = new Mutation();
                    mut.setColumn_or_supercolumn(new ColumnOrSuperColumn().setSuper_column(superColumn));
                    insertion_list.add(mut);
                }
            }
        }

        // Create Mutation Map
        Map<String, List<Mutation>> columnFamilyValues = new HashMap<String, List<Mutation>>();
        columnFamilyValues.put(columnFamily, insertion_list);
        Bytes b = CassandraUtilities.toBytes(tf.getId(), tf.getId().getClass());
        mutationMap.put(b.getBytes(), columnFamilyValues);

        return mutationMap;
    }

    /**
     * Check on batch limit.
     */
    private void onBatchLimit()
    {
        if (batchSize > 0 && batchSize == nodes.size())
        {
            executeBatch();
            nodes.clear();
        }
    }

    /* (non-Javadoc)
     * @see com.impetus.kundera.client.ClientPropertiesSetter#populateClientProperties(com.impetus.kundera.client.Client, java.util.Map)
     */
    @Override
    public void populateClientProperties(Client client, Map<String, Object> properties)
    {
        new CassandraClientProperties().populateClientProperties(client, properties);
    }

    /**
     * Returns raw cassandra client from thrift connection pool.
     *
     * @param persistenceUnit persistence unit.
     * @param schema schema or keyspace.
     * @return raw cassandra client.
     */
    protected Cassandra.Client getRawClient(final String persistenceUnit, final String schema)
    {
        IPooledConnection connection = PelopsUtils.getCassandraConnection(persistenceUnit);
        Cassandra.Client client = connection.getAPI();
        try
        {
            client.set_keyspace(schema);
            client.set_cql_version(getCqlVersion());
        }
        catch (InvalidRequestException irex)
        {
            log.error("Error during borrowing a connection, Details:" + irex.getMessage());
            throw new KunderaException(irex);
        }
        catch (TException tex)
        {
            log.error("Error during borrowing a connection, Details:" + tex.getMessage());
            throw new KunderaException(tex);
        }

        return client;

    }
}
