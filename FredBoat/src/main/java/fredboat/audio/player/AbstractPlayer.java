/*
 * MIT License
 *
 * Copyright (c) 2017 Frederik Ar. Mikkelsen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package fredboat.audio.player;

import com.google.common.collect.Lists;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.TrackMarker;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import fredboat.audio.queue.AudioTrackContext;
import fredboat.audio.queue.ITrackProvider;
import fredboat.audio.queue.SplitAudioTrackContext;
import fredboat.audio.queue.TrackEndMarkerHandler;
import fredboat.commandmeta.MessagingException;
import fredboat.util.TextUtils;
import lavalink.client.player.IPlayer;
import lavalink.client.player.LavalinkPlayer;
import lavalink.client.player.LavaplayerPlayerWrapper;
import lavalink.client.player.event.AudioEventAdapterWrapped;
import net.dv8tion.jda.core.audio.AudioSendHandler;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public abstract class AbstractPlayer extends AudioEventAdapterWrapped implements AudioSendHandler {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(AbstractPlayer.class);

    protected final IPlayer player;
    ITrackProvider audioTrackProvider;
    private AudioFrame lastFrame = null;
    protected AudioTrackContext context;
    private final AudioLossCounter audioLossCounter = new AudioLossCounter();

    Consumer<AudioTrackContext> onPlayHook;
    Consumer<Throwable> onErrorHook;

    private static final int MAX_HISTORY_SIZE = 20;
    private AudioTrackContext queuedTrackInHistory = null;
    private ConcurrentLinkedQueue<AudioTrackContext> historyQueue = new ConcurrentLinkedQueue<>();

    @SuppressWarnings("LeakingThisInConstructor")
    AbstractPlayer(String guildId, AudioConnectionFacade audioConnectionFacade) {
        player = audioConnectionFacade.createPlayer(guildId);

        player.addListener(this);
    }

    public void play() {
        log.trace("play()");

        if (player.isPaused()) {
            player.setPaused(false);
        }
        if (player.getPlayingTrack() == null) {
            loadAndPlay();
        }

    }

    public void setPause(boolean pause) {
        log.trace("setPause({})", pause);

        if (pause) {
            player.setPaused(true);
        } else {
            player.setPaused(false);
            play();
        }
    }

    /**
     * Pause the player
     */
    public void pause() {
        log.trace("pause()");

        player.setPaused(true);
    }

    /**
     * Clear the tracklist and stop the current track
     */
    public void stop() {
        log.trace("stop()");

        audioTrackProvider.clear();
        stopTrack();
    }

    /**
     * Skip the current track
     */
    public void skip() {
        log.trace("skip()");

        audioTrackProvider.skipped();
        stopTrack();
    }

    /**
     * Stop the current track.
     */
    public void stopTrack() {
        log.trace("stopTrack()");

        context = null;
        player.stopTrack();
    }

    public boolean isQueueEmpty() {
        log.trace("isQueueEmpty()");

        return player.getPlayingTrack() == null && audioTrackProvider.isEmpty();
    }

    public List<AudioTrackContext> getTracksInHistory(int start, int end) {
        start = Math.max(start, 0);
        end = Math.max(end, start);
        List<AudioTrackContext> historyList = new ArrayList<>(historyQueue);

        if (historyList.size() >= end) {
            return Lists.reverse(new ArrayList<>(historyQueue)).subList(start, end);
        } else {
            return new ArrayList<>();
        }
    }

    public int getTrackCountInHistory() {
        return historyQueue.size();
    }

    public boolean isHistoryQueueEmpty() {
        return historyQueue.isEmpty();
    }

    public AudioTrackContext getPlayingTrack() {
        log.trace("getPlayingTrack()");

        if (player.getPlayingTrack() == null && context == null) {
            return audioTrackProvider.peek();
        }


        return context;
    }

    //the unshuffled playlist
    public List<AudioTrackContext> getRemainingTracks() {
        log.trace("getRemainingTracks()");

        //Includes currently playing track, which comes first
        List<AudioTrackContext> list = new ArrayList<>();
        AudioTrackContext atc = getPlayingTrack();
        if (atc != null) {
            list.add(atc);
        }

        list.addAll(audioTrackProvider.getAsList());
        return list;
    }

    public void setVolume(float vol) {
        player.setVolume((int) (vol * 100));
    }

    public float getVolume() {
        return ((float) player.getVolume()) / 100;
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        log.trace("onTrackEnd({} {} {}) called", track.getInfo().title, endReason.name(), endReason.mayStartNext);

        if (endReason == AudioTrackEndReason.FINISHED || endReason == AudioTrackEndReason.STOPPED) {
            updateHistoryQueue();
            loadAndPlay();
        } else if(endReason == AudioTrackEndReason.CLEANUP) {
            log.info("Track " + track.getIdentifier() + " was cleaned up");
        } else if (endReason == AudioTrackEndReason.LOAD_FAILED) {
            if (onErrorHook != null)
                onErrorHook.accept(new MessagingException("Track `" + TextUtils.escapeAndDefuse(track.getInfo().title) + "` failed to load. Skipping..."));
            audioTrackProvider.skipped();
            loadAndPlay();
        } else {
            log.warn("Track " + track.getIdentifier() + " ended with unexpected reason: " + endReason);
        }
    }

    //request the next track from the track provider and start playing it
    private void loadAndPlay() {
        log.trace("loadAndPlay()");

        AudioTrackContext atc = null;
        if (audioTrackProvider != null) {
            atc = audioTrackProvider.provideAudioTrack();
        } else {
            log.warn("TrackProvider doesn't exist");
        }

        if (atc != null) {
            queuedTrackInHistory = atc;
            playTrack(atc);
        }
    }

    private void updateHistoryQueue() {
        if (historyQueue.size() == MAX_HISTORY_SIZE) {
            historyQueue.poll();
        }
        historyQueue.add(queuedTrackInHistory);
    }

    /**
     * Plays the provided track.
     * <p>
     * Silently playing a track will not trigger the onPlayHook (which announces the track usually)
     */
    private void playTrack(AudioTrackContext trackContext, boolean... silent) {
        log.trace("playTrack({})", trackContext.getEffectiveTitle());

        context = trackContext;
        player.playTrack(trackContext.getTrack());
        trackContext.getTrack().setPosition(trackContext.getStartPosition());

        if (trackContext instanceof SplitAudioTrackContext) {
            //Ensure we don't step over our bounds
            log.info("Start: " + trackContext.getStartPosition() + " End: " + (trackContext.getStartPosition() + trackContext.getEffectiveDuration()));

            trackContext.getTrack().setMarker(
                    new TrackMarker(trackContext.getStartPosition() + trackContext.getEffectiveDuration(),
                            new TrackEndMarkerHandler(this, trackContext)));
        }

        if (silent.length < 1 || !silent[0]) {
            if (onPlayHook != null) onPlayHook.accept(trackContext);
        }
    }

    void destroy() {
        log.trace("destroy()");
        stop();
        player.removeListener(this);
        if (player instanceof LavalinkPlayer) {
            ((LavalinkPlayer) player).getLink().destroy();
        }
    }

    @Override
    public byte[] provide20MsAudio() {
        return lastFrame.data;
    }

    @Override
    public boolean canProvide() {
        LavaplayerPlayerWrapper lavaplayerPlayer = (LavaplayerPlayerWrapper) player;
        lastFrame = lavaplayerPlayer.provide();

        if(lastFrame == null) {
            audioLossCounter.onLoss();
            return false;
        } else {
            audioLossCounter.onSuccess();
            return true;
        }
    }

    public AudioLossCounter getAudioLossCounter() {
        return audioLossCounter;
    }

    @Override
    public boolean isOpus() {
        return true;
    }

    public boolean isPlaying() {
        log.trace("isPlaying()");

        return player.getPlayingTrack() != null && !player.isPaused();
    }

    public boolean isPaused() {
        log.trace("isPaused()");

        return player.isPaused();
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        log.error("Lavaplayer encountered an exception while playing {}" +
                "\nPerformance stats for errored track: {}", track.getIdentifier(), audioLossCounter, exception);
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        log.error("Lavaplayer got stuck while playing {}\nPerformance stats for stuck track: {}",
                track.getIdentifier(), audioLossCounter);
    }

    public long getPosition() {
        return player.getTrackPosition();
    }

    public void seekTo(long position) {
        if (context.getTrack().isSeekable()) {
            player.seekTo(position);
        } else {
            throw new MessagingException(context.i18n("seekDeniedLiveTrack"));
        }
    }

    public IPlayer getPlayer() {
        return player;
    }
}
