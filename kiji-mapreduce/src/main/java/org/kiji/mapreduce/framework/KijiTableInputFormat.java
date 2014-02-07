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

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.SerializationUtils;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.mapreduce.TableSplit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.annotations.ApiStability;
import org.kiji.mapreduce.impl.KijiTableSplit;
import org.kiji.schema.EntityId;
import org.kiji.schema.HBaseEntityId;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiRegion;
import org.kiji.schema.KijiRowData;
import org.kiji.schema.KijiRowScanner;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;
import org.kiji.schema.KijiTableReader.KijiScannerOptions;
import org.kiji.schema.KijiURI;
import org.kiji.schema.filter.KijiRowFilter;
import org.kiji.schema.hbase.HBaseScanOptions;
import org.kiji.schema.impl.hbase.HBaseKijiRowData;
import org.kiji.schema.impl.hbase.HBaseKijiTable;
import org.kiji.schema.layout.ColumnReaderSpec;
import org.kiji.schema.util.ResourceUtils;

/** InputFormat for Hadoop MapReduce jobs reading from a Kiji table. */
@ApiAudience.Framework
@ApiStability.Evolving
public final class KijiTableInputFormat
    extends InputFormat<EntityId, KijiRowData>
    implements Configurable {

  /**
   * Number of bytes from the row-key to include when reporting progress.
   * Use 32 bits precision, ie. 4 billion row keys granularity.
   */
  private static final int PROGRESS_PRECISION_NBYTES = 4;

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
    return new KijiTableRecordReader(mConf);
  }

  /**
   * Reports the HBase table name for the specified Kiji table.
   *
   * @param table Kiji table to report the HBase table name of.
   * @return the HBase table name for the specified Kiji table.
   * @throws IOException on I/O error.
   */
  private static byte[] getHBaseTableName(KijiTable table) throws IOException {
    final HBaseKijiTable htable = HBaseKijiTable.downcast(table);
    final HTableInterface hti = htable.openHTableConnection();
    try {
      return hti.getTableName();
    } finally {
      hti.close();
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<InputSplit> getSplits(JobContext context) throws IOException {
    final Configuration conf = context.getConfiguration();
    final KijiURI inputTableURI =
        KijiURI.newBuilder(conf.get(KijiConfKeys.KIJI_INPUT_TABLE_URI)).build();
    final Kiji kiji = Kiji.Factory.open(inputTableURI, conf);
    try {
      final KijiTable table = kiji.openTable(inputTableURI.getTable());
      try {
        final byte[] htableName = getHBaseTableName(table);
        final List<InputSplit> splits = Lists.newArrayList();
        byte[] scanStartKey = HConstants.EMPTY_START_ROW;
        if (null != conf.get(KijiConfKeys.KIJI_START_ROW_KEY)) {
          scanStartKey = Base64.decodeBase64(conf.get(KijiConfKeys.KIJI_START_ROW_KEY));
        }
        byte[] scanLimitKey = HConstants.EMPTY_END_ROW;
        if (null != conf.get(KijiConfKeys.KIJI_LIMIT_ROW_KEY)) {
          scanLimitKey = Base64.decodeBase64(conf.get(KijiConfKeys.KIJI_LIMIT_ROW_KEY));
        }

        for (KijiRegion region : table.getRegions()) {
          final byte[] regionStartKey = region.getStartKey();
          final byte[] regionEndKey = region.getEndKey();
          // Determine if the scan start and limit key fall into the region.
          // Logic was copied from o.a.h.h.m.TableInputFormatBase
          if ((scanStartKey.length == 0 || regionEndKey.length == 0
               || Bytes.compareTo(scanStartKey, regionEndKey) < 0)
             && (scanLimitKey.length == 0
               || Bytes.compareTo(scanLimitKey, regionStartKey) > 0)) {
            byte[] splitStartKey = (scanStartKey.length == 0
              || Bytes.compareTo(regionStartKey, scanStartKey) >= 0)
              ? regionStartKey : scanStartKey;
            byte[] splitEndKey = ((scanLimitKey.length == 0
              || Bytes.compareTo(regionEndKey, scanLimitKey) <= 0)
              && regionEndKey.length > 0)
              ? regionEndKey : scanLimitKey;

            // TODO(KIJIMR-65): For now pick the first available location (ie. region server),
            // if any.
            final String location =
              region.getLocations().isEmpty() ? null : region.getLocations().iterator().next();
            final TableSplit tableSplit =
              new TableSplit(htableName, splitStartKey, splitEndKey, location);
            splits.add(new KijiTableSplit(tableSplit));
          }
        }
        return splits;

      } finally {
        ResourceUtils.releaseOrLog(table);
      }
    } finally {
      ResourceUtils.releaseOrLog(kiji);
    }
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
   * @throws IOException on I/O error.
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
    job.setInputFormatClass(KijiTableInputFormat.class);
    final String serializedRequest =
        Base64.encodeBase64String(SerializationUtils.serialize(dataRequest));
    conf.set(KijiConfKeys.KIJI_INPUT_DATA_REQUEST, serializedRequest);
    conf.set(KijiConfKeys.KIJI_INPUT_TABLE_URI, tableURI.toString());
    if (null != startRow) {
      conf.set(KijiConfKeys.KIJI_START_ROW_KEY,
          Base64.encodeBase64String(startRow.getHBaseRowKey()));
    }
    if (null != endRow) {
      conf.set(KijiConfKeys.KIJI_LIMIT_ROW_KEY,
          Base64.encodeBase64String(endRow.getHBaseRowKey()));
    }
    if (null != filter) {
      conf.set(KijiConfKeys.KIJI_ROW_FILTER, filter.toJson().toString());
    }
  }

  /** Hadoop record reader for Kiji table rows. */
  public static final class KijiTableRecordReader
      extends RecordReader<EntityId, KijiRowData> {

    private static final Logger LOG = LoggerFactory.getLogger(KijiTableRecordReader.class);

    /** Data request. */
    private final KijiDataRequest mDataRequest;

    private Kiji mKiji = null;
    private KijiTable mTable = null;
    private KijiTableReader mReader = null;
    private KijiRowScanner mScanner = null;
    private Iterator<KijiRowData> mIterator = null;
    private KijiTableSplit mSplit = null;
    private HBaseKijiRowData mCurrentRow = null;

    private long mStartPos;
    private long mStopPos;

    /**
     * Creates a new RecordReader for this input format.
     *
     * Perform the actual reads from Kiji.
     *
     * @param conf Configuration for the target Kiji.
     */
    private KijiTableRecordReader(Configuration conf) {
      // Get data request from the job configuration.
      final String dataRequestB64 = conf.get(KijiConfKeys.KIJI_INPUT_DATA_REQUEST);
      Preconditions.checkNotNull(dataRequestB64, "Missing data request in job configuration.");
      final byte[] dataRequestBytes = Base64.decodeBase64(Bytes.toBytes(dataRequestB64));
      mDataRequest = (KijiDataRequest) SerializationUtils.deserialize(dataRequestBytes);
    }

    /** {@inheritDoc} */
    @Override
    public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
      Preconditions.checkArgument(split instanceof KijiTableSplit,
          "InputSplit is not a KijiTableSplit: %s", split);
      mSplit = (KijiTableSplit) split;

      final Configuration conf = context.getConfiguration();
      final KijiURI inputURI =
          KijiURI.newBuilder(conf.get(KijiConfKeys.KIJI_INPUT_TABLE_URI)).build();

      // When using Kiji tables as an input to MapReduce jobs, turn off block caching.
      final HBaseScanOptions hBaseScanOptions = new HBaseScanOptions();
      hBaseScanOptions.setCacheBlocks(false);

      // Extract the ColumnReaderSpecs and build a mapping from column to the appropriate overrides.
      final ImmutableMap.Builder<KijiColumnName, ColumnReaderSpec> overridesBuilder =
          ImmutableMap.builder();
      for (KijiDataRequest.Column column : mDataRequest.getColumns()) {
        if (column.getReaderSpec() != null) {
          overridesBuilder.put(column.getColumnName(), column.getReaderSpec());
        }
      }

      final KijiScannerOptions scannerOptions = new KijiScannerOptions()
          .setStartRow(HBaseEntityId.fromHBaseRowKey(mSplit.getStartRow()))
          .setStopRow(HBaseEntityId.fromHBaseRowKey(mSplit.getEndRow()))
          .setHBaseScanOptions(hBaseScanOptions);
      final String filterJson = conf.get(KijiConfKeys.KIJI_ROW_FILTER);
      if (null != filterJson) {
        final KijiRowFilter filter = KijiRowFilter.toFilter(filterJson);
        scannerOptions.setKijiRowFilter(filter);
      }
      mKiji = Kiji.Factory.open(inputURI, conf);
      mTable = mKiji.openTable(inputURI.getTable());
      mReader = mTable.getReaderFactory().readerBuilder()
          .withColumnReaderSpecOverrides(overridesBuilder.build())
          .buildAndOpen();
      mScanner = mReader.getScanner(mDataRequest, scannerOptions);
      mIterator = mScanner.iterator();
      mCurrentRow = null;

      mStartPos = bytesToPosition(mSplit.getStartRow(), PROGRESS_PRECISION_NBYTES);
      long stopPos = bytesToPosition(mSplit.getEndRow(), PROGRESS_PRECISION_NBYTES);
      mStopPos = (stopPos > 0) ? stopPos : (1L << (PROGRESS_PRECISION_NBYTES * 8));
      LOG.info("Progress reporting: start={} stop={}", mStartPos, mStopPos);
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

    /**
     * Converts a byte array into an integer position in the row-key space.
     *
     * @param bytes Byte array to convert to an approximate position.
     * @param nbytes Number of bytes to use (must be in the range 1..8).
     * @return the approximate position in the row-key space.
     */
    public static long bytesToPosition(final byte[] bytes, final int nbytes) {
      long position = 0;
      if (bytes != null) {
        for (int i = 0; i < nbytes; ++i) {
          final int bvalue = (i < bytes.length) ? (0xff & bytes[i]) : 0;
          position = (position << 8) + bvalue;
        }
      }
      return position;
    }

    /**
     * Computes the start position from the start row key, for progress reporting.
     *
     * @param startRowKey Start row key to compute the position of.
     * @return the start position from the start row key.
     */
    public static long getStartPos(byte[] startRowKey) {
      return bytesToPosition(startRowKey, PROGRESS_PRECISION_NBYTES);
    }

    /**
     * Computes the stop position from the stop row key, for progress reporting.
     *
     * @param stopRowKey Stop row key to compute the position of.
     * @return the stop position from the start row key.
     */
    public static long getStopPos(byte[] stopRowKey) {
      long stopPos = bytesToPosition(stopRowKey, PROGRESS_PRECISION_NBYTES);
      return (stopPos > 0) ? stopPos : (1L << (PROGRESS_PRECISION_NBYTES * 8));
    }

    /**
     * Compute the progress (between 0.0f and 1.0f) for the current row key.
     *
     * @param startPos Computed start position (using getStartPos).
     * @param stopPos Computed stop position (using getStopPos).
     * @param currentRowKey Current row to compute a progress for.
     * @return the progress indicator for the given row, start and stop positions.
     */
    public static float computeProgress(long startPos, long stopPos, byte[] currentRowKey) {
      Preconditions.checkArgument(startPos <= stopPos,
          "Invalid start/stop positions: start=%s stop=%s", startPos, stopPos);
      final long currentPos = bytesToPosition(currentRowKey, PROGRESS_PRECISION_NBYTES);
      Preconditions.checkArgument(startPos <= currentPos,
          "Invalid start/current positions: start=%s current=%s", startPos, currentPos);
      Preconditions.checkArgument(currentPos <= stopPos,
          "Invalid current/stop positions: current=%s stop=%s", currentPos, stopPos);
      if (startPos == stopPos) {
        // Row key range is too small to perceive progress: report 50% completion
        return 0.5f;
      } else {
        return (float) (((double) currentPos - startPos) / (stopPos - startPos));
      }
    }

    /** {@inheritDoc} */
    @Override
    public float getProgress() throws IOException {
      if (mCurrentRow == null) {
        return 0.0f;
      }
      final byte[] currentRowKey = mCurrentRow.getHBaseResult().getRow();
      return computeProgress(mStartPos, mStopPos, currentRowKey);
    }

    /** {@inheritDoc} */
    @Override
    public boolean nextKeyValue() throws IOException {
      if (mIterator.hasNext()) {
        mCurrentRow = (HBaseKijiRowData) mIterator.next();
        return true;
      } else {
        mCurrentRow = null;
        return false;
      }
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
      ResourceUtils.closeOrLog(mScanner);
      ResourceUtils.closeOrLog(mReader);
      ResourceUtils.releaseOrLog(mTable);
      ResourceUtils.releaseOrLog(mKiji);

      mIterator = null;
      mScanner = null;
      mReader = null;
      mTable = null;
      mKiji = null;
      mSplit = null;
      mCurrentRow = null;
    }
  }
}
