package com.hello.suripu.core.processors.insights;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.hello.suripu.core.db.DeviceDataInsightQueryDAO;
import com.hello.suripu.core.db.SleepStatsDAODynamoDB;
import com.hello.suripu.core.db.responses.Response;
import com.hello.suripu.core.firmware.HardwareVersion;
import com.hello.suripu.core.models.CalibratedDeviceData;
import com.hello.suripu.core.models.Calibration;
import com.hello.suripu.core.models.Device;
import com.hello.suripu.core.models.DeviceAccountPair;
import com.hello.suripu.core.models.DeviceData;
import com.hello.suripu.core.models.DeviceId;
import com.hello.suripu.core.models.Insights.InsightCard;
import com.hello.suripu.core.models.Insights.Message.HumidityMsgEN;
import com.hello.suripu.core.models.Insights.Message.Text;
import com.hello.suripu.core.util.DataUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Created by jyfan on 9/16/15.
 */
public class Humidity {
    private static final Logger LOGGER = LoggerFactory.getLogger(Humidity.class);

    //Make sure these are consistent with TemperatureHumidity.java
    public static final int ALERT_HUMIDITY_LOW = 20;
    public static final int ALERT_HUMIDITY_HIGH = 70;

    public static final int IDEAL_HUMIDITY_MIN = 30;
    public static final int IDEAL_HUMIDITY_MAX = 60;

    private static final Integer PRE_BED_BEGIN_HOUR_LOCAL = 21; // 9pm
    private static final Integer PRE_BED_END_HOUR_LOCAL = 1; // 2am
    //Do not set PRE_BED_END_HOUR_LOCAL before midnight or sql query will need to change

    public static Optional<InsightCard> getInsights(final Long accountId,
                                                    final DeviceAccountPair deviceAccountPair,
                                                    final Optional<Device.Color> colorOptional,
                                                    final Optional<Calibration> calibrationOptional,
                                                    final DeviceDataInsightQueryDAO deviceDataDAO,
                                                    final SleepStatsDAODynamoDB sleepStatsDAODynamoDB) {


        final Optional<Integer> timeZoneOffsetOptional = sleepStatsDAODynamoDB.getTimeZoneOffset(accountId);
        if (!timeZoneOffsetOptional.isPresent()) {
            LOGGER.debug("action=insight-absent insight=humidity reason=timezoneoffset-absent account_id={}", accountId);
            return Optional.absent();
        }
        final Integer timeZoneOffset = timeZoneOffsetOptional.get();

        final List<DeviceData> deviceDatas = getDeviceData(accountId, deviceAccountPair, deviceDataDAO, timeZoneOffset);
        if (deviceDatas.isEmpty()) {
            LOGGER.debug("action=insight-absent insight=lights reason=device-data-empty account_id={}", accountId);
            return Optional.absent();
        }

        final Integer humMedian = getMedianHumidity(deviceDatas, colorOptional, calibrationOptional);

        final Optional<InsightCard> card = processData(accountId, humMedian);
        return card;
    }

    @VisibleForTesting
    public static Optional<InsightCard> processData(final Long accountId, final Integer medianHumidity) {

        final Text text;
        if (medianHumidity < IDEAL_HUMIDITY_MIN) {
            text = HumidityMsgEN.getLowHumidity();
        }
        else if (medianHumidity < IDEAL_HUMIDITY_MAX) {
            text = HumidityMsgEN.getIdealHumidity();
        }
        else {
            text = HumidityMsgEN.getHighHumidity();
        }

        return Optional.of(InsightCard.createBasicInsightCard(accountId, text.title, text.message,
                InsightCard.Category.HUMIDITY, InsightCard.TimePeriod.MONTHLY,
                DateTime.now(DateTimeZone.UTC), InsightCard.InsightType.DEFAULT));
    }

    @VisibleForTesting
    public static Integer getMedianHumidity(final List<DeviceData> data,
                                            final Optional<Device.Color> colorOptional,
                                            final Optional<Calibration> calibrationOptional ) {

        final Device.Color color = colorOptional.or(Device.DEFAULT_COLOR);

        final DescriptiveStatistics humStats = new DescriptiveStatistics();
        for (final DeviceData deviceData : data) {
            final CalibratedDeviceData calibratedDeviceData = new CalibratedDeviceData(deviceData, color, calibrationOptional);
            humStats.addValue(calibratedDeviceData.humidity());
        }

        final Integer medianHumidity = (int) humStats.getPercentile(50);
        return medianHumidity;
    }

    private static final List<DeviceData> getDeviceData(final Long accountId, final DeviceAccountPair deviceAccountPair, final DeviceDataInsightQueryDAO deviceDataDAO, final Integer timeZoneOffset) {

        final DateTime queryEndTime = DateTime.now(DateTimeZone.forOffsetMillis(timeZoneOffset)).withHourOfDay(PRE_BED_BEGIN_HOUR_LOCAL);
        final DateTime queryStartTime = queryEndTime.minusDays(InsightCard.PAST_WEEK);

        final DateTime queryEndTimeLocal = queryEndTime.plusMillis(timeZoneOffset);
        final DateTime queryStartTimeLocal = queryStartTime.plusMillis(timeZoneOffset);

        //Grab all pre-bed data for past week
        final DeviceId deviceId = DeviceId.create(deviceAccountPair.externalDeviceId);
        final Response<ImmutableList<DeviceData>> response = deviceDataDAO.getBetweenHourDateByTS(
                accountId, deviceId, queryStartTime, queryEndTime, queryStartTimeLocal, queryEndTimeLocal, PRE_BED_BEGIN_HOUR_LOCAL, PRE_BED_END_HOUR_LOCAL);
        if (response.status == Response.Status.SUCCESS) {
            return response.data;
        }
        return Lists.newArrayList();
    }

}
