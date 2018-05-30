/**
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
package org.apache.hadoop.dfs;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Date;

import org.apache.hadoop.fs.FsShell;
import org.apache.hadoop.io.UTF8;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableFactories;
import org.apache.hadoop.io.WritableFactory;
import org.apache.hadoop.io.WritableUtils;

/** 
 * DatanodeInfo represents the status of a DataNode.
 * This object is used for communication in the
 * Datanode Protocol and the Client Protocol.
 *
 * @author Mike Cafarella
 * @author Konstantin Shvachko
 */
public class DatanodeInfo extends DatanodeID {
  protected long capacity;
  protected long remaining;
  protected long lastUpdate;
  protected int xceiverCount;

  // administrative states of a datanode
  public enum AdminStates {NORMAL, DECOMMISSION_INPROGRESS, DECOMMISSIONED; }
  protected AdminStates adminState;


  DatanodeInfo() {
    super();
    adminState = null;
  }
  
  DatanodeInfo( DatanodeInfo from ) {
    super( from );
    this.capacity = from.getCapacity();
    this.remaining = from.getRemaining();
    this.lastUpdate = from.getLastUpdate();
    this.xceiverCount = from.getXceiverCount();
    this.adminState = from.adminState;
  }

  DatanodeInfo( DatanodeID nodeID ) {
    super( nodeID );
    this.capacity = 0L;
    this.remaining = 0L;
    this.lastUpdate = 0L;
    this.xceiverCount = 0;
    this.adminState = null;
  }
  
  /** The raw capacity. */
  public long getCapacity() { return capacity; }

  /** The raw free space. */
  public long getRemaining() { return remaining; }

  /** The time when this information was accurate. */
  public long getLastUpdate() { return lastUpdate; }

  /** number of active connections */
  public int getXceiverCount() { return xceiverCount; }

  /** Sets raw capacity. */
  void setCapacity(long capacity) { 
    this.capacity = capacity; 
  }

  /** Sets raw free space. */
  void setRemaining(long remaining) { 
    this.remaining = remaining; 
  }

  /** Sets time when this information was accurate. */
  void setLastUpdate(long lastUpdate) { 
    this.lastUpdate = lastUpdate; 
  }

  /** Sets number of active connections */
  void setXceiverCount(int xceiverCount) { 
    this.xceiverCount = xceiverCount; 
  }

  /** A formatted string for reporting the status of the DataNode. */
  public String getDatanodeReport() {
    StringBuffer buffer = new StringBuffer();
    long c = getCapacity();
    long r = getRemaining();
    long u = c - r;
    buffer.append("Name: "+name+"\n");
    if (isDecommissioned()) {
      buffer.append("State          : Decommissioned\n");
    } else if (isDecommissionInProgress()) {
      buffer.append("State          : Decommission in progress\n");
    } else {
      buffer.append("State          : In Service\n");
    }
    buffer.append("Total raw bytes: "+c+" ("+FsShell.byteDesc(c)+")"+"\n");
    buffer.append("Used raw bytes: "+u+" ("+FsShell.byteDesc(u)+")"+"\n");
    buffer.append("% used: "+FsShell.limitDecimal(((1.0*u)/c)*100,2)+"%"+"\n");
    buffer.append("Last contact: "+new Date(lastUpdate)+"\n");
    return buffer.toString();
  }

  /**
   * Start decommissioning a node.
   * old state.
   */
  void startDecommission() {
    adminState = AdminStates.DECOMMISSION_INPROGRESS;
  }

  /**
   * Stop decommissioning a node.
   * old state.
   */
  void stopDecommission() {
    adminState = null;
  }

  /**
   * Returns true if the node is in the process of being decommissioned
   */
   boolean isDecommissionInProgress() {
     if (adminState == AdminStates.DECOMMISSION_INPROGRESS) {
       return true;
     }
     return false;
   }

  /**
   * Returns true if the node has been decommissioned.
   */
   boolean isDecommissioned() {
     if (adminState == AdminStates.DECOMMISSIONED) {
       return true;
     }
     return false;
   }

  /**
   * Sets the admin state to indicate that decommision is complete.
   */
   void setDecommissioned() {
     assert isDecommissionInProgress();
     adminState = AdminStates.DECOMMISSIONED;
   }

   /**
    * Retrieves the admin state of this node.
    */
    AdminStates getAdminState() {
      if (adminState == null) {
        return AdminStates.NORMAL;
      }
      return adminState;
    }

   /**
    * Sets the admin state of this node.
    */
    void setAdminState(AdminStates newState) {
      if (newState == AdminStates.NORMAL) {
        adminState = null;
      }
      else {
        adminState = newState;
      }
    }

  /////////////////////////////////////////////////
  // Writable
  /////////////////////////////////////////////////
  static {                                      // register a ctor
    WritableFactories.setFactory
      (DatanodeInfo.class,
       new WritableFactory() {
         public Writable newInstance() { return new DatanodeInfo(); }
       });
  }

  /**
   */
  public void write(DataOutput out) throws IOException {
    super.write( out );
    out.writeLong(capacity);
    out.writeLong(remaining);
    out.writeLong(lastUpdate);
    out.writeInt(xceiverCount);
    WritableUtils.writeEnum(out, getAdminState());
  }

  /**
   */
  public void readFields(DataInput in) throws IOException {
    super.readFields(in);
    this.capacity = in.readLong();
    this.remaining = in.readLong();
    this.lastUpdate = in.readLong();
    this.xceiverCount = in.readInt();
    AdminStates newState = (AdminStates) WritableUtils.readEnum(in,
                                         AdminStates.class);
    setAdminState(newState);
  }
}
