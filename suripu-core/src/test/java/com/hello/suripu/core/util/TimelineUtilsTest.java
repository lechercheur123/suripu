package com.hello.suripu.core.util;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.hello.suripu.core.models.AggregateSleepStats;
import com.hello.suripu.core.models.Event;
import com.hello.suripu.core.models.Events.MotionEvent;
import com.hello.suripu.core.models.MainEventTimes;
import com.hello.suripu.core.models.SleepSegment;
import com.hello.suripu.core.models.SleepStats;
import com.hello.suripu.core.models.TimeZoneHistory;
import com.hello.suripu.core.models.TrackerMotion;
import com.hello.suripu.core.translations.English;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;



/**
 * Created by jarredheinrich on 10/7/16.
 */

public class TimelineUtilsTest {

    final private TimelineUtils timelineUtils = new TimelineUtils();

    private List<TrackerMotion> loadTrackerMotionFromCSV(final String resource){
        final URL fixtureCSVFile = Resources.getResource(resource);
        final List<TrackerMotion> trackerMotions = new ArrayList<>();
        try {
            final String csvString = Resources.toString(fixtureCSVFile, Charsets.UTF_8);
            final String[] lines = csvString.split("\\n");
            for(int i = 1; i < lines.length; i++){
                final String[] columns = lines[i].split(",");
                final TrackerMotion trackerMotion = new TrackerMotion(0L, 0L, 0L, Long.valueOf(columns[0]), Integer.valueOf(columns[1]), Integer.valueOf(columns[2]), 0L, 0L,0L);
                //if(trackerMotion.value > 0){
                trackerMotions.add(trackerMotion);
                //}
            }
        }catch (IOException ex){
            ex.printStackTrace();
        }

        return trackerMotions;
    }

    @Test
    public void testComputeStats() {
        //false positive night - motion at start and end of night
        final List<TrackerMotion> trackerMotions =  loadTrackerMotionFromCSV("fixtures/algorithm/millionaires_challenge_2015_02_20_raw.csv");
        final List<MotionEvent> motionEvents = timelineUtils.generateMotionEvents(trackerMotions);
        final long sleepTime = trackerMotions.get(0).timestamp;
        final long wakeTime = trackerMotions.get(0).timestamp + 24000000L;
        final TimeZoneHistory timeZoneHistory1 = new TimeZoneHistory(1428408400000L, 3600000, "America/Los_Angeles");
        final List<TimeZoneHistory> timeZoneHistoryList = new ArrayList<>();
        timeZoneHistoryList.add(timeZoneHistory1);
        final TimeZoneOffsetMap timeZoneOffsetMap = TimeZoneOffsetMap.createFromTimezoneHistoryList(timeZoneHistoryList);

        final Optional<Event> sleep = Optional.of(Event.createFromType(Event.Type.SLEEP, sleepTime, sleepTime, -25200000, Optional.of(English.IN_BED_MESSAGE), Optional.<SleepSegment.SoundInfo>absent(), Optional.<Integer>absent()));
        final Optional<Event> wake = Optional.of(Event.createFromType(Event.Type.WAKE_UP, wakeTime, wakeTime, -25200000, Optional.of(English.OUT_OF_BED_MESSAGE), Optional.<SleepSegment.SoundInfo>absent(), Optional.<Integer>absent()));

        final List<Event> mainEvents = new ArrayList<Event>();

        mainEvents.add(sleep.get());
        mainEvents.add(wake.get());


        final Map<Long, Event> timelineEvents = TimelineRefactored.populateTimeline(motionEvents, timeZoneOffsetMap);
        for (final Event event : mainEvents) {
            timelineEvents.put(event.getStartTimestamp(), event);
        }
        final List<Event> eventsWithSleepEvents = TimelineRefactored.mergeEvents(timelineEvents);
        final List<Event> smoothedEvents = timelineUtils.smoothEvents(eventsWithSleepEvents);

        List<Event> cleanedUpEvents;
        cleanedUpEvents = timelineUtils.removeMotionEventsOutsideBedPeriod(smoothedEvents, sleep, wake);
        final List<Event> greyEvents = timelineUtils.greyNullEventsOutsideBedPeriod(cleanedUpEvents, sleep, wake);
        final List<Event> nonSignificantFilteredEvents = timelineUtils.removeEventBeforeSignificant(greyEvents);
        final List<SleepSegment> sleepSegments = timelineUtils.eventsToSegments(nonSignificantFilteredEvents);
        final SleepStats sleepStats = TimelineUtils.computeStats(sleepSegments, trackerMotions, 70, true, true);
        final Integer uninterruptedDuration = 380;
        assertThat(sleepStats.uninterruptedSleepDurationInMinutes, is(uninterruptedDuration));
    }

    @Test
    public void testMotionDuringSleepCheck(){
        //false positive night - motion at start and end of night
        List<TrackerMotion> trackerMotions = loadTrackerMotionFromCSV("fixtures/algorithm/false_night_2016_08_01.csv");
        long fallAsleepTime = 1470114360000L;
        long wakeUpTime = 1470139440000L;
        boolean testMotionDuringSleep = timelineUtils.motionDuringSleepCheck(trackerMotions, fallAsleepTime, wakeUpTime);
        assertThat(testMotionDuringSleep, is(false));
        //okay night
        trackerMotions = loadTrackerMotionFromCSV("fixtures/algorithm/millionaires_challenge_2015_02_20_raw.csv");
        fallAsleepTime = 1424496240000L;
        wakeUpTime = 1424528700000L;
        testMotionDuringSleep = timelineUtils.motionDuringSleepCheck(trackerMotions, fallAsleepTime, wakeUpTime);
        assertThat(testMotionDuringSleep, is(true));

    }

    @Test
    public void testIsLockedDown(){
        final long accountId = 0L;
        final DateTime start = new DateTime(2015, 2, 21, 5, 22 , DateTimeZone.UTC);
        long inbed = start.getMillis();
        long sleep = start.plusMinutes(15).getMillis();
        long wake = start.plusHours(8).getMillis();
        long outOfBed = start.plusHours(9).plusMinutes(30).getMillis();
        long createdAt = start.plusHours(10).getMillis();
        final int offset = 0;
        final Optional<MainEventTimes> computedMainEventTimesOptionalSuccess = Optional.of(MainEventTimes.createMainEventTimes(accountId, inbed, offset, sleep, offset, wake, offset, outOfBed, offset, createdAt, offset));

        final List<TrackerMotion> originalTrackerMotions = loadTrackerMotionFromCSV("fixtures/algorithm/millionaires_challenge_2015_02_20_raw.csv");


        final SleepStats sleepStats = new SleepStats(0,0,0,0,510,false,0,0L,0L,0);
        final List<AggregateSleepStats> prevSleepStats = Lists.newArrayList();
        AggregateSleepStats aggSleepStats = new AggregateSleepStats.Builder()
                .withSleepStats(sleepStats)
                .build();
        for(int i  = 0; i < 7; i++) {
            prevSleepStats.add(aggSleepStats);
        }
        //locked down: sufficient duration with minimal motion after oob
        boolean isLockedDown= timelineUtils.isLockedDown(ImmutableList.copyOf(prevSleepStats),computedMainEventTimesOptionalSuccess,ImmutableList.copyOf(originalTrackerMotions), true);
        assert(isLockedDown);

        //not locked down: insufficient time
        wake = start.plusHours(6).getMillis();
        outOfBed = start.plusHours(9).plusMinutes(5).getMillis();
        final Optional<MainEventTimes> computedMainEventTimesOptionalFailDuration = Optional.of(MainEventTimes.createMainEventTimes(accountId, inbed, offset, sleep, offset, wake, offset, outOfBed, offset, createdAt, offset));
        isLockedDown= timelineUtils.isLockedDown(ImmutableList.copyOf(prevSleepStats),computedMainEventTimesOptionalFailDuration,ImmutableList.copyOf(originalTrackerMotions), true);
        assert(!isLockedDown);

        // not locked down: lots of motion after oob
        wake = start.plusHours(8).getMillis();
        outOfBed = start.plusHours(8).plusMinutes(1).getMillis();
        createdAt = start.plusHours(8).plusMinutes(2).getMillis();
        final Optional<MainEventTimes> computedMainEventTimesOptionalFailMotion = Optional.of(MainEventTimes.createMainEventTimes(accountId, inbed, offset, sleep, offset, wake, offset, outOfBed, offset, createdAt, offset));
        isLockedDown= timelineUtils.isLockedDown(ImmutableList.copyOf(prevSleepStats),computedMainEventTimesOptionalFailMotion,ImmutableList.copyOf(originalTrackerMotions), true);
        assert(!isLockedDown);

    }

}