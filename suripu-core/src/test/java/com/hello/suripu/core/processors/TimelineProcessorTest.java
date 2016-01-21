package com.hello.suripu.core.processors;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.logging.LoggingProtos;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.db.AccountReadDAO;
import com.hello.suripu.core.db.CalibrationDAO;
import com.hello.suripu.core.db.DefaultModelEnsembleDAO;
import com.hello.suripu.core.db.DeviceDataReadAllSensorsDAO;
import com.hello.suripu.core.db.DeviceReadForTimelineDAO;
import com.hello.suripu.core.db.FeatureExtractionModelsDAO;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.FeedbackReadDAO;
import com.hello.suripu.core.db.OnlineHmmModelsDAO;
import com.hello.suripu.core.db.PillDataReadDAO;
import com.hello.suripu.core.db.RingTimeHistoryReadDAO;
import com.hello.suripu.core.db.SleepHmmDAO;
import com.hello.suripu.core.db.SleepStatsDAO;
import com.hello.suripu.core.db.UserTimelineTestGroupDAO;
import com.hello.suripu.core.db.colors.SenseColorDAO;
import com.hello.suripu.core.flipper.DynamoDBAdapter;
import com.hello.suripu.core.models.Account;
import com.hello.suripu.core.models.AggregateSleepStats;
import com.hello.suripu.core.models.AllSensorSampleList;
import com.hello.suripu.core.models.Calibration;
import com.hello.suripu.core.models.Device;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.models.MotionScore;
import com.hello.suripu.core.models.OnlineHmmData;
import com.hello.suripu.core.models.OnlineHmmPriors;
import com.hello.suripu.core.models.OnlineHmmScratchPad;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.SleepStats;
import com.hello.suripu.core.models.TimelineFeedback;
import com.hello.suripu.core.models.TimelineResult;
import com.hello.suripu.core.models.TrackerMotion;
import com.hello.suripu.core.models.device.v2.Sense;
import com.hello.suripu.core.util.FeatureExtractionModelData;
import com.hello.suripu.core.util.SleepHmmWithInterpretation;
import com.librato.rollout.RolloutAdapter;
import com.librato.rollout.RolloutClient;
import dagger.Module;
import dagger.ObjectGraph;
import dagger.Provides;
import junit.framework.TestCase;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Created by benjo on 1/21/16.
 */
public class TimelineProcessorTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimelineProcessorTest.class);

    final PillDataReadDAO pillDataReadDAO = new PillDataReadDAO() {
        @Override
        public ImmutableList<TrackerMotion> getBetweenLocalUTC(long accountId, DateTime startLocalTime, DateTime endLocalTime) {

            return OnlineHmmTest.getTypicalDayOfPill(startLocalTime,endLocalTime,0);
        }
    };


    final DeviceDataReadAllSensorsDAO deviceDataReadAllSensorsDAO = new DeviceDataReadAllSensorsDAO() {
        @Override
        public AllSensorSampleList generateTimeSeriesByUTCTimeAllSensors(Long queryStartTimestampInUTC, Long queryEndTimestampInUTC, Long accountId, String externalDeviceId, int slotDurationInMinutes, Integer missingDataDefaultValue, Optional<Device.Color> color, Optional<Calibration> calibrationOptional) {
            return OnlineHmmTest.getTypicalDayOfSense(new DateTime(queryStartTimestampInUTC).withZone(DateTimeZone.UTC),new DateTime(queryEndTimestampInUTC).withZone(DateTimeZone.UTC),0);
        }
    };

    final RingTimeHistoryReadDAO ringTimeHistoryDAODynamoDB = new RingTimeHistoryReadDAO() {
        @Override
        public List<RingTime> getRingTimesBetween(String senseId, Long accountId, DateTime startTime, DateTime endTime) {
            return null;
        }
    };

    final FeedbackReadDAO feedbackDAO = new FeedbackReadDAO() {
        @Override
        public ImmutableList<TimelineFeedback> getCorrectedForNight(Long accountId,DateTime dateOfNight) {
            return null;
        }

        @Override
        public ImmutableList<TimelineFeedback> getForTimeRange(Long tstartUTC, Long tstopUTC) {
            return null;
        }
    };

    final SleepHmmDAO sleepHmmDAO = new SleepHmmDAO() {
        @Override
        public Optional<SleepHmmWithInterpretation> getLatestModelForDate(long accountId, long timeOfInterestMillis) {
            return Optional.absent();
        }
    };

    final AccountReadDAO accountDAO = new AccountReadDAO() {
        @Override
        public Optional<Account> getById(Long id) {
            final Account account = new Account.Builder().withDOB("1980-01-01").build();
            return Optional.of(account);
        }

        @Override
        public Optional<Account> getByEmail(String email) {
            return null;
        }

        @Override
        public List<Account> getRecent(Integer limit) {
            return null;
        }

        @Override
        public Optional<Account> exists(String email, String password) {
            return null;
        }

        @Override
        public List<Account> getByNamePartial(String namePartial) {
            return null;
        }

        @Override
        public List<Account> getByEmailPartial(String emailPartial) {
            return null;
        }
    };


    final SleepStatsDAO sleepStatsDAO = new SleepStatsDAO() {
        @Override
        public Boolean updateStat(Long accountId, DateTime date, Integer sleepScore, MotionScore motionScore, SleepStats stats, Integer offsetMillis) {
            return Boolean.TRUE;
        }

        @Override
        public Optional<Integer> getTimeZoneOffset(Long accountId) {
            return null;
        }

        @Override
        public Optional<Integer> getTimeZoneOffset(Long accountId, DateTime queryDate) {
            return null;
        }

        @Override
        public Optional<AggregateSleepStats> getSingleStat(Long accountId, String date) {
            return null;
        }

        @Override
        public ImmutableList<AggregateSleepStats> getBatchStats(Long accountId, String startDate, String endDate) {
            return null;
        }
    };

    final SenseColorDAO senseColorDAO = new SenseColorDAO() {
        @Override
        public Optional<Device.Color> getColorForSense(String senseId) {
            return Optional.absent();
        }

        @Override
        public Optional<Sense.Color> get(String senseId) {
            return null;
        }

        @Override
        public int saveColorForSense(String senseId, String color) {
            return 0;
        }

        @Override
        public int update(String senseId, String color) {
            return 0;
        }

        @Override
        public ImmutableList<String> missing() {
            return null;
        }
    };

    final OnlineHmmModelsDAO priorsDAO = new OnlineHmmModelsDAO() {
        @Override
        public OnlineHmmData getModelDataByAccountId(Long accountId, DateTime date) {
            return null;
        }

        @Override
        public boolean updateModelPriorsAndZeroOutScratchpad(Long accountId, DateTime date, OnlineHmmPriors priors) {
            return false;
        }

        @Override
        public boolean updateScratchpad(Long accountId, DateTime date, OnlineHmmScratchPad scratchPad) {
            return false;
        }
    };

    final FeatureExtractionModelsDAO featureExtractionModelsDAO = new FeatureExtractionModelsDAO() {
        @Override
        public FeatureExtractionModelData getLatestModelForDate(Long accountId, DateTime dateTimeLocalUTC, Optional<UUID> uuidForLogger) {
            return null;
        }
    };

    final CalibrationDAO calibrationDAO = new CalibrationDAO() {
        @Override
        public Optional<Calibration> get(String senseId) {
            return null;
        }

        @Override
        public Optional<Calibration> getStrict(String senseId) {
            return null;
        }

        @Override
        public Optional<Boolean> putForce(Calibration calibration) {
            return null;
        }

        @Override
        public Optional<Boolean> put(Calibration calibration) {
            return null;
        }

        @Override
        public Map<String, Calibration> getBatch(Set<String> senseIds) {
            return null;
        }

        @Override
        public Map<String, Calibration> getBatchStrict(Set<String> senseIds) {
            return null;
        }

        @Override
        public Boolean delete(String senseId) {
            return null;
        }

        @Override
        public Map<String, Optional<Boolean>> putBatchForce(List<Calibration> calibrations) {
            return null;
        }

        @Override
        public Map<String, Optional<Boolean>> putBatch(List<Calibration> calibration) {
            return null;
        }
    };

    final DefaultModelEnsembleDAO defaultModelEnsembleDAO = new DefaultModelEnsembleDAO() {
        @Override
        public OnlineHmmPriors getDefaultModelEnsemble() {
            return OnlineHmmPriors.createEmpty();
        }

        @Override
        public OnlineHmmPriors getSeedModel() {
            return OnlineHmmPriors.createEmpty();
        }
    };

    final UserTimelineTestGroupDAO userTimelineTestGroupDAO = new UserTimelineTestGroupDAO() {
        @Override
        public Optional<Long> getUserGestGroup(Long accountId, DateTime timeToQueryUTC) {
            return Optional.absent();
        }

        @Override
        public void setUserTestGroup(Long accountId, Long groupId) {

        }
    };

    final DeviceReadForTimelineDAO deviceReadForTimelineDAO = new DeviceReadForTimelineDAO() {
        @Override
        public ImmutableList<DeviceAccountPair> getSensesForAccountId(Long accountId) {
            return null;
        }

        @Override
        public Optional<DeviceAccountPair> getMostRecentSensePairByAccountId(Long accountId) {
            return Optional.of(new DeviceAccountPair(0L,0L,"foobars",new DateTime(0L)));
        }

        @Override
        public Optional<Long> getPartnerAccountId(Long accountId) {
            return Optional.absent();
        }
    };

    final RolloutAdapter rolloutAdapter = new RolloutAdapter() {
        @Override
        public boolean userFeatureActive(String feature, long userId, List<String> userGroups) {
            boolean hasFeature = false;
            LOGGER.info("userFeatureActive {}={}",feature,hasFeature);
            return hasFeature;
        }

        @Override
        public boolean deviceFeatureActive(String feature, String deviceId, List<String> userGroups) {
            boolean hasFeature = false;
            LOGGER.info("deviceFeatureActive {}={}",feature,hasFeature);
            return false;
        }
    };

    @Module(
            injects = TimelineProcessor.class,
            library = true
    )
    class RolloutLocalModule {
        @Provides @Singleton
        RolloutAdapter providesRolloutAdapter() {
            return rolloutAdapter;
        }

        @Provides @Singleton
        RolloutClient providesRolloutClient(RolloutAdapter adapter) {
            return new RolloutClient(adapter);
        }

    }

    @Test
    public void testTimelineProcessorSimple() {

        ObjectGraphRoot.getInstance().init(new RolloutLocalModule());

        final TimelineProcessor timelineProcessor = TimelineProcessor.createTimelineProcessor(
                pillDataReadDAO,deviceReadForTimelineDAO,deviceDataReadAllSensorsDAO,
                ringTimeHistoryDAODynamoDB,feedbackDAO,sleepHmmDAO,accountDAO,sleepStatsDAO,
                senseColorDAO,priorsDAO,featureExtractionModelsDAO,calibrationDAO,
                defaultModelEnsembleDAO,userTimelineTestGroupDAO);


        final TimelineResult timelineResult = timelineProcessor.retrieveTimelinesFast(0L,DateTime.now(),Optional.<TimelineFeedback>absent());

        TestCase.assertTrue(timelineResult.timelines.size() > 0);
        TestCase.assertTrue(timelineResult.logV2.isPresent());

        try {
            final LoggingProtos.BatchLogMessage batchLogMessage = LoggingProtos.BatchLogMessage.newBuilder().mergeFrom(timelineResult.logV2.get().toProtoBuf()).build();

            final List<LoggingProtos.TimelineLog> logs = batchLogMessage.getTimelineLogList();

            TestCase.assertFalse(logs.isEmpty());

            final LoggingProtos.TimelineLog lastLog = logs.get(logs.size()-1);

            TestCase.assertTrue(lastLog.getAlgorithm().equals(LoggingProtos.TimelineLog.AlgType.VOTING));

        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }

    }
}
