package com.hello.suripu.workers.timeline;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownReason;
import com.amazonaws.services.kinesis.model.Record;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.RingTimeDAODynamoDB;
import com.hello.suripu.core.db.TimelineDAODynamoDB;
import com.hello.suripu.core.models.Timeline;
import com.hello.suripu.core.processors.TimelineProcessor;
import com.hello.suripu.core.util.DateTimeUtil;
import com.hello.suripu.workers.framework.HelloBaseRecordProcessor;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by pangwu on 1/26/15.
 */
public class TimelineRecordProcessor extends HelloBaseRecordProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(TimelineRecordProcessor.class);
    private final TimelineProcessor timelineProcessor;
    private final TimelineWorkerConfiguration configuration;
    private final MergedUserInfoDynamoDB mergedUserInfoDynamoDB;
    private final RingTimeDAODynamoDB ringTimeDAODynamoDB;
    private final TimelineDAODynamoDB timelineDAODynamoDB;
    private final DeviceDAO deviceDAO;

    public TimelineRecordProcessor(final TimelineProcessor timelineProcessor,
                                   final DeviceDAO deviceDAO,
                                   final MergedUserInfoDynamoDB mergedUserInfoDynamoDB,
                                   final RingTimeDAODynamoDB ringTimeDAODynamoDB,
                                   final TimelineDAODynamoDB timelineDAODynamoDB,
                                   final TimelineWorkerConfiguration configuration){

        this.timelineProcessor = timelineProcessor;
        this.configuration = configuration;
        this.mergedUserInfoDynamoDB = mergedUserInfoDynamoDB;
        this.ringTimeDAODynamoDB = ringTimeDAODynamoDB;
        this.timelineDAODynamoDB = timelineDAODynamoDB;
        this.deviceDAO = deviceDAO;

    }

    @Override
    public void initialize(String s) {
        LOGGER.info("Time line processor initialized: " + s);
    }

    @Override
    public void processRecords(final List<Record> list, final IRecordProcessorCheckpointer iRecordProcessorCheckpointer) {
        final List<SenseCommandProtos.batched_pill_data> batchedPillData = new ArrayList<>();
        for(final Record record:list){
            try {
                SenseCommandProtos.batched_pill_data dataBatch = SenseCommandProtos.batched_pill_data.parseFrom(record.getData().array());
                batchedPillData.add(dataBatch);
            } catch (InvalidProtocolBufferException e) {
                LOGGER.error("Failed to decode protobuf: {}", e.getMessage());
            }
        }

        final Map<String, Set<DateTime>> pillIdTargetDatesMap = BatchProcessUtils.groupRequestingPillIds(batchedPillData);
        final Map<Long, DateTime> groupedAccountIdTargetDateLocalUTCMap = BatchProcessUtils.groupAccountAndProcessDateLocalUTC(pillIdTargetDatesMap,
                DateTime.now().withZone(DateTimeZone.UTC),
                this.configuration.getEarliestProcessTime(),
                this.configuration.getLastProcessTime(),
                this.deviceDAO,
                this.mergedUserInfoDynamoDB);

        batchProcess(groupedAccountIdTargetDateLocalUTCMap);

        try {
            iRecordProcessorCheckpointer.checkpoint();
        } catch (InvalidStateException e) {
            LOGGER.error("checkpoint {}", e.getMessage());
        } catch (ShutdownException e) {
            LOGGER.error("Received shutdown command at checkpoint, bailing. {}", e.getMessage());
        }
    }

    private void batchProcess(final Map<Long, DateTime> groupedAccountIdTargetDateLocalUTCMap){
        for(final Long accountId:groupedAccountIdTargetDateLocalUTCMap.keySet()) {
            if(this.timelineProcessor.shouldProcessTimelineByWorker(accountId,
                    this.configuration.getMaxNoMoitonPeriodInMinutes(),
                    DateTime.now())){
                continue;
            }

            try {
                final List<Timeline> timelines = this.timelineProcessor.retrieveTimelines(accountId,
                        groupedAccountIdTargetDateLocalUTCMap.get(accountId).toString(DateTimeUtil.DYNAMO_DB_DATE_FORMAT),
                        missingDataDefaultValue(accountId),
                        hasAlarmInTimeline(accountId));
                this.timelineDAODynamoDB.saveTimelinesForDate(accountId,
                        groupedAccountIdTargetDateLocalUTCMap.get(accountId),
                        timelines);
                LOGGER.info("Timeline saved for account {} at local utc {}",
                        accountId,
                        groupedAccountIdTargetDateLocalUTCMap.get(accountId).toString(DateTimeUtil.DYNAMO_DB_DATE_FORMAT));

                // TODO: Push notification here?
            }catch (AmazonServiceException awsException){
                LOGGER.error("Failed to generate timeline: {}", awsException.getErrorMessage());
            }catch (Exception ex){
                LOGGER.error("Failed to generate timeline. General error {}", ex.getMessage());
            }
        }

    }


    @Override
    public void shutdown(final IRecordProcessorCheckpointer iRecordProcessorCheckpointer, final ShutdownReason shutdownReason) {
        LOGGER.warn("SHUTDOWN: {}", shutdownReason.toString());
    }
}
