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
package org.apache.accumulo.master.replication;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.replication.ReplicationSchema.WorkSection;
import org.apache.accumulo.core.replication.StatusUtil;
import org.apache.accumulo.core.replication.proto.Replication.Status;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.master.Master;
import org.apache.accumulo.server.replication.ReplicationTable;
import org.apache.accumulo.server.zookeeper.DistributedWorkQueue;
import org.apache.accumulo.server.zookeeper.ZooCache;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Read work records from the replication table, create work entries for other nodes to complete.
 * <p>
 * Uses the DistributedWorkQueue to make the work available for any tserver. This approach does not consider the locality of the tabletserver performing the
 * work in relation to the data being replicated (local HDFS blocks).
 */
public class ReplicationWorkAssigner implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(ReplicationWorkAssigner.class);

  private Master master;
  private Connector conn;

  private AccumuloConfiguration conf;
  private DistributedWorkQueue workQueue;
  private Set<String> queuedWork;
  private int maxQueueSize;
  private ZooCache zooCache;

  public ReplicationWorkAssigner(Master master, Connector conn) {
    this.master = master;
    this.conn = conn;
  }

  /*
   * Getters/setters for testing purposes
   */
  protected Connector getConnector() {
    return conn;
  }

  protected void setConnector(Connector conn) {
    this.conn = conn;
  }

  protected AccumuloConfiguration getConf() {
    return conf;
  }

  protected void setConf(AccumuloConfiguration conf) {
    this.conf = conf;
  }

  protected DistributedWorkQueue getWorkQueue() {
    return workQueue;
  }

  protected void setWorkQueue(DistributedWorkQueue workQueue) {
    this.workQueue = workQueue;
  }

  protected Set<String> getQueuedWork() {
    return queuedWork;
  }

  protected void setQueuedWork(Set<String> queuedWork) {
    this.queuedWork = queuedWork;
  }

  protected int getMaxQueueSize() {
    return maxQueueSize;
  }

  protected void setMaxQueueSize(int maxQueueSize) {
    this.maxQueueSize = maxQueueSize;
  }

  protected ZooCache getZooCache() {
    return zooCache;
  }

  protected void setZooCache(ZooCache zooCache) {
    this.zooCache = zooCache;
  }

  /**
   * Initialize the DistributedWorkQueue using the proper ZK location
   * 
   * @param conf
   */
  protected void initializeWorkQueue(AccumuloConfiguration conf) {
    workQueue = new DistributedWorkQueue(ZooUtil.getRoot(master.getInstance()) + Constants.ZREPLICATION, conf);
  }

  /**
   * Initialize the queuedWork set with the work already sent out
   */
  protected void initializeQueuedWork() {
    Preconditions.checkArgument(null == queuedWork, "Expected queuedWork to be null");
    queuedWork = new HashSet<>();
    try {
      queuedWork.addAll(workQueue.getWorkQueued());
    } catch (KeeperException | InterruptedException e) {
      throw new RuntimeException("Error reading existing queued replication work", e);
    }
  }

  @Override
  public void run() {
    while (master.stillMaster()) {
      if (null == conf) {
        conf = master.getConfiguration().getConfiguration();
      }

      // Get the maximum number of entries we want to queue work for (or the default)
      maxQueueSize = conf.getCount(Property.REPLICATION_MAX_WORK_QUEUE);

      if (null == workQueue) {
        initializeWorkQueue(conf);
      }

      if (null == queuedWork) {
        initializeQueuedWork();
      }

      if (null == zooCache) {
        zooCache = new ZooCache();
      }

      // Scan over the work records, adding the work to the queue
      createWork();

      // Keep the state of the work we queued correct
      cleanupFinishedWork();
    }
  }

  /**
   * Scan over the {@link WorkSection} of the replication table adding work for entries that
   * have data to replicate and have not already been queued.
   */
  protected void createWork() {
    // Create a batchscanner over the replication table's work entries
    BatchScanner bs;
    try {
      bs = ReplicationTable.getBatchScanner(conn, 4);
    } catch (TableNotFoundException e) {
      log.warn("Could not find replication table", e);
      return;
    }

    WorkSection.limit(bs);
    bs.setRanges(Collections.singleton(new Range()));
    Text buffer = new Text();
    try {
      for (Entry<Key,Value> entry : bs) {
        // If we're not working off the entries, we need to not shoot ourselves in the foot by continuing
        // to add more work entries
        if (queuedWork.size() > maxQueueSize) {
          log.warn("Queued replication work exceeds configured maximum ({}), sleeping to allow work to occur", maxQueueSize);
          return;
        }

        WorkSection.getFile(entry.getKey(), buffer);
        String file = buffer.toString();
        Status status;
        try {
          status = StatusUtil.fromValue(entry.getValue());
        } catch (InvalidProtocolBufferException e) {
          log.warn("Could not deserialize protobuf from work entry for {}", file, e);
          continue;
        }

        // If there is work to do
        if (StatusUtil.isWorkRequired(status)) {
          Path p = new Path(file);
          String filename = p.getName();
          entry.getKey().getColumnQualifier(buffer);
          String key = filename + "|" + buffer;

          // And, we haven't already queued this file up for work already
          if (!queuedWork.contains(key)) {
            queueWork(key, file);
          }
        }
      }
    } finally {
      if (null != bs) {
        bs.close();
      }
    }
  }

  /**
   * Distribute the work for the given path with filename
   * 
   * @param path
   *          Full path to a file
   * @param filename
   *          Filename
   */
  protected void queueWork(String key, String path) {
    try {
      workQueue.addWork(key, path);
      queuedWork.add(key);
    } catch (KeeperException | InterruptedException e) {
      log.warn("Could not queue work for {}", path, e);
    }
  }

  /**
   * Iterate over the queued work to remove entries that have been completed.
   */
  protected void cleanupFinishedWork() {
    final Iterator<String> work = queuedWork.iterator();
    final String instanceId = master.getInstance().getInstanceID();
    while (work.hasNext()) {
      String filename = work.next();
      // Null equates to the work was finished
      if (null == zooCache.get(ZooUtil.getRoot(instanceId) + Constants.ZREPLICATION + "/" + filename)) {
        work.remove();
      }
    }
  }
}
