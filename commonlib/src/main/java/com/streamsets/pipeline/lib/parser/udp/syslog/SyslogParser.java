/**
 * Code was adapted from:
 * https://github.com/apache/flume/blob/trunk/flume-ng-core/src/main/java/org/apache/flume/source/SyslogParser.java
 *
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
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
package com.streamsets.pipeline.lib.parser.udp.syslog;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.lib.parser.net.syslog.SyslogDecoder;
import com.streamsets.pipeline.lib.parser.net.syslog.SyslogMessage;
import com.streamsets.pipeline.lib.parser.udp.AbstractParser;
import io.netty.buffer.ByteBuf;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.List;

public class SyslogParser extends AbstractParser {
  private final Charset charset;
  private long recordId = 0;

  public SyslogParser(Stage.Context context, Charset charset) {
    super(context);
    this.charset = charset;
  }

  public Record buildRecord(SyslogMessage message) {
    Record record = context.createRecord(generateRecordId(message.getSenderAddress()));
    message.populateRecord(record);
    return record;
  }

  @Override
  public List<Record> parse(
      ByteBuf buf,
      InetSocketAddress recipient,
      InetSocketAddress sender) throws OnRecordErrorException {
    List<Record> records = new LinkedList<>();
    List<SyslogMessage> messages = new LinkedList<>();
    try {
      new SyslogDecoder(charset).decodeStandaloneBuffer(buf, messages, sender, recipient);
      for (SyslogMessage message : messages) {
        records.add(buildRecord(message));
      }
      return records;
    } catch (OnRecordErrorException e) {
      // throw new exception with record populated with raw message
      Record record = context.createRecord(generateRecordId(sender));
      record.set(Field.create(buf.toString(charset)));
      throw new OnRecordErrorException(record, e.getErrorCode(), e.getParams());
    }
  }

  private String generateRecordId(InetSocketAddress sender) {
    String senderHost = SyslogDecoder.resolveHostAddressString(sender);
    return generateRecordId(senderHost + ":" + sender.getPort());
  }

  private String generateRecordId(String sender) {
    return sender + "::" + recordId++;
  }
}
