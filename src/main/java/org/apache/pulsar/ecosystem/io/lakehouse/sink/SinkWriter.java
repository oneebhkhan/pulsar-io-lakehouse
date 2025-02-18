/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.ecosystem.io.lakehouse.sink;

import com.google.common.base.Strings;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.pulsar.ecosystem.io.lakehouse.SinkConnectorConfig;
import org.apache.pulsar.ecosystem.io.lakehouse.common.SchemaConverter;
import org.apache.pulsar.ecosystem.io.lakehouse.exception.CommitFailedException;
import org.apache.pulsar.ecosystem.io.lakehouse.exception.LakehouseConnectorException;
import org.apache.pulsar.ecosystem.io.lakehouse.exception.LakehouseWriterException;

/**
 * Writer thread. Fetch records from queue, and write them into lakehouse table.
 */
@Slf4j
public class SinkWriter implements Runnable {
    private final SinkConnectorConfig sinkConnectorConfig;
    private LakehouseWriter writer;
    private Schema currentPulsarSchema;
    private Schema schemaWithoutNull;
    private PulsarSinkRecord lastRecord;
    private final GenericDatumReader<GenericRecord> datumReader;
    private final long timeIntervalPerCommit;
    private long lastCommitTime;
    private long recordsCnt;
    private final long maxRecordsPerCommit;
    private final int maxCommitFailedTimes;
    private volatile boolean running;
    private final LinkedBlockingQueue<PulsarSinkRecord> messages;
    private int commitFailedCnt;


    public SinkWriter(SinkConnectorConfig sinkConnectorConfig, LinkedBlockingQueue<PulsarSinkRecord> messages) {
        this.messages = messages;
        this.sinkConnectorConfig = sinkConnectorConfig;
        this.datumReader = new GenericDatumReader<>();
        this.timeIntervalPerCommit = TimeUnit.SECONDS.toMillis(sinkConnectorConfig.getMaxCommitInterval());
        this.maxRecordsPerCommit = sinkConnectorConfig.getMaxRecordsPerCommit();
        this.maxCommitFailedTimes = sinkConnectorConfig.getMaxCommitFailedTimes();
        this.lastCommitTime = System.currentTimeMillis();
        this.recordsCnt = 0;
        this.commitFailedCnt = 0;
        this.running = true;
    }

    public void run() {
        log.info("DEBUG-Running Status Start: " + running);
        while (running) {
            try {
                PulsarSinkRecord pulsarSinkRecord = messages.poll(100, TimeUnit.MILLISECONDS);
                if (pulsarSinkRecord == null) {
                    if (recordsCnt > 0) {
                        commitIfNeed();
                    }
                    continue;
                }

                if (log.isDebugEnabled()) {
                    pulsarSinkRecord.getRecord().getMessage().ifPresent(m -> {
                        log.debug("Handling message: {}", m.getMessageId());
                    });
                }

                String schemaStr = pulsarSinkRecord.getSchema();
                if (Strings.isNullOrEmpty(schemaStr.trim())) {
                    log.error("Failed to get schema from record, skip the record");
                    continue;
                }
                if (currentPulsarSchema == null || !currentPulsarSchema.toString().equals(schemaStr)) {
                    Schema schema = new Schema.Parser().parse(schemaStr);
                    currentPulsarSchema = schema;
                    if (log.isDebugEnabled()) {
                        log.debug("new schema: {}", currentPulsarSchema);
                    }
                    schemaWithoutNull = SchemaConverter.convertPulsarAvroSchemaToNonNullSchema(schema);
                    datumReader.setSchema(schemaWithoutNull);
                    datumReader.setExpected(schemaWithoutNull);
                    if (getOrCreateWriter().updateSchema(schema)) {
                        resetStatus();
                    }
                }
                Optional<GenericRecord> avroRecord =
                    convertToAvroGenericData(pulsarSinkRecord, schemaWithoutNull, datumReader);
                if (avroRecord.isPresent()) {
                    getOrCreateWriter().writeAvroRecord(avroRecord.get());
                    lastRecord = pulsarSinkRecord;
                    recordsCnt++;
                    commitIfNeed();
                }

            } catch (Exception e) {
                log.error("process record failed. ", e);
                // fail the sink connector.
                running = false;
            }
        }
        log.info("DEBUG-Running Status End: " + running);
    }

    private void commitIfNeed() throws LakehouseConnectorException {
        log.info("DEBUG-needCommit: " + needCommit());
        if (needCommit()) {
            if (log.isDebugEnabled()) {
                log.debug("Commit ");
            }
            if (getOrCreateWriter().flush()) {
                resetStatus();
            } else {
                commitFailedCnt++;
                log.warn("Commit records failed {} times", commitFailedCnt);
                if (commitFailedCnt > maxCommitFailedTimes) {
                    String errMsg = "Exceed the max commit failed times, the allowed max failure times is "
                        + maxCommitFailedTimes;
                    log.error(errMsg);
                    throw new CommitFailedException(errMsg);
                }
            }
        }
    }

    private LakehouseWriter getOrCreateWriter() throws LakehouseWriterException {
        if (writer != null) {
            return writer;
        }
        writer = LakehouseWriter.getWriter(sinkConnectorConfig, currentPulsarSchema);
        return writer;
    }

    private void resetStatus() {
        if (lastRecord != null) {
            lastRecord.ack();
        }
        lastCommitTime = System.currentTimeMillis();
        recordsCnt = 0;
        commitFailedCnt = 0;
    }

    private boolean needCommit() {
//        log.info("DEBUG: last"+);
        return System.currentTimeMillis() - lastCommitTime >= timeIntervalPerCommit
            || recordsCnt >= maxRecordsPerCommit;
    }

    public Optional<GenericRecord> convertToAvroGenericData(PulsarSinkRecord record,
                                                            Schema schema,
                                                            GenericDatumReader<GenericRecord> datumReader)
        throws IOException {
        switch (record.getSchemaType()) {
            case AVRO:
                return Optional.of((GenericRecord) record.getNativeObject());
            case JSON:
                Decoder decoder = DecoderFactory.get()
                    .jsonDecoder(schema, record.getNativeObject().toString());
                return Optional.of(datumReader.read(null, decoder));
            default:
                try {
                    GenericRecord gr = PrimitiveFactory.getPulsarPrimitiveObject(record.getSchemaType(),
                        record.getNativeObject(), sinkConnectorConfig.getOverrideFieldName()).getRecord();
                    return Optional.of(gr);
                } catch (Exception e) {
                    log.error("not support this kind of schema: {}", record.getSchemaType(), e);
                    return Optional.empty();
                }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void close() throws IOException {
        running = false;
        if (writer != null) {
            writer.close();
        }
        if (lastRecord != null) {
            lastRecord.ack();
        }
    }
}
