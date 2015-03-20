import com.hello.suripu.algorithm.core.AmplitudeData;
import com.hello.suripu.algorithm.sleep.Vote;
import org.joda.time.DateTime;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Created by pangwu on 3/19/15.
 */
public class VoteTest {

    @Test
    public void testInsertBefore(){
        final List<AmplitudeData> original = new ArrayList<>();
        final DateTime now = DateTime.now();
        original.add(new AmplitudeData(now.getMillis(), 0, 0));

        final List<AmplitudeData> expected = new ArrayList<>();
        for(int i = 0; i < 60; i++){
            expected.add(0, new AmplitudeData(now.minusMinutes(i + 1).getMillis(), 0, 0));
        }
        expected.addAll(original);
        final List<AmplitudeData> actual = Vote.insertBefore(original, 60);

        assertThat(actual.size(), is(expected.size()));
        for(int i = 0; i < actual.size(); i++){
            assertThat(actual.get(i).timestamp, is(expected.get(i).timestamp));
            assertThat(actual.get(i).amplitude, is(expected.get(i).amplitude));
            assertThat(actual.get(i).offsetMillis, is(expected.get(i).offsetMillis));
        }
    }


    @Test
    public void testTrim(){
        final List<AmplitudeData> original = new ArrayList<>();
        final List<AmplitudeData> expected = new ArrayList<>();

        final DateTime now = DateTime.now();
        final DateTime trimTime = now.plusMinutes(11);
        for(int i = 0; i < 60; i++){
            original.add(new AmplitudeData(now.plusMinutes(i).getMillis(), i, 0));
            if(!now.plusMinutes(i).isBefore(trimTime)){
                expected.add(original.get(original.size() - 1));
            }
        }

        final List<AmplitudeData> actual = Vote.trim(original, trimTime.getMillis(), original.get(original.size() - 1).timestamp);
        assertThat(actual.size(), is(expected.size()));

        for(int i = 0; i < actual.size(); i++){
            assertThat(actual.get(i).timestamp, is(expected.get(i).timestamp));
            assertThat(actual.get(i).offsetMillis, is(expected.get(i).offsetMillis));
            assertThat(actual.get(i).amplitude, is(actual.get(i).amplitude));
        }
    }
}
