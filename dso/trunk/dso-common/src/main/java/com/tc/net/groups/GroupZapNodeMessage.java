/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.net.groups;

import com.tc.io.TCByteBufferInput;
import com.tc.io.TCByteBufferOutput;
import com.tc.util.Assert;

import java.io.IOException;

public class GroupZapNodeMessage extends AbstractGroupMessage {

  public static final int ZAP_NODE_REQUEST = 0;

  private int             zapNodeType;
  private String          reason;
  private long[]          weights;

  // To make serialization happy
  public GroupZapNodeMessage() {
    super(-1);
  }

  public GroupZapNodeMessage(int type, int zapNodeType, String reason, long[] weights) {
    super(type);
    this.reason = reason;
    this.zapNodeType = zapNodeType;
    this.weights = weights;
  }

  @Override
  protected void basicDeserializeFrom(TCByteBufferInput in) throws IOException {
    Assert.assertEquals(ZAP_NODE_REQUEST, getType());
    zapNodeType = in.readInt();
    reason = in.readString();
    weights = new long[in.readInt()];
    for (int i = 0; i < weights.length; i++) {
      weights[i] = in.readLong();
    }
  }

  @Override
  protected void basicSerializeTo(TCByteBufferOutput out) {
    Assert.assertEquals(ZAP_NODE_REQUEST, getType());
    out.writeInt(zapNodeType);
    out.writeString(reason);
    out.writeInt(weights.length);
    for (long weight : weights) {
      out.writeLong(weight);
    }
  }

  @Override
  public String toString() {
    return "GroupZapNodeMessage [ " + zapNodeType + " , " + reason + " , weights = " + toString(weights) + " ]";
  }

  private String toString(long[] l) {
    if (l == null) return "null";
    if (l.length == 0) return "empty";
    StringBuffer sb = new StringBuffer();
    for (long element : l) {
      sb.append(String.valueOf(element)).append(",");
    }
    sb.setLength(sb.length() - 1);
    return sb.toString();
  }

  public String getReason() {
    return reason;
  }

  public int getZapNodeType() {
    return zapNodeType;
  }

  public long[] getWeights() {
    return weights;
  }
}
