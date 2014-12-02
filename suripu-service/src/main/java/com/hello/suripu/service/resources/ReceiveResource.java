package com.hello.suripu.service.resources;

import com.google.common.base.Optional;
import com.google.common.cache.CacheLoader;
import com.google.protobuf.TextFormat;
import com.hello.dropwizard.mikkusu.helpers.AdditionalMediaTypes;
import com.hello.suripu.api.audio.AudioControlProtos;
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.api.input.DataInputProtos;
import com.hello.suripu.api.input.InputProtos;
import com.hello.suripu.api.output.OutputProtos;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.DeviceDataDAO;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.MergedAlarmInfoDynamoDB;
import com.hello.suripu.core.firmware.FirmwareUpdateStore;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.hello.suripu.core.flipper.GroupFlipper;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.logging.KinesisLoggerFactory;
import com.hello.suripu.core.models.AlarmInfo;
import com.hello.suripu.core.models.CurrentRoomState;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.models.DeviceData;
import com.hello.suripu.core.models.RingTime;
import com.hello.suripu.core.models.TempTrackerData;
import com.hello.suripu.core.models.TrackerMotion;
import com.hello.suripu.core.oauth.AccessToken;
import com.hello.suripu.core.oauth.OAuthScope;
import com.hello.suripu.core.oauth.Scope;
import com.hello.suripu.core.processors.RingProcessor;
import com.hello.suripu.core.resources.BaseResource;
import com.hello.suripu.core.util.DeviceIdUtil;
import com.hello.suripu.core.util.RoomConditionUtil;
import com.hello.suripu.service.SignedMessage;
import com.librato.rollout.RolloutClient;
import com.yammer.metrics.annotation.Timed;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.DateTimeZone;
import org.skife.jdbi.v2.exceptions.UnableToExecuteStatementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Path("/in")
public class ReceiveResource extends BaseResource {

    @Inject
    RolloutClient featureFlipper;

    private static final Pattern PG_UNIQ_PATTERN = Pattern.compile("ERROR: duplicate key value violates unique constraint \"(\\w+)\"");
    private static final Logger LOGGER = LoggerFactory.getLogger(ReceiveResource.class);
    private static final int OFFSET_MILLIS = -25200000;

    private final DeviceDataDAO deviceDataDAO;
    private final DeviceDAO deviceDAO;
    private final KeyStore keyStore;
    private final MergedAlarmInfoDynamoDB mergedInfoDynamoDB;

    private final KinesisLoggerFactory kinesisLoggerFactory;
    private final Boolean debug;

    private final FirmwareUpdateStore firmwareUpdateStore;
    private final GroupFlipper groupFlipper;

    @Context
    HttpServletRequest request;

    public ReceiveResource(final DeviceDataDAO deviceDataDAO,
                           final DeviceDAO deviceDAO,
                           final KeyStore keyStore,
                           final KinesisLoggerFactory kinesisLoggerFactory,
                           final MergedAlarmInfoDynamoDB mergedInfoDynamoDB,
                           final Boolean debug,
                           final FirmwareUpdateStore firmwareUpdateStore,
                           final GroupFlipper groupFlipper) {
        this.deviceDataDAO = deviceDataDAO;
        this.deviceDAO = deviceDAO;

        this.keyStore = keyStore;
        this.kinesisLoggerFactory = kinesisLoggerFactory;

        this.mergedInfoDynamoDB = mergedInfoDynamoDB;

        this.debug = debug;

        final CacheLoader<String, Optional<byte[]>> loader = new CacheLoader<String, Optional<byte[]>>() {
            public Optional<byte[]> load(String key) {
                return keyStore.get(key);
            }
        };

        this.firmwareUpdateStore = firmwareUpdateStore;
        this.groupFlipper = groupFlipper;
    }

    @POST
    @Timed
    @Path("/temp/tracker")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Deprecated
    public void sendTempData(
            @Valid List<TempTrackerData> trackerData,
            @Scope({OAuthScope.API_INTERNAL_DATA_WRITE}) AccessToken accessToken) {

        if(trackerData.size() == 0){
            LOGGER.info("Account {} tries to upload empty payload.", accessToken.accountId);
            return;
        }

        final List<DeviceAccountPair> pairs = deviceDAO.getTrackerIds(accessToken.accountId);

        final Map<String, Long> pairsLookup = new HashMap<>(pairs.size());
        for (DeviceAccountPair pair: pairs) {
            pairsLookup.put(pair.externalDeviceId, pair.internalDeviceId);
        }

        if(pairs.isEmpty()) {
            LOGGER.warn("No tracker registered for account = {}", accessToken.accountId);
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build());
        }

        if(pairs.size() > 1) {
            LOGGER.warn("Too many trackers ({}) for account = {}", pairs.size(), accessToken.accountId);
        }

        for(final TempTrackerData tempTrackerData : trackerData) {

            final Long trackerId = pairsLookup.get(tempTrackerData.trackerId);
            if(trackerId == null) {
                LOGGER.warn("TrackerId {} is not paired to account: {}", tempTrackerData.trackerId, accessToken.accountId);
                throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build());
            }

            // add to kinesis - 1 sample per min
            if (tempTrackerData.value > 0) {
                final String pillID = trackerId.toString();

                final InputProtos.PillDataKinesis pillKinesisData = InputProtos.PillDataKinesis.newBuilder()
                        .setAccountIdLong(accessToken.accountId)
                        .setPillIdLong(trackerId)
                        .setTimestamp(tempTrackerData.timestamp)
                        .setValue(TrackerMotion.Utils.rawToMilliMS2((long) tempTrackerData.value)) // in milli-g
                        .setOffsetMillis(this.OFFSET_MILLIS)
                        .build();

                final byte[] pillDataBytes = pillKinesisData.toByteArray();
                final DataLogger dataLogger = kinesisLoggerFactory.get(QueueName.PILL_DATA);
                final String sequenceNumber = dataLogger.put(pillID, pillDataBytes);
                LOGGER.debug("Pill Data added to Kinesis with sequenceNumber = {}", sequenceNumber);
            }
        }
    }



    @POST
    @Path("/sense/batch")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Timed
    public byte[] receiveBatchSenseData(final byte[] body) {
        final SignedMessage signedMessage = SignedMessage.parse(body);
        DataInputProtos.batched_periodic_data data = null;

        try {
            data = DataInputProtos.batched_periodic_data.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            final String errorMessage = String.format("Failed parsing protobuf: %s", exception.getMessage());
            LOGGER.error(errorMessage);

            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity((debug) ? errorMessage : "bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }
        LOGGER.debug("Received protobuf message {}", TextFormat.shortDebugString(data));


        LOGGER.debug("Received valid protobuf {}", data.toString());
        LOGGER.debug("Received protobuf message {}", TextFormat.shortDebugString(data));

        final Optional<byte[]> optionalKeyBytes = keyStore.get(data.getDeviceId());
        if(!optionalKeyBytes.isPresent()) {
            LOGGER.error("Failed to get key from key store for device_id = {}", data.getDeviceId());
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(optionalKeyBytes.get());

        if(error.isPresent()) {
            LOGGER.error(error.get().message);
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity((debug) ? error.get().message : "bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }

        final DataLogger batchSenseDataLogger = kinesisLoggerFactory.get(QueueName.SENSE_SENSORS_DATA);
        batchSenseDataLogger.put(data.getDeviceId(), signedMessage.body);
        return generateSyncResponse(data.getDeviceId(), data.getFirmwareVersion(), optionalKeyBytes.get(), data);
    }


    @POST
    @Path("/morpheus/pb2")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Timed
    public byte[] morpheusProtobufReceiveEncrypted(final byte[] body) {
        final SignedMessage signedMessage = SignedMessage.parse(body);
        DataInputProtos.periodic_data data = null;

        try {
            data = DataInputProtos.periodic_data.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            final String errorMessage = String.format("Failed parsing protobuf: %s", exception.getMessage());
            LOGGER.error(errorMessage);

            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity((debug) ? errorMessage : "bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }
        LOGGER.debug("Received protobuf message {}", TextFormat.shortDebugString(data));


        // get MAC address of morpheus
        final Optional<String> deviceIdOptional = DeviceIdUtil.getMorpheusId(data);
        if(!deviceIdOptional.isPresent()){
            LOGGER.error("Cannot get morpheus id");
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity((debug) ? "Cannot get morpheus id" : "bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }


        final String deviceName = deviceIdOptional.get();
        LOGGER.debug("Received valid protobuf {}", deviceName.toString());
        LOGGER.debug("Received protobuf message {}", TextFormat.shortDebugString(data));

        final Optional<byte[]> optionalKeyBytes = keyStore.get(data.getDeviceId());
        if(!optionalKeyBytes.isPresent()) {
            LOGGER.error("Failed to get key from key store for device_id = {}", data.getDeviceId());
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(optionalKeyBytes.get());

        if(error.isPresent()) {
            LOGGER.error(error.get().message);
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity((debug) ? error.get().message : "bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }

        // Saving sense data to kinesis
        final DataLogger senseSensorsDataLogger = kinesisLoggerFactory.get(QueueName.SENSE_SENSORS_DATA);
        senseSensorsDataLogger.put(deviceName, signedMessage.body);
        final DataInputProtos.batched_periodic_data batch = DataInputProtos.batched_periodic_data.newBuilder()
                .addData(data)
                .setDeviceId(data.getDeviceId())
                .setFirmwareVersion(data.getFirmwareVersion())
                .build();
        return generateSyncResponse(data.getDeviceId(), data.getFirmwareVersion(), optionalKeyBytes.get(), batch);
    }


    /**
     * Persists data and generates SyncResponse
     * @param deviceName
     * @param firmwareVersion
     * @param encryptionKey
     * @param batch
     * @return
     */
    private byte[] generateSyncResponse(final String deviceName, final int firmwareVersion, final byte[] encryptionKey, final DataInputProtos.batched_periodic_data batch) {
        // TODO: Warning, since we query dynamoDB based on user input, the user can generate a lot of
        // requests to break our bank(Assume that Dynamo DB never goes down).
        // May be we should somehow cache these data to reduce load & cost.

        // TODO: remove the dependency on deviceDAO
        // The accounts should come from DynamoDB table
        final List<DeviceAccountPair> deviceAccountPairs = deviceDAO.getAccountIdsForDeviceId(deviceName);
        final List<AlarmInfo> alarmInfoList = new ArrayList<>();

        try {
            alarmInfoList.addAll(this.mergedInfoDynamoDB.getInfo(deviceName));  // get alarm related info from DynamoDB "cache".
        }catch (Exception ex){
            LOGGER.error("Failed to retrieve info from merge info db for device {}: {}", deviceName, ex.getMessage());
        }

        RingTime nextRingTimeFromWorker = RingTime.createEmpty();

        LOGGER.debug("Found {} pairs", deviceAccountPairs.size());

        // This is the default timezone.
        DateTimeZone userTimeZone = DateTimeZone.forID("America/Los_Angeles");
        final OutputProtos.SyncResponse.Builder responseBuilder = OutputProtos.SyncResponse.newBuilder();

        // ********************* Morpheus Data and Alarm processing **************************
        // Here comes to a discussion: Shall we loop over the device_id:account_id relation based on the
        // DynamoDB "cache" or PostgresSQL accout_device_map table?
        // Looping over DynamoDB can save a hit to the PostgresSQL every minute for every Morpheus,
        // but may run into data consistency issue if the alarm_info table is not update correctly.
        // Looping over the PostgresSQL table can avoid the issue of data consistency, since everything
        // is checked on actual device:account pair. However, this might bring in availability concern.
        //
        // For now, I rely on the actual device_account_map table instead of the dynamo DB cache.
        // Because I think it will make the implementation much simpler. If the PostgreSQL is down, sensor data
        // will not be stored anyway.
        //
        // Pang, 09/26/2014
        for (final DeviceAccountPair pair : deviceAccountPairs) {
            // Get the timezone for current user.
            for(final AlarmInfo alarmInfo:alarmInfoList){
                if(alarmInfo.accountId == pair.accountId){
                    if(alarmInfo.timeZone.isPresent()){
                        userTimeZone = alarmInfo.timeZone.get();
                    }else{
                        LOGGER.warn("User {} on device {} time zone not set.", pair.accountId, alarmInfo.deviceId);
                    }

                    if(alarmInfo.ringTime.isPresent()){
                        if(alarmInfo.ringTime.get().isEmpty()){
                            continue;
                        }

                        if(nextRingTimeFromWorker.isEmpty()){
                            nextRingTimeFromWorker = alarmInfo.ringTime.get();
                        }else{
                            if(alarmInfo.ringTime.get().actualRingTimeUTC < nextRingTimeFromWorker.actualRingTimeUTC){
                                nextRingTimeFromWorker = alarmInfo.ringTime.get();
                            }
                        }
                    }
                }
            }

            // TODO: this should be done in the worker
            for(DataInputProtos.periodic_data data : batch.getDataList()) {
                final Long timestampMillis = data.getUnixTime() * 1000L;
                final DateTime roundedDateTime = new DateTime(timestampMillis, DateTimeZone.UTC).withSecondOfMinute(0);
                if(roundedDateTime.isAfter(roundedDateTime.plusHours(2)) || roundedDateTime.isAfter(roundedDateTime.minusHours(2))) {
                    LOGGER.error("The clock for device {} is not within reasonable bounds (2h)", data.getDeviceId());
                    LOGGER.error("Current time = {}, received time = {}", DateTime.now(), roundedDateTime);
                    // TODO: throw exception?
                    // throw new WebApplicationException(Response.Status.BAD_REQUEST);
                    continue;
                }

                final DeviceData.Builder builder = new DeviceData.Builder()
                        .withAccountId(pair.accountId)
                        .withDeviceId(pair.internalDeviceId)
                        .withAmbientTemperature(data.getTemperature())
                        .withAmbientAirQuality(data.getDust(), data.getFirmwareVersion())
                        .withAmbientAirQualityRaw(data.getDust())
                        .withAmbientDustVariance(data.getDustVariability())
                        .withAmbientDustMin(data.getDustMin())
                        .withAmbientDustMax(data.getDustMax())
                        .withAmbientHumidity(data.getHumidity())
                        .withAmbientLight(data.getLight())
                        .withAmbientLightVariance(data.getLightVariability())
                        .withAmbientLightPeakiness(data.getLightTonality())
                        .withOffsetMillis(userTimeZone.getOffset(roundedDateTime))
                        .withDateTimeUTC(roundedDateTime)
                        .withFirmwareVersion(data.getFirmwareVersion())
                        .withWaveCount(data.hasWaveCount() ? data.getWaveCount() : 0)
                        .withHoldCount(data.hasHoldCount() ? data.getHoldCount() : 0);

                final DeviceData deviceData = builder.build();
                final CurrentRoomState currentRoomState = CurrentRoomState.fromDeviceData(deviceData, DateTime.now(), 2);
                responseBuilder.setRoomConditions(
                        OutputProtos.SyncResponse.RoomConditions.valueOf(
                                RoomConditionUtil.getGeneralRoomCondition(currentRoomState).ordinal()));

                try {
                    deviceDataDAO.insert(deviceData);
                    LOGGER.trace("Data saved to DB: {}", TextFormat.shortDebugString(data));
                } catch (UnableToExecuteStatementException exception) {
                    final Matcher matcher = PG_UNIQ_PATTERN.matcher(exception.getMessage());
                    if (!matcher.find()) {
                        LOGGER.error(exception.getMessage());
                        throw new WebApplicationException(
                                Response.status(Response.Status.BAD_REQUEST)
                                        .entity(exception.getMessage())
                                        .type(MediaType.TEXT_PLAIN_TYPE)
                                        .build());
                    }

                    LOGGER.warn("Duplicate device sensor value for account_id = {}, time: {}", pair.accountId, roundedDateTime);
                }
            }
        }

        // TODO: remove this once we are sure no worker consumes this stream
//        final DataLogger dataLogger = kinesisLoggerFactory.get(QueueName.MORPHEUS_DATA);
//        final byte[] morpheusDataInBytes = data.toByteArray();
//        final String shardingKey = deviceName;
//
//        final String sequenceNumber = dataLogger.put(shardingKey, morpheusDataInBytes);
//        LOGGER.trace("Morpheus data saved to Kinesis with sequence number = {}", sequenceNumber);


        Optional<RingTime> nextRegularRingTime = Optional.absent();
        int ringOffsetFromNowInSecond = -1;
        int ringDurationInMS = 30 * DateTimeConstants.MILLIS_PER_SECOND;  // TODO: make this flexible so we can adjust based on user preferences.
        long nextRingTimestamp = 0;

        try {
            nextRegularRingTime = Optional.of(RingProcessor.getNextRegularRingTime(alarmInfoList,
                    deviceName,
                    DateTime.now()));
        }catch (Exception ex){
            LOGGER.error("Get next regular ring time for device {} failed: {}", deviceName, ex.getMessage());
        }

        if(nextRegularRingTime.isPresent()) {
            RingTime replyRingTime = nextRegularRingTime.get();
            // Now the ring time for different users is sorted, get the nearest one.
            if (nextRegularRingTime.equals(nextRingTimeFromWorker)) {
                replyRingTime = nextRingTimeFromWorker;
            }

            nextRingTimestamp = replyRingTime.actualRingTimeUTC;
            LOGGER.debug("Next ring time: {}", new DateTime(nextRingTimestamp, userTimeZone));

            if(nextRingTimestamp != 0) {
                ringOffsetFromNowInSecond = (int) ((nextRingTimestamp - DateTime.now().getMillis()) / DateTimeConstants.MILLIS_PER_SECOND);
            }
        }



        final OutputProtos.SyncResponse.Alarm.Builder alarmBuilder = OutputProtos.SyncResponse.Alarm.newBuilder()
                .setStartTime((int) (nextRingTimestamp / DateTimeConstants.MILLIS_PER_SECOND))
                .setEndTime((int) ((nextRingTimestamp + ringDurationInMS) / DateTimeConstants.MILLIS_PER_SECOND))
                .setRingDurationInSecond(ringDurationInMS / DateTimeConstants.MILLIS_PER_SECOND)
                .setRingOffsetFromNowInSecond(ringOffsetFromNowInSecond);

        // TODO: Fix the IndexOutOfBoundException
//        for(int i = 0; i < replyRingTime.soundIds.length; i++){
//            alarmBuilder.setRingtoneIds(i, replyRingTime.soundIds[i]);
//        }

        responseBuilder.setAlarm(alarmBuilder.build());

        final String firmwareFeature = String.format("firmware_release_%s", firmwareVersion);
        final List<String> groups = groupFlipper.getGroups(deviceName);
        if(featureFlipper.deviceFeatureActive(firmwareFeature, deviceName, groups)) {
            LOGGER.debug("Feature is active!");
        }


        if(!groups.isEmpty()) {
            LOGGER.debug("DeviceId {} belongs to groups: {}", deviceName, groups);
            final List<OutputProtos.SyncResponse.FileDownload> fileDownloadList = firmwareUpdateStore.getFirmwareUpdate(deviceName, groups.get(0), firmwareVersion);
            LOGGER.debug("{} files added to syncResponse to be downloaded", fileDownloadList.size());
            responseBuilder.addAllFiles(fileDownloadList);
        } else {
            final List<OutputProtos.SyncResponse.FileDownload> fileDownloadList = firmwareUpdateStore.getFirmwareUpdateContent(deviceName, firmwareVersion);
            if(!fileDownloadList.isEmpty()) {
                LOGGER.debug("Adding {} files to Files to Download list", fileDownloadList.size());
                responseBuilder.addAllFiles(fileDownloadList);
            }
        }

        final AudioControlProtos.AudioControl.Builder audioControl = AudioControlProtos.AudioControl
                .newBuilder()
                .setAudioCaptureAction(AudioControlProtos.AudioControl.AudioCaptureAction.OFF);

        if(featureFlipper.deviceFeatureActive(FeatureFlipper.AUDIO_CAPTURE, deviceName, groups)) {
            LOGGER.debug("AUDIO_CAPTURE feature is active for device_id = {}", deviceName);
            audioControl.setAudioCaptureAction(AudioControlProtos.AudioControl.AudioCaptureAction.ON);
        }

        responseBuilder.setAudioControl(audioControl);

        final OutputProtos.SyncResponse syncResponse = responseBuilder.build();

        LOGGER.debug("Len pb = {}", syncResponse.toByteArray().length);

        final Optional<byte[]> signedResponse = SignedMessage.sign(syncResponse.toByteArray(), encryptionKey);
        if(!signedResponse.isPresent()) {
            LOGGER.error("Failed signing message");
            throw new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity((debug) ? "Failed signing message" : "server error")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }

        return signedResponse.get();
    }


    @POST
    @Path("/pill")
    @Consumes(AdditionalMediaTypes.APPLICATION_PROTOBUF)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Timed
    public byte[] onPillBatchProtobufReceived(final byte[] body) {
        final SignedMessage signedMessage = SignedMessage.parse(body);
        SenseCommandProtos.batched_pill_data batchPilldata = null;

        try {
            batchPilldata = SenseCommandProtos.batched_pill_data.parseFrom(signedMessage.body);
        } catch (IOException exception) {
            final String errorMessage = String.format("Failed parsing protobuf: %s", exception.getMessage());
            LOGGER.error(errorMessage);

            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity((debug) ? errorMessage : "bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }
        LOGGER.debug("Received for pill protobuf message {}", TextFormat.shortDebugString(batchPilldata));


        final Optional<byte[]> optionalKeyBytes = keyStore.get(batchPilldata.getDeviceId());
        if(!optionalKeyBytes.isPresent()) {
            LOGGER.error("Failed to get key from key store for device_id = {}", batchPilldata.getDeviceId());
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }
        final Optional<SignedMessage.Error> error = signedMessage.validateWithKey(optionalKeyBytes.get());

        if(error.isPresent()) {
            LOGGER.error(error.get().message);
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity((debug) ? error.get().message : "bad request")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }

        // Put raw pill data into Kinesis
        final DataLogger batchDataLogger = kinesisLoggerFactory.get(QueueName.BATCH_PILL_DATA);
        batchDataLogger.put(batchPilldata.getDeviceId(),  signedMessage.body);


        // TODO: everything below this is kept for backward compatibility
        // TODO: remove is shortly after we've migrated to new worker

        // This is the default timezone.
        DateTimeZone userTimeZone = DateTimeZone.forID("America/Los_Angeles");
        final String senseId  = batchPilldata.getDeviceId();
        final List<AlarmInfo> alarmInfoList = this.mergedInfoDynamoDB.getInfo(senseId);
        for(final AlarmInfo info:alarmInfoList){
            if(info.timeZone.isPresent()){
                userTimeZone = info.timeZone.get();
            }
        }

        // TODO: MOVE THIS TO THE WORKERS! SURIPU-SERVICE SHOULD NOT BE TALKING TO POSTGRESQL HAS MUCH AS POSSIBLE
        // ********************* Pill Data Storage ****************************
        if(batchPilldata.getPillsCount() > 0){
            for(final SenseCommandProtos.pill_data pill: batchPilldata.getPillsList()){

                final String pillId = pill.getDeviceId();
                final Optional<DeviceAccountPair> internalPillPairingMap = this.deviceDAO.getInternalPillId(pillId);

                if(!internalPillPairingMap.isPresent()){
                    LOGGER.warn("Cannot find internal pill id for pill {}", pillId);
                    continue;
                }

                final InputProtos.PillDataKinesis.Builder pillKinesisDataBuilder = InputProtos.PillDataKinesis.newBuilder();

                final long timestampMillis = pill.getTimestamp() * 1000L;
                final DateTime roundedDateTime = new DateTime(timestampMillis, DateTimeZone.UTC)
                        .withSecondOfMinute(0);

                pillKinesisDataBuilder.setAccountIdLong(internalPillPairingMap.get().accountId)
                        .setPillId(pillId)
                        .setPillIdLong(internalPillPairingMap.get().internalDeviceId)
                        .setTimestamp(roundedDateTime.getMillis())
                        .setOffsetMillis(userTimeZone.getOffset(roundedDateTime));



                if(pill.hasBatteryLevel()){
                    pillKinesisDataBuilder.setBatteryLevel(pill.getBatteryLevel());
                }

                if(pill.hasFirmwareVersion()){
                    pillKinesisDataBuilder.setFirmwareVersion(pill.getFirmwareVersion());
                }

                if(pill.hasUptime()){
                    pillKinesisDataBuilder.setUpTime(pill.getUptime());
                }

                if(pill.hasMotionDataEntrypted()){
                    pillKinesisDataBuilder.setEncryptedData(pill.getMotionDataEntrypted());
                }


                final byte[] pillDataBytes = pillKinesisDataBuilder.build().toByteArray();
                final DataLogger dataLogger = kinesisLoggerFactory.get(QueueName.PILL_DATA);
                final String sequenceNumber = dataLogger.put(internalPillPairingMap.get().internalDeviceId.toString(),  // WTF?
                        pillDataBytes);
                LOGGER.trace("Pill Data added to Kinesis with sequenceNumber = {}", sequenceNumber);

            }
        }

        final SenseCommandProtos.MorpheusCommand responseCommand = SenseCommandProtos.MorpheusCommand.newBuilder()
                .setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PILL_DATA)
                .setVersion(0)
                .build();

        final Optional<byte[]> signedResponse = SignedMessage.sign(responseCommand.toByteArray(), optionalKeyBytes.get());
        if(!signedResponse.isPresent()) {
            LOGGER.error("Failed signing message");
            throw new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity((debug) ? "Failed signing message" : "server error")
                    .type(MediaType.TEXT_PLAIN_TYPE).build()
            );
        }

        return signedResponse.get();
    }
}
