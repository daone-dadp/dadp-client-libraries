/*
 * Decompiled with CFR 0.152.
 */
package com.sqream.jdbc.connector.serverAPI.Statement;

import com.sqream.jdbc.SQDatabaseMetaData;
import com.sqream.jdbc.connector.BlockDto;
import com.sqream.jdbc.connector.ColumnMetadataDto;
import com.sqream.jdbc.connector.ConnException;
import com.sqream.jdbc.connector.FetchMetadataDto;
import com.sqream.jdbc.connector.MemoryAllocationService;
import com.sqream.jdbc.connector.StatementStateDto;
import com.sqream.jdbc.connector.TableMetadata;
import com.sqream.jdbc.connector.enums.StatementType;
import com.sqream.jdbc.connector.messenger.Messenger;
import com.sqream.jdbc.connector.serverAPI.SqreamConnectionContext;
import com.sqream.jdbc.connector.serverAPI.Statement.BasesProtocolPhase;
import com.sqream.jdbc.connector.serverAPI.Statement.SqreamExecutedStatement;
import com.sqream.jdbc.connector.serverAPI.enums.StatementPhase;
import com.sqream.jdbc.utils.Utils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SqreamExecutedStatementImpl
extends BasesProtocolPhase
implements SqreamExecutedStatement {
    private static final Logger LOGGER = Logger.getLogger(SQDatabaseMetaData.class.getName());
    private StatementType type;
    private StatementStateDto statementState;
    TableMetadata inputMetadata;
    TableMetadata outputMetadata;
    TableMetadata activeMetadata;

    public SqreamExecutedStatementImpl(SqreamConnectionContext context, StatementType type, StatementStateDto statementState) {
        super(context);
        this.type = type;
        this.inputMetadata = this.createInputTableMetadata();
        this.outputMetadata = this.createOutputTableMetadata();
        this.statementState = statementState;
        this.setActiveMetadata(this.inputMetadata);
    }

    @Override
    public void setActiveMetadata(TableMetadata metadata) {
        this.activeMetadata = metadata;
    }

    @Override
    public TableMetadata getActiveMetadata() {
        return this.activeMetadata;
    }

    @Override
    protected StatementPhase getStatementPhase() {
        return StatementPhase.EXECUTED;
    }

    @Override
    public StatementType getType() {
        return this.type;
    }

    @Override
    public long getUpdatedRows() {
        return this.context.getUpdatedRows();
    }

    @Override
    public StatementStateDto getStatementDto() {
        return this.statementState;
    }

    @Override
    public BlockDto fetch() {
        this.validateFetch();
        return this.fetchChunk();
    }

    @Override
    public void put(BlockDto block) {
        this.validatePut();
        try {
            Messenger messenger = this.context.getMessenger();
            messenger.put(block.getFillSize());
            LOGGER.log(Level.FINE, "sending header");
            messenger.sendBinaryHeader(Utils.totalLengthForHeader(this.inputMetadata, block));
            LOGGER.log(Level.FINE, "sent header done, start to send data");
            this.sendDataToSocket(this.inputMetadata, block);
            LOGGER.log(Level.FINE, "sent data done");
            messenger.isPutted();
        }
        catch (ConnException e) {
            this.close();
            throw new RuntimeException(e);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public TableMetadata getInputMeta() {
        return this.inputMetadata;
    }

    @Override
    public TableMetadata getOutputMeta() {
        return this.outputMetadata;
    }

    private void validateFetch() {
        if (this.type != StatementType.QUERY && this.type != StatementType.PARAMED_SELECT) {
            this.close();
            throw new IllegalStateException(MessageFormat.format("Statement [{0}] is not a select statement.", this.context.getQuery()));
        }
    }

    private void validatePut() {
        if (!StatementType.isParameterized(this.type.getValue()) && this.statementState.getPutFile() == null) {
            this.close();
            throw new IllegalStateException(MessageFormat.format("Statement [{0}] is not a parameterized statement.", this.context.getQuery()));
        }
    }

    private BlockDto fetchChunk() {
        FetchMetadataDto fetchMeta = null;
        try {
            Messenger messenger = this.context.getMessenger();
            fetchMeta = messenger.fetch();
            if (fetchMeta.getNewRowsFetched() == 0) {
                BlockDto emptyBlock = new BlockDto(new ByteBuffer[0], new ByteBuffer[0], new ByteBuffer[0], 0, new ByteBuffer[0], new ArrayList<List<List<Integer>>>());
                emptyBlock.setFillSize(0);
                return emptyBlock;
            }
            ByteBuffer[] fetch_buffers = new ByteBuffer[fetchMeta.colAmount()];
            for (int i = 0; i < fetchMeta.colAmount(); ++i) {
                fetch_buffers[i] = MemoryAllocationService.allocateByteBuffer(fetchMeta.getSizeByIndex(i)).order(ByteOrder.LITTLE_ENDIAN);
            }
            this.context.getMessenger().parseHeader();
            for (ByteBuffer fetchBuffer : fetch_buffers) {
                messenger.fetchBinaryData(fetchBuffer, fetchBuffer.capacity());
            }
            return this.parse(fetch_buffers, this.outputMetadata, fetchMeta.getNewRowsFetched());
        }
        catch (ConnException e) {
            this.close();
            throw new RuntimeException(e);
        }
    }

    private BlockDto parse(ByteBuffer[] fetchBuffers, TableMetadata metadata, int rowsFetched) {
        ByteBuffer[] lenColumnsArray = new ByteBuffer[metadata.getRowLength()];
        ByteBuffer[] dataColumns = new ByteBuffer[metadata.getRowLength()];
        ByteBuffer[] nullColumns = new ByteBuffer[metadata.getRowLength()];
        ByteBuffer[] nvarcLenColumns = new ByteBuffer[metadata.getRowLength()];
        ArrayList<List<List<Integer>>> nvarcLenColumnsArray = new ArrayList<List<List<Integer>>>();
        int idx = 0;
        int buf_idx = 0;
        while (idx < metadata.getRowLength()) {
            if (metadata.isNullable(idx)) {
                nullColumns[idx] = fetchBuffers[buf_idx];
                ++buf_idx;
            } else {
                nullColumns[idx] = null;
            }
            if (metadata.getType(idx).equals("ftArray")) {
                lenColumnsArray[idx] = fetchBuffers[buf_idx];
                ++buf_idx;
            } else {
                lenColumnsArray[idx] = null;
            }
            if (metadata.isTruVarchar(idx)) {
                if (metadata.getType(idx).equals("ftArray")) {
                    nvarcLenColumnsArray.add(this.getBlobLen(fetchBuffers[buf_idx], lenColumnsArray[idx]));
                } else {
                    nvarcLenColumns[idx] = fetchBuffers[buf_idx];
                    nvarcLenColumnsArray.add(null);
                    ++buf_idx;
                }
            } else if (metadata.getType(idx).equals("ftArray")) {
                nvarcLenColumnsArray.add(null);
            } else {
                nvarcLenColumns[idx] = null;
                nvarcLenColumnsArray.add(null);
            }
            dataColumns[idx] = fetchBuffers[buf_idx];
            ++idx;
            ++buf_idx;
        }
        BlockDto resultBlock = new BlockDto(dataColumns, nullColumns, nvarcLenColumns, rowsFetched, lenColumnsArray, nvarcLenColumnsArray);
        resultBlock.setFillSize(rowsFetched);
        return resultBlock;
    }

    private List<List<Integer>> getBlobLen(ByteBuffer dataBuffer, ByteBuffer lenColumnsArray) {
        ArrayList<List<Integer>> blobArrayLenRow = new ArrayList<List<Integer>>();
        if (lenColumnsArray == null) {
            throw new RuntimeException("Length Text columns can't be null");
        }
        if (dataBuffer.array().length != 0) {
            int lenCount = dataBuffer.getInt();
            int currentRowStartOffset = 0;
            int counterLenOffset = 0;
            block0: for (int lenArr = 0; lenArr < lenColumnsArray.array().length; lenArr += 4) {
                int offset = (int)Math.ceil((double)lenCount / 8.0) * 8 + 8 + currentRowStartOffset;
                ArrayList<Integer> blobArrayLen = new ArrayList<Integer>();
                int lenByteArray = lenColumnsArray.getInt(lenArr);
                int count = 0;
                if (lenCount == 0) {
                    blobArrayLenRow.add(blobArrayLen);
                    currentRowStartOffset += lenByteArray;
                    if ((counterLenOffset += lenByteArray) >= dataBuffer.array().length) continue;
                    lenCount = dataBuffer.getInt(counterLenOffset);
                    continue;
                }
                for (int i = offset; i < dataBuffer.array().length; i += 4) {
                    int value = dataBuffer.getInt(i);
                    if (count != 0) {
                        int previousIndex = dataBuffer.getInt(i - 4);
                        int v = previousIndex % 8;
                        value = v != 0 ? value - previousIndex - (8 - v) : (value -= previousIndex);
                    }
                    blobArrayLen.add(value);
                    if (++count != lenCount) continue;
                    currentRowStartOffset += lenByteArray;
                    if ((counterLenOffset += lenByteArray) < dataBuffer.array().length) {
                        lenCount = dataBuffer.getInt(counterLenOffset);
                    }
                    blobArrayLenRow.add(blobArrayLen);
                    continue block0;
                }
            }
        }
        return blobArrayLenRow;
    }

    private TableMetadata createTableMetadata(List<ColumnMetadataDto> columnMetadataDtos, StatementType statementType) {
        return TableMetadata.builder().rowLength(columnMetadataDtos.size()).fromColumnsMetadata(columnMetadataDtos).statementType(statementType).build();
    }

    private TableMetadata createInputTableMetadata() {
        return this.createTableMetadata(this.context.getInputColumnsMetadata(), this.type);
    }

    private TableMetadata createOutputTableMetadata() {
        return this.createTableMetadata(this.context.getOutputColumnsMetadata(), StatementType.QUERY);
    }

    private void sendDataToSocket(TableMetadata tableMetadata, BlockDto block) throws IOException, ConnException {
        Messenger messenger = this.context.getMessenger();
        for (int idx = 0; idx < tableMetadata.getRowLength(); ++idx) {
            if (tableMetadata.isNullable(idx)) {
                LOGGER.log(Level.FINEST, MessageFormat.format("start to send nullable for column {0}", tableMetadata.getType(idx)));
                messenger.sendBinaryData((ByteBuffer)block.getNullBuffers()[idx].position(block.getFillSize()));
            }
            if (tableMetadata.isTruVarchar(idx)) {
                LOGGER.log(Level.FINEST, MessageFormat.format("start to send nvarchar length for column {0}", tableMetadata.getType(idx)));
                messenger.sendBinaryData(block.getNvarcLenBuffers()[idx]);
            }
            if (tableMetadata.getType(idx).equals("ftArray")) {
                LOGGER.log(Level.FINEST, MessageFormat.format("start to send array length for column {0}", tableMetadata.getType(idx)));
                messenger.sendBinaryData(block.getLenColumnsArray()[idx]);
            }
            LOGGER.log(Level.FINEST, MessageFormat.format("start to send column data {0}", tableMetadata.getType(idx)));
            messenger.sendBinaryData(block.getDataBuffers()[idx]);
        }
    }
}

