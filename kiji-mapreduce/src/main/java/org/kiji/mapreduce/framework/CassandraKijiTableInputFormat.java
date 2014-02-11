/**
 * (c) Copyright 2012 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
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
 */

package org.kiji.mapreduce.framework;

import com.datastax.driver.core.Row;
import com.google.common.base.Preconditions;
import org.apache.cassandra.hadoop2.ConfigHelper;
import org.apache.cassandra.hadoop2.cql3.DataStaxCqlPagingInputFormat;
import org.apache.cassandra.hadoop2.cql3.DataStaxCqlPagingRecordReader;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.SerializationUtils;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.*;
import org.kiji.annotations.ApiAudience;
import org.kiji.annotations.ApiStability;
import org.kiji.schema.*;
import org.kiji.schema.filter.KijiRowFilter;
import org.kiji.schema.impl.cassandra.*;
import org.kiji.schema.util.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * InputFormat for Hadoop MapReduce jobs reading from a Cassandra-backed Kiji table.
 *
 * Wraps around the Cassandra CQL3 Hadoop InputFormat class to convert from raw Cassandra data into
 * Kiji data.
 */
@ApiAudience.Framework
@ApiStability.Evolving
public final class CassandraKijiTableInputFormat
    extends InputFormat<EntityId, KijiRowData>
    implements Configurable {

  /** Configuration of this input format. */
  private Configuration mConf;

  /** {@inheritDoc} */
  @Override
  public void setConf(Configuration conf) {
    mConf = conf;
  }

  /** {@inheritDoc} */
  @Override
  public Configuration getConf() {
    return mConf;
  }

  /** {@inheritDoc} */
  @Override
  public RecordReader<EntityId, KijiRowData> createRecordReader(
      InputSplit split,
      TaskAttemptContext context
  ) throws IOException {
    return new CassandraKijiTableRecordReader(mConf);
  }

  /** Store instance of Cassandra's InputFormat so that we can wrap around its various methods. */
  private DataStaxCqlPagingInputFormat mCqlPagingInputFormat;

  public CassandraKijiTableInputFormat() {
    super();
    mCqlPagingInputFormat = new DataStaxCqlPagingInputFormat();
  }

  /**
   * Configure all of the Cassandra-specific stuff right before calling the Cassandra code for
   * getInputSplits.
   *
   * @param conf Hadoop Configuration for the MR job.
   */
  private void setCassandraSpecificConfiguration(Configuration conf) throws IOException {

    final KijiURI inputTableURI =
        KijiURI.newBuilder(conf.get(KijiConfKeys.KIJI_INPUT_TABLE_URI)).build();
    assert(inputTableURI.isCassandra());

    final Kiji kiji = Kiji.Factory.open(inputTableURI, conf);
    assert(kiji instanceof CassandraKiji);
    final CassandraKiji cassandraKiji = (CassandraKiji) kiji;

    CassandraAdmin admin = cassandraKiji.getCassandraAdmin();

    // TODO: Remove this hardcoding, use KijiManagedCassandraTableName instead.
    String keyspace = "kiji_" + inputTableURI.getInstance();
    String columnFamily = "table_" + inputTableURI.getTable();
    ConfigHelper.setInputColumnFamily(conf, keyspace, columnFamily);

    // TODO: Figure out a better way of getting the partitioner...
    String partitioner = "Murmur3Partitioner";
    ConfigHelper.setInputPartitioner(conf, partitioner);

    CassandraKijiURI cassandraInputTableURI = (CassandraKijiURI) inputTableURI;
    final List<String> cassandraHosts = cassandraInputTableURI.getCassandraNodes();
    final int cassandraPort = cassandraInputTableURI.getCassandraClientPort();
    ConfigHelper.setInputInitialAddress(conf, cassandraHosts.get(0));
    ConfigHelper.setInputNativeTransportPort(conf, String.format("%s", cassandraPort));
    // Leave the RPC port as the default for now...
    //ConfigHelper.setInputRpcPort(conf, "9160");

    // TODO: Possibly include page row size and split size here...

  }

  /** {@inheritDoc} */
  @Override
  public List<InputSplit> getSplits(JobContext context) throws IOException {
    final Configuration conf = context.getConfiguration();
    final KijiURI inputTableURI =
        KijiURI.newBuilder(conf.get(KijiConfKeys.KIJI_INPUT_TABLE_URI)).build();
    assert(inputTableURI.isCassandra());

    final Kiji kiji = Kiji.Factory.open(inputTableURI, conf);

    // Set all of the Cassandra-specific configuration stuff right before calling the Cassandra code!
    setCassandraSpecificConfiguration(conf);

    return mCqlPagingInputFormat.getSplits(context);
  }

  /**
   * Configures a Hadoop M/R job to read from a given table.
   *
   * @param job Job to configure.
   * @param tableURI URI of the table to read from.
   * @param dataRequest Data request.
   * @param startRow Minimum row key to process. May be left null to indicate
   *     that scanning should start at the beginning of the table.
   * @param endRow Maximum row key to process. May be left null to indicate that
   *     scanning should continue to the end of the table.
   * @param filter Filter to use for scanning. May be left null.
   * @throws java.io.IOException on I/O error.
   */
  public static void configureJob(
      Job job,
      KijiURI tableURI,
      KijiDataRequest dataRequest,
      EntityId startRow,
      EntityId endRow,
      KijiRowFilter filter
  ) throws IOException {
    Preconditions.checkNotNull(job, "job must not be null");
    Preconditions.checkNotNull(tableURI, "tableURI must not be null");
    Preconditions.checkNotNull(dataRequest, "dataRequest must not be null");

    final Configuration conf = job.getConfiguration();

    // TODO: Check for jars config:
    // GenericTableMapReduceUtil.initTableInput(hbaseTableName, scan, job);

    // Write all the required values to the job's configuration object.
    job.setInputFormatClass(CassandraKijiTableInputFormat.class);
    final String serializedRequest =
        Base64.encodeBase64String(SerializationUtils.serialize(dataRequest));
    conf.set(KijiConfKeys.KIJI_INPUT_DATA_REQUEST, serializedRequest);
    conf.set(KijiConfKeys.KIJI_INPUT_TABLE_URI, tableURI.toString());

    // TODO: Need to pick a better exception class here...
    if (null != startRow) {
      throw new KijiIOException("Cannot specify a start row for C* KijiMR jobs");
    }
    if (null != endRow) {
      throw new KijiIOException("Cannot specify an end row for C* KijiMR jobs");
    }
    if (null != filter) {
      conf.set(KijiConfKeys.KIJI_ROW_FILTER, filter.toJson().toString());
    }
  }

  /** Hadoop record reader for Kiji table rows. */
  public static final class CassandraKijiTableRecordReader
      extends RecordReader<EntityId, KijiRowData> {

    private static final Logger LOG = LoggerFactory.getLogger(CassandraKijiTableRecordReader.class);

    /** Data request. */
    private final KijiDataRequest mDataRequest;
    private final DataStaxCqlPagingRecordReader mRecordReader;

    private Kiji mKiji = null;
    private KijiTable mTable = null;
    private EntityIdFactory mEntityIdFactory;
    private KijiRowData mCurrentRow = null;

    // We need a reader to transform Cassandra Rows into CassandraKijiDataRows.
    private CassandraKijiTableReader mReader;

    /**
     * Creates a new RecordReader for this input format.
     *
     * Perform the actual reads from Kiji.
     *
     * @param conf Configuration for the target Kiji.
     */
    private CassandraKijiTableRecordReader(Configuration conf) throws IOException {
      // Get data request from the job configuration.
      final String dataRequestB64 = conf.get(KijiConfKeys.KIJI_INPUT_DATA_REQUEST);
      Preconditions.checkNotNull(dataRequestB64, "Missing data request in job configuration.");
      final byte[] dataRequestBytes = Base64.decodeBase64(Bytes.toBytes(dataRequestB64));
      mDataRequest = (KijiDataRequest) SerializationUtils.deserialize(dataRequestBytes);
      mRecordReader = new DataStaxCqlPagingRecordReader();

      final KijiURI inputURI =
          KijiURI.newBuilder(conf.get(KijiConfKeys.KIJI_INPUT_TABLE_URI)).build();
      mKiji = Kiji.Factory.open(inputURI, conf);
      mTable = mKiji.openTable(inputURI.getTable());
      mEntityIdFactory = EntityIdFactory.getFactory(mTable.getLayout());

      // Get a bunch of stuff that we'll need to go from a Row to a CassandraKijiRowData.
      mReader = CassandraKijiTableReader.create((CassandraKijiTable)mTable);

    }

    /** {@inheritDoc} */
    @Override
    public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
      LOG.info("Creating Cassandra table record reader...");
      mRecordReader.initialize(split, context);
    }

    /** {@inheritDoc} */
    @Override
    public EntityId getCurrentKey() throws IOException {
      return mCurrentRow.getEntityId();
    }

    /** {@inheritDoc} */
    @Override
    public KijiRowData getCurrentValue() throws IOException {
      return mCurrentRow;
    }

    /** {@inheritDoc} */
    @Override
    public float getProgress() throws IOException {
      if (mCurrentRow == null) {
        return 0.0f;
      }
      return 1.0f;
    }

    /** {@inheritDoc} */
    @Override
    public boolean nextKeyValue() throws IOException {
      boolean hasNext = mRecordReader.nextKeyValue();
      if (!hasNext) {
        mCurrentRow = null;
        return false;
      }

      // -------------------------------------------------------------------------------------------
      // Keep reading more Rows from the record reader until the entity IDs no longer match, or
      // until the record reader runs out of rows.
      // (i.e., until you get to data for the next Kiji row)
      // TODO: Unit test the heck out of this code!

      HashSet<Row> cassandraRowsForThisKijiRow = new HashSet<Row>();
      EntityId entityIdForThisKijiRow = null;

      LOG.info("Creating a new Kiji row!");

      while (true) {
        Row cassandraRow = mRecordReader.getCurrentValue();
        Preconditions.checkNotNull(cassandraRow);
        assert(null != cassandraRow);

        // Figure out the entity ID from the cassandraRow.
        ByteBuffer currentEntityIdBlob = cassandraRow.getBytes(CassandraKiji.CASSANDRA_KEY_COL);
        byte[] eidBytes = CassandraByteUtil.byteBuffertoBytes(currentEntityIdBlob);
        EntityId eid = mEntityIdFactory.getEntityIdFromHBaseRowKey(eidBytes);

        if (null == entityIdForThisKijiRow || eid == entityIdForThisKijiRow) {
          LOG.info("Adding another C* row to the Kiji row.");
          // If this is a cassandraRow for the current Kiji cassandraRow we are processing, then add this to our list.
          cassandraRowsForThisKijiRow.add(cassandraRow);
          entityIdForThisKijiRow = eid;
        } else {
          // We are done with rows for this entity ID!
          LOG.info("Got a different entity Id, ending this row.");
          break;
        }

        assert(entityIdForThisKijiRow == eid);

        // Break the loop if this is the last value left.
        if (!mRecordReader.nextKeyValue()) {
          LOG.info("No more values left in backing Cassandra / Hadoop record reader, ending this row.");
          break;
        }
      }

      // We should have gotten at least one row for a given entity ID.
      assert(cassandraRowsForThisKijiRow.size() >= 1);
      assert(entityIdForThisKijiRow != null);

      LOG.info(String.format(
          "Updating the current Kiji row after reading %d Cassandra Rows.",
          cassandraRowsForThisKijiRow.size()
      ));
      mCurrentRow = mReader.getRowDataFromCassandraRows(mDataRequest, entityIdForThisKijiRow, cassandraRowsForThisKijiRow);
      return true;
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
      ResourceUtils.closeOrLog(mReader);
      ResourceUtils.releaseOrLog(mTable);
      ResourceUtils.releaseOrLog(mKiji);

      mReader = null;
      mTable = null;
      mKiji = null;
    }
  }
}
