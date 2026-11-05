package app.peakpic.b2b;

import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public record EventWindow(RuntimeProperties properties, Clock clock) {
    public void requireWritable() {
        if (clock.instant().isBefore(properties.startsAt())) throw new RuntimeFailure(409, "아직 참여 기간이 아닙니다.");
        if (!clock.instant().isBefore(properties.endsAt())) throw new RuntimeFailure(410, "참여 기간이 종료되었습니다.");
    }
    public void requireReadable() {
        if (!clock.instant().isBefore(properties.downloadsUntil())) throw new RuntimeFailure(410, "사진 보관 기간이 종료되었습니다.");
    }
}
