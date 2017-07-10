package guichaguri.trackplayer.metadata.components;

import android.content.Context;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaSessionCompat;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import guichaguri.trackplayer.logic.Events;
import guichaguri.trackplayer.logic.MediaManager;
import guichaguri.trackplayer.logic.Utils;
import guichaguri.trackplayer.logic.track.Track;

/**
 * @author Guilherme Chaguri
 */
public class ButtonListener extends MediaSessionCompat.Callback {

    private final Context context;
    private final MediaManager manager;

    public ButtonListener(Context context, MediaManager manager) {
        this.context = context;
        this.manager = manager;
    }

    private void dispatch(String event, WritableMap data) {
        Events.dispatchEvent(context, event, data);
    }

    @Override
    public void onPrepare() {
        dispatch(Events.BUTTON_LOAD, null);
    }

    @Override
    public void onPlay() {
        dispatch(Events.BUTTON_PLAY, null);
    }

    @Override
    public void onPause() {
        dispatch(Events.BUTTON_PAUSE, null);
    }

    @Override
    public void onStop() {
        dispatch(Events.BUTTON_STOP, null);
    }

    @Override
    public void onSkipToNext() {
        dispatch(Events.BUTTON_SKIP_NEXT, null);
    }

    @Override
    public void onSkipToPrevious() {
        dispatch(Events.BUTTON_SKIP_PREVIOUS, null);
    }

    @Override
    public void onSkipToQueueItem(long id) {
        Track track = manager.getPlayback().getTrackById(id);
        if(track != null) {
            WritableMap map = Arguments.createMap();
            map.putString("id", track.id);
            dispatch(Events.BUTTON_SKIP, map);
        }
    }

    @Override
    public void onSeekTo(long pos) {
        WritableMap map = Arguments.createMap();
        Utils.setTime(map, "position", pos);
        dispatch(Events.BUTTON_SEEK_TO, map);
    }

    @Override
    public void onSetRating(RatingCompat rating) {
        WritableMap map = Arguments.createMap();
        Utils.setRating(map, "rating", rating);
        dispatch(Events.BUTTON_SET_RATING, map);
    }

}
