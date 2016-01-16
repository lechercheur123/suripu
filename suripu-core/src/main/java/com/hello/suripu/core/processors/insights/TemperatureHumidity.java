package com.hello.suripu.core.processors.insights;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.hello.suripu.core.db.DeviceDataInsightQueryDAO;
import com.hello.suripu.core.db.SleepStatsDAODynamoDB;
import com.hello.suripu.core.db.responses.DeviceDataResponse;
import com.hello.suripu.core.db.responses.Response;
import com.hello.suripu.core.models.AccountInfo;
import com.hello.suripu.core.models.DeviceData;
import com.hello.suripu.core.models.DeviceId;
import com.hello.suripu.core.models.Insights.InsightCard;
import com.hello.suripu.core.models.Insights.Message.TemperatureMsgEN;
import com.hello.suripu.core.models.Insights.Message.Text;
import com.hello.suripu.core.preferences.TemperatureUnit;
import com.hello.suripu.core.util.DataUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Created by kingshy on 1/5/15.
 */
public class TemperatureHumidity {
    private static final Logger LOGGER = LoggerFactory.getLogger(TemperatureHumidity.class);

    public static final int IDEAL_TEMP_MIN = 59;
    public static final int IDEAL_TEMP_MAX = 73;

    public static final int IDEAL_TEMP_MIN_CELSIUS = 15;
    public static final int IDEAL_TEMP_MAX_CELSIUS = 23;

    public static final int ALERT_TEMP_MIN = 55;
    public static final int ALERT_TEMP_MAX = 79;

    public static final int ALERT_TEMP_MIN_CELSIUS = 13;
    public static final int ALERT_TEMP_MAX_CELSIUS = 26;


    public static final int ALERT_HUMIDITY_LOW = 20;
    public static final int ALERT_HUMIDITY_HIGH = 70;

    public static final int IDEAL_HUMIDITY_MIN = 30;
    public static final int IDEAL_HUMIDITY_MAX = 60;

    private static final int COLD_TEMP_ADJUST = 3; // adjust for cold sleeper
    private static final int HOT_TEMP_ADJUST = 5; // adjust for hot sleeper

    private static final int TEMP_START_HOUR = 23; // 11pm
    private static final int TEMP_END_HOUR = 6; // 6am

    public static Optional<InsightCard> getInsights(final Long accountId, final DeviceId deviceId,
                                                    final DeviceDataInsightQueryDAO deviceDataDAO,
                                                    final AccountInfo.SleepTempType tempPref,
                                                    final TemperatureUnit tempUnit, final SleepStatsDAODynamoDB sleepStatsDAODynamoDB) {
        final Optional<Integer> timeZoneOffsetOptional = sleepStatsDAODynamoDB.getTimeZoneOffset(accountId);
        if (!timeZoneOffsetOptional.isPresent()) {
            return Optional.absent(); //cannot compute insight without timezone info
        }
        final Integer timeZoneOffset = timeZoneOffsetOptional.get();

        // get light data for last three days, filter by time
        final DateTime queryEndTime = DateTime.now(DateTimeZone.forOffsetMillis(timeZoneOffset)).withHourOfDay(TEMP_END_HOUR); // today 6am
        final DateTime queryStartTime = queryEndTime.minusDays(InsightCard.RECENT_DAYS).withHourOfDay(TEMP_START_HOUR); // 11pm three days ago

        final DateTime queryEndTimeLocal = queryEndTime.plusMillis(timeZoneOffset);
        final DateTime queryStartTimeLocal = queryStartTime.plusMillis(timeZoneOffset);

        final int slotDuration = 30;
        final List<DeviceData> sensorData;
        final DeviceDataResponse response = deviceDataDAO.getBetweenByLocalHourAggregateBySlotDuration(
                accountId, deviceId, queryStartTime, queryEndTime,
                queryStartTimeLocal, queryEndTimeLocal, TEMP_START_HOUR, TEMP_END_HOUR, slotDuration);
        if (response.status == Response.Status.SUCCESS) {
            sensorData = response.data;
        } else {
            sensorData = Lists.newArrayList();
        }

        final Optional<InsightCard> card = processData(accountId, sensorData, tempPref, tempUnit);
        return card;
    }

    public static Optional<InsightCard> processData(final Long accountId, final List<DeviceData> data,
                                                    final AccountInfo.SleepTempType tempPref,
                                                    final TemperatureUnit tempUnit) {

        if (data.isEmpty()) {
            return Optional.absent();
        }

        // TODO if location is available, compare with users from the same city

        // get min, max and average
        final DescriptiveStatistics stats = new DescriptiveStatistics();
        for (final DeviceData deviceData : data) {
            stats.addValue(DataUtils.calibrateTemperature(deviceData.ambientTemperature));
        }

        final double tmpMinValue = stats.getMin();
        final int minTempC = (int) tmpMinValue;
        final int minTempF = celsiusToFahrenheit(tmpMinValue);

        final double tmpMaxValue = stats.getMax();
        final int maxTempC = (int) tmpMaxValue;
        final int maxTempF = celsiusToFahrenheit(tmpMaxValue);

        LOGGER.debug("Temp for account {}: min {}, max {}", accountId, minTempF, maxTempF);

        /* Possible cases
                    min                       max
                    |------ ideal range ------|
            |----|                              |-----|
            too cold                            too hot

                |------|                  |-------|
                a little cold               a little warm

                |-------- way out of range! -------|
         */

        // todo: edits
        // Unit conversion for passing into TemperatureMsgEN
        int minTemp = minTempF;
        int maxTemp = maxTempF;
        int idealMin = IDEAL_TEMP_MIN;
        int idealMax = IDEAL_TEMP_MAX;
        if (tempUnit == TemperatureUnit.CELSIUS) {
            minTemp = fahrenheitToCelsius((double) minTempF);
            maxTemp = fahrenheitToCelsius((double) maxTempF);
            idealMin = IDEAL_TEMP_MIN_CELSIUS;
            idealMax = IDEAL_TEMP_MAX_CELSIUS;
        }

        Text text;
        final String commonMsg = TemperatureMsgEN.getCommonMsg(minTemp, maxTemp, tempUnit.toString());

        //careful: comparisons are only done in Fahrenheit, but TemperatureMsgEN gets passed the units of the user!
        if (IDEAL_TEMP_MIN <= minTempF && maxTempF <= IDEAL_TEMP_MAX) {
            text = TemperatureMsgEN.getTempMsgPerfect(commonMsg);

        } else if (maxTempF < IDEAL_TEMP_MIN) {
            text = TemperatureMsgEN.getTempMsgTooCold(commonMsg, idealMin, tempUnit.toString());

        } else if (minTempF > IDEAL_TEMP_MAX) {
            text = TemperatureMsgEN.getTempMsgTooHot(commonMsg, idealMax, tempUnit.toString());

        } else if (minTempF < IDEAL_TEMP_MIN && maxTempF <= IDEAL_TEMP_MAX) {
            text = TemperatureMsgEN.getTempMsgCool(commonMsg);

        } else if (minTempF > IDEAL_TEMP_MIN && maxTempF > IDEAL_TEMP_MAX) {
            text = TemperatureMsgEN.getTempMsgWarm(commonMsg);

        } else {
            // both min and max are outside of ideal range
            text = TemperatureMsgEN.getTempMsgBad(commonMsg, idealMin, idealMax, tempUnit.toString());
        }

        return Optional.of(new InsightCard(accountId, text.title, text.message,
                InsightCard.Category.TEMPERATURE, InsightCard.TimePeriod.RECENTLY,
                DateTime.now(DateTimeZone.UTC)));
    }

    private static int celsiusToFahrenheit(final double value) {
        return (int) Math.round((value * 9.0) / 5.0) + 32;
    }

    private static int fahrenheitToCelsius(final double value) {
        return (int) ((value - 32.0) * (5.0/9.0));
    }
}
